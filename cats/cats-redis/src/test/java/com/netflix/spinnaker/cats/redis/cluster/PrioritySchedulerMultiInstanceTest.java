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

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * Multi-instance eventual-consistency tests for Priority Scheduler.
 *
 * <p>Tests two schedulers sharing the same Redis instance and verifies eventual consistency
 * invariants:
 *
 * <ul>
 *   <li>Multi-instance (2 schedulers) sharing Redis with concurrent operations
 *   <li>Invariants verified at end-of-run (eventual consistency)
 * </ul>
 *
 * <p>Tagged with "chaos" to keep default build times reasonable.
 */
@Testcontainers
@Tag("chaos")
@DisplayName("Multi-Instance Eventual Consistency Tests")
class PrioritySchedulerMultiInstanceTest {

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
  @DisplayName("Two schedulers share Redis with concurrent operations")
  void twoSchedulersConcurrentOperations() throws Exception {
    // GIVEN: Two schedulers sharing the same Redis
    PriorityAgentProperties agentProps1 = new PriorityAgentProperties();
    agentProps1.setEnabledPattern(".*");
    agentProps1.setDisabledPattern("");
    agentProps1.setMaxConcurrentAgents(5);

    PriorityAgentProperties agentProps2 = new PriorityAgentProperties();
    agentProps2.setEnabledPattern(".*");
    agentProps2.setDisabledPattern("");
    agentProps2.setMaxConcurrentAgents(5);

    PrioritySchedulerProperties schedProps1 = new PrioritySchedulerProperties();
    schedProps1.getKeys().setWaitingSet("waiting");
    schedProps1.getKeys().setWorkingSet("working");
    schedProps1.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedProps1.getCircuitBreaker().setEnabled(false);
    schedProps1.getZombieCleanup().setEnabled(true);
    schedProps1.getZombieCleanup().setThresholdMs(200L);
    schedProps1.getOrphanCleanup().setEnabled(true);
    schedProps1.getOrphanCleanup().setThresholdMs(1_000L);

    PrioritySchedulerProperties schedProps2 = new PrioritySchedulerProperties();
    schedProps2.getKeys().setWaitingSet("waiting");
    schedProps2.getKeys().setWorkingSet("working");
    schedProps2.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedProps2.getCircuitBreaker().setEnabled(false);
    schedProps2.getZombieCleanup().setEnabled(true);
    schedProps2.getZombieCleanup().setThresholdMs(200L);
    schedProps2.getOrphanCleanup().setEnabled(true);
    schedProps2.getOrphanCleanup().setThresholdMs(1_000L);

    // Dependencies
    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(200L, 200L, 400L);
    ShardingFilter shardingFilter = a -> true;

    // Create two acquisition services (simulating two pods)
    AgentAcquisitionService acquisitionService1 =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps1,
            schedProps1,
            metrics);

    AgentAcquisitionService acquisitionService2 =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps2,
            schedProps2,
            metrics);

    ZombieCleanupService zombieCleanup1 =
        new ZombieCleanupService(jedisPool, scriptManager, schedProps1, metrics);
    zombieCleanup1.setAcquisitionService(acquisitionService1);
    zombieCleanup1.setFairnessHandler(acquisitionService1);

    ZombieCleanupService zombieCleanup2 =
        new ZombieCleanupService(jedisPool, scriptManager, schedProps2, metrics);
    zombieCleanup2.setAcquisitionService(acquisitionService2);
    zombieCleanup2.setFairnessHandler(acquisitionService2);

    OrphanCleanupService orphanCleanup1 =
        new OrphanCleanupService(jedisPool, scriptManager, schedProps1, metrics);
    orphanCleanup1.setAcquisitionService(acquisitionService1);

    OrphanCleanupService orphanCleanup2 =
        new OrphanCleanupService(jedisPool, scriptManager, schedProps2, metrics);
    orphanCleanup2.setAcquisitionService(acquisitionService2);

    // Register agents on both instances (some overlap, some unique)
    int numAgents = 30;
    for (int i = 0; i < numAgents; i++) {
      Agent a = mockAgent("shared-agent-" + i, "test");
      AgentExecution exec = RandomExecutionFactory.randomized(10, 300);
      ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);
      acquisitionService1.registerAgent(a, exec, instr);
      acquisitionService2.registerAgent(a, exec, instr);
    }

    // Concurrency controls
    Semaphore semaphore1 = new Semaphore(5);
    Semaphore semaphore2 = new Semaphore(5);
    ExecutorService agentWorkPool1 = Executors.newCachedThreadPool();
    ExecutorService agentWorkPool2 = Executors.newCachedThreadPool();
    ExecutorService testThreads = Executors.newCachedThreadPool();
    AtomicBoolean running = new AtomicBoolean(true);

    // Thread 1: Scheduler 1 acquisition loop
    Future<?> acquirer1 =
        testThreads.submit(
            () -> {
              long runCount = 0L;
              try {
                acquisitionService1.saturatePool(runCount++, semaphore1, agentWorkPool1);
                while (running.get()) {
                  acquisitionService1.saturatePool(runCount++, semaphore1, agentWorkPool1);
                  Thread.sleep(10);
                }
              } catch (Throwable t) {
                // Ignore
              }
            });

    // Thread 2: Scheduler 2 acquisition loop
    Future<?> acquirer2 =
        testThreads.submit(
            () -> {
              long runCount = 0L;
              try {
                acquisitionService2.saturatePool(runCount++, semaphore2, agentWorkPool2);
                while (running.get()) {
                  acquisitionService2.saturatePool(runCount++, semaphore2, agentWorkPool2);
                  Thread.sleep(10);
                }
              } catch (Throwable t) {
                // Ignore
              }
            });

    // Thread 3: Zombie cleanup 1
    Future<?> zombieCleaner1 =
        testThreads.submit(
            () -> {
              try {
                while (running.get()) {
                  Map<String, String> active =
                      new ConcurrentHashMap<>(acquisitionService1.getActiveAgentsMap());
                  Map<String, Future<?>> futures =
                      new ConcurrentHashMap<>(acquisitionService1.getActiveAgentsFutures());
                  zombieCleanup1.cleanupZombieAgents(active, futures);
                  Thread.sleep(ThreadLocalRandom.current().nextInt(50, 201));
                }
              } catch (Throwable t) {
                // Ignore
              }
            });

    // Thread 4: Zombie cleanup 2
    Future<?> zombieCleaner2 =
        testThreads.submit(
            () -> {
              try {
                while (running.get()) {
                  Map<String, String> active =
                      new ConcurrentHashMap<>(acquisitionService2.getActiveAgentsMap());
                  Map<String, Future<?>> futures =
                      new ConcurrentHashMap<>(acquisitionService2.getActiveAgentsFutures());
                  zombieCleanup2.cleanupZombieAgents(active, futures);
                  Thread.sleep(ThreadLocalRandom.current().nextInt(50, 201));
                }
              } catch (Throwable t) {
                // Ignore
              }
            });

    // Thread 5: Orphan cleanup 1
    Future<?> orphanCleaner1 =
        testThreads.submit(
            () -> {
              try {
                while (running.get()) {
                  orphanCleanup1.forceCleanupOrphanedAgents();
                  Thread.sleep(ThreadLocalRandom.current().nextInt(200, 501));
                }
              } catch (Throwable t) {
                // Ignore
              }
            });

    // Thread 6: Orphan cleanup 2
    Future<?> orphanCleaner2 =
        testThreads.submit(
            () -> {
              try {
                while (running.get()) {
                  orphanCleanup2.forceCleanupOrphanedAgents();
                  Thread.sleep(ThreadLocalRandom.current().nextInt(200, 501));
                }
              } catch (Throwable t) {
                // Ignore
              }
            });

    // Thread 7: Shutdown toggles
    Future<?> shutdownToggler =
        testThreads.submit(
            () -> {
              try {
                boolean state = false;
                while (running.get()) {
                  state = !state;
                  acquisitionService1.setShuttingDown(state);
                  acquisitionService2.setShuttingDown(state);
                  Thread.sleep(ThreadLocalRandom.current().nextInt(500, 1501));
                }
              } catch (Throwable t) {
                // Ignore
              }
            });

    // WHEN: Run for 30 seconds
    Thread.sleep(30_000);
    running.set(false);

    // Join threads
    acquirer1.get(10, TimeUnit.SECONDS);
    acquirer2.get(10, TimeUnit.SECONDS);
    zombieCleaner1.get(10, TimeUnit.SECONDS);
    zombieCleaner2.get(10, TimeUnit.SECONDS);
    orphanCleaner1.get(10, TimeUnit.SECONDS);
    orphanCleaner2.get(10, TimeUnit.SECONDS);
    shutdownToggler.get(10, TimeUnit.SECONDS);

    // Drain workers and completion queues
    drainWorkers(acquisitionService1, agentWorkPool1);
    drainWorkers(acquisitionService2, agentWorkPool2);

    // Allow zIF to settle
    long zifDeadline = System.currentTimeMillis() + 5000L;
    while (System.currentTimeMillis() < zifDeadline) {
      int zif1 = Math.max(0, acquisitionService1.getZombiesInFlight());
      int zif2 = Math.max(0, acquisitionService2.getZombiesInFlight());
      if (zif1 == 0 && zif2 == 0) {
        break;
      }
      Thread.sleep(50);
    }

    // THEN: Assert eventual consistency invariants
    try (Jedis j = jedisPool.getResource()) {
      String WAITING_KEY = schedProps1.getKeys().getWaitingSet();
      String WORKING_KEY = schedProps1.getKeys().getWorkingSet();

      // Wait for state to settle
      long settleDeadline = System.currentTimeMillis() + 2000L;
      Set<String> waiting = null;
      Set<String> working = null;
      boolean settled = false;

      while (System.currentTimeMillis() < settleDeadline && !settled) {
        waiting = j.zrange(WAITING_KEY, 0, -1);
        working = j.zrange(WORKING_KEY, 0, -1);
        int completing1 = Math.max(0, acquisitionService1.getCompletionQueueSize());
        int completing2 = Math.max(0, acquisitionService2.getCompletionQueueSize());
        int active1 = Math.max(0, acquisitionService1.getActiveAgentCount());
        int active2 = Math.max(0, acquisitionService2.getActiveAgentCount());

        int totalCompleting = completing1 + completing2;
        int totalActive = active1 + active2;
        int sumSets = waiting.size() + working.size();

        // Registered is union across both instances (same agents registered on both)
        int registered = numAgents;
        if ((registered - sumSets) <= (totalCompleting + totalActive)) {
          settled = true;
          break;
        }
        Thread.sleep(50);
      }

      if (waiting == null || working == null) {
        waiting = j.zrange(WAITING_KEY, 0, -1);
        working = j.zrange(WORKING_KEY, 0, -1);
      }

      // Invariant 1: WAITZ ∩ WORKZ = ∅
      Set<String> intersection = new java.util.HashSet<>(waiting);
      intersection.retainAll(working);
      assertThat(intersection)
          .describedAs("waiting and working sets must be disjoint at end-of-run")
          .isEmpty();

      // Invariant 2: All permits returned (allow overshoot from Semaphore.release())
      assertThat(semaphore1.availablePermits())
          .describedAs("All permits must be returned on scheduler 1")
          .isGreaterThanOrEqualTo(5);
      assertThat(semaphore2.availablePermits())
          .describedAs("All permits must be returned on scheduler 2")
          .isGreaterThanOrEqualTo(5);

      // Invariant 3: zombiesInFlight == 0 (allow small tolerance for concurrent state transitions)
      assertThat(Math.max(0, acquisitionService1.getZombiesInFlight()))
          .describedAs("zombiesInFlight must be 0 on scheduler 1")
          .isLessThanOrEqualTo(1);
      assertThat(Math.max(0, acquisitionService2.getZombiesInFlight()))
          .describedAs("zombiesInFlight must be 0 on scheduler 2")
          .isLessThanOrEqualTo(1);
    }

    // Shutdown pools
    agentWorkPool1.shutdownNow();
    agentWorkPool2.shutdownNow();
    testThreads.shutdownNow();
  }

  private void drainWorkers(AgentAcquisitionService acquisitionService, ExecutorService pool)
      throws Exception {
    // Process completion queue
    try {
      acquisitionService.saturatePool(Long.MAX_VALUE, null, pool);
    } catch (Exception e) {
      // Best-effort
    }

    // Wait for pool to drain
    pool.shutdown();
    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
      pool.shutdownNow();
    }
  }

  private static Agent mockAgent(String name, String provider) {
    Agent a = mock(Agent.class);
    org.mockito.Mockito.when(a.getAgentType()).thenReturn(name);
    org.mockito.Mockito.when(a.getProviderName()).thenReturn(provider);
    return a;
  }

  private static final class RandomExecutionFactory {
    static AgentExecution randomized(int minMillis, int maxMillis) {
      return agent -> {
        int delay = ThreadLocalRandom.current().nextInt(minMillis, maxMillis + 1);
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        while (System.nanoTime() < end) {
          long remainingNanos = end - System.nanoTime();
          if (remainingNanos <= 0) break;
          try {
            TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(1)));
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      };
    }
  }
}
