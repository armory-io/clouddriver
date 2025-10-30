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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
@DisplayName("Coordination: Zombie and Orphan cleanup concurrently")
class ZombieOrphanCoordinationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private RedisScriptManager scripts;
  private PrioritySchedulerMetrics metrics;

  @BeforeEach
  void setup() {
    jedisPool = new JedisPool(new JedisPoolConfig(), redis.getHost(), redis.getMappedPort(6379));
    metrics = new PrioritySchedulerMetrics(new DefaultRegistry());
    scripts = new RedisScriptManager(jedisPool, metrics);
    scripts.initializeScripts();
    try (Jedis j = jedisPool.getResource()) {
      j.flushDB();
    }
  }

  @AfterEach
  void teardown() {
    if (jedisPool != null) {
      try (Jedis j = jedisPool.getResource()) {
        j.flushDB();
      } catch (Exception ignore) {
      }
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("Both cleanup services act on same stuck agent exactly once")
  void zombieAndOrphanCoordination() throws Exception {
    // Properties
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getZombieCleanup().setEnabled(true);
    schedProps.getZombieCleanup().setThresholdMs(300);
    schedProps.getOrphanCleanup().setEnabled(true);
    schedProps.getOrphanCleanup().setThresholdMs(500);

    // Service wiring
    AgentIntervalProvider ivp = a -> new AgentIntervalProvider.Interval(200L, 200L, 200L);
    ShardingFilter shard = a -> true;

    AgentAcquisitionService acq =
        new AgentAcquisitionService(
            jedisPool, scripts, ivp, shard, agentProps, schedProps, metrics);

    ZombieCleanupService zombies =
        new ZombieCleanupService(jedisPool, scripts, schedProps, metrics);
    zombies.setAcquisitionService(acq);
    zombies.setFairnessHandler(acq);

    OrphanCleanupService orphans =
        new OrphanCleanupService(jedisPool, scripts, schedProps, metrics);
    orphans.setAcquisitionService(acq);

    // Slow agent blocks until released
    Agent slow = mock(Agent.class);
    when(slow.getAgentType()).thenReturn("coord-agent");
    when(slow.getProviderName()).thenReturn("test");
    AgentExecution exec = mock(AgentExecution.class);
    ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // Mockito for void method: use doAnswer(...).when(exec).executeAgent(...)
    org.mockito.Mockito.doAnswer(
            inv -> {
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              return null;
            })
        .when(exec)
        .executeAgent(any());

    acq.registerAgent(slow, exec, instr);

    Semaphore sem = new Semaphore(1);
    ExecutorService pool = Executors.newFixedThreadPool(1);
    int got = acq.saturatePool(0L, sem, pool);
    assertThat(got).isEqualTo(1);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    // Wait past timeout+thresholds
    Thread.sleep(800);

    // Snapshots for zombie cleanup
    Map<String, String> active = new ConcurrentHashMap<>(acq.getActiveAgentsMap());
    Map<String, Future<?>> futures = new ConcurrentHashMap<>(acq.getActiveAgentsFutures());

    // Run both cleanups concurrently to validate coordination
    ExecutorService cleaners = Executors.newFixedThreadPool(2);
    Future<?> zf = cleaners.submit(() -> zombies.cleanupZombieAgents(active, futures));
    Future<?> of = cleaners.submit(orphans::forceCleanupOrphanedAgents);
    zf.get(5, TimeUnit.SECONDS);
    of.get(5, TimeUnit.SECONDS);

    // Early permit release should have occurred
    assertThat(sem.availablePermits()).isEqualTo(1);

    // Let worker finish; poll briefly for zIF to reconcile to 0
    release.countDown();
    long deadline = System.currentTimeMillis() + 1000L;
    while (System.currentTimeMillis() < deadline && Math.max(0, acq.getZombiesInFlight()) > 0) {
      Thread.sleep(25);
    }
    assertThat(Math.max(0, acq.getZombiesInFlight())).isEqualTo(0);

    cleaners.shutdownNow();
    pool.shutdownNow();
  }
}
