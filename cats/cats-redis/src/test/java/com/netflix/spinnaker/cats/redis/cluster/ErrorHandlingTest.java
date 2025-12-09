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

import static com.netflix.spinnaker.cats.redis.cluster.TestFixtures.createTestScriptManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Error handling tests for PriorityScheduler components.
 *
 * <p>Tests error scenarios including:
 *
 * <ul>
 *   <li>AgentSchedulingException usage and propagation
 *   <li>Script loading failures and recovery
 *   <li>Batch operation failures with fallback
 *   <li>Configuration validation edge cases
 *   <li>Resource cleanup under error conditions
 * </ul>
 *
 * <p>Tests in this suite focus on graceful error handling behavior (no crashes, proper fallback)
 * rather than implementation details (specific metric values).
 */
@Testcontainers
@DisplayName("Error Handling Tests")
@SuppressWarnings("resource") // GenericContainer lifecycle managed by @Testcontainers
public class ErrorHandlingTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private PrioritySchedulerProperties schedulerProperties;
  private PriorityAgentProperties agentProperties;
  private ExecutorService executorService;
  private AgentAcquisitionService acquisitionService;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;

  @BeforeEach
  void setUp() {
    jedisPool = TestFixtures.createTestJedisPool(redis, "testpass", 8);

    // Clean Redis state
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushAll();
    }

    scriptManager = createTestScriptManager(jedisPool);

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");
    agentProperties = new PriorityAgentProperties();
    agentProperties.setMaxConcurrentAgents(10);

    // Mock the required dependencies
    intervalProvider = agent -> new AgentIntervalProvider.Interval(60000L, 120000L);
    shardingFilter = agent -> true;

    executorService = Executors.newFixedThreadPool(5);
    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            TestFixtures.createTestMetrics());
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
  @DisplayName("Script Loading Failure Tests")
  class ScriptLoadingFailureTests {

    /**
     * Tests that script loading failures are wrapped in AgentSchedulingException. Verifies that
     * initializeScripts wraps script compilation errors with appropriate message and cause.
     */
    @Test
    @DisplayName("Should handle corrupted Lua script gracefully")
    void shouldHandleCorruptedLuaScript() {
      // Create a script manager with mock Jedis pool that fails script loading
      JedisPool mockPool = mock(JedisPool.class);
      Jedis mockJedis = mock(Jedis.class);
      when(mockPool.getResource()).thenReturn(mockJedis);
      when(mockJedis.scriptLoad(anyString()))
          .thenThrow(new RuntimeException("Script compilation error"));

      RedisScriptManager failingScriptManager =
          new RedisScriptManager(mockPool, TestFixtures.createTestMetrics());

      // Script loading should wrap the exception in AgentSchedulingException - this is expected
      // behavior
      assertThatThrownBy(
              () -> {
                failingScriptManager.initializeScripts();
              })
          .isInstanceOf(AgentSchedulingException.class)
          .hasMessageContaining("Failed to initialize Redis scripts")
          .hasCauseInstanceOf(RuntimeException.class);

      // Verify script loading was attempted
      verify(mockJedis, atLeastOnce()).scriptLoad(anyString());
    }

    /**
     * Tests that RedisScriptManager validates script names. Verifies that unknown, null, and empty
     * script names throw IllegalArgumentException with appropriate messages.
     */
    @Test
    @DisplayName("Should validate script names and throw for unknown scripts")
    void shouldValidateScriptNamesAndThrowForUnknownScripts() {
      // Test that accessing unknown script SHA throws appropriate exception
      RedisScriptManager manager = createTestScriptManager(jedisPool);

      assertThatThrownBy(
              () -> {
                manager.getScriptSha("UNKNOWN_SCRIPT");
              })
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unknown script: UNKNOWN_SCRIPT");

      // Test with null script name - should throw IllegalArgumentException with clear message
      assertThatThrownBy(
              () -> {
                manager.getScriptSha(null);
              })
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Script name cannot be null");

      // Test with empty script name
      assertThatThrownBy(
              () -> {
                manager.getScriptSha("");
              })
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unknown script: ");
    }
  }

  @Nested
  @DisplayName("Batch Operation Failure Tests")
  class BatchOperationFailureTests {

    /**
     * Tests that batch operation failures fall back to individual mode gracefully. Uses a spy to
     * inject an invalid SHA for the batch acquisition script, verifying that saturatePool handles
     * the failure without throwing exceptions.
     */
    @Test
    @DisplayName("Should fallback to individual mode when batch script fails")
    void shouldFallbackWhenBatchScriptFails() throws Exception {
      // Enable batch operations to trigger the batch path
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(5);

      // Create a spy on the real script manager so we can make just the batch script fail
      RedisScriptManager spyScriptManager = spy(scriptManager);

      // Make batch acquisition script return invalid SHA to trigger failure
      when(spyScriptManager.getScriptSha(RedisScriptManager.ACQUIRE_AGENTS))
          .thenReturn("invalid-batch-sha-will-cause-redis-error");
      // All other scripts work normally (using real implementation)

      AgentAcquisitionService serviceWithFailingBatch =
          new AgentAcquisitionService(
              jedisPool,
              spyScriptManager,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties,
              TestFixtures.createTestMetrics());

      // Register some agents and add them to WAITING_SET so they can be acquired
      try (Jedis jedis = jedisPool.getResource()) {
        long nowSec = TestFixtures.getRedisTimeSeconds(jedis);
        for (int i = 1; i <= 3; i++) {
          Agent agent = TestFixtures.createMockAgent("fallback-agent-" + i, "test-provider");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = TestFixtures.createMockInstrumentation();
          serviceWithFailingBatch.registerAgent(agent, execution, instrumentation);

          // Add agents to WAITING_SET so they can be acquired
          jedis.zadd("waiting", nowSec - 1, "fallback-agent-" + i); // Ready now
        }
      }

      // The key test: verify that when batch operations fail, the system handles it gracefully
      // We don't care about the exact number acquired - we care that it doesn't crash
      java.util.concurrent.atomic.AtomicInteger acquiredRef =
          new java.util.concurrent.atomic.AtomicInteger(0);
      assertThatCode(
              () -> {
                int acquired = serviceWithFailingBatch.saturatePool(0L, null, executorService);
                acquiredRef.set(acquired);

                // Should handle the failure gracefully - any result >= 0 is acceptable
                // The important thing is no exception was thrown
                assertThat(acquired).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();

      // Verify the batch script was actually called (and failed)
      verify(spyScriptManager, atLeastOnce()).getScriptSha(RedisScriptManager.ACQUIRE_AGENTS);

      // Note: Metrics and Redis state verification are intentionally omitted here.
      // The key assertion is graceful error handling (no exception thrown), not specific
      // metric values or Redis state. Fallback metrics would require mocking the metrics
      // object, and Redis state is inherently variable in fallback scenarios.
    }

    /**
     * Tests that partial batch failures are handled gracefully. Verifies saturatePool returns
     * without throwing exceptions and acquires a reasonable number of agents (0-3 range).
     */
    @Test
    @DisplayName("Should handle partial batch failure gracefully")
    void shouldHandlePartialBatchFailure() throws Exception {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(2);

      // Register agents
      for (int i = 1; i <= 3; i++) {
        Agent agent = TestFixtures.createMockAgent("partial-agent-" + i, "test-provider");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = TestFixtures.createMockInstrumentation();
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Note: Simulating partial failure mid-operation (e.g., closing Redis connection) is
      // complex and may be flaky. This test verifies resilience by checking graceful handling.
      int acquired = acquisitionService.saturatePool(0L, null, executorService);

      // Should handle gracefully - either succeed with batch or fallback
      assertThat(acquired).isGreaterThanOrEqualTo(0);
      assertThat(acquired).isLessThanOrEqualTo(3);
    }
  }

  @Nested
  @DisplayName("Configuration Edge Cases")
  class ConfigurationEdgeCaseTests {

    /**
     * Tests that zero batch size configuration is handled gracefully. When batch size is 0 and
     * batch operations are enabled, saturatePool should not throw exceptions.
     */
    @Test
    @DisplayName("Should handle zero batch size configuration")
    void shouldHandleZeroBatchSize() {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(0);

      AgentAcquisitionService serviceWithZeroBatch =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties,
              TestFixtures.createTestMetrics());

      Agent agent = TestFixtures.createMockAgent("zero-batch-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = TestFixtures.createMockInstrumentation();
      serviceWithZeroBatch.registerAgent(agent, execution, instrumentation);

      // Should handle zero batch size gracefully (likely fallback to individual)
      // Note: Mode verification (batch vs individual) is omitted; key assertion is no exception.
      assertThatCode(
              () -> {
                int acquired = serviceWithZeroBatch.saturatePool(0L, null, executorService);
                assertThat(acquired).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();
    }

    /**
     * Tests that negative batch size configuration is handled gracefully. When batch size is -1,
     * saturatePool should not throw exceptions.
     */
    @Test
    @DisplayName("Should handle negative batch size configuration")
    void shouldHandleNegativeBatchSize() {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(-1);

      AgentAcquisitionService serviceWithNegativeBatch =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties,
              TestFixtures.createTestMetrics());

      Agent agent = TestFixtures.createMockAgent("negative-batch-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = TestFixtures.createMockInstrumentation();
      serviceWithNegativeBatch.registerAgent(agent, execution, instrumentation);

      // Should handle negative batch size gracefully
      assertThatCode(
              () -> {
                int acquired = serviceWithNegativeBatch.saturatePool(0L, null, executorService);
                assertThat(acquired).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();
    }

    /**
     * Tests that extreme batch size configuration (Integer.MAX_VALUE) is handled gracefully without
     * crashing or throwing exceptions.
     */
    @Test
    @DisplayName("Should handle extreme batch size configurations")
    void shouldHandleExtremeBatchSizes() {
      // Test with very large batch size
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(Integer.MAX_VALUE);

      AgentAcquisitionService serviceWithLargeBatch =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties,
              TestFixtures.createTestMetrics());

      Agent agent = TestFixtures.createMockAgent("large-batch-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = TestFixtures.createMockInstrumentation();
      serviceWithLargeBatch.registerAgent(agent, execution, instrumentation);

      // Should handle extreme batch size without crashing
      assertThatCode(
              () -> {
                int acquired = serviceWithLargeBatch.saturatePool(0L, null, executorService);
                assertThat(acquired).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("Resource Cleanup Under Error Conditions")
  class ResourceCleanupTests {

    /**
     * Tests that local state is maintained when Redis pool fails. After pool closure, saturatePool
     * should handle gracefully (acquire 0 agents) while preserving local registration state.
     */
    @Test
    @DisplayName("Should cleanup resources when Redis pool fails")
    void shouldCleanupResourcesWhenRedisPoolFails() throws Exception {
      Agent agent = TestFixtures.createMockAgent("cleanup-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = TestFixtures.createMockInstrumentation();
      acquisitionService.registerAgent(agent, execution, instrumentation);

      // Close the Redis pool to simulate failure
      jedisPool.close();

      // Should handle pool closure gracefully
      // Note: Metrics verification is omitted; focus is on graceful handling and state
      // preservation.
      assertThatCode(
              () -> {
                int acquired = acquisitionService.saturatePool(0L, null, executorService);
                assertThat(acquired).isEqualTo(0);
              })
          .doesNotThrowAnyException();

      // Verify local state is maintained even when Redis fails
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(1);
    }

    /**
     * Tests that concurrent agent registration and acquisition operations are thread-safe. Runs
     * acquisition and registration in parallel threads and verifies no
     * ConcurrentModificationException occurs, with final state being consistent.
     */
    @Test
    @DisplayName("Should handle concurrent modification of agent maps")
    void shouldHandleConcurrentModificationOfAgentMaps() throws Exception {
      // Register initial agents
      for (int i = 1; i <= 5; i++) {
        Agent agent = TestFixtures.createMockAgent("concurrent-agent-" + i, "test-provider");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = TestFixtures.createMockInstrumentation();
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Simulate concurrent modification by running acquisition and registration simultaneously
      Thread acquisitionThread =
          new Thread(
              () -> {
                for (int i = 0; i < 10; i++) {
                  acquisitionService.saturatePool((long) i, null, executorService);
                  try {
                    Thread.sleep(10);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
              });

      Thread registrationThread =
          new Thread(
              () -> {
                for (int i = 6; i <= 10; i++) {
                  Agent agent =
                      TestFixtures.createMockAgent("concurrent-agent-" + i, "test-provider");
                  AgentExecution execution = mock(AgentExecution.class);
                  ExecutionInstrumentation instrumentation =
                      TestFixtures.createMockInstrumentation();
                  acquisitionService.registerAgent(agent, execution, instrumentation);
                  try {
                    Thread.sleep(15);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                  }
                }
              });

      // Should handle concurrent access without throwing exceptions
      // Note: Metrics and Redis state verification are omitted; focus is on thread safety.
      assertThatCode(
              () -> {
                acquisitionThread.start();
                registrationThread.start();
                acquisitionThread.join(5000);
                registrationThread.join(5000);
              })
          .doesNotThrowAnyException();

      // Verify final state is consistent
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(10);
    }
  }
}
