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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Comprehensive test suite for AgentAcquisitionService using testcontainers.
 *
 * <p>Tests cover: - Agent registration and unregistration - Agent acquisition and scheduling logic
 * - Concurrency control and semaphore handling - Redis integration for agent state management -
 * Error handling and edge cases - Performance under load
 */
@Testcontainers
@DisplayName("AgentAcquisitionService Tests")
class AgentAcquisitionServiceTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private AgentAcquisitionService acquisitionService;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private ClusteredSortAgentProperties agentProperties;
  private ClusteredSortSchedulerProperties schedulerProperties;
  private ExecutorService executorService;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    scriptManager = new RedisScriptManager(jedisPool);
    scriptManager.initializeScripts();

    // Mock dependencies
    intervalProvider = mock(AgentIntervalProvider.class);
    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    // Create properties with test values
    agentProperties = new ClusteredSortAgentProperties();
    agentProperties.setMaxConcurrentAgents(5);
    agentProperties.setEnabledPattern(".*");
    agentProperties.setDisabledPattern("");

    schedulerProperties = new ClusteredSortSchedulerProperties();
    schedulerProperties.setRefreshPeriodSeconds(10);

    executorService = Executors.newCachedThreadPool();

    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);
  }

  /**
   * Helper method to recreate the AgentAcquisitionService after changing properties. This is needed
   * because the service compiles patterns in the constructor.
   */
  private void recreateAcquisitionService() {
    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);
  }

  @Nested
  @DisplayName("Agent Registration Tests")
  class AgentRegistrationTests {

    @Test
    @DisplayName("Should register enabled agents successfully")
    void shouldRegisterEnabledAgentsSuccessfully() {
      // Given
      Agent agent = createMockAgent("test-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When
      acquisitionService.registerAgent(agent, execution, instrumentation);

      // Then
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(1);
      assertThat(acquisitionService.getActiveAgentCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should not register disabled agents")
    void shouldNotRegisterDisabledAgents() {
      // Given
      Agent agent = createMockAgent("disabled-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // Update properties to disable this agent using pattern
      ClusteredSortAgentProperties testAgentProperties = new ClusteredSortAgentProperties();
      testAgentProperties.setDisabledPattern("disabled-agent");
      testAgentProperties.setMaxConcurrentAgents(5);
      testAgentProperties.setEnabledPattern(".*");

      acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              testAgentProperties,
              schedulerProperties);

      // When
      acquisitionService.registerAgent(agent, execution, instrumentation);

      // Then
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should unregister agents successfully")
    void shouldUnregisterAgentsSuccessfully() {
      // Given
      Agent agent = createMockAgent("test-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(agent, execution, instrumentation);
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(1);

      // When
      acquisitionService.unregisterAgent(agent);

      // Then
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle multiple agent registrations")
    void shouldHandleMultipleAgentRegistrations() {
      // Given
      Agent agent1 = createMockAgent("agent-1", "provider-1");
      Agent agent2 = createMockAgent("agent-2", "provider-2");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When
      acquisitionService.registerAgent(agent1, execution, instrumentation);
      acquisitionService.registerAgent(agent2, execution, instrumentation);

      // Then
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Agent Acquisition Tests")
  class AgentAcquisitionTests {

    @BeforeEach
    void setUpAgents() {
      // Set up interval provider to return reasonable intervals
      when(intervalProvider.getInterval(any(Agent.class)))
          .thenReturn(new AgentIntervalProvider.Interval(1000L, 5000L, 2000L));
    }

    @Test
    @DisplayName("Should acquire ready agents from Redis")
    void shouldAcquireReadyAgentsFromRedis() {
      // Given
      Agent agent = createMockAgent("ready-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(agent, execution, instrumentation);

      // Add agent to Redis WAITING set with past score (ready for execution)
      try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WAITZ", System.currentTimeMillis() - 1000, "ready-agent");
      }

      // When
      int acquired = acquisitionService.saturatePool(1L, null, executorService);

      // Then
      assertThat(acquired).isEqualTo(1);
      assertThat(acquisitionService.getActiveAgentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should respect concurrency limits")
    void shouldRespectConcurrencyLimits() {
      // Given - Set max concurrent agents to 2
      agentProperties.setMaxConcurrentAgents(2);

      Agent agent1 = createMockAgent("agent-1", "test-provider");
      Agent agent2 = createMockAgent("agent-2", "test-provider");
      Agent agent3 = createMockAgent("agent-3", "test-provider");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(agent1, execution, instrumentation);
      acquisitionService.registerAgent(agent2, execution, instrumentation);
      acquisitionService.registerAgent(agent3, execution, instrumentation);

      // Add all agents to Redis as ready
      try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
        long readyScore = System.currentTimeMillis() - 1000;
        jedis.zadd("WAITZ", readyScore, "agent-1");
        jedis.zadd("WAITZ", readyScore, "agent-2");
        jedis.zadd("WAITZ", readyScore, "agent-3");
      }

      // When - First acquisition should get 2 agents
      int firstAcquired = acquisitionService.saturatePool(1L, null, executorService);

      // Simulate agents still running by not removing them from active tracking
      // Second acquisition should get 0 more agents due to limit
      int secondAcquired = acquisitionService.saturatePool(2L, null, executorService);

      // Then
      assertThat(firstAcquired).isEqualTo(2);
      assertThat(secondAcquired).isEqualTo(0);
      assertThat(acquisitionService.getActiveAgentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle semaphore-based concurrency control")
    void shouldHandleSemaphoreBasedConcurrencyControl() {
      // Given
      Semaphore semaphore = new Semaphore(1); // Only 1 permit
      Agent agent1 = createMockAgent("agent-1", "test-provider");
      Agent agent2 = createMockAgent("agent-2", "test-provider");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(agent1, execution, instrumentation);
      acquisitionService.registerAgent(agent2, execution, instrumentation);

      // Add agents to Redis as ready
      try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
        long readyScore = System.currentTimeMillis() - 1000;
        jedis.zadd("WAITZ", readyScore, "agent-1");
        jedis.zadd("WAITZ", readyScore, "agent-2");
      }

      // When
      int acquired = acquisitionService.saturatePool(1L, semaphore, executorService);

      // Then - Should only acquire 1 agent due to semaphore limit
      assertThat(acquired).isEqualTo(1);
      assertThat(semaphore.availablePermits()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should skip agents not ready for execution")
    void shouldSkipAgentsNotReadyForExecution() {
      // Given
      Agent agent = createMockAgent("future-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(agent, execution, instrumentation);

      // Add agent to Redis with future score (not ready yet)
      try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WAITZ", System.currentTimeMillis() + 60000, "future-agent");
      }

      // When
      int acquired = acquisitionService.saturatePool(1L, null, executorService);

      // Then
      assertThat(acquired).isEqualTo(0);
      assertThat(acquisitionService.getActiveAgentCount()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Error Handling Tests")
  class ErrorHandlingTests {

    @Test
    @DisplayName("Should handle Redis connection failures gracefully")
    void shouldHandleRedisConnectionFailuresGracefully() {
      // Given
      jedisPool.close(); // Close connection pool
      Agent agent = createMockAgent("test-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(agent, execution, instrumentation);

      // When
      int acquired = acquisitionService.saturatePool(1L, null, executorService);

      // Then - Should return 0 and not crash
      assertThat(acquired).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle missing agents in Redis gracefully")
    void shouldHandleMissingAgentsInRedisGracefully() {
      // Given
      Agent agent = createMockAgent("missing-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(agent, execution, instrumentation);
      // Note: Not adding agent to Redis

      // When
      int acquired = acquisitionService.saturatePool(1L, null, executorService);

      // Then
      assertThat(acquired).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Performance Tests")
  class PerformanceTests {

    @Test
    @DisplayName("Should handle high volume agent registration efficiently")
    void shouldHandleHighVolumeAgentRegistrationEfficiently() {
      // Given
      int agentCount = 1000;
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      long startTime = System.currentTimeMillis();

      // When - Register many agents
      for (int i = 0; i < agentCount; i++) {
        Agent agent = createMockAgent("agent-" + i, "provider-" + (i % 10));
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      long duration = System.currentTimeMillis() - startTime;

      // Then
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(agentCount);
      assertThat(duration).isLessThan(5000); // Should complete within 5 seconds
    }

    @Test
    @DisplayName("Should handle concurrent agent operations efficiently")
    void shouldHandleConcurrentAgentOperationsEfficiently() throws InterruptedException {
      // Given
      int threadCount = 10;
      int agentsPerThread = 100;
      Thread[] threads = new Thread[threadCount];

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When - Multiple threads register agents concurrently
      for (int t = 0; t < threadCount; t++) {
        final int threadId = t;
        threads[t] =
            new Thread(
                () -> {
                  for (int i = 0; i < agentsPerThread; i++) {
                    Agent agent =
                        createMockAgent("agent-" + threadId + "-" + i, "provider-" + threadId);
                    acquisitionService.registerAgent(agent, execution, instrumentation);
                  }
                });
        threads[t].start();
      }

      // Wait for all threads to complete
      for (Thread thread : threads) {
        thread.join();
      }

      // Then
      assertThat(acquisitionService.getRegisteredAgentCount())
          .isEqualTo(threadCount * agentsPerThread);
    }
  }

  @Nested
  @DisplayName("Disabled Pattern Integration Tests")
  class DisabledPatternIntegrationTests {

    @Test
    @DisplayName("Should disable agents using pattern matching")
    void shouldDisableAgentsUsingPatternMatching() {
      // Given - Set up disabled pattern
      agentProperties.setDisabledPattern("aws-(test|dev)-.*");
      recreateAcquisitionService();

      // Create test agents and mocks
      Agent enabledAgent = createMockAgent("aws-prod-ec2", "aws");
      Agent disabledAgent1 = createMockAgent("aws-test-ec2", "aws");
      Agent disabledAgent2 = createMockAgent("aws-dev-compute", "aws");
      Agent otherEnabledAgent = createMockAgent("gcp-test-compute", "gcp");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
      when(shardingFilter.filter(any())).thenReturn(true);

      // When - Register agents
      acquisitionService.registerAgent(enabledAgent, execution, instrumentation);
      acquisitionService.registerAgent(disabledAgent1, execution, instrumentation);
      acquisitionService.registerAgent(disabledAgent2, execution, instrumentation);
      acquisitionService.registerAgent(otherEnabledAgent, execution, instrumentation);

      // Then - Only non-matching agents should be registered (2 enabled, 2 disabled)
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should be case sensitive in pattern matching")
    void shouldBeCaseSensitiveInPatternMatching() {
      // Given - Case sensitive pattern
      agentProperties.setDisabledPattern("aws-.*");
      recreateAcquisitionService();

      Agent lowerCaseAgent = createMockAgent("aws-ec2", "aws");
      Agent upperCaseAgent = createMockAgent("AWS-ec2", "aws");
      Agent mixedCaseAgent = createMockAgent("aws-EC2", "aws");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
      when(shardingFilter.filter(any())).thenReturn(true);

      // When - Register agents
      acquisitionService.registerAgent(lowerCaseAgent, execution, instrumentation);
      acquisitionService.registerAgent(upperCaseAgent, execution, instrumentation);
      acquisitionService.registerAgent(mixedCaseAgent, execution, instrumentation);

      // Then - Only uppercase agent enabled (doesn't match "aws-.*" pattern)
      assertThat(acquisitionService.getRegisteredAgentCount()).isEqualTo(1);
    }
  }

  private Agent createMockAgent(String agentType, String providerName) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    when(agent.getProviderName()).thenReturn(providerName);
    return agent;
  }
}
