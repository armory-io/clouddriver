/*
 * Copyright 2025 Harness, Inc.
 */

package com.netflix.spinnaker.cats.redis.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Integration: Orphan cadence and Zombie fairness")
class CleanupCadenceAndZombieFairnessIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379).withCommand("redis-server");

  private JedisPool jedisPool;
  private RedisScriptManager scripts;
  private PrioritySchedulerMetrics metrics;

  @BeforeEach
  void setUp() {
    jedisPool = new JedisPool(new JedisPoolConfig(), redis.getHost(), redis.getMappedPort(6379));
    metrics = new PrioritySchedulerMetrics(new DefaultRegistry());
    scripts = new RedisScriptManager(jedisPool, metrics);
    scripts.initializeScripts();
    try (Jedis j = jedisPool.getResource()) {
      j.flushAll();
    }
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

  @Nested
  @DisplayName("Orphan cadence")
  class OrphanCadence {
    @Test
    @DisplayName("Runs each interval and preserves leadership TTL semantics")
    void runsEachInterval() throws Exception {
      PrioritySchedulerProperties props = new PrioritySchedulerProperties();
      props.getKeys().setWaitingSet("waiting");
      props.getKeys().setWorkingSet("working");
      props.getKeys().setCleanupLeaderKey("cleanup-leader");
      props.getOrphanCleanup().setEnabled(true);
      props.getOrphanCleanup().setIntervalMs(1000); // 1s cadence
      props.getOrphanCleanup().setRunBudgetMs(200); // budget inside service
      props.getOrphanCleanup().setLeadershipTtlMs(1500);

      OrphanCleanupService orphan = new OrphanCleanupService(jedisPool, scripts, props, metrics);

      long t0 = System.currentTimeMillis();
      orphan.cleanupOrphanedAgentsIfNeeded(); // first run
      long first = orphan.getLastOrphanCleanup();
      assertThat(first).isGreaterThanOrEqualTo(t0);

      // Before interval elapsed: should skip
      Thread.sleep(200);
      long before = orphan.getLastOrphanCleanup();
      orphan.cleanupOrphanedAgentsIfNeeded();
      assertThat(orphan.getLastOrphanCleanup()).isEqualTo(before);

      // After interval: should run again
      Thread.sleep(900);
      orphan.cleanupOrphanedAgentsIfNeeded();
      long second = orphan.getLastOrphanCleanup();
      assertThat(second).isGreaterThan(first);
    }
  }

  @Nested
  @DisplayName("Zombie fairness")
  class ZombieFairness {
    @Test
    @DisplayName("Early permit release increments zIF; worker exit decrements it")
    void earlyPermitReleaseAccounting() throws Exception {
      // Scheduler-ish setup
      AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
      when(intervalProvider.getInterval(any(Agent.class)))
          .thenReturn(new AgentIntervalProvider.Interval(1_000L, 200L, 1_000L));
      ShardingFilter shardingFilter = mock(ShardingFilter.class);
      when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(1);
      agentProps.setEnabledPattern(".*");
      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.getKeys().setWaitingSet("waiting");
      schedProps.getKeys().setWorkingSet("working");
      schedProps.getZombieCleanup().setEnabled(true);
      schedProps.getZombieCleanup().setThresholdMs(300); // short threshold

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool,
              scripts,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedProps,
              metrics);

      // Register a slow agent whose execute blocks until we let it go
      Agent slow = mock(Agent.class);
      when(slow.getAgentType()).thenReturn("fair-agent");
      when(slow.getProviderName()).thenReturn("test");
      AgentExecution exec = mock(AgentExecution.class);
      ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      doAnswer(
              inv -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                return null;
              })
          .when(exec)
          .executeAgent(any());

      acq.registerAgent(slow, exec, instr);

      // Acquire with a real semaphore and a small executor
      Semaphore sem = new Semaphore(1);
      ExecutorService pool = Executors.newFixedThreadPool(1);
      int got = acq.saturatePool(0L, sem, pool);
      assertThat(got).isEqualTo(1);
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

      // Snapshot maps for zombie cleanup
      // NOTE: Pass empty futures map to prevent zombie cleanup from canceling the future
      // We want to control when the worker completes to properly verify zIF accounting
      Map<String, String> active = new ConcurrentHashMap<>(acq.getActiveAgentsMap());
      Map<String, java.util.concurrent.Future<?>> futures =
          new ConcurrentHashMap<>(); // Empty - don't let cleanup cancel the future

      ZombieCleanupService zombies =
          new ZombieCleanupService(jedisPool, scripts, schedProps, metrics);
      zombies.setAcquisitionService(acq);
      zombies.setFairnessHandler(acq);

      // CRITICAL: Check zIF before any cleanup
      int zifBefore = acq.getZombiesInFlight();
      assertThat(zifBefore).describedAs("zombiesInFlight should be 0 before cleanup").isEqualTo(0);

      // Wait just past timeout + threshold and perform cleanup
      // Timeout=200ms, Threshold=300ms, so wait > 500ms to ensure zombie detection
      Thread.sleep(700); // Increased from 600 to 700 for more reliable test

      int cleaned = zombies.cleanupZombieAgents(active, futures);

      // CRITICAL: Check zIF immediately after cleanup, BEFORE releasing the worker
      // The worker is still blocked on release.await(), so it cannot decrement zIF yet
      int zifAfterCleanup = acq.getZombiesInFlight();
      assertThat(zifAfterCleanup)
          .describedAs(
              "zombiesInFlight MUST be incremented after zombie cleanup to track fairness debt. "
                  + "If this fails, early permit release is not properly tracking zombies. "
                  + "cleaned=%d, active_size=%d, zifBefore=%d",
              cleaned, active.size(), zifBefore)
          .isGreaterThanOrEqualTo(1);

      // CRITICAL INVARIANT 2: Permit must be available immediately after early release
      // This proves that zombie cleanup actually released the semaphore
      assertThat(sem.availablePermits())
          .describedAs("Semaphore permit must be available after early release")
          .isEqualTo(1);

      // Now let worker finish; the finally block will run and decrement zIF
      // With new ordering: conditionalReleaseAgent -> removeActiveAgent -> permit handling
      // The worker should detect that permit was pre-released (permitHeld CAS fails)
      // and should decrement zIF (if it had been incremented)
      release.countDown();
      Thread.sleep(250);

      // CRITICAL INVARIANT 3: zombiesInFlight must be decremented when worker finishes
      // This closes the fairness accounting loop: +1 on early release, -1 on completion
      int zifAfterCompletion = acq.getZombiesInFlight();
      assertThat(zifAfterCompletion)
          .describedAs(
              "zombiesInFlight should be decremented to 0 after worker completion. "
                  + "zif_before=%d, zif_after_cleanup=%d, zif_after_completion=%d. "
                  + "If this fails, the worker's finally block is not properly decrementing zIF.",
              zifBefore, zifAfterCleanup, zifAfterCompletion)
          .isEqualTo(0);

      // Verify cleanup did identify and process the zombie
      assertThat(cleaned)
          .describedAs("Zombie cleanup should have cleaned at least 1 agent")
          .isGreaterThanOrEqualTo(1);

      pool.shutdownNow();
    }
  }
}
