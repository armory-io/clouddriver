/*
 * Copyright 2025 Harness, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.cats.redis.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Poller reload mid-cycle concurrency tests (no permit leaks)")
class PollerReloadConcurrencyTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(32);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    intervalProvider = agent -> new AgentIntervalProvider.Interval(500L, 1000L);
    shardingFilter = a -> true;

    agentProperties = new PriorityAgentProperties();
    agentProperties.setMaxConcurrentAgents(5);
    agentProperties.setEnabledPattern(".*");
    agentProperties.setDisabledPattern("");

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.setRefreshPeriodSeconds(1);
    schedulerProperties.getBatchOperations().setEnabled(true);
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedulerProperties.getCircuitBreaker().setEnabled(false);
  }

  @AfterEach
  void tearDown() {
    try (Jedis j = jedisPool.getResource()) {
      // Use DEL on known keys to avoid deprecated flushDB
      j.del("waiting");
      j.del("working");
    }
  }

  private Agent mkAgent(String type) {
    Agent a = mock(Agent.class);
    org.mockito.Mockito.when(a.getAgentType()).thenReturn(type);
    org.mockito.Mockito.when(a.getProviderName()).thenReturn("test");
    return a;
  }

  private ExecutionInstrumentation mkInstr(CountDownLatch latch) {
    return new ExecutionInstrumentation() {
      @Override
      public void executionStarted(Agent agent) {}

      @Override
      public void executionCompleted(Agent agent, long elapsedTimeMs) {
        latch.countDown();
      }

      @Override
      public void executionFailed(Agent agent, Throwable cause, long elapsedTimeMs) {
        latch.countDown();
      }
    };
  }

  private AgentExecution mkExec() {
    return agent -> {};
  }

  @Test
  @DisplayName("Batch acquisition tolerates mid-cycle unregister without permit leaks")
  void batchMidCycleUnregister_noPermitLeaks() throws Exception {
    PrioritySchedulerMetrics metrics =
        new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
    RedisScriptManager scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();

    AgentAcquisitionService svc =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    // Register many agents to widen the batch candidate-building window
    int numAgents = 50;
    List<String> agentTypes = new ArrayList<>();
    for (int i = 0; i < numAgents; i++) {
      String at = String.format(Locale.ROOT, "batch-a-%03d", i);
      agentTypes.add(at);
      svc.registerAgent(mkAgent(at), mkExec(), mkInstr(new CountDownLatch(0)));
    }

    // Seed waiting as ready
    try (Jedis j = jedisPool.getResource()) {
      long now = Long.parseLong(j.time().get(0));
      for (String at : agentTypes) {
        j.zadd("waiting", now - 5, at);
      }
    }

    // Prepare semaphore for acquisition permits
    Semaphore permits = new Semaphore(agentProperties.getMaxConcurrentAgents());

    // Start a concurrent unregister simulating Poller reload
    Thread mutator =
        new Thread(
            () -> {
              try {
                // Small jitter to overlap with candidate building
                Thread.sleep(10);
              } catch (InterruptedException ignored) {
              }
              int toRemove = 10;
              for (int i = 0; i < toRemove; i++) {
                String at = agentTypes.get(ThreadLocalRandom.current().nextInt(agentTypes.size()));
                svc.unregisterAgent(mkAgent(at));
              }
            });
    mutator.start();

    int acquired = svc.saturatePool(1L, permits, Executors.newCachedThreadPool());
    assertThat(acquired).isBetween(0, agentProperties.getMaxConcurrentAgents());

    // Wait until all permits are returned (or timeout)
    long deadline = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < deadline) {
      if (permits.availablePermits() == agentProperties.getMaxConcurrentAgents()
          && svc.getActiveAgentCount() == 0
          && svc.getFuturesMapSize() == 0
          && svc.getZombiesInFlight() == 0) {
        break;
      }
      Thread.sleep(10);
    }

    // No permit leaks (strict); other cleanup paths are async and may lag slightly
    assertThat(permits.availablePermits()).isEqualTo(agentProperties.getMaxConcurrentAgents());

    mutator.join();
  }

  @Test
  @DisplayName("Zombie cleanup releases permits correctly after mid-cycle unregister")
  void zombieCleanup_releasesPermits_afterMidCycleUnregister() throws Exception {
    PrioritySchedulerMetrics metrics =
        new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
    RedisScriptManager scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();

    AgentAcquisitionService svc =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);
    ZombieCleanupService zombie =
        new ZombieCleanupService(jedisPool, scriptManager, schedulerProperties, metrics);
    zombie.setAcquisitionService(svc);

    // Register a few agents and seed waiting
    List<String> agents = List.of("z-a1", "z-a2", "z-a3", "z-a4", "z-a5");
    for (String at : agents) {
      svc.registerAgent(mkAgent(at), mkExec(), mkInstr(new java.util.concurrent.CountDownLatch(0)));
    }
    try (Jedis j = jedisPool.getResource()) {
      long now = Long.parseLong(j.time().get(0));
      for (String at : agents) {
        j.zadd("waiting", now - 2, at);
      }
    }

    // Acquire some agents
    Semaphore permits = new Semaphore(agentProperties.getMaxConcurrentAgents());
    svc.saturatePool(3L, permits, Executors.newCachedThreadPool());

    // Simulate mid-cycle unregisters that could leave lingering active entries
    svc.unregisterAgent(mkAgent("z-a2"));
    svc.unregisterAgent(mkAgent("z-a4"));

    // Run zombie cleanup with snapshots
    zombie.cleanupZombieAgentsIfNeeded(svc.getActiveAgentsMap(), svc.getActiveAgentsFutures());

    // Wait until permits are fully returned (or timeout)
    long deadline = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < deadline) {
      if (permits.availablePermits() == agentProperties.getMaxConcurrentAgents()) {
        break;
      }
      Thread.sleep(10);
    }

    // No permit leaks after zombie cleanup
    assertThat(permits.availablePermits()).isEqualTo(agentProperties.getMaxConcurrentAgents());
  }

  @Test
  @DisplayName("Orphan cleanup handles mid-cycle unregister without affecting permits")
  void orphanCleanup_noPermitLeak_afterMidCycleUnregister() throws Exception {
    PrioritySchedulerMetrics metrics =
        new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
    RedisScriptManager scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();

    AgentAcquisitionService svc =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);
    OrphanCleanupService orphan =
        new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties, metrics);
    orphan.setAcquisitionService(svc);

    // Register and seed
    List<String> agents = List.of("o-a1", "o-a2", "o-a3");
    for (String at : agents) {
      svc.registerAgent(mkAgent(at), mkExec(), mkInstr(new java.util.concurrent.CountDownLatch(0)));
    }
    try (Jedis j = jedisPool.getResource()) {
      long now = Long.parseLong(j.time().get(0));
      for (String at : agents) {
        j.zadd("waiting", now - 2, at);
      }
    }

    // Acquire
    Semaphore permits = new Semaphore(agentProperties.getMaxConcurrentAgents());
    svc.saturatePool(4L, permits, Executors.newCachedThreadPool());

    // Unregister mid-cycle
    svc.unregisterAgent(mkAgent("o-a2"));

    // Force orphan cleanup (bypass interval/leadership)
    orphan.forceCleanupOrphanedAgents();

    // Ensure no permit leaks
    long deadline = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < deadline) {
      if (permits.availablePermits() == agentProperties.getMaxConcurrentAgents()) {
        break;
      }
      Thread.sleep(10);
    }
    assertThat(permits.availablePermits()).isEqualTo(agentProperties.getMaxConcurrentAgents());
  }

  @Test
  @DisplayName("Individual acquisition tolerates mid-cycle unregister without permit leaks")
  void individualMidCycleUnregister_noPermitLeaks() throws Exception {
    PrioritySchedulerMetrics metrics =
        new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
    RedisScriptManager scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();

    // Force individual acquisition
    schedulerProperties.getBatchOperations().setEnabled(false);

    AgentAcquisitionService svc =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    // Register a few agents
    List<String> agentTypes = List.of("ind-a1", "ind-a2", "ind-a3");
    for (String at : agentTypes) {
      svc.registerAgent(mkAgent(at), mkExec(), mkInstr(new CountDownLatch(0)));
    }

    // Seed waiting
    try (Jedis j = jedisPool.getResource()) {
      long now = Long.parseLong(j.time().get(0));
      for (String at : agentTypes) {
        j.zadd("waiting", now - 2, at);
      }
    }

    Semaphore permits = new Semaphore(agentProperties.getMaxConcurrentAgents());

    Thread mutator =
        new Thread(
            () -> {
              try {
                Thread.sleep(5);
              } catch (InterruptedException ignored) {
              }
              // Remove one agent while the cycle is in-flight
              svc.unregisterAgent(mkAgent("ind-a2"));
            });
    mutator.start();

    int acquired = svc.saturatePool(2L, permits, Executors.newCachedThreadPool());
    assertThat(acquired).isBetween(0, agentTypes.size());

    // Wait until all permits are returned (or timeout)
    long deadline = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < deadline) {
      if (permits.availablePermits() == agentProperties.getMaxConcurrentAgents()
          && svc.getActiveAgentCount() == 0
          && svc.getFuturesMapSize() == 0
          && svc.getZombiesInFlight() == 0) {
        break;
      }
      Thread.sleep(10);
    }

    // No permit leaks (strict); other cleanup paths are async and may lag slightly
    assertThat(permits.availablePermits()).isEqualTo(agentProperties.getMaxConcurrentAgents());

    mutator.join();
  }
}
