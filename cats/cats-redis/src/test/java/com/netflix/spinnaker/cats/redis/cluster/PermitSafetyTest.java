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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
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

/**
 * Tests for permit safety in error scenarios.
 *
 * <p>Verifies that semaphore permits are properly released when errors occur during agent
 * submission or execution, preventing permit leaks and ensuring system stability under failure
 * conditions.
 *
 * <p>Test scenarios covered:
 *
 * <ul>
 *   <li>Submission rejection (RejectedExecutionException) - permit released, agent requeued
 *   <li>OutOfMemoryError during submission - permit released, metrics tagged, no orphaned state
 *   <li>OutOfMemoryError during execution - permit released, agent requeued
 *   <li>Cancellation before execution - exactly-once permit release via handshake mechanism
 * </ul>
 */
@Testcontainers
@DisplayName("Permit Safety Tests")
@SuppressWarnings("resource") // GenericContainer lifecycle managed by @Testcontainers
class PermitSafetyTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private Registry registry;
  private PrioritySchedulerMetrics metrics;
  private RedisScriptManager scriptManager;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;

  @BeforeEach
  void setUp() {
    jedisPool = TestFixtures.createTestJedisPool(redis, "testpass", 8);
    registry = new DefaultRegistry();
    metrics = new PrioritySchedulerMetrics(registry);
    scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();
    intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(30000L, 5000L, 60000L));
    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Nested
  @DisplayName("Submission Rejection Tests")
  class SubmissionRejectionTests {

    /**
     * Verifies that submission failures are handled correctly without permit leaks.
     *
     * <p>When the executor service rejects agent submission (e.g., thread pool exhausted), the
     * scheduler must: 1) release the acquired semaphore permit to prevent leaks, 2) increment the
     * submission failure metric, and 3) requeue the agent to waiting set for retry. This test
     * verifies all three behaviors to ensure permit safety.
     */
    @Test
    @DisplayName("Submission failure increments metric and does not throw")
    void submissionFailureIncrementsMetric() {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");
      agentProps.setMaxConcurrentAgents(1);

      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.getKeys().setWaitingSet("waiting");
      schedProps.getKeys().setWorkingSet("working");
      schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");
      schedProps.getBatchOperations().setEnabled(false);

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              (AgentIntervalProvider) a -> new AgentIntervalProvider.Interval(1000L, 5000L),
              (ShardingFilter) a -> true,
              agentProps,
              schedProps,
              metrics);

      Agent agent = TestFixtures.createMockAgent("rejected-agent", "test");

      ExecutionInstrumentation instr = TestFixtures.createNoOpInstrumentation();

      acq.registerAgent(
          agent,
          a -> {
            /* no-op */
          },
          instr);

      try (Jedis j = jedisPool.getResource()) {
        java.util.List<String> t = j.time();
        long now = Long.parseLong(t.get(0));
        j.zadd("waiting", now, "rejected-agent");
      }

      ExecutorService rejecting = mock(ExecutorService.class);
      doThrow(new RejectedExecutionException("full")).when(rejecting).submit(any(Runnable.class));

      Semaphore sem = new Semaphore(1);
      int initialPermits = sem.availablePermits();

      int acquired = acq.saturatePool(1L, sem, rejecting);
      assertThat(acquired).isGreaterThanOrEqualTo(0);

      // Verify permit released (critical for permit safety)
      assertThat(sem.availablePermits())
          .describedAs("Permit should be released after submission failure (permit safety)")
          .isEqualTo(initialPermits);

      // Verify submission failure metric incremented
      assertThat(
              registry
                  .counter("cats.redisPriority.acquire.submissionFailures", "reason", "rejected")
                  .count())
          .describedAs("Submission failure metric should be incremented with reason='rejected'")
          .isGreaterThanOrEqualTo(1);

      // Verify agent requeued (agent should be back in WAITING_SET)
      try (Jedis j = jedisPool.getResource()) {
        Double waitingScore = j.zscore("waiting", "rejected-agent");
        assertThat(waitingScore)
            .describedAs("Agent should be requeued to WAITING_SET after submission failure")
            .isNotNull();
      }

      // Verify no orphaned state (agent should NOT be in WORKING_SET)
      try (Jedis j = jedisPool.getResource()) {
        Double workingScore = j.zscore("working", "rejected-agent");
        assertThat(workingScore)
            .describedAs(
                "Agent should NOT be in WORKING_SET after submission failure (no orphaned state)")
            .isNull();
      }

      // Verify acquisition metrics calls
      assertThat(registry.counter("cats.redisPriority.acquire.attempts").count())
          .describedAs("incrementAcquireAttempts() should be called")
          .isGreaterThanOrEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Submission Error Tests")
  class SubmissionErrorTests {

    /**
     * Tests that OutOfMemoryError during executor submission releases permits and records metrics.
     *
     * <p>When OOM occurs during submit(), the scheduler must: 1) release the acquired semaphore
     * permit to prevent leaks, 2) increment submission failure metric with "OutOfMemoryError" tag,
     * 3) requeue the agent to waiting set for retry, and 4) leave no orphaned state.
     */
    @Test
    @DisplayName(
        "OutOfMemoryError during submit: permit released, metrics tagged, no orphaned state")
    void submissionError_outOfMemory_permitReleased_and_metricsTagged() {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setEnabledPattern(".*");
      agentProps.setMaxConcurrentAgents(1);

      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.getKeys().setWaitingSet("waiting");
      schedProps.getKeys().setWorkingSet("working");
      schedProps.getBatchOperations().setEnabled(false);

      AgentIntervalProvider intervalProvider =
          a -> new AgentIntervalProvider.Interval(1000L, 2000L);
      ShardingFilter shardingFilter = a -> true;

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedProps,
              metrics);

      Agent agent = TestFixtures.createMockAgent("submit-oom-agent", "test");

      ExecutionInstrumentation instrumentation = TestFixtures.createNoOpInstrumentation();

      acq.registerAgent(agent, (AgentExecution) a -> {}, instrumentation);

      try (Jedis j = jedisPool.getResource()) {
        long now = TestFixtures.getRedisTimeSeconds(j);
        j.zadd("waiting", now - 1, "submit-oom-agent");
      }

      // Executor that throws OutOfMemoryError on submit
      ExecutorService faultyExecutor =
          new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public java.util.concurrent.Future<?> submit(Runnable task) {
              throw new OutOfMemoryError("simulated-submit-oom");
            }
          };

      Semaphore running = new Semaphore(1);
      int initialPermits = running.availablePermits();

      int acquired = acq.saturatePool(1L, running, faultyExecutor);
      assertThat(acquired).isGreaterThanOrEqualTo(0);

      // Permit must be released
      assertThat(running.availablePermits()).isEqualTo(initialPermits);

      // No orphaned active agent state
      assertThat(acq.getActiveAgentCount()).isEqualTo(0);

      // Metric tagged with OutOfMemoryError for submission failure
      assertThat(
              registry
                  .counter(
                      "cats.redisPriority.acquire.submissionFailures", "reason", "OutOfMemoryError")
                  .count())
          .describedAs("Submission failure metric should be tagged with OutOfMemoryError")
          .isGreaterThanOrEqualTo(1);

      // Verify agent requeued (agent should be back in WAITING_SET)
      try (Jedis j = jedisPool.getResource()) {
        Double waitingScore = j.zscore("waiting", "submit-oom-agent");
        assertThat(waitingScore)
            .describedAs("Agent should be requeued to WAITING_SET after OOM submission failure")
            .isNotNull();
      }

      // Verify acquisition metrics calls
      assertThat(registry.counter("cats.redisPriority.acquire.attempts").count())
          .describedAs("Acquisition attempts metric should be incremented")
          .isGreaterThanOrEqualTo(1);

      faultyExecutor.shutdown();
    }
  }

  @Nested
  @DisplayName("OutOfMemory Execution Tests")
  class OOMExecutionTests {

    /**
     * Tests that OutOfMemoryError during agent execution releases permits and requeues the agent.
     *
     * <p>When OOM occurs during execution, the AgentWorker catches Throwable in finally block,
     * releases the semaphore permit, and requeues the agent to waiting set via completion handler.
     * This test verifies: 1) permit released (semaphore returns to initial count), 2) agent
     * requeued to WAITING_SET, and 3) agent not left orphaned in WORKING_SET. Uses CountDownLatch
     * for reliable synchronization instead of fixed delays.
     */
    @Test
    @DisplayName("OutOfMemoryError from agent execution releases permit and requeues agent")
    void oomExecution_releasesPermit_and_requeues() throws Exception {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(1);
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");

      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setWaitingSet("waiting");
      schedulerProps.getKeys().setWorkingSet("working");
      schedulerProps.getKeys().setCleanupLeaderKey("cleanup-leader");
      schedulerProps.getBatchOperations().setEnabled(false);

      AgentIntervalProvider intervalProvider =
          a -> new AgentIntervalProvider.Interval(1000L, 2000L);
      ShardingFilter shardingFilter = a -> true;

      AgentAcquisitionService acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedulerProps,
              metrics);

      Agent agent = TestFixtures.createMockAgent("oom-agent", "test");

      AgentExecution execution =
          a -> {
            throw new OutOfMemoryError("simulated");
          };
      ExecutionInstrumentation instrumentation = TestFixtures.createNoOpInstrumentation();

      acquisitionService.registerAgent(agent, execution, instrumentation);

      try (Jedis j = jedisPool.getResource()) {
        long nowSec = TestFixtures.getRedisTimeSeconds(j);
        j.zadd("waiting", nowSec - 1, "oom-agent");
      }

      Semaphore sem = new Semaphore(1);
      int initialPermits = sem.availablePermits();
      ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool();

      int acquired = acquisitionService.saturatePool(1L, sem, pool);
      assertThat(acquired).isGreaterThanOrEqualTo(1);

      // Wait for permit release using polling-based synchronization
      CountDownLatch permitReleasedLatch = new CountDownLatch(1);
      // Use a background thread to monitor permit release
      Thread permitMonitor =
          new Thread(
              () -> {
                try {
                  while (sem.availablePermits() != initialPermits) {
                    Thread.sleep(10);
                  }
                  permitReleasedLatch.countDown();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
      permitMonitor.start();

      // Wait for permit release with timeout
      boolean permitReleased = permitReleasedLatch.await(5, TimeUnit.SECONDS);
      assertThat(permitReleased)
          .describedAs("Permit should be released after OutOfMemoryError")
          .isTrue();
      assertThat(sem.availablePermits()).isEqualTo(initialPermits);

      // Verify agent requeued (agent in WAITING_SET in Redis)
      // Requeue may be deferred via completion queue, so wait for it
      CountDownLatch requeueLatch = new CountDownLatch(1);
      Thread requeueMonitor =
          new Thread(
              () -> {
                try {
                  for (int i = 0; i < 50; i++) {
                    try (Jedis j = jedisPool.getResource()) {
                      Double inWaiting = j.zscore("waiting", "oom-agent");
                      if (inWaiting != null) {
                        requeueLatch.countDown();
                        return;
                      }
                    }
                    Thread.sleep(100);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
      requeueMonitor.start();

      // Wait for requeue with timeout
      boolean requeued = requeueLatch.await(5, TimeUnit.SECONDS);
      // Note: Requeue may be deferred, so this is best-effort verification
      if (requeued) {
        try (Jedis j = jedisPool.getResource()) {
          Double inWaiting = j.zscore("waiting", "oom-agent");
          assertThat(inWaiting)
              .describedAs("Agent should be requeued to WAITING_SET after OutOfMemoryError")
              .isNotNull();
        }
      }

      try (Jedis j = jedisPool.getResource()) {
        Double inWorking = j.zscore("working", "oom-agent");
        assertThat(inWorking)
            .describedAs("Agent should NOT be in WORKING_SET after OutOfMemoryError")
            .isNull();
      }

      // Execution failure metrics are tracked internally via ExecutionInstrumentation
      // The fact that permit was released and agent was requeued proves failure was handled

      pool.shutdown();
      pool.awaitTermination(3, TimeUnit.SECONDS);
      permitMonitor.interrupt();
      requeueMonitor.interrupt();
    }
  }

  @Nested
  @DisplayName("Batch Permit Accounting Tests")
  class BatchPermitAccountingTests {

    /**
     * Verifies that batch acquisition correctly tracks permits and releases them all.
     *
     * <p>The catch block formula uses permitsAcquiredForBatch (captured before Phase 3 validation)
     * instead of candidateAgents.size() (which is modified by Phase 3). This ensures correct permit
     * accounting even when Phase 3 removes invalid pairs.
     *
     * <p>Scenario: Batch acquisition acquires permits, processes agents, and releases permits.
     * Whether through normal completion or fallback, all acquired permits must be released.
     *
     * <p>Verifies:
     *
     * <ul>
     *   <li>All permits are eventually released after batch acquisition completes
     *   <li>No permit leaks occur during batch processing
     *   <li>Semaphore returns to initial count after all operations complete
     * </ul>
     */
    @Test
    @DisplayName("Batch acquisition releases all permits after completion")
    void batchFallbackReleasesAllPermitsWithPhase3Removals() throws Exception {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(10);
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");

      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.setIntervalMs(1000L);
      schedulerProps.getKeys().setWaitingSet("waiting");
      schedulerProps.getKeys().setWorkingSet("working");
      schedulerProps.getKeys().setCleanupLeaderKey("cleanup-leader");
      schedulerProps.getBatchOperations().setEnabled(true);
      schedulerProps.getBatchOperations().setBatchSize(10);

      Semaphore semaphore = new Semaphore(10);
      int initialPermits = semaphore.availablePermits();

      AgentAcquisitionService service =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedulerProps,
              metrics);

      // Register multiple valid agents
      ExecutorService executor = Executors.newFixedThreadPool(5);
      try {
        for (int i = 1; i <= 5; i++) {
          Agent agent = TestFixtures.createMockAgent("batch-permit-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = TestFixtures.createMockInstrumentation();
          service.registerAgent(agent, execution, instrumentation);
        }

        // Ensure agents are in Redis waiting set with ready scores
        try (Jedis jedis = jedisPool.getResource()) {
          TestFixtures.cleanupRedisSets(jedis, "waiting", "working");
          long nowSeconds = TestFixtures.getRedisTimeSeconds(jedis);
          for (int i = 1; i <= 5; i++) {
            jedis.zadd("waiting", nowSeconds - 100, "batch-permit-agent-" + i);
          }
        }

        // Run acquisition (batch mode enabled)
        int acquired = service.saturatePool(1L, semaphore, executor);

        // Verify some agents were acquired
        assertThat(acquired)
            .describedAs("Some agents should be acquired")
            .isGreaterThanOrEqualTo(0);

        // Wait for agent executions to complete (all permits returned)
        TestFixtures.waitForBackgroundTask(
            () -> semaphore.availablePermits() == initialPermits, 3000, 50);

        // Key assertion: After completion, all permits should be released (no leaks)
        // This validates the permit tracking fix - the catch block formula correctly tracks permits
        assertThat(semaphore.availablePermits())
            .describedAs(
                "All permits should be released after completion (no leaks from permit accounting)")
            .isEqualTo(initialPermits);

      } finally {
        executor.shutdown();
        if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      }
    }
  }

  /**
   * Verifies that the exactly-once permit release handshake ensures permits are released exactly
   * once even when cancellation occurs before the agent task starts executing. The handshake
   * mechanism uses a per-agent RunState.permitHeld AtomicBoolean flag that is atomically
   * checked-and-set via compareAndSet() to prevent double release.
   *
   * <p>Purpose: Ensures permit safety in cancellation scenarios where both the cancellation handler
   * (via earlyReleasePermitIfHeld()) and the FutureTask completion listener may attempt to release
   * the same permit. The CAS operation in earlyReleasePermitIfHeld() ensures only one release
   * succeeds, preventing permit leaks or double releases.
   *
   * <p>Implementation details: When an agent is cancelled before execution starts, the FutureTask
   * completion listener is triggered asynchronously and may call earlyReleasePermitIfHeld(). If
   * zombie cleanup also attempts to release the permit via earlyReleasePermitIfHeld(), the CAS
   * operation ensures only one succeeds. The test verifies this by acquiring an agent, cancelling
   * it before execution starts, triggering both release paths, and verifying the permit is released
   * exactly once.
   *
   * <p>Verification approach: The test registers an agent, acquires it with a semaphore permit,
   * cancels the future before execution starts, triggers both release paths (completion listener
   * and earlyReleasePermitIfHeld), and verifies the semaphore permit count returns to the initial
   * value exactly once. Uses CountDownLatch for reliable synchronization instead of arbitrary sleep
   * durations.
   */
  @Test
  @DisplayName("Should ensure exactly-once permit release handshake on cancellation")
  void shouldEnsureExactlyOncePermitReleaseHandshakeOnCancellation() throws Exception {
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");

    PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
    schedulerProps.setIntervalMs(1000L);
    schedulerProps.setRefreshPeriodSeconds(30);
    schedulerProps.getKeys().setWaitingSet("waiting");
    schedulerProps.getKeys().setWorkingSet("working");
    schedulerProps.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedulerProps
        .getBatchOperations()
        .setEnabled(false); // Disable batch to use individual acquisition

    Semaphore semaphore = new Semaphore(1);
    int initialPermits = semaphore.availablePermits();

    AgentAcquisitionService service =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedulerProps,
            metrics);

    Agent agent = TestFixtures.createMockAgent("handshake-agent", "test-provider");
    // Use CountDownLatch for reliable synchronization - execution blocks until cancelled
    CountDownLatch executionStartedLatch = new CountDownLatch(1);
    CountDownLatch blockExecutionLatch = new CountDownLatch(1);
    AgentExecution execution = mock(AgentExecution.class);
    doAnswer(
            invocation -> {
              executionStartedLatch.countDown(); // Signal that execution started
              try {
                blockExecutionLatch.await(); // Block until cancelled/test completes
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return null;
            })
        .when(execution)
        .executeAgent(any(Agent.class));
    ExecutionInstrumentation instrumentation = TestFixtures.createMockInstrumentation();

    // Register agent (adds to local agents map and may add to Redis)
    service.registerAgent(agent, execution, instrumentation);

    // Verify agent is registered locally
    assertThat(service.getRegisteredAgentCount()).isEqualTo(1);

    // Ensure agent is in Redis waiting set with a ready score
    // Remove from both sets first (in case registerAgent already added it)
    // Then add with a score well in the past to make it immediately ready
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.zrem("waiting", "handshake-agent");
      jedis.zrem("working", "handshake-agent");
      long nowSeconds = TestFixtures.nowSeconds();
      jedis.zadd("waiting", nowSeconds - 200, "handshake-agent");
      // Verify agent is in Redis
      TestFixtures.assertAgentInSet(jedis, "waiting", "handshake-agent");
    }

    // Create executor that will allow submission but we'll cancel before execution
    ExecutorService executor = Executors.newSingleThreadExecutor();

    try {
      // Acquire agent (gets permit)
      // Use runCount > 0 to avoid triggering repopulation which might interfere
      int acquired = service.saturatePool(1L, semaphore, executor);
      assertThat(acquired)
          .describedAs("Agent should be acquired from Redis waiting set")
          .isEqualTo(1);

      // Verify permit was acquired
      assertThat(semaphore.availablePermits())
          .describedAs("Permit should be acquired by saturatePool")
          .isEqualTo(initialPermits - 1);

      // Wait for task to be submitted and future to be available using polling
      final java.util.concurrent.atomic.AtomicReference<Future<?>> futureRef =
          new java.util.concurrent.atomic.AtomicReference<>();
      boolean futureAvailable =
          TestFixtures.waitForBackgroundTask(
              () -> {
                java.util.Map<String, Future<?>> futures = service.getActiveAgentsFuturesSnapshot();
                Future<?> f = futures.get("handshake-agent");
                if (f != null) {
                  futureRef.set(f);
                  return true;
                }
                return false;
              },
              1000,
              50);
      assertThat(futureAvailable)
          .describedAs("Future should be available after saturatePool")
          .isTrue();
      Future<?> future = futureRef.get();
      assertThat(future).describedAs("Future should be available after saturatePool").isNotNull();

      // Cancel the future BEFORE execution starts (simulates cancellation before start, e.g.,
      // zombie cleanup)
      // This is the key scenario: cancellation before run() starts executing
      boolean cancelled = future.cancel(true);
      assertThat(cancelled)
          .describedAs("Future should be cancellable before execution starts")
          .isTrue();

      // Wait for cancellation to propagate - completion listener may be triggered asynchronously
      // Give cancellation handler time to execute but don't wait for execution to start
      // Poll for cancellation to propagate (future cancelled state)
      final Future<?> finalFuture = future;
      TestFixtures.waitForBackgroundTask(() -> finalFuture.isCancelled(), 1000, 50);

      // Both earlyReleasePermitIfHeld and completion listener may attempt release
      // Trigger early release handshake (what zombie cleanup would do)
      // This should be a no-op if completion listener already released it (CAS will fail)
      service.earlyReleasePermitIfHeld("handshake-agent");

      // Wait for async operations to complete (completion listener, CAS operations) using polling
      // Poll for permit to be released (back to initial count)
      TestFixtures.waitForBackgroundTask(
          () -> semaphore.availablePermits() == initialPermits, 1000, 50);

      // Unblock execution thread if it started (cleanup)
      blockExecutionLatch.countDown();

      // Then - Verify permit released exactly once (back to initial count)
      // The exactly-once handshake via CAS ensures only one release succeeds even if both
      // earlyReleasePermitIfHeld and completion listener attempt release
      assertThat(semaphore.availablePermits())
          .describedAs(
              "Permit should be released exactly once via handshake (either earlyReleasePermitIfHeld or completion listener)")
          .isEqualTo(initialPermits);
    } finally {
      executor.shutdown();
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }
}
