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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("AgentAcquisitionService Rejection Handling Tests")
class AgentAcquisitionServiceRejectionTest {

  @Container
  private static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private JedisPool jedisPool;
  private AgentAcquisitionService acquisitionService;
  private RedisScriptManager scriptManager;

  private ShardingFilter shardingFilter;
  private AgentIntervalProvider intervalProvider;
  private PrioritySchedulerMetrics metrics;
  private Registry registry;
  private Agent mockAgent;
  private AgentExecution agentExecution;
  private ExecutionInstrumentation executionInstrumentation;

  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    // Initialize mocks
    shardingFilter = mock(ShardingFilter.class);
    intervalProvider = mock(AgentIntervalProvider.class);
    registry = new DefaultRegistry();
    metrics = new PrioritySchedulerMetrics(registry); // Don't spy on final class
    mockAgent = mock(Agent.class);
    agentExecution = mock(AgentExecution.class);
    executionInstrumentation = mock(ExecutionInstrumentation.class);

    // Create JedisPool
    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(10);
    poolConfig.setMaxIdle(5);
    poolConfig.setMinIdle(1);
    jedisPool =
        new JedisPool(poolConfig, redis.getHost(), redis.getMappedPort(6379), 2000, null, 0);

    // Clear Redis
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushDB();
    }

    // Setup properties
    agentProperties = new PriorityAgentProperties();
    agentProperties.setEnabledPattern(".*");
    agentProperties.setMaxConcurrentAgents(5); // Limited for testing

    schedulerProperties = new PrioritySchedulerProperties();
    // Disable circuit breaker for testing
    schedulerProperties.getCircuitBreaker().setEnabled(false);
    PrioritySchedulerProperties.BatchOperations batchOps =
        new PrioritySchedulerProperties.BatchOperations();
    batchOps.setEnabled(false);
    schedulerProperties.setBatchOperations(batchOps);
    schedulerProperties.setRefreshPeriodSeconds(30);

    PrioritySchedulerProperties.Keys keysProperties = new PrioritySchedulerProperties.Keys();
    keysProperties.setWaitingSet("waiting-test");
    keysProperties.setWorkingSet("working-test");
    schedulerProperties.setKeys(keysProperties);

    // Initialize script manager
    scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();

    // Setup mocks
    when(shardingFilter.filter(any())).thenReturn(true);
    AgentIntervalProvider.Interval interval = new AgentIntervalProvider.Interval(60000, 120000);
    when(intervalProvider.getInterval(any())).thenReturn(interval);
    when(mockAgent.getAgentType()).thenReturn("test-agent");

    // Create acquisition service
    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("Should handle RejectedExecutionException and release permit")
  void testRejectedExecutionHandling() throws InterruptedException {
    // Create a thread pool that will reject submissions
    ExecutorService rejectingPool =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), // No queue - immediate rejection
            r -> {
              Thread t = new Thread(r);
              t.setName("test-worker");
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy()); // Reject immediately

    // Block the single thread so all subsequent submissions are rejected
    CountDownLatch blockingLatch = new CountDownLatch(1);
    rejectingPool.submit(
        () -> {
          try {
            blockingLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    // Wait for blocking task to start
    Thread.sleep(100);

    // Create semaphore to track permits
    Semaphore runningAgents = new Semaphore(5);
    int initialPermits = runningAgents.availablePermits();

    // Register multiple agents
    for (int i = 1; i <= 3; i++) {
      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("agent-" + i);
      acquisitionService.registerAgent(agent, agentExecution, executionInstrumentation);
    }

    // Try to acquire and submit agents - should handle rejections gracefully
    int acquired = acquisitionService.saturatePool(1, runningAgents, rejectingPool);

    // Should have acquired agents from Redis
    assertTrue(acquired > 0, "Should have acquired at least one agent");

    // Permits should be released for rejected agents
    assertEquals(
        initialPermits,
        runningAgents.availablePermits(),
        "All permits should be released for rejected submissions");

    // Check metrics were actually incremented
    // We can query the Registry to verify metrics even without mocking
    assertTrue(
        registry
                .counter("cats.redisPriority.acquire.submissionFailures", "reason", "rejected")
                .count()
            > 0,
        "Submission failure metrics should be incremented for rejections");

    // Verify agents were queued for retry
    try (Jedis jedis = jedisPool.getResource()) {
      // At least some agents should be back in waiting set due to rejection
      long waitingCount = jedis.zcard("waiting-test");
      assertTrue(waitingCount > 0, "Rejected agents should be queued for retry");
    }

    // Cleanup
    blockingLatch.countDown();
    rejectingPool.shutdown();
    assertTrue(rejectingPool.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("Should handle mixed success and rejection scenarios")
  void testMixedSuccessAndRejection() throws Exception {
    // Create a thread pool with limited capacity
    ThreadPoolExecutor limitedPool =
        new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), // Can queue only 1 task
            r -> {
              Thread t = new Thread(r);
              t.setName("limited-worker-" + t.getId());
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    // Track successful executions
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(2);
    CountDownLatch completeLatch = new CountDownLatch(1);

    // Mock slow agent execution
    doAnswer(
            invocation -> {
              startLatch.countDown();
              completeLatch.await(); // Block until released
              successCount.incrementAndGet();
              return null;
            })
        .when(agentExecution)
        .executeAgent(any());

    // Register many agents (more than pool can handle)
    for (int i = 1; i <= 10; i++) {
      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("agent-" + i);
      acquisitionService.registerAgent(agent, agentExecution, executionInstrumentation);
    }

    // Create semaphore
    Semaphore runningAgents = new Semaphore(10);
    int initialPermits = runningAgents.availablePermits();

    // Try to acquire and submit agents
    int acquired = acquisitionService.saturatePool(1, runningAgents, limitedPool);
    assertTrue(acquired > 0, "Should acquire agents");

    // Wait for some tasks to start
    assertTrue(startLatch.await(2, TimeUnit.SECONDS), "Some tasks should start");

    // At this point, 2 are executing, 1 is queued, rest should be rejected

    // Check that permits are properly managed (some consumed, some released)
    int currentPermits = runningAgents.availablePermits();
    assertTrue(
        currentPermits < initialPermits, "Some permits should be consumed by executing tasks");
    assertTrue(
        currentPermits > initialPermits - acquired,
        "Some permits should be released for rejected tasks");

    // Release the blocking tasks
    completeLatch.countDown();

    // Wait for pool to complete
    limitedPool.shutdown();
    assertTrue(limitedPool.awaitTermination(5, TimeUnit.SECONDS));

    // Verify we had successful executions
    assertTrue(successCount.get() > 0, "Should have some successful executions");
    assertTrue(successCount.get() <= 3, "Should not exceed pool capacity + queue");

    // Verify metrics tracked attempts
    assertTrue(
        registry.counter("cats.redisPriority.acquire.attempts").count() >= 1,
        "Should have tracked at least one acquisition attempt");
  }

  @Test
  @DisplayName("Should handle unexpected exceptions during submission")
  void testUnexpectedExceptionHandling() throws Exception {
    // Create a custom executor that throws unexpected exceptions
    ExecutorService faultyExecutor =
        new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
          @Override
          public Future<?> submit(Runnable task) {
            throw new IllegalStateException("Unexpected executor failure");
          }
        };

    // Register an agent
    acquisitionService.registerAgent(mockAgent, agentExecution, executionInstrumentation);

    // Create semaphore
    Semaphore runningAgents = new Semaphore(5);
    int initialPermits = runningAgents.availablePermits();

    // Try to acquire and submit - should handle exception gracefully
    int acquired = acquisitionService.saturatePool(1, runningAgents, faultyExecutor);

    // Should have acquired from Redis
    assertEquals(1, acquired, "Should report acquisition even if submission fails");

    // Permits should be released
    assertEquals(
        initialPermits,
        runningAgents.availablePermits(),
        "Permits should be released on submission failure");

    // Verify metrics tracked the submission failure
    assertTrue(
        registry
                .counter(
                    "cats.redisPriority.acquire.submissionFailures",
                    "reason",
                    "IllegalStateException")
                .count()
            > 0,
        "Should track the IllegalStateException submission failure");

    // Cleanup
    faultyExecutor.shutdown();
  }

  @Test
  @DisplayName("Should not leak permits when no agents are ready")
  void testNoPermitLeakWhenNoAgentsReady() throws Exception {
    // Create normal thread pool
    ExecutorService threadPool = Executors.newFixedThreadPool(2);

    // Create semaphore
    Semaphore runningAgents = new Semaphore(5);
    int initialPermits = runningAgents.availablePermits();

    // Don't register any agents - Redis is empty

    // Try to acquire
    int acquired = acquisitionService.saturatePool(1, runningAgents, threadPool);

    // Should not acquire anything
    assertEquals(0, acquired, "Should not acquire any agents");

    // Permits should remain unchanged
    assertEquals(
        initialPermits,
        runningAgents.availablePermits(),
        "Permits should remain unchanged when no agents ready");

    // Cleanup
    threadPool.shutdown();
    assertTrue(threadPool.awaitTermination(1, TimeUnit.SECONDS));
  }
}
