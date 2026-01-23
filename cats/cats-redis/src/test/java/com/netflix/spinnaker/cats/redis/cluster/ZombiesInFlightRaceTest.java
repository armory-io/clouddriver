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
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Tests for zombiesInFlight tracking correctness under concurrent operations.
 *
 * <p>These tests verify that the set-based zombiesInFlight implementation maintains correct
 * accounting under real concurrent conditions using the actual AgentAcquisitionService.
 */
@Testcontainers
@DisplayName("ZombiesInFlight Concurrent Correctness Tests")
@SuppressWarnings("resource") // GenericContainer lifecycle managed by @Testcontainers
@Timeout(120)
class ZombiesInFlightRaceTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private AgentAcquisitionService acquisitionService;
  private Registry registry;
  private PrioritySchedulerMetrics metrics;
  private RedisScriptManager scriptManager;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private ExecutorService executor;
  private Semaphore semaphore;

  @BeforeEach
  void setUp() {
    jedisPool = TestFixtures.createTestJedisPool(redis, "testpass", 50);

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushAll();
    }

    registry = new DefaultRegistry();
    metrics = new PrioritySchedulerMetrics(registry);
    scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();

    intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(30000L, 5000L, 60000L));
    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedProps.getCircuitBreaker().setEnabled(false);

    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    executor = Executors.newFixedThreadPool(20);
    semaphore = new Semaphore(10);
  }

  @AfterEach
  void tearDown() {
    TestFixtures.shutdownExecutorSafely(executor);
    TestFixtures.closePoolSafely(jedisPool);
  }

  @Nested
  @DisplayName("Real AgentAcquisitionService Tests")
  class RealServiceTests {

    /**
     * Tests that zombiesInFlight correctly tracks agents through early release and worker exit.
     *
     * <p>This is an end-to-end test using the real AgentAcquisitionService:
     * 1. Register and start an agent
     * 2. Call earlyReleasePermitIfHeld (simulating zombie cleanup)
     * 3. Verify zombiesInFlight is 1
     * 4. Let worker complete
     * 5. Verify zombiesInFlight returns to 0
     */
    @Test
    @DisplayName("zombiesInFlight correctly tracks through lifecycle")
    void zombiesInFlightCorrectlyTracksThroughLifecycle() throws Exception {
      CountDownLatch agentStarted = new CountDownLatch(1);
      CountDownLatch releaseAgent = new CountDownLatch(1);

      AgentExecution execution = mock(AgentExecution.class);
      doAnswer(
              inv -> {
                agentStarted.countDown();
                releaseAgent.await(30, TimeUnit.SECONDS);
                return null;
              })
          .when(execution)
          .executeAgent(any(Agent.class));

      Agent agent = TestFixtures.createMockAgent("lifecycle-agent", "test-provider");
      ExecutionInstrumentation instr = TestFixtures.createMockInstrumentation();
      acquisitionService.registerAgent(agent, execution, instr);

      // Add to Redis
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("waiting", System.currentTimeMillis() / 1000.0 - 100, "lifecycle-agent");
      }

      // Acquire and start
      acquisitionService.saturatePool(1L, semaphore, executor);
      assertThat(agentStarted.await(5, TimeUnit.SECONDS)).isTrue();

      // Before early release: zombiesInFlight should be 0
      assertThat(acquisitionService.getZombiesInFlight())
          .describedAs("Before early release")
          .isEqualTo(0);

      // Simulate zombie cleanup
      acquisitionService.earlyReleasePermitIfHeld("lifecycle-agent");

      // After early release: zombiesInFlight should be exactly 1
      assertThat(acquisitionService.getZombiesInFlight())
          .describedAs("After early release, before worker exit")
          .isEqualTo(1);

      // Let worker complete
      releaseAgent.countDown();

      // Wait for worker to exit and clean up
      TestFixtures.waitForBackgroundTask(
          () -> acquisitionService.getZombiesInFlight() == 0, 10_000L, 50L);

      // After worker exit: zombiesInFlight should be 0
      assertThat(acquisitionService.getZombiesInFlight())
          .describedAs("After worker exit")
          .isEqualTo(0);
    }

    /**
     * Stress test: Multiple agents going through zombie cleanup concurrently.
     *
     * <p>Registers agents up to the concurrency limit, starts them all,
     * calls earlyReleasePermitIfHeld on all, then lets them complete.
     * Verifies zombiesInFlight settles to 0 with no drift.
     */
    @RepeatedTest(3)
    @DisplayName("Concurrent zombie cleanup maintains correct accounting")
    void concurrentZombieCleanupMaintainsCorrectAccounting() throws Exception {
      // Use exactly the semaphore limit to ensure all can start
      int numAgents = 10;
      CountDownLatch allStarted = new CountDownLatch(numAgents);
      CountDownLatch releaseAll = new CountDownLatch(1);
      AtomicInteger completedCount = new AtomicInteger(0);

      List<Agent> agents = new ArrayList<>();
      for (int i = 0; i < numAgents; i++) {
        final int idx = i;
        Agent agent = TestFixtures.createMockAgent("stress-agent-" + i, "stress-provider");
        agents.add(agent);

        AgentExecution execution = mock(AgentExecution.class);
        doAnswer(
                inv -> {
                  allStarted.countDown();
                  releaseAll.await(60, TimeUnit.SECONDS);
                  // Random work time to create more race opportunities
                  Thread.sleep(idx % 50);
                  completedCount.incrementAndGet();
                  return null;
                })
            .when(execution)
            .executeAgent(any(Agent.class));

        ExecutionInstrumentation instr = TestFixtures.createMockInstrumentation();
        acquisitionService.registerAgent(agent, execution, instr);
      }

      // Add all to Redis
      try (Jedis jedis = jedisPool.getResource()) {
        for (int i = 0; i < numAgents; i++) {
          jedis.zadd("waiting", System.currentTimeMillis() / 1000.0 - 100 - i, "stress-agent-" + i);
        }
      }

      // Acquire all agents - with enough semaphore permits, one cycle should work
      acquisitionService.saturatePool(1L, semaphore, executor);

      // Wait for all to start
      assertThat(allStarted.await(10, TimeUnit.SECONDS))
          .describedAs("All agents should start (semaphore permits=" + semaphore.availablePermits() + ")")
          .isTrue();

      // Concurrently call earlyReleasePermitIfHeld on all agents
      CountDownLatch earlyReleasesDone = new CountDownLatch(numAgents);
      for (int i = 0; i < numAgents; i++) {
        final int idx = i;
        new Thread(() -> {
          acquisitionService.earlyReleasePermitIfHeld("stress-agent-" + idx);
          earlyReleasesDone.countDown();
        }).start();
      }
      assertThat(earlyReleasesDone.await(5, TimeUnit.SECONDS)).isTrue();

      // zombiesInFlight should be between 0 and numAgents
      int zifAfterEarlyRelease = acquisitionService.getZombiesInFlight();
      assertThat(zifAfterEarlyRelease)
          .describedAs("zombiesInFlight after early releases")
          .isGreaterThanOrEqualTo(0)
          .isLessThanOrEqualTo(numAgents);

      // Release all workers
      releaseAll.countDown();

      // Wait for all to complete
      TestFixtures.waitForBackgroundTask(
          () -> completedCount.get() >= numAgents, 30_000L, 100L);

      // Wait for zombiesInFlight to settle
      TestFixtures.waitForBackgroundTask(
          () -> acquisitionService.getZombiesInFlight() == 0, 10_000L, 50L);

      // Final check: zombiesInFlight must be exactly 0
      assertThat(acquisitionService.getZombiesInFlight())
          .describedAs("zombiesInFlight after all workers complete (no drift)")
          .isEqualTo(0);
    }

    /**
     * Tests that repeated early release on same agent is idempotent.
     *
     * <p>With set-based tracking, calling earlyReleasePermitIfHeld multiple times
     * should only add the agent once to the set.
     */
    @Test
    @DisplayName("Repeated early release is idempotent")
    void repeatedEarlyReleaseIsIdempotent() throws Exception {
      CountDownLatch agentStarted = new CountDownLatch(1);
      CountDownLatch releaseAgent = new CountDownLatch(1);

      AgentExecution execution = mock(AgentExecution.class);
      doAnswer(
              inv -> {
                agentStarted.countDown();
                releaseAgent.await(30, TimeUnit.SECONDS);
                return null;
              })
          .when(execution)
          .executeAgent(any(Agent.class));

      Agent agent = TestFixtures.createMockAgent("idempotent-agent", "test-provider");
      ExecutionInstrumentation instr = TestFixtures.createMockInstrumentation();
      acquisitionService.registerAgent(agent, execution, instr);

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("waiting", System.currentTimeMillis() / 1000.0 - 100, "idempotent-agent");
      }

      acquisitionService.saturatePool(1L, semaphore, executor);
      assertThat(agentStarted.await(5, TimeUnit.SECONDS)).isTrue();

      // Call earlyReleasePermitIfHeld multiple times
      for (int i = 0; i < 10; i++) {
        acquisitionService.earlyReleasePermitIfHeld("idempotent-agent");
      }

      // With set-based tracking, zombiesInFlight should be 1, not 10
      assertThat(acquisitionService.getZombiesInFlight())
          .describedAs("zombiesInFlight should be 1 regardless of repeated calls")
          .isEqualTo(1);

      // Cleanup
      releaseAgent.countDown();
      TestFixtures.waitForBackgroundTask(
          () -> acquisitionService.getZombiesInFlight() == 0, 10_000L, 50L);
    }

    /**
     * Tests that worker exit without early release doesn't affect zombiesInFlight.
     *
     * <p>Normal completion path: no early release, so nothing in zombiesInFlight set.
     */
    @Test
    @DisplayName("Normal completion without zombie cleanup leaves zombiesInFlight at 0")
    void normalCompletionLeavesZombiesInFlightAtZero() throws Exception {
      CountDownLatch agentCompleted = new CountDownLatch(1);

      AgentExecution execution = mock(AgentExecution.class);
      doAnswer(
              inv -> {
                Thread.sleep(50); // Brief work
                agentCompleted.countDown();
                return null;
              })
          .when(execution)
          .executeAgent(any(Agent.class));

      Agent agent = TestFixtures.createMockAgent("normal-agent", "test-provider");
      ExecutionInstrumentation instr = TestFixtures.createMockInstrumentation();
      acquisitionService.registerAgent(agent, execution, instr);

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("waiting", System.currentTimeMillis() / 1000.0 - 100, "normal-agent");
      }

      acquisitionService.saturatePool(1L, semaphore, executor);

      // Wait for completion
      assertThat(agentCompleted.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(200); // Allow cleanup

      // zombiesInFlight should always be 0 for normal completion
      assertThat(acquisitionService.getZombiesInFlight())
          .describedAs("Normal completion should not affect zombiesInFlight")
          .isEqualTo(0);
    }

    /**
     * Tests interleaved early release and worker exit across multiple agents.
     *
     * <p>This simulates the real-world scenario where zombie cleanup runs while
     * agents are completing, creating interleaved add/remove operations.
     */
    @RepeatedTest(5)
    @DisplayName("Interleaved operations maintain correct accounting")
    void interleavedOperationsMaintainCorrectAccounting() throws Exception {
      int numAgents = 10;
      CountDownLatch allDone = new CountDownLatch(numAgents);
      AtomicInteger activeZombies = new AtomicInteger(0);

      for (int i = 0; i < numAgents; i++) {
        final int idx = i;
        CountDownLatch agentStarted = new CountDownLatch(1);
        CountDownLatch canFinish = new CountDownLatch(1);

        AgentExecution execution = mock(AgentExecution.class);
        doAnswer(
                inv -> {
                  agentStarted.countDown();
                  canFinish.await(30, TimeUnit.SECONDS);
                  return null;
                })
            .when(execution)
            .executeAgent(any(Agent.class));

        Agent agent = TestFixtures.createMockAgent("interleave-" + i, "test-provider");
        ExecutionInstrumentation instr = TestFixtures.createMockInstrumentation();
        acquisitionService.registerAgent(agent, execution, instr);

        try (Jedis jedis = jedisPool.getResource()) {
          jedis.zadd("waiting", System.currentTimeMillis() / 1000.0 - 100 - i, "interleave-" + i);
        }

        // Start agent
        acquisitionService.saturatePool((long) i, semaphore, executor);
        if (agentStarted.await(2, TimeUnit.SECONDS)) {
          // Half get early release, half complete normally
          if (idx % 2 == 0) {
            acquisitionService.earlyReleasePermitIfHeld("interleave-" + idx);
            activeZombies.incrementAndGet();
          }
          canFinish.countDown();
        }
        allDone.countDown();
      }

      allDone.await(30, TimeUnit.SECONDS);

      // Wait for all workers to exit
      TestFixtures.waitForBackgroundTask(
          () -> acquisitionService.getZombiesInFlight() == 0, 15_000L, 100L);

      // Final accounting must be correct
      assertThat(acquisitionService.getZombiesInFlight())
          .describedAs("After interleaved operations, zombiesInFlight must settle to 0")
          .isEqualTo(0);
    }
  }
}
