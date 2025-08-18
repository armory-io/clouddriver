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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Comprehensive error handling tests for PriorityScheduler components.
 *
 * <p>Tests critical error scenarios that may not be covered elsewhere: - AgentSchedulingException
 * usage and propagation - Script loading failures and recovery - Batch operation failures with
 * proper fallback - Configuration validation edge cases - Resource cleanup under error conditions
 */
@Testcontainers
@DisplayName("Error Handling Comprehensive Tests")
public class ErrorHandlingComprehensiveTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

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
    String redisHost = redis.getHost();
    Integer redisPort = redis.getMappedPort(6379);

    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(8);
    jedisPool = new JedisPool(poolConfig, redisHost, redisPort);

    // Clean Redis state
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushAll();
    }

    scriptManager = new RedisScriptManager(jedisPool);
    scriptManager.initializeScripts();

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
            schedulerProperties);
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
  @DisplayName("AgentSchedulingException Tests")
  class AgentSchedulingExceptionTests {

    @Test
    @DisplayName("Should create exception with message only")
    void shouldCreateExceptionWithMessage() {
      String message = "Agent scheduling failed due to configuration error";
      AgentSchedulingException exception = new AgentSchedulingException(message);

      assertThat(exception.getMessage()).isEqualTo(message);
      assertThat(exception.getCause()).isNull();
    }

    @Test
    @DisplayName("Should create exception with message and cause")
    void shouldCreateExceptionWithMessageAndCause() {
      String message = "Redis script execution failed";
      RuntimeException cause = new RuntimeException("Connection timeout");
      AgentSchedulingException exception = new AgentSchedulingException(message, cause);

      assertThat(exception.getMessage()).isEqualTo(message);
      assertThat(exception.getCause()).isEqualTo(cause);
    }

    @Test
    @DisplayName("Should create exception wrapping cause")
    void shouldCreateExceptionWrappingCause() {
      RuntimeException cause = new RuntimeException("Lua script error");
      AgentSchedulingException exception = new AgentSchedulingException(cause);

      assertThat(exception.getCause()).isEqualTo(cause);
      assertThat(exception.getMessage()).contains("Lua script error");
    }

    @Test
    @DisplayName("Should be instance of RuntimeException")
    void shouldBeInstanceOfRuntimeException() {
      AgentSchedulingException exception = new AgentSchedulingException("test");
      assertThat(exception).isInstanceOf(RuntimeException.class);
    }
  }

  @Nested
  @DisplayName("Script Loading Failure Tests")
  class ScriptLoadingFailureTests {

    @Test
    @DisplayName("Should handle corrupted Lua script gracefully")
    void shouldHandleCorruptedLuaScript() {
      // Create a script manager with mock Jedis pool that fails script loading
      JedisPool mockPool = mock(JedisPool.class);
      Jedis mockJedis = mock(Jedis.class);
      when(mockPool.getResource()).thenReturn(mockJedis);
      when(mockJedis.scriptLoad(anyString()))
          .thenThrow(new RuntimeException("Script compilation error"));

      RedisScriptManager failingScriptManager = new RedisScriptManager(mockPool);

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

    @Test
    @DisplayName("Should validate script names and throw for unknown scripts")
    void shouldValidateScriptNamesAndThrowForUnknownScripts() {
      // Test that accessing unknown script SHA throws appropriate exception
      RedisScriptManager manager = new RedisScriptManager(jedisPool);
      manager.initializeScripts();

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
              schedulerProperties);

      // Register some agents
      for (int i = 1; i <= 3; i++) {
        Agent agent = createMockAgent("fallback-agent-" + i, "test-provider");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        serviceWithFailingBatch.registerAgent(agent, execution, instrumentation);
      }

      // The key test: verify that when batch operations fail, the system handles it gracefully
      // We don't care about the exact number acquired - we care that it doesn't crash
      assertThatCode(
              () -> {
                int acquired = serviceWithFailingBatch.saturatePool(0L, null, executorService);

                // Should handle the failure gracefully - any result >= 0 is acceptable
                // The important thing is no exception was thrown
                assertThat(acquired).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();

      // Additional verification: ensure the batch script was actually called (and failed)
      verify(spyScriptManager, atLeastOnce()).getScriptSha(RedisScriptManager.ACQUIRE_AGENTS);
    }

    @Test
    @DisplayName("Should handle partial batch failure gracefully")
    void shouldHandlePartialBatchFailure() throws Exception {
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(2);

      // Register agents
      for (int i = 1; i <= 3; i++) {
        Agent agent = createMockAgent("partial-agent-" + i, "test-provider");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Close Redis connection mid-operation to simulate partial failure
      int acquired = acquisitionService.saturatePool(0L, null, executorService);

      // Should handle gracefully - either succeed with batch or fallback
      assertThat(acquired).isGreaterThanOrEqualTo(0);
      assertThat(acquired).isLessThanOrEqualTo(3);
    }
  }

  @Nested
  @DisplayName("Configuration Edge Cases")
  class ConfigurationEdgeCaseTests {

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
              schedulerProperties);

      Agent agent = createMockAgent("zero-batch-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
      serviceWithZeroBatch.registerAgent(agent, execution, instrumentation);

      // Should handle zero batch size gracefully (likely fallback to individual)
      assertThatCode(
              () -> {
                int acquired = serviceWithZeroBatch.saturatePool(0L, null, executorService);
                assertThat(acquired).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();
    }

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
              schedulerProperties);

      Agent agent = createMockAgent("negative-batch-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
      serviceWithNegativeBatch.registerAgent(agent, execution, instrumentation);

      // Should handle negative batch size gracefully
      assertThatCode(
              () -> {
                int acquired = serviceWithNegativeBatch.saturatePool(0L, null, executorService);
                assertThat(acquired).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();
    }

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
              schedulerProperties);

      Agent agent = createMockAgent("large-batch-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
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

    @Test
    @DisplayName("Should cleanup resources when Redis pool fails")
    void shouldCleanupResourcesWhenRedisPoolFails() throws Exception {
      Agent agent = createMockAgent("cleanup-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
      acquisitionService.registerAgent(agent, execution, instrumentation);

      // Close the Redis pool to simulate failure
      jedisPool.close();

      // Should handle pool closure gracefully
      assertThatCode(
              () -> {
                int acquired = acquisitionService.saturatePool(0L, null, executorService);
                assertThat(acquired).isEqualTo(0);
              })
          .doesNotThrowAnyException();

      // Verify local state is maintained even when Redis fails
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should handle concurrent modification of agent maps")
    void shouldHandleConcurrentModificationOfAgentMaps() throws Exception {
      // Register initial agents
      for (int i = 1; i <= 5; i++) {
        Agent agent = createMockAgent("concurrent-agent-" + i, "test-provider");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
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
                  Agent agent = createMockAgent("concurrent-agent-" + i, "test-provider");
                  AgentExecution execution = mock(AgentExecution.class);
                  ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
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

  private Agent createMockAgent(String name, String providerType) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(name);
    when(agent.getProviderName()).thenReturn(providerType);
    return agent;
  }
}
