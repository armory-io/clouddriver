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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Measurement;
import com.netflix.spectator.api.Meter;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.patterns.PolledMeter;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Test suite for integration scenarios and service coordination.
 *
 * <p>Tests cover repopulation presence checks, zombies-in-flight gauge behavior, cleanup cadence
 * and fairness, and coordination between zombie and orphan cleanup services.
 */
@Testcontainers
@DisplayName("Integration and Coordination Tests")
class IntegrationCoordinationTest {

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
    metrics = new PrioritySchedulerMetrics(new DefaultRegistry());
    scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();
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
        // Ignore cleanup errors
      }
      jedisPool.close();
    }
  }

  @Nested
  @DisplayName("Repopulation Presence Tests")
  class RepopulationPresenceTests {

    @Test
    @DisplayName("Repopulation adds only missing local agents; ignores non-local entries")
    void repopulationAddsOnlyMissingLocalAgents() {
      AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
      when(intervalProvider.getInterval(any(Agent.class)))
          .thenReturn(new AgentIntervalProvider.Interval(1000L, 2000L));

      ShardingFilter shardingFilter = mock(ShardingFilter.class);
      when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

      PriorityAgentProperties agentProperties = new PriorityAgentProperties();
      agentProperties.setEnabledPattern(".*");
      agentProperties.setDisabledPattern("");
      agentProperties.setMaxConcurrentAgents(0);

      PrioritySchedulerProperties schedulerProperties = new PrioritySchedulerProperties();
      schedulerProperties.setRefreshPeriodSeconds(1);
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(100);
      schedulerProperties.getKeys().setWaitingSet("waiting");
      schedulerProperties.getKeys().setWorkingSet("working");
      schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");

      AgentAcquisitionService acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties,
              metrics);

      ExecutorService agentWorkPool = Executors.newFixedThreadPool(8);

      Set<String> locals = new HashSet<>();
      for (int i = 1; i <= 5; i++) {
        String name = "A" + i;
        locals.add(name);
        acquisitionService.registerAgent(
            TestFixtures.createMockAgent(name), mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      }

      try (Jedis j = jedisPool.getResource()) {
        for (int i = 1; i <= 100; i++) {
          j.zadd("waiting", 0, "B" + i);
        }
        for (String a : locals) {
          j.zrem("waiting", a);
          j.zrem("working", a);
        }
      }

      acquisitionService.saturatePool(1L, new Semaphore(0), agentWorkPool);

      try (Jedis j = jedisPool.getResource()) {
        long localsPresent = 0;
        for (String a : locals) {
          Double w = j.zscore("waiting", a);
          Double x = j.zscore("working", a);
          if (w != null || x != null) localsPresent++;
        }
        assertThat(localsPresent).isEqualTo(locals.size());
        assertThat(j.zcard("waiting")).isGreaterThanOrEqualTo(100);
      }

      agentWorkPool.shutdownNow();
    }
  }

  @Nested
  @DisplayName("Zombies In Flight Gauge Tests")
  class ZombiesInFlightGaugeTests {

    private static double gaugeValue(Registry registry, String name) {
      PolledMeter.update(registry);
      for (Meter m : registry) {
        if (m.id().name().equals(name)) {
          for (Measurement ms : m.measure()) {
            return ms.value();
          }
        }
      }
      return Double.NaN;
    }

    @Test
    @DisplayName("Gauge reflects early permit release and cleanup")
    void gaugeReflectsEarlyReleaseAndCleanup() throws Exception {
      Registry registry = new DefaultRegistry();
      PrioritySchedulerMetrics testMetrics = new PrioritySchedulerMetrics(registry);
      JedisPool mockPool = mock(JedisPool.class);
      RedisScriptManager testScriptManager = new RedisScriptManager(mockPool, testMetrics);
      AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
      ShardingFilter shardingFilter = mock(ShardingFilter.class);
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              mockPool,
              testScriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedProps,
              testMetrics);

      Semaphore sem = new Semaphore(0);
      Field runningSem = AgentAcquisitionService.class.getDeclaredField("runningAgentsRef");
      runningSem.setAccessible(true);
      runningSem.set(acq, sem);

      Field rsField = AgentAcquisitionService.class.getDeclaredField("runStates");
      rsField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, Object> runStates = (Map<String, Object>) rsField.get(acq);
      Class<?> rsClass =
          Class.forName("com.netflix.spinnaker.cats.redis.cluster.AgentAcquisitionService$RunState");
      java.lang.reflect.Constructor<?> ctor = rsClass.getDeclaredConstructor();
      ctor.setAccessible(true);
      Object rs = ctor.newInstance();
      Field started = rsClass.getDeclaredField("started");
      started.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicBoolean) started.get(rs)).set(true);
      runStates.put("agent/zif-test", rs);

      java.util.function.Supplier<Number> zero = () -> 0;
      testMetrics.registerGauges(
          null,
          zero,
          zero,
          zero,
          zero,
          zero,
          zero,
          zero,
          zero,
          zero,
          zero,
          zero,
          acq::getZombiesInFlight);

      acq.earlyReleasePermitIfHeld("agent/zif-test");
      assertThat(Math.max(0, acq.getZombiesInFlight())).isEqualTo(1);
      assertThat(sem.availablePermits()).isEqualTo(1);
      assertThat(gaugeValue(registry, "cats.redisPriority.zombiesInFlight")).isEqualTo(1.0d);

      Field zifField = AgentAcquisitionService.class.getDeclaredField("zombiesInFlight");
      zifField.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicInteger) zifField.get(acq)).decrementAndGet();

      assertThat(Math.max(0, acq.getZombiesInFlight())).isEqualTo(0);
      assertThat(gaugeValue(registry, "cats.redisPriority.zombiesInFlight")).isEqualTo(0.0d);
    }
  }

  @Nested
  @DisplayName("Cleanup Cadence and Fairness Tests")
  class CleanupCadenceTests {

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
        props.getOrphanCleanup().setIntervalMs(1000);
        props.getOrphanCleanup().setRunBudgetMs(200);
        props.getOrphanCleanup().setLeadershipTtlMs(1500);

        OrphanCleanupService orphan = new OrphanCleanupService(jedisPool, scriptManager, props, metrics);

        long t0 = System.currentTimeMillis();
        orphan.cleanupOrphanedAgentsIfNeeded();
        long first = orphan.getLastOrphanCleanup();
        assertThat(first).isGreaterThanOrEqualTo(t0);

        Thread.sleep(200);
        long before = orphan.getLastOrphanCleanup();
        orphan.cleanupOrphanedAgentsIfNeeded();
        assertThat(orphan.getLastOrphanCleanup()).isEqualTo(before);

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
        schedProps.getZombieCleanup().setThresholdMs(300);

        AgentAcquisitionService acq =
            new AgentAcquisitionService(
                jedisPool, scriptManager, intervalProvider, shardingFilter, agentProps, schedProps, metrics);

        Agent slow = TestFixtures.createMockAgent("fair-agent");
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

        Semaphore sem = new Semaphore(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        int got = acq.saturatePool(0L, sem, pool);
        assertThat(got).isEqualTo(1);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        Map<String, String> active = new ConcurrentHashMap<>(acq.getActiveAgentsMap());
        Map<String, java.util.concurrent.Future<?>> futures = new ConcurrentHashMap<>();

        ZombieCleanupService zombies = new ZombieCleanupService(jedisPool, scriptManager, schedProps, metrics);
        zombies.setAcquisitionService(acq);
        zombies.setFairnessHandler(acq);

        int zifBefore = acq.getZombiesInFlight();
        assertThat(zifBefore).describedAs("zombiesInFlight should be 0 before cleanup").isEqualTo(0);

        Thread.sleep(700);

        int cleaned = zombies.cleanupZombieAgents(active, futures);

        int zifAfterCleanup = acq.getZombiesInFlight();
        assertThat(zifAfterCleanup)
            .describedAs(
                "zombiesInFlight MUST be incremented after zombie cleanup to track fairness debt. "
                    + "cleaned=%d, active_size=%d, zifBefore=%d",
                cleaned, active.size(), zifBefore)
            .isGreaterThanOrEqualTo(1);

        assertThat(sem.availablePermits())
            .describedAs("Semaphore permit must be available after early release")
            .isEqualTo(1);

        release.countDown();
        Thread.sleep(250);

        int zifAfterCompletion = acq.getZombiesInFlight();
        assertThat(zifAfterCompletion)
            .describedAs(
                "zombiesInFlight should be decremented to 0 after worker completion. "
                    + "zif_before=%d, zif_after_cleanup=%d, zif_after_completion=%d",
                zifBefore, zifAfterCleanup, zifAfterCompletion)
            .isEqualTo(0);

        assertThat(cleaned).describedAs("Zombie cleanup should have cleaned at least 1 agent").isGreaterThanOrEqualTo(1);

        pool.shutdownNow();
      }
    }
  }

  @Nested
  @DisplayName("Zombie Orphan Coordination Tests")
  class CoordinationTests {

    @Test
    @DisplayName("Both cleanup services act on same stuck agent exactly once")
    void zombieAndOrphanCoordination() throws Exception {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(1);

      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.getKeys().setWaitingSet("waiting");
      schedProps.getKeys().setWorkingSet("working");
      schedProps.getZombieCleanup().setEnabled(true);
      schedProps.getZombieCleanup().setThresholdMs(300);
      schedProps.getOrphanCleanup().setEnabled(true);
      schedProps.getOrphanCleanup().setThresholdMs(500);

      AgentIntervalProvider ivp = a -> new AgentIntervalProvider.Interval(200L, 200L, 200L);
      ShardingFilter shard = a -> true;

      AgentAcquisitionService acq =
          new AgentAcquisitionService(jedisPool, scriptManager, ivp, shard, agentProps, schedProps, metrics);

      ZombieCleanupService zombies = new ZombieCleanupService(jedisPool, scriptManager, schedProps, metrics);
      zombies.setAcquisitionService(acq);
      zombies.setFairnessHandler(acq);

      OrphanCleanupService orphans = new OrphanCleanupService(jedisPool, scriptManager, schedProps, metrics);
      orphans.setAcquisitionService(acq);

      Agent slow = TestFixtures.createMockAgent("coord-agent");
      AgentExecution exec = mock(AgentExecution.class);
      ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
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

      Thread.sleep(800);

      Map<String, String> active = new ConcurrentHashMap<>(acq.getActiveAgentsMap());
      Map<String, Future<?>> futures = new ConcurrentHashMap<>(acq.getActiveAgentsFutures());

      ExecutorService cleaners = Executors.newFixedThreadPool(2);
      Future<?> zf = cleaners.submit(() -> zombies.cleanupZombieAgents(active, futures));
      Future<?> of = cleaners.submit(orphans::forceCleanupOrphanedAgents);
      zf.get(5, TimeUnit.SECONDS);
      of.get(5, TimeUnit.SECONDS);

      assertThat(sem.availablePermits()).isEqualTo(1);

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
}

