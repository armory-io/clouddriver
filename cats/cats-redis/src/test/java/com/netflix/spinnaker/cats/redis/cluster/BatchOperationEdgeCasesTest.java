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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Edge case tests for batch operations in PriorityScheduler components.
 *
 * <p>Tests realistic failure scenarios, boundary conditions, and edge cases that could occur in
 * production environments, especially with large numbers of agents (5K+ AWS accounts scenario).
 */
@Testcontainers
@DisplayName("Batch Operations Edge Cases Tests")
public class BatchOperationEdgeCasesTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private PrioritySchedulerProperties schedulerProperties;
  private PriorityAgentProperties agentProperties;
  private ExecutorService executorService;
  private AgentAcquisitionService acquisitionService;
  private ZombieCleanupService zombieService;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;

  @BeforeEach
  void setUp() {
    String redisHost = redis.getHost();
    Integer redisPort = redis.getMappedPort(6379);

    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(8);
    jedisPool = new JedisPool(poolConfig, redisHost, redisPort);

    // Clean Redis state
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushAll();
    }

    scriptManager =
        new RedisScriptManager(
            jedisPool,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    scriptManager.initializeScripts();

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");
    agentProperties = new PriorityAgentProperties();
    agentProperties.setMaxConcurrentAgents(50);

    executorService = Executors.newFixedThreadPool(10);

    // Mock the required dependencies
    intervalProvider = agent -> new AgentIntervalProvider.Interval(60000L, 120000L);
    shardingFilter = agent -> true;

    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    zombieService =
        new ZombieCleanupService(
            jedisPool,
            scriptManager,
            schedulerProperties,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
  }

  @AfterEach
  void tearDown() {
    if (executorService != null) {
      executorService.shutdown();
    }
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Nested
  @DisplayName("Large Scale Batch Operation Tests")
  class LargeScaleBatchOperationTests {

    @Test
    @DisplayName("Should handle large batch of agents efficiently")
    void shouldHandleLargeBatchOfAgentsEfficiently() throws Exception {
      // Simulate 5K+ AWS accounts scenario (scaled down for test)
      int agentCount = 500; // Scaled down but still significant
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(50);

      // Register many agents
      for (int i = 1; i <= agentCount; i++) {
        Agent agent = createMockAgent("large-scale-agent-" + i, "aws");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      long startTime = System.currentTimeMillis();
      int acquired = acquisitionService.saturatePool(0L, null, executorService);
      long duration = System.currentTimeMillis() - startTime;

      // Should acquire agents efficiently
      assertThat(acquired).isLessThanOrEqualTo(50); // Limited by concurrency
      assertThat(duration).isLessThan(5000); // Should complete within 5 seconds

      System.out.println(
          "Large scale test: " + acquired + " agents acquired in " + duration + "ms");
    }

    @Test
    @DisplayName("Should handle memory pressure with many agents")
    void shouldHandleMemoryPressureWithManyAgents() throws Exception {
      int agentCount = 1000;
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties
          .getBatchOperations()
          .setBatchSize(25); // Smaller batches for memory efficiency

      // Register agents with various execution times
      for (int i = 1; i <= agentCount; i++) {
        Agent agent = createMockAgent("memory-test-agent-" + i, "aws");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Monitor memory usage (simplified check)
      Runtime runtime = Runtime.getRuntime();
      long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

      int acquired = acquisitionService.saturatePool(0L, null, executorService);

      long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
      long memoryIncrease = memoryAfter - memoryBefore;

      // Memory increase should be reasonable (less than 100MB for 1000 agents)
      assertThat(memoryIncrease).isLessThan(100 * 1024 * 1024);
      assertThat(acquired).isGreaterThan(0);
    }
  }

  @Nested
  @DisplayName("Redis Connection Edge Cases")
  class RedisConnectionEdgeCaseTests {

    @Test
    @DisplayName("Should handle Redis connection timeout during batch operation")
    void shouldHandleRedisConnectionTimeoutDuringBatchOperation() throws Exception {
      schedulerProperties.getBatchOperations().setEnabled(true);

      // Register agents
      for (int i = 1; i <= 5; i++) {
        Agent agent = createMockAgent("timeout-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Create a Jedis pool with very short timeout to simulate connection issues
      JedisPoolConfig shortTimeoutConfig = new JedisPoolConfig();
      shortTimeoutConfig.setMaxTotal(1);
      shortTimeoutConfig.setMaxWaitMillis(10); // Very short wait
      JedisPool shortTimeoutPool =
          new JedisPool(shortTimeoutConfig, redis.getHost(), redis.getMappedPort(6379));

      AgentAcquisitionService timeoutService =
          new AgentAcquisitionService(
              shortTimeoutPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // Copy agents to the new service
      for (int i = 1; i <= 5; i++) {
        Agent agent = createMockAgent("timeout-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        timeoutService.registerAgent(agent, execution, instrumentation);
      }

      // Should handle connection timeout gracefully
      assertThatCode(
              () -> {
                int acquired = timeoutService.saturatePool(0L, null, executorService);
                assertThat(acquired).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();

      shortTimeoutPool.close();
    }

    @Test
    @DisplayName("Should handle Redis script execution failure with proper fallback")
    void shouldHandleRedisScriptExecutionFailureWithProperFallback() throws Exception {
      schedulerProperties.getBatchOperations().setEnabled(true);

      // Mock script manager that simulates script execution failure
      RedisScriptManager mockScriptManager = spy(scriptManager);
      when(mockScriptManager.getScriptSha(RedisScriptManager.ACQUIRE_AGENTS))
          .thenReturn("nonexistent-sha-that-will-cause-noscript-error");

      AgentAcquisitionService serviceWithFailingScript =
          new AgentAcquisitionService(
              jedisPool,
              mockScriptManager,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // Register agents
      for (int i = 1; i <= 3; i++) {
        Agent agent = createMockAgent("script-fail-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        serviceWithFailingScript.registerAgent(agent, execution, instrumentation);
      }

      // Should fallback to individual acquisition when script fails
      int acquired = serviceWithFailingScript.saturatePool(0L, null, executorService);

      // Should still acquire agents via fallback (individual mode)
      assertThat(acquired).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("Batch Size Boundary Tests")
  class BatchSizeBoundaryTests {

    @Test
    @DisplayName("Should handle batch size equal to agent count")
    void shouldHandleBatchSizeEqualToAgentCount() throws Exception {
      int agentCount = 5;
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(agentCount); // Exact match

      for (int i = 1; i <= agentCount; i++) {
        Agent agent = createMockAgent("exact-batch-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      int acquired = acquisitionService.saturatePool(0L, null, executorService);
      assertThat(acquired).isEqualTo(agentCount);
    }

    @Test
    @DisplayName("Should handle batch size larger than agent count")
    void shouldHandleBatchSizeLargerThanAgentCount() throws Exception {
      int agentCount = 3;
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(10); // Much larger than agent count

      for (int i = 1; i <= agentCount; i++) {
        Agent agent = createMockAgent("small-count-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      int acquired = acquisitionService.saturatePool(0L, null, executorService);
      assertThat(acquired).isEqualTo(agentCount);
    }

    @Test
    @DisplayName("Should handle single agent with batch operations enabled")
    void shouldHandleSingleAgentWithBatchOperationsEnabled() throws Exception {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(10);

      Agent agent = createMockAgent("single-batch-agent", "test");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
      acquisitionService.registerAgent(agent, execution, instrumentation);

      int acquired = acquisitionService.saturatePool(0L, null, executorService);
      assertThat(acquired).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Concurrent Access Edge Cases")
  class ConcurrentAccessEdgeCaseTests {

    @Test
    @DisplayName("Should handle concurrent batch operations safely")
    void shouldHandleConcurrentBatchOperationsSafely() throws Exception {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(5);

      // Register agents
      for (int i = 1; i <= 20; i++) {
        Agent agent = createMockAgent("concurrent-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Run multiple acquisition attempts concurrently
      ExecutorService concurrentExecutor = Executors.newFixedThreadPool(3);
      List<Future<Integer>> futures = new ArrayList<>();

      for (int i = 0; i < 3; i++) {
        final long runId = i;
        futures.add(
            concurrentExecutor.submit(
                () -> acquisitionService.saturatePool(runId, null, executorService)));
      }

      // Wait for all to complete
      List<Integer> results = new ArrayList<>();
      for (Future<Integer> future : futures) {
        results.add(future.get(5, TimeUnit.SECONDS));
      }

      // Should handle concurrent access without exceptions
      assertThat(results).allSatisfy(result -> assertThat(result).isGreaterThanOrEqualTo(0));

      concurrentExecutor.shutdown();
    }

    @Test
    @DisplayName("Should handle agent registration during batch operation")
    void shouldHandleAgentRegistrationDuringBatchOperation() throws Exception {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(5);

      // Register initial agents
      for (int i = 1; i <= 5; i++) {
        Agent agent = createMockAgent("initial-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Start acquisition in background
      Future<Integer> acquisitionFuture =
          Executors.newSingleThreadExecutor()
              .submit(
                  () -> {
                    int totalAcquired = 0;
                    for (int i = 0; i < 5; i++) {
                      totalAcquired +=
                          acquisitionService.saturatePool((long) i, null, executorService);
                      try {
                        Thread.sleep(100);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                      }
                    }
                    return totalAcquired;
                  });

      // Register additional agents while acquisition is running
      Thread.sleep(50);
      for (int i = 6; i <= 10; i++) {
        Agent agent = createMockAgent("dynamic-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
        Thread.sleep(20);
      }

      // Should complete without exceptions
      Integer totalAcquired = acquisitionFuture.get(10, TimeUnit.SECONDS);
      assertThat(totalAcquired).isGreaterThanOrEqualTo(0);
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(10);
    }
  }

  @Nested
  @DisplayName("Zombie Cleanup Batch Edge Cases")
  class ZombieCleanupBatchEdgeCaseTests {

    @Test
    @DisplayName("Should handle large number of zombie agents efficiently")
    void shouldHandleLargeNumberOfZombieAgentsEfficiently() {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(50);

      // Create many zombie agents
      Map<String, String> activeAgents = new ConcurrentHashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new ConcurrentHashMap<>();
      long oldScoreSeconds = (System.currentTimeMillis() - 120000) / 1000; // 2 minutes ago

      try (Jedis jedis = jedisPool.getResource()) {
        for (int i = 1; i <= 50; i++) {
          String agentType = "large-zombie-" + i;
          jedis.zadd("working", oldScoreSeconds, agentType);
          activeAgents.put(agentType, String.valueOf(oldScoreSeconds));
          activeAgentsFutures.put(agentType, mock(Future.class));
        }
      }

      long startTime = System.currentTimeMillis();
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);
      long duration = System.currentTimeMillis() - startTime;

      // Should clean up all zombies efficiently
      assertThat(cleaned).isEqualTo(50);
      assertThat(duration).isLessThan(10000); // Should complete within 10 seconds
      assertThat(activeAgents).isEmpty();
      assertThat(activeAgentsFutures).isEmpty();

      System.out.println("Large zombie cleanup: " + cleaned + " agents in " + duration + "ms");
    }

    @Test
    @DisplayName("Should handle mixed zombie ages in batch")
    void shouldHandleMixedZombieAgesInBatch() {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(10);

      Map<String, String> activeAgents = new ConcurrentHashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new ConcurrentHashMap<>();
      long currentTime = System.currentTimeMillis();

      try (Jedis jedis = jedisPool.getResource()) {
        // Mix of old zombies and recent agents
        for (int i = 1; i <= 5; i++) {
          // Old zombies (should be cleaned)
          String zombieType = "old-zombie-" + i;
          long oldScore = (currentTime - 120000) / 1000; // 2 minutes ago
          jedis.zadd("working", oldScore, zombieType);
          activeAgents.put(zombieType, String.valueOf(oldScore));
          activeAgentsFutures.put(zombieType, mock(Future.class));

          // Recent agents (should NOT be cleaned)
          String recentType = "recent-agent-" + i;
          long recentScore = (currentTime - 10000) / 1000; // 10 seconds ago
          jedis.zadd("working", recentScore, recentType);
          activeAgents.put(recentType, String.valueOf(recentScore));
          activeAgentsFutures.put(recentType, mock(Future.class));
        }
      }

      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Should only clean the old zombies, not the recent agents
      assertThat(cleaned).isEqualTo(5);
      assertThat(activeAgents).hasSize(5); // Recent agents should remain
      assertThat(activeAgentsFutures).hasSize(5);
    }
  }

  @Nested
  @DisplayName("Resource Exhaustion Edge Cases")
  class ResourceExhaustionEdgeCaseTests {

    @Test
    @DisplayName("Should handle Redis memory pressure gracefully")
    void shouldHandleRedisMemoryPressureGracefully() throws Exception {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(100);

      // Fill Redis with data to simulate memory pressure
      try (Jedis jedis = jedisPool.getResource()) {
        for (int i = 0; i < 1000; i++) {
          jedis.set("memory-pressure-key-" + i, "x".repeat(1000));
        }
      }

      // Register agents
      for (int i = 1; i <= 10; i++) {
        Agent agent = createMockAgent("pressure-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Should handle memory pressure without crashing
      assertThatCode(
              () -> {
                int acquired = acquisitionService.saturatePool(0L, null, executorService);
                assertThat(acquired).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should handle semaphore exhaustion in batch mode")
    void shouldHandleSemaphoreExhaustionInBatchMode() throws Exception {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(10);

      // Create very limited semaphore
      Semaphore limitedSemaphore = new Semaphore(2);

      // Register more agents than semaphore permits
      for (int i = 1; i <= 10; i++) {
        Agent agent = createMockAgent("semaphore-agent-" + i, "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Should respect semaphore limits even in batch mode
      int acquired = acquisitionService.saturatePool(0L, limitedSemaphore, executorService);
      assertThat(acquired).isLessThanOrEqualTo(2);

      // Wait for execution to complete
      Thread.sleep(100);
      assertThat(limitedSemaphore.availablePermits()).isEqualTo(2);
    }
  }

  private Agent createMockAgent(String name, String providerType) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(name);
    when(agent.getProviderName()).thenReturn(providerType);
    return agent;
  }
}
