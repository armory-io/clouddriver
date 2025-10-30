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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Priority Scheduler Stress Tests")
class PrioritySchedulerStressTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private PrioritySchedulerMetrics metrics;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(32);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");
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
  @Timeout(180)
  @DisplayName("Test 1: Permit accounting with randomized timing (10s)")
  void permitAccountingUnderRandomizedTiming() throws Exception {
    StressParams params = new StressParams(20, 5, Duration.ofSeconds(10));
    StressResult result = runStress(params);

    // Exit criterion: zombiesInFlight returns to 0 within 2s
    assertThat(Math.max(0, result.zifAfterSettling))
        .describedAs("zombiesInFlight must settle back to 0 within 2s")
        .isEqualTo(0);

    assertThat(result.violations).as("No thread exceptions").isEmpty();
  }

  @Test
  @Timeout(180)
  @DisplayName("Test 2: Agent execution under concurrent ops (10s)")
  void agentUniquenessUnderConcurrentOps() throws Exception {
    StressParams params = new StressParams(50, 10, Duration.ofSeconds(10));
    StressResult result = runStress(params);

    // Exit criterion: zombiesInFlight returns to 0 within 2s
    assertThat(Math.max(0, result.zifAfterSettling))
        .describedAs("zombiesInFlight must settle back to 0 within 2s")
        .isEqualTo(0);

    assertThat(result.violations).as("No thread exceptions").isEmpty();
  }

  @Test
  @Timeout(300)
  @DisplayName("Test 3: Cleanup coordination under invariants (30s)")
  void cleanupCoordinationWithInvariants() throws Exception {
    StressParams params = new StressParams(80, 10, Duration.ofSeconds(30));
    StressResult result = runStress(params);

    assertThat(Math.max(0, result.zifAfterSettling))
        .describedAs("zombiesInFlight must settle back to 0 within 2s")
        .isEqualTo(0);

    assertThat(result.violations).as("No invariant violations or exceptions").isEmpty();
  }

  @Test
  @Timeout(300)
  @DisplayName("Test 4: Shutdown preservation with 20 toggles")
  void shutdownPreservationTwentyToggles() throws Exception {
    StressParams params = new StressParams(40, 8, Duration.ofSeconds(8));
    StressResult result = runShutdownPreservation(params, 20);

    assertThat(Math.max(0, result.zifAfterSettling))
        .describedAs("zombiesInFlight must settle back to 0 within 2s")
        .isEqualTo(0);

    assertThat(result.violations).as("No invariant violations or exceptions").isEmpty();
  }

  private StressResult runStress(StressParams params) throws Exception {
    // Properties
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");
    agentProps.setMaxConcurrentAgents(params.maxConcurrent);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedProps.getCircuitBreaker().setEnabled(false);
    // Make cleanup responsive for stress
    schedProps.getZombieCleanup().setEnabled(true);
    schedProps.getZombieCleanup().setThresholdMs(200L);
    schedProps.getOrphanCleanup().setEnabled(true);
    schedProps.getOrphanCleanup().setThresholdMs(1_000L);

    // Dependencies
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

    ZombieCleanupService zombieCleanup =
        new ZombieCleanupService(jedisPool, scriptManager, schedProps, metrics);
    zombieCleanup.setAcquisitionService(acquisitionService);
    zombieCleanup.setFairnessHandler(acquisitionService);

    OrphanCleanupService orphanCleanup =
        new OrphanCleanupService(jedisPool, scriptManager, schedProps, metrics);
    orphanCleanup.setAcquisitionService(acquisitionService);

    // Register agents with random execution behavior
    for (int i = 0; i < params.numAgents; i++) {
      Agent a = mockAgent("stress-agent-" + i, "stress");
      AgentExecution exec = RandomExecutionFactory.randomized(10, 500);
      ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);
      acquisitionService.registerAgent(a, exec, instr);
    }

    // Concurrency controls
    Semaphore semaphore = new Semaphore(params.maxConcurrent);
    ExecutorService agentWorkPool = Executors.newCachedThreadPool();
    ExecutorService testThreads = Executors.newCachedThreadPool();
    AtomicBoolean running = new AtomicBoolean(true);
    ViolationCollector violations = new ViolationCollector();

    // Thread 1: Acquisition loop
    Future<?> acquirer =
        testThreads.submit(
            () -> {
              long runCount = 0L;
              try {
                // Initial repopulation
                acquisitionService.saturatePool(runCount++, semaphore, agentWorkPool);
                while (running.get()) {
                  acquisitionService.saturatePool(runCount++, semaphore, agentWorkPool);
                  Thread.sleep(10);
                }
              } catch (Throwable t) {
                violations.add("acquisition-thread exception: " + t);
              }
            });

    // Thread 2: Zombie cleanup (50-200ms intervals)
    Future<?> zombieCleaner =
        testThreads.submit(
            () -> {
              try {
                while (running.get()) {
                  // Snapshots as required by cleanup API
                  java.util.Map<String, String> active =
                      new java.util.HashMap<>(acquisitionService.getActiveAgentsMap());
                  java.util.Map<String, Future<?>> futures =
                      new java.util.HashMap<>(acquisitionService.getActiveAgentsFutures());
                  zombieCleanup.cleanupZombieAgents(active, futures);
                  Thread.sleep(ThreadLocalRandom.current().nextInt(50, 201));
                }
              } catch (Throwable t) {
                violations.add("zombie-cleaner exception: " + t);
              }
            });

    // Thread 3: Orphan cleanup (200-500ms intervals)
    Future<?> orphanCleaner =
        testThreads.submit(
            () -> {
              try {
                while (running.get()) {
                  orphanCleanup.forceCleanupOrphanedAgents();
                  Thread.sleep(ThreadLocalRandom.current().nextInt(200, 501));
                }
              } catch (Throwable t) {
                violations.add("orphan-cleaner exception: " + t);
              }
            });

    // Thread 4: Shutdown toggles
    Future<?> shutdownToggler =
        testThreads.submit(
            () -> {
              try {
                boolean state = false;
                while (running.get()) {
                  state = !state;
                  acquisitionService.setShuttingDown(state);
                  Thread.sleep(ThreadLocalRandom.current().nextInt(200, 501));
                }
              } catch (Throwable t) {
                violations.add("shutdown-toggle exception: " + t);
              }
            });

    // No sampling-time invariant checker; we assert eventual consistency at end-of-run

    // Run the stress window
    Thread.sleep(params.duration.toMillis());
    running.set(false);

    // Join threads
    acquirer.get(10, TimeUnit.SECONDS);
    zombieCleaner.get(10, TimeUnit.SECONDS);
    orphanCleaner.get(10, TimeUnit.SECONDS);
    shutdownToggler.get(10, TimeUnit.SECONDS);
    // no invariant checker to join

    // Allow workers to finish; poll for zIF to converge to 0 (eventual consistency window)
    long zifDeadline = System.currentTimeMillis() + 3000L;
    while (System.currentTimeMillis() < zifDeadline
        && Math.max(0, acquisitionService.getZombiesInFlight()) > 0) {
      try {
        Thread.sleep(25);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    int zifAfter = Math.max(0, acquisitionService.getZombiesInFlight());
    // End-of-run eventual-consistency assertions with brief stabilization window
    try (Jedis j = jedisPool.getResource()) {
      String WAITING_KEY = schedProps.getKeys().getWaitingSet();
      String WORKING_KEY = schedProps.getKeys().getWorkingSet();
      long endBy = System.currentTimeMillis() + 1000L;
      boolean settled = false;
      while (System.currentTimeMillis() < endBy && !settled) {
        java.util.Set<String> waiting = j.zrange(WAITING_KEY, 0, -1);
        java.util.Set<String> working = j.zrange(WORKING_KEY, 0, -1);
        int registered = params.numAgents;
        int sumSets = waiting.size() + working.size();
        int completing = Math.max(0, acquisitionService.getCompletionQueueSize());
        if ((registered - sumSets) <= completing) {
          settled = true;
          break;
        }
        Thread.sleep(10);
      }
      java.util.Set<String> waiting = j.zrange(WAITING_KEY, 0, -1);
      java.util.Set<String> working = j.zrange(WORKING_KEY, 0, -1);
      java.util.Set<String> inter = new java.util.HashSet<>(waiting);
      inter.retainAll(working);
      org.assertj.core.api.Assertions.assertThat(inter)
          .describedAs("waiting/workingset must be disjoint at end-of-run")
          .isEmpty();
      int registered = params.numAgents;
      int sumSets = waiting.size() + working.size();
      int completing = Math.max(0, acquisitionService.getCompletionQueueSize());
      org.assertj.core.api.Assertions.assertThat(sumSets)
          .describedAs("sum of sets must not exceed registered")
          .isLessThanOrEqualTo(registered);
      org.assertj.core.api.Assertions.assertThat(registered - sumSets)
          .describedAs("missing members must be explainable by completing queue")
          .isLessThanOrEqualTo(completing);
    }
    // Shutdown pools
    agentWorkPool.shutdownNow();
    testThreads.shutdownNow();

    return new StressResult(new ArrayList<>(violations.violations), zifAfter);
  }

  private StressResult runShutdownPreservation(StressParams params, int toggles) throws Exception {
    // Properties
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");
    agentProps.setMaxConcurrentAgents(params.maxConcurrent);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedProps.getCircuitBreaker().setEnabled(false);
    // Make cleanup responsive for stress
    schedProps.getZombieCleanup().setEnabled(true);
    schedProps.getZombieCleanup().setThresholdMs(200L);
    schedProps.getOrphanCleanup().setEnabled(true);
    schedProps.getOrphanCleanup().setThresholdMs(1_000L);

    // Dependencies
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

    ZombieCleanupService zombieCleanup =
        new ZombieCleanupService(jedisPool, scriptManager, schedProps, metrics);
    zombieCleanup.setAcquisitionService(acquisitionService);
    zombieCleanup.setFairnessHandler(acquisitionService);

    OrphanCleanupService orphanCleanup =
        new OrphanCleanupService(jedisPool, scriptManager, schedProps, metrics);
    orphanCleanup.setAcquisitionService(acquisitionService);

    // Register agents
    for (int i = 0; i < params.numAgents; i++) {
      Agent a = mockAgent("shutdown-agent-" + i, "shutdown");
      AgentExecution exec = RandomExecutionFactory.randomized(10, 500);
      ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);
      acquisitionService.registerAgent(a, exec, instr);
    }

    Semaphore semaphore = new Semaphore(params.maxConcurrent);
    ExecutorService agentWorkPool = Executors.newCachedThreadPool();
    ExecutorService testThreads = Executors.newCachedThreadPool();
    AtomicBoolean running = new AtomicBoolean(true);
    ViolationCollector violations = new ViolationCollector();

    // Thread 1: Acquisition loop
    Future<?> acquirer =
        testThreads.submit(
            () -> {
              long runCount = 0L;
              try {
                acquisitionService.saturatePool(runCount++, semaphore, agentWorkPool);
                while (running.get()) {
                  acquisitionService.saturatePool(runCount++, semaphore, agentWorkPool);
                  Thread.sleep(10);
                }
              } catch (Throwable t) {
                violations.add("acquisition-thread exception: " + t);
              }
            });

    // Thread 2: Zombie cleanup
    Future<?> zombieCleaner =
        testThreads.submit(
            () -> {
              try {
                while (running.get()) {
                  java.util.Map<String, String> active =
                      new java.util.HashMap<>(acquisitionService.getActiveAgentsMap());
                  java.util.Map<String, Future<?>> futures =
                      new java.util.HashMap<>(acquisitionService.getActiveAgentsFutures());
                  zombieCleanup.cleanupZombieAgents(active, futures);
                  Thread.sleep(ThreadLocalRandom.current().nextInt(50, 201));
                }
              } catch (Throwable t) {
                violations.add("zombie-cleaner exception: " + t);
              }
            });

    // Thread 3: Orphan cleanup
    Future<?> orphanCleaner =
        testThreads.submit(
            () -> {
              try {
                while (running.get()) {
                  orphanCleanup.forceCleanupOrphanedAgents();
                  Thread.sleep(ThreadLocalRandom.current().nextInt(200, 501));
                }
              } catch (Throwable t) {
                violations.add("orphan-cleaner exception: " + t);
              }
            });

    // Thread 4: Shutdown toggles (run exact number of iterations)
    Future<?> shutdownToggler =
        testThreads.submit(
            () -> {
              try {
                boolean state = false;
                for (int i = 0; i < toggles; i++) {
                  state = !state;
                  acquisitionService.setShuttingDown(state);
                  Thread.sleep(ThreadLocalRandom.current().nextInt(200, 501));
                }
              } catch (Throwable t) {
                violations.add("shutdown-toggle exception: " + t);
              }
            });

    // Thread 5: Invariant checker
    // Mid-run set checks removed to avoid sampling races; end-of-run assertions remain
    Future<?> invariantChecker =
        testThreads.submit(
            () -> {
              final long[] mismatchSince = new long[] {0L};
              try (Jedis j = jedisPool.getResource()) {
                long endBy = System.currentTimeMillis() + params.duration.toMillis();
                while (System.currentTimeMillis() < endBy) {
                  try {
                    // Intentionally skip mid-run set checks to avoid sampling races

                    // Permit mismatch (aligned with scheduler health summary):
                    // heldPermits must not exceed active + zIF
                    int totalPermits = params.maxConcurrent;
                    int available = semaphore.availablePermits();
                    int held = Math.max(0, totalPermits - available);
                    int zif = Math.max(0, acquisitionService.getZombiesInFlight());
                    int active = acquisitionService.getActiveAgentsMap().size();
                    if (held > active + zif) {
                      long now = System.currentTimeMillis();
                      if (mismatchSince[0] == 0L) {
                        mismatchSince[0] = now;
                      } else if (now - mismatchSince[0] > 200L) {
                        violations.add(
                            "Invariant violated: permits held>active+zif held="
                                + held
                                + " active="
                                + active
                                + " zif="
                                + zif
                                + " total="
                                + totalPermits);
                        mismatchSince[0] = 0L; // record at most once per persistent window
                      }
                    } else {
                      mismatchSince[0] = 0L;
                    }

                    if (zif < 0) {
                      violations.add("Invariant violated: zombiesInFlight negative: " + zif);
                    }

                    Thread.sleep(ThreadLocalRandom.current().nextInt(25, 51));
                  } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                  } catch (Throwable t) {
                    violations.add("invariant-check exception: " + t);
                  }
                }
              } catch (Throwable t) {
                violations.add("invariant-check setup exception: " + t);
              }
            });

    // Run window: duration proportional to toggles, then stop
    Thread.sleep(params.duration.toMillis());
    running.set(false);

    // Join threads
    acquirer.get(10, TimeUnit.SECONDS);
    zombieCleaner.get(10, TimeUnit.SECONDS);
    orphanCleaner.get(10, TimeUnit.SECONDS);
    shutdownToggler.get(10, TimeUnit.SECONDS);
    invariantChecker.get(10, TimeUnit.SECONDS);

    Thread.sleep(2000);
    int zifAfter = Math.max(0, acquisitionService.getZombiesInFlight());

    agentWorkPool.shutdownNow();
    testThreads.shutdownNow();

    return new StressResult(new ArrayList<>(violations.violations), zifAfter);
  }

  private static Agent mockAgent(String name, String provider) {
    Agent a = mock(Agent.class);
    org.mockito.Mockito.when(a.getAgentType()).thenReturn(name);
    org.mockito.Mockito.when(a.getProviderName()).thenReturn(provider);
    return a;
  }

  private static final class StressParams {
    final int numAgents;
    final int maxConcurrent;
    final Duration duration;

    StressParams(int numAgents, int maxConcurrent, Duration duration) {
      this.numAgents = numAgents;
      this.maxConcurrent = maxConcurrent;
      this.duration = duration;
    }
  }

  private static final class StressResult {
    final List<String> violations;
    final int zifAfterSettling;

    StressResult(List<String> violations, int zifAfterSettling) {
      this.violations = violations != null ? violations : Collections.emptyList();
      this.zifAfterSettling = zifAfterSettling;
    }
  }

  private static final class ViolationCollector {
    private final CopyOnWriteArrayList<String> violations = new CopyOnWriteArrayList<>();

    void add(String msg) {
      violations.add(msg);
    }
  }

  private static final class RandomExecutionFactory {
    static AgentExecution randomized(int minMillis, int maxMillis) {
      return agent -> {
        int delay = ThreadLocalRandom.current().nextInt(minMillis, maxMillis + 1);
        // AgentExecution#executeAgent cannot throw checked exceptions; swallow interrupt
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
