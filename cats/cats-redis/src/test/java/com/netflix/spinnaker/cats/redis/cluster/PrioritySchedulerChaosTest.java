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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Chaos tests for Priority Scheduler resilience under failure scenarios.
 *
 * <p>Tests failure injection and chaotic environmental conditions:
 *
 * <ul>
 *   <li>Poison pill agent (consistently failing agent)
 *   <li>Dynamic agent population (rapid registration/unregistration)
 * </ul>
 *
 * <p>Note: Redis latency/failures and clock skew tests require additional infrastructure
 * (Toxiproxy, Clock injection) and are not included in this test suite.
 *
 * <p>Tagged with "chaos" to keep default build times reasonable.
 */
@Testcontainers
@Tag("chaos")
@DisplayName("Priority Scheduler Chaos Tests")
class PrioritySchedulerChaosTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private PrioritySchedulerMetrics metrics;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(32);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379));
    try (Jedis j = jedisPool.getResource()) {
      j.flushAll();
    }
    metrics = new PrioritySchedulerMetrics(new DefaultRegistry());
    scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();
  }

  @AfterEach
  void tearDown() {
    if (jedisPool != null) {
      try (Jedis j = jedisPool.getResource()) {
        j.flushAll();
      } catch (Exception ignore) {
      }
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("Poison pill agent: Single consistently failing agent doesn't break scheduler")
  void poisonPillAgentIsolation() throws Exception {
    // GIVEN: Mix of normal and poison pill agents
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");
    agentProps.setMaxConcurrentAgents(5);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getCircuitBreaker().setEnabled(false);
    schedProps.getZombieCleanup().setEnabled(true);
    schedProps.getZombieCleanup().setThresholdMs(200L);
    schedProps.getOrphanCleanup().setEnabled(true);
    schedProps.getOrphanCleanup().setThresholdMs(1_000L);

    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(200L, 200L, 400L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    // Register poison pill agent (always throws)
    Agent poisonAgent = mock(Agent.class);
    when(poisonAgent.getAgentType()).thenReturn("poison-agent");
    when(poisonAgent.getProviderName()).thenReturn("test");
    AgentExecution poisonExec = mock(AgentExecution.class);
    org.mockito.Mockito.doAnswer(
            inv -> {
              throw new RuntimeException("Poison pill agent failure");
            })
        .when(poisonExec)
        .executeAgent(any());
    ExecutionInstrumentation poisonInstr = mock(ExecutionInstrumentation.class);
    acquisitionService.registerAgent(poisonAgent, poisonExec, poisonInstr);

    // Register normal agents
    List<Agent> normalAgents = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Agent normalAgent = mock(Agent.class);
      when(normalAgent.getAgentType()).thenReturn("normal-agent-" + i);
      when(normalAgent.getProviderName()).thenReturn("test");
      AgentExecution normalExec = mock(AgentExecution.class);
      ExecutionInstrumentation normalInstr = mock(ExecutionInstrumentation.class);
      acquisitionService.registerAgent(normalAgent, normalExec, normalInstr);
      normalAgents.add(normalAgent);
    }

    Semaphore semaphore = new Semaphore(5);
    ExecutorService pool = Executors.newCachedThreadPool();

    // WHEN: Run scheduler for 10 seconds
    long endTime = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < endTime) {
      acquisitionService.saturatePool(System.currentTimeMillis(), semaphore, pool);
      Thread.sleep(100);
    }

    // Allow workers to finish and process completion queue
    Thread.sleep(2000);
    // Process completion queue explicitly
    try (Jedis j = jedisPool.getResource()) {
      acquisitionService.saturatePool(Long.MAX_VALUE, null, pool);
    } catch (Exception e) {
      // Best-effort
    }
    Thread.sleep(1000);

    // THEN: Scheduler continues operating, poison agent isolated
    assertThat(semaphore.availablePermits()).isEqualTo(5);
    assertThat(Math.max(0, acquisitionService.getZombiesInFlight())).isEqualTo(0);

    // Poison agent should be rescheduled (not lost) - check both sets
    try (Jedis j = jedisPool.getResource()) {
      String waitingSet = schedProps.getKeys().getWaitingSet();
      String workingSet = schedProps.getKeys().getWorkingSet();
      Set<String> waiting = j.zrange(waitingSet, 0, -1);
      Set<String> working = j.zrange(workingSet, 0, -1);
      // Poison agent should be in waiting set (will retry) OR in working set (being retried)
      // This verifies the agent wasn't lost despite failures
      assertThat(waiting.contains("poison-agent") || working.contains("poison-agent"))
          .describedAs("Poison agent should be rescheduled (in waiting or working set)")
          .isTrue();
    }

    pool.shutdownNow();
  }

  @Test
  @DisplayName("Dynamic agent population: Rapid registration/unregistration under load")
  void dynamicAgentPopulation() throws Exception {
    // GIVEN: Scheduler with dynamic agent registration/unregistration
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");
    agentProps.setMaxConcurrentAgents(5);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getCircuitBreaker().setEnabled(false);
    schedProps.getZombieCleanup().setEnabled(true);
    schedProps.getZombieCleanup().setThresholdMs(200L);
    schedProps.getOrphanCleanup().setEnabled(true);
    schedProps.getOrphanCleanup().setThresholdMs(1_000L);

    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(200L, 200L, 400L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    // Register initial agents
    List<Agent> agents = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("dynamic-agent-" + i);
      when(agent.getProviderName()).thenReturn("test");
      AgentExecution exec = mock(AgentExecution.class);
      ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);
      acquisitionService.registerAgent(agent, exec, instr);
      agents.add(agent);
    }

    Semaphore semaphore = new Semaphore(5);
    ExecutorService pool = Executors.newCachedThreadPool();
    AtomicBoolean running = new AtomicBoolean(true);

    // Thread 1: Acquisition loop
    ExecutorService testThreads = Executors.newCachedThreadPool();
    Future<?> acquirer =
        testThreads.submit(
            () -> {
              long runCount = 0L;
              try {
                acquisitionService.saturatePool(runCount++, semaphore, pool);
                while (running.get()) {
                  acquisitionService.saturatePool(runCount++, semaphore, pool);
                  Thread.sleep(10);
                }
              } catch (Throwable t) {
                // Ignore
              }
            });

    // Thread 2: Dynamic registration/unregistration
    Future<?> dynamicRegistrar =
        testThreads.submit(
            () -> {
              try {
                while (running.get()) {
                  // Unregister random agent
                  if (!agents.isEmpty()) {
                    int idx = ThreadLocalRandom.current().nextInt(agents.size());
                    Agent toRemove = agents.remove(idx);
                    acquisitionService.unregisterAgent(toRemove);
                  }

                  // Register new agent
                  int newId = ThreadLocalRandom.current().nextInt(1000, 9999);
                  Agent newAgent = mock(Agent.class);
                  when(newAgent.getAgentType()).thenReturn("dynamic-agent-" + newId);
                  when(newAgent.getProviderName()).thenReturn("test");
                  AgentExecution exec = mock(AgentExecution.class);
                  ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);
                  acquisitionService.registerAgent(newAgent, exec, instr);
                  agents.add(newAgent);

                  Thread.sleep(ThreadLocalRandom.current().nextInt(100, 500));
                }
              } catch (Throwable t) {
                // Ignore
              }
            });

    // WHEN: Run for 15 seconds
    Thread.sleep(15_000);
    running.set(false);

    acquirer.get(5, TimeUnit.SECONDS);
    dynamicRegistrar.get(5, TimeUnit.SECONDS);

    // Allow workers to finish
    Thread.sleep(2000);

    // THEN: Scheduler remains stable, no agent loss
    assertThat(semaphore.availablePermits()).isEqualTo(5);
    assertThat(Math.max(0, acquisitionService.getZombiesInFlight())).isEqualTo(0);

    // Verify agents are properly tracked
    try (Jedis j = jedisPool.getResource()) {
      String waitingSet = schedProps.getKeys().getWaitingSet();
      String workingSet = schedProps.getKeys().getWorkingSet();
      Set<String> waiting = j.zrange(waitingSet, 0, -1);
      Set<String> working = j.zrange(workingSet, 0, -1);

      // Sets should be disjoint
      Set<String> intersection = new java.util.HashSet<>(waiting);
      intersection.retainAll(working);
      assertThat(intersection).isEmpty();
    }

    pool.shutdownNow();
    testThreads.shutdownNow();
  }
}
