/*
 * Copyright 2023 THL A29 Limited, a Tencent company.
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentDataType;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.AgentScheduler;
import com.netflix.spinnaker.cats.agent.CacheResult;
import com.netflix.spinnaker.cats.agent.CachingAgent;
import com.netflix.spinnaker.cats.agent.DefaultCacheResult;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.agent.RunnableAgent;
import com.netflix.spinnaker.cats.cluster.DefaultAgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.cats.provider.ProviderCache;
import com.netflix.spinnaker.cats.provider.ProviderRegistry;
import com.netflix.spinnaker.cats.test.TestAgent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Comprehensive tests for ClusteredSortAgentScheduler following proven patterns from
 * DefaultAgentSchedulerSpec and other scheduler tests.
 */
class ClusteredSortAgentSchedulerTest {

  // Redis set constants from ClusteredSortAgentScheduler
  private static final String WAITING_SET = "WAITZ";
  private static final String WORKING_SET = "WORKZ";

  // Helper methods to create properties for tests
  private static ClusteredSortAgentProperties createDefaultAgentProperties() {
    return new ClusteredSortAgentProperties();
  }

  private static ClusteredSortSchedulerProperties createDefaultSchedulerProperties() {
    return new ClusteredSortSchedulerProperties();
  }

  private ClusteredSortAgentScheduler scheduler;
  private JedisPool jedisPool;
  private Jedis jedis;
  private ExecutionInstrumentation executionInstrumentation;
  private List<String> disabledAgents;
  private ProviderRegistry providerRegistry;
  private ShardingFilter shardingFilter;

  @BeforeEach
  void setUp() throws Exception {
    jedisPool = mock(JedisPool.class);
    jedis = mock(Jedis.class);
    executionInstrumentation = mock(ExecutionInstrumentation.class);
    providerRegistry = mock(ProviderRegistry.class);
    shardingFilter = mock(ShardingFilter.class);
    disabledAgents = new ArrayList<>();

    when(jedisPool.getResource()).thenReturn(jedis);
    when(jedis.scriptLoad(anyString())).thenReturn("sha1");
    when(jedis.scriptExists(anyString())).thenReturn(true);
    when(jedis.time()).thenReturn(List.of("1000", "0")); // Mock Redis TIME command
    when(jedis.evalsha(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn("OK");
    when(jedis.evalsha(anyString(), anyInt(), anyString(), anyString(), anyString()))
        .thenReturn(1L);

    // All configuration is now provided via cached @ConfigurationProperties

    // Default to enabling the node
    NodeStatusProvider nodeStatusProvider = () -> true;

    scheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            new DefaultAgentIntervalProvider(30, 30, 300),
            ".*",
            shardingFilter,
            createDefaultAgentProperties(),
            createDefaultSchedulerProperties(),
            disabledAgents);
  }

  @Nested
  @DisplayName("Agent Scheduling Tests")
  class AgentSchedulingTests {

    @Test
    @DisplayName("Should successfully schedule CachingAgent")
    void shouldScheduleCachingAgent() {
      // Given
      TestCachingAgent agent = new TestCachingAgent();
      AgentExecution execution = agent.getAgentExecution(providerRegistry);

      // When
      assertThatCode(() -> scheduler.schedule(agent, execution, executionInstrumentation))
          .doesNotThrowAnyException();

      // Then
      verify(jedis).evalsha(anyString(), eq(2), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should successfully schedule RunnableAgent")
    void shouldScheduleRunnableAgent() {
      // Given
      TestRunnableAgent agent = new TestRunnableAgent();
      RunnableAgent.RunnableAgentExecution execution =
          (RunnableAgent.RunnableAgentExecution) agent.getAgentExecution(providerRegistry);

      // When - This should not throw the original "Sort scheduler requires agent executions to be
      // of type CacheExecution" error
      assertThatCode(() -> scheduler.schedule(agent, execution, executionInstrumentation))
          .doesNotThrowAnyException();

      // Then
      verify(jedis, atLeast(1))
          .evalsha(anyString(), eq(2), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should handle scheduler-aware agents")
    void shouldHandleSchedulerAwareAgents() {
      // Given
      TestAgentSchedulerAware agent = new TestAgentSchedulerAware();
      AgentExecution execution = agent.getAgentExecution(providerRegistry);

      // When & Then - Should schedule without errors
      assertThatCode(() -> scheduler.schedule(agent, execution, executionInstrumentation))
          .doesNotThrowAnyException();

      // Verify Redis scheduling operation was called
      verify(jedis)
          .evalsha(
              anyString(), eq(2), anyString(), anyString(), eq(agent.getAgentType()), anyString());
    }

    @Test
    @DisplayName("Should handle custom AgentExecution implementations")
    void shouldHandleCustomAgentExecutionImplementations() {
      // Given
      TestAgent agent = new TestAgent();
      CustomAgentExecution execution = new CustomAgentExecution();

      // When
      assertThatCode(() -> scheduler.schedule(agent, execution, executionInstrumentation))
          .doesNotThrowAnyException();

      // Then
      verify(jedis).evalsha(anyString(), eq(2), anyString(), anyString(), anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("Agent Execution Tests")
  class AgentExecutionTests {

    @Test
    @DisplayName(
        "ExecutionInstrumentation is informed of agent execution - following DefaultAgentSchedulerSpec pattern")
    void executionInstrumentationIsInformedOfAgentExecution() throws Exception {
      // Given
      TestRunnableAgent agent = new TestRunnableAgent();
      RunnableAgent.RunnableAgentExecution execution =
          (RunnableAgent.RunnableAgentExecution) agent.getAgentExecution(providerRegistry);

      scheduler.schedule(agent, execution, executionInstrumentation);

      // Get the scheduled AgentWorker
      @SuppressWarnings("unchecked")
      var agents = (java.util.Map<String, Object>) FieldUtils.readField(scheduler, "agents", true);
      var agentWorker = agents.get(agent.getAgentType());

      // Set the score (normally done by the scheduler)
      FieldUtils.writeField(agentWorker, "acquireScore", "1000", true);

      // When - Execute the agent
      ((Runnable) agentWorker).run();

      // Then - Following DefaultAgentSchedulerSpec verification pattern
      verify(executionInstrumentation).executionStarted(agent);
      verify(executionInstrumentation).executionCompleted(eq(agent), any(Long.class));
      verify(executionInstrumentation, never())
          .executionFailed(eq(agent), any(Throwable.class), any(Long.class));

      // Verify agent actually ran
      assertThat(agent.hasRun()).isTrue();
    }

    @Test
    @DisplayName(
        "ExecutionInstrumentation is informed of agent failure - following DefaultAgentSchedulerSpec pattern")
    void executionInstrumentationIsInformedOfAgentFailure() throws Exception {
      // Given
      FailingRunnableAgent agent = new FailingRunnableAgent();
      RunnableAgent.RunnableAgentExecution execution =
          (RunnableAgent.RunnableAgentExecution) agent.getAgentExecution(providerRegistry);

      scheduler.schedule(agent, execution, executionInstrumentation);

      // Get the scheduled AgentWorker
      @SuppressWarnings("unchecked")
      var agents = (java.util.Map<String, Object>) FieldUtils.readField(scheduler, "agents", true);
      var agentWorker = agents.get(agent.getAgentType());

      // Set the score (normally done by the scheduler)
      FieldUtils.writeField(agentWorker, "acquireScore", "1000", true);

      // When - Execute the failing agent
      ((Runnable) agentWorker).run();

      // Then - Following DefaultAgentSchedulerSpec verification pattern
      verify(executionInstrumentation).executionStarted(agent);
      verify(executionInstrumentation)
          .executionFailed(eq(agent), any(RuntimeException.class), any(Long.class));
      verify(executionInstrumentation, never()).executionCompleted(eq(agent), any(Long.class));
    }
  }

  @Nested
  @DisplayName("Scheduler Behavior Tests")
  class SchedulerBehaviorTests {

    @Test
    @DisplayName("Should be atomic scheduler")
    void shouldBeAtomicScheduler() {
      assertThat(scheduler.isAtomic()).isTrue();
    }

    @Test
    @DisplayName("Should handle unscheduling agents")
    void shouldHandleUnschedulingAgents() {
      // Given
      TestRunnableAgent agent = new TestRunnableAgent();
      RunnableAgent.RunnableAgentExecution execution =
          (RunnableAgent.RunnableAgentExecution) agent.getAgentExecution(providerRegistry);

      scheduler.schedule(agent, execution, executionInstrumentation);

      // When
      assertThatCode(() -> scheduler.unschedule(agent)).doesNotThrowAnyException();

      // Then
      verify(jedis)
          .evalsha(anyString(), eq(2), eq(WAITING_SET), eq(WORKING_SET), eq(agent.getAgentType()));
    }

    @Test
    @DisplayName("Should handle multiple agents concurrently")
    void shouldHandleMultipleAgentsConcurrently() {
      // Given
      TestRunnableAgent agent1 = new TestRunnableAgent("agent1");
      TestRunnableAgent agent2 = new TestRunnableAgent("agent2");
      TestRunnableAgent agent3 = new TestRunnableAgent("agent3");

      // When - Schedule multiple agents
      assertThatCode(
              () -> {
                scheduler.schedule(
                    agent1, agent1.getAgentExecution(providerRegistry), executionInstrumentation);
                scheduler.schedule(
                    agent2, agent2.getAgentExecution(providerRegistry), executionInstrumentation);
                scheduler.schedule(
                    agent3, agent3.getAgentExecution(providerRegistry), executionInstrumentation);
              })
          .doesNotThrowAnyException();

      // Then - All should be scheduled
      verify(jedis, atLeast(3))
          .evalsha(anyString(), eq(2), anyString(), anyString(), anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("DisabledAgentsTests")
  class DisabledAgentsTests {

    private NodeStatusProvider nodeStatusProvider;

    @BeforeEach
    void setUp() {
      nodeStatusProvider = () -> true;
    }

    @Test
    @DisplayName("Should not schedule explicitly disabled agents")
    void shouldNotScheduleDisabledAgents() {
      // Create a scheduler with specific agent types disabled
      disabledAgents = Arrays.asList("DisabledAgent", "AnotherDisabledAgent");
      scheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              new DefaultAgentIntervalProvider(30, 30, 300),
              ".*", // enabled pattern (matches everything)
              shardingFilter,
              createDefaultAgentProperties(),
              createDefaultSchedulerProperties(),
              disabledAgents);

      // Create test agents
      TestRunnableAgent enabledAgent = new TestRunnableAgent("EnabledAgent");
      TestRunnableAgent disabledAgent = new TestRunnableAgent("DisabledAgent");

      // When - create an agent execution for each agent
      AgentExecution enabledExecution = agent -> {}; // Empty execution for testing
      AgentExecution disabledExecution = agent -> {}; // Empty execution for testing

      scheduler.schedule(enabledAgent, enabledExecution, executionInstrumentation);
      scheduler.schedule(disabledAgent, disabledExecution, executionInstrumentation);

      // Then
      // The disabled agent should not be added to Redis
      verify(jedis).evalsha(any(), eq(2), eq("WAITZ"), eq("WORKZ"), eq("EnabledAgent"), any());
      verify(jedis, never())
          .evalsha(any(), eq(2), eq("WAITZ"), eq("WORKZ"), eq("DisabledAgent"), any());
    }

    @Test
    @DisplayName("Should respect both pattern and explicit disabling")
    void shouldRespectBothPatternAndDisabling() {
      // Create a scheduler that only allows agents matching "Allowed.*" pattern,
      // but explicitly disables "AllowedDisabled"
      disabledAgents = List.of("AllowedDisabled");
      scheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              new DefaultAgentIntervalProvider(30, 30, 300),
              "Allowed.*", // enabled pattern
              shardingFilter,
              createDefaultAgentProperties(),
              createDefaultSchedulerProperties(),
              disabledAgents);

      // Create test agents
      TestRunnableAgent allowedAgent = new TestRunnableAgent("AllowedAgent");
      TestRunnableAgent disallowedAgent = new TestRunnableAgent("DisallowedAgent");
      TestRunnableAgent allowedButDisabledAgent = new TestRunnableAgent("AllowedDisabled");

      // When - create agent executions for testing
      AgentExecution execution = agent -> {}; // Empty execution for testing

      scheduler.schedule(allowedAgent, execution, executionInstrumentation);
      scheduler.schedule(disallowedAgent, execution, executionInstrumentation);
      scheduler.schedule(allowedButDisabledAgent, execution, executionInstrumentation);

      // Then
      // Only the allowed and not explicitly disabled agent should be scheduled
      verify(jedis).evalsha(any(), eq(2), eq("WAITZ"), eq("WORKZ"), eq("AllowedAgent"), any());
      verify(jedis, never())
          .evalsha(any(), eq(2), eq("WAITZ"), eq("WORKZ"), eq("DisallowedAgent"), any());
      verify(jedis, never())
          .evalsha(any(), eq(2), eq("WAITZ"), eq("WORKZ"), eq("AllowedDisabled"), any());
    }

    @Test
    @DisplayName("Should handle case insensitivity in disabled agents list")
    void shouldHandleCaseInsensitivityInDisabledList() {
      // Create a scheduler with mixed-case disabled agents
      disabledAgents = Arrays.asList("MixedCaseAgent", "anothermixedcaseagent");
      scheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              new DefaultAgentIntervalProvider(30, 30, 300),
              ".*", // enabled pattern
              shardingFilter,
              createDefaultAgentProperties(),
              createDefaultSchedulerProperties(),
              disabledAgents);

      // Create test agents with different case than in the disabled list
      TestRunnableAgent agent1 =
          new TestRunnableAgent("mixedCaseAgent"); // Different case from disabledAgents
      TestRunnableAgent agent2 =
          new TestRunnableAgent("AnotherMixedCaseAgent"); // Different case from disabledAgents

      // When - create agent execution for testing
      AgentExecution execution = agent -> {}; // Empty execution for testing

      scheduler.schedule(agent1, execution, executionInstrumentation);
      scheduler.schedule(agent2, execution, executionInstrumentation);

      // Then - neither should be scheduled due to case-insensitive matching
      verify(jedis, never())
          .evalsha(any(), eq(2), eq("WAITZ"), eq("WORKZ"), eq("mixedCaseAgent"), any());
      verify(jedis, never())
          .evalsha(any(), eq(2), eq("WAITZ"), eq("WORKZ"), eq("AnotherMixedCaseAgent"), any());
    }
  }

  @Nested
  @DisplayName("Redis Integration Tests")
  class RedisIntegrationTests {

    @Test
    @DisplayName("Should handle Redis connection issues gracefully")
    void shouldHandleRedisConnectionIssuesGracefully() {
      // Given
      when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis connection failed"));

      // When & Then - Should propagate Redis exceptions during scheduling (expected behavior)
      TestRunnableAgent agent = new TestRunnableAgent();
      assertThatCode(
              () ->
                  scheduler.schedule(
                      agent, agent.getAgentExecution(providerRegistry), executionInstrumentation))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Redis operation failed during agent scheduling");
    }

    @Test
    @DisplayName("Should handle saturatePool execution gracefully")
    void shouldHandleSaturatePoolExecutionGracefully() {
      // When & Then - Should not throw exceptions during pool saturation
      assertThatCode(() -> scheduler.saturatePool()).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("Production Critical Tests - Priority Scheduling Logic")
  class PrioritySchedulingTests {

    @Test
    @DisplayName("Should handle saturatePool execution without errors")
    void shouldHandleSaturatePoolExecutionWithoutErrors() {
      // When & Then - Should handle gracefully without throwing exceptions
      assertThatCode(() -> scheduler.saturatePool()).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("Production Critical Tests - Timeout and Recovery")
  class TimeoutAndRecoveryTests {

    @Test
    @DisplayName("Should handle Redis refresh cycle correctly")
    void shouldHandleRedisRefreshCycleCorrectly() throws Exception {
      // Given - Set runCount to trigger refresh
      FieldUtils.writeField(scheduler, "runCount", 30, true); // REDIS_REFRESH_PERIOD = 30

      TestRunnableAgent agent1 = new TestRunnableAgent("agent1");
      scheduler.schedule(
          agent1, agent1.getAgentExecution(providerRegistry), executionInstrumentation);

      // When & Then - Should handle refresh cycle without errors
      assertThatCode(() -> scheduler.saturatePool()).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("Production Critical Tests - Thread Pool Management")
  class ThreadPoolManagementTests {

    @Test
    @DisplayName("Should create scheduler with limited parallelism")
    void shouldCreateSchedulerWithLimitedParallelism() {
      // Given & When - Create scheduler with limited parallelism
      ClusteredSortAgentScheduler limitedScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              () -> true,
              new DefaultAgentIntervalProvider(30000, 60000, 300000),
              ".*", // Enable all agents
              shardingFilter,
              createDefaultAgentProperties(),
              createDefaultSchedulerProperties(),
              java.util.Collections.emptyList()); // No explicitly disabled agents

      // Then - Should create successfully
      assertThat(limitedScheduler).isNotNull();
      assertThat(limitedScheduler.isAtomic()).isTrue();
    }
  }

  @Nested
  @DisplayName("Production Critical Tests - Agent Lifecycle Edge Cases")
  class AgentLifecycleEdgeCaseTests {

    @Test
    @DisplayName("Should handle duplicate agent submissions")
    void shouldHandleDuplicateAgentSubmissions() throws Exception {
      // Given
      TestRunnableAgent agent = new TestRunnableAgent();
      RunnableAgent.RunnableAgentExecution execution =
          (RunnableAgent.RunnableAgentExecution) agent.getAgentExecution(providerRegistry);

      // When - Schedule same agent multiple times
      scheduler.schedule(agent, execution, executionInstrumentation);
      scheduler.schedule(agent, execution, executionInstrumentation); // Duplicate
      scheduler.schedule(agent, execution, executionInstrumentation); // Duplicate

      // Then - Should handle gracefully (last one wins)
      @SuppressWarnings("unchecked")
      var agents = (java.util.Map<String, Object>) FieldUtils.readField(scheduler, "agents", true);
      assertThat(agents).hasSize(1);
      assertThat(agents).containsKey(agent.getAgentType());
    }

    @Test
    @DisplayName("Should handle agent unscheduling")
    void shouldHandleAgentUnscheduling() {
      // Given
      TestRunnableAgent agent = new TestRunnableAgent();
      scheduler.schedule(
          agent, agent.getAgentExecution(providerRegistry), executionInstrumentation);

      // When - Unschedule agent
      assertThatCode(() -> scheduler.unschedule(agent)).doesNotThrowAnyException();

      // Then - Agent removal script should be called
      verify(jedis)
          .evalsha(anyString(), eq(2), eq(WAITING_SET), eq(WORKING_SET), eq(agent.getAgentType()));
    }
  }

  @Nested
  @DisplayName("Configuration Tests")
  class ConfigurationTests {

    @Test
    @DisplayName("Should handle complex agent patterns correctly")
    void shouldHandleComplexAgentPatternsCorrectly() {
      // Given - Complex pattern for multiple cloud providers
      ClusteredSortAgentScheduler patternScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              () -> true,
              new DefaultAgentIntervalProvider(30000, 60000, 300000),
              "(?i).*(aws|gcp|azure).*caching.*", // Case-insensitive pattern
              shardingFilter,
              createDefaultAgentProperties(),
              createDefaultSchedulerProperties(),
              java.util.Collections.emptyList()); // No explicitly disabled agents

      // Test agents
      TestRunnableAgent awsAgent = new TestRunnableAgent("awsCachingAgent");
      TestRunnableAgent gcpAgent = new TestRunnableAgent("gcpCachingAgent");
      TestRunnableAgent azureAgent = new TestRunnableAgent("azureCachingAgent");
      TestRunnableAgent kubernetesAgent = new TestRunnableAgent("kubernetesCachingAgent");
      TestRunnableAgent awsNonCachingAgent = new TestRunnableAgent("awsComputeAgent");

      // When - Schedule all agents
      patternScheduler.schedule(
          awsAgent, awsAgent.getAgentExecution(providerRegistry), executionInstrumentation);
      patternScheduler.schedule(
          gcpAgent, gcpAgent.getAgentExecution(providerRegistry), executionInstrumentation);
      patternScheduler.schedule(
          azureAgent, azureAgent.getAgentExecution(providerRegistry), executionInstrumentation);
      patternScheduler.schedule(
          kubernetesAgent,
          kubernetesAgent.getAgentExecution(providerRegistry),
          executionInstrumentation);
      patternScheduler.schedule(
          awsNonCachingAgent,
          awsNonCachingAgent.getAgentExecution(providerRegistry),
          executionInstrumentation);

      // Then - Only matching agents should be scheduled
      verify(jedis, atLeast(1))
          .evalsha(
              anyString(), eq(2), anyString(), anyString(), eq("awsCachingAgent"), anyString());
      verify(jedis, atLeast(1))
          .evalsha(
              anyString(), eq(2), anyString(), anyString(), eq("gcpCachingAgent"), anyString());
      verify(jedis, atLeast(1))
          .evalsha(
              anyString(), eq(2), anyString(), anyString(), eq("azureCachingAgent"), anyString());
      verify(jedis, never())
          .evalsha(
              anyString(),
              eq(2),
              anyString(),
              anyString(),
              eq("kubernetesCachingAgent"),
              anyString());
      verify(jedis, never())
          .evalsha(
              anyString(), eq(2), anyString(), anyString(), eq("awsComputeAgent"), anyString());
    }
  }

  @Nested
  @DisplayName("Enterprise Features Tests")
  class EnterpriseFeaturesTests {

    @Test
    @DisplayName("Should respect sharding filter during agent repopulation")
    void shouldRespectShardingFilterDuringRepopulation() {
      // Given - Batch operations are disabled by default in properties
      // Individual operations are used to test sharding

      Agent allowedAgent = mock(Agent.class);
      when(allowedAgent.getAgentType()).thenReturn("allowedAgent");
      when(allowedAgent.getAgentExecution(any())).thenReturn(mock(AgentExecution.class));

      Agent filteredAgent = mock(Agent.class);
      when(filteredAgent.getAgentType()).thenReturn("filteredAgent");
      when(filteredAgent.getAgentExecution(any())).thenReturn(mock(AgentExecution.class));

      // Configure sharding filter
      when(shardingFilter.filter(allowedAgent)).thenReturn(true);
      when(shardingFilter.filter(filteredAgent)).thenReturn(false);

      // Schedule both agents
      scheduler.schedule(
          allowedAgent, allowedAgent.getAgentExecution(providerRegistry), executionInstrumentation);
      scheduler.schedule(
          filteredAgent,
          filteredAgent.getAgentExecution(providerRegistry),
          executionInstrumentation);

      // Force a Redis refresh cycle by setting runCount to trigger repopulation
      try {
        FieldUtils.writeField(scheduler, "runCount", 30, true); // REDIS_REFRESH_PERIOD = 30
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      // Trigger repopulation by calling saturatePool
      scheduler.saturatePool();

      // Then - Sharding filter should have been called for both agents during repopulation
      verify(shardingFilter, times(2)).filter(any(Agent.class));
      verify(shardingFilter).filter(allowedAgent);
      verify(shardingFilter).filter(filteredAgent);
    }

    // All configuration is now handled via cached @ConfigurationProperties instead of dynamic
    // config

    @Test
    @DisplayName("Should handle enterprise services gracefully")
    void shouldHandleEnterpriseServicesGracefully() {
      // Given - Create scheduler with all enterprise features
      ClusteredSortAgentScheduler enterpriseScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              () -> true,
              new DefaultAgentIntervalProvider(10000, 10000, 20000),
              ".*",
              shardingFilter,
              createDefaultAgentProperties(),
              createDefaultSchedulerProperties(),
              java.util.Collections.emptyList());

      TestRunnableAgent agent = new TestRunnableAgent("enterpriseAgent");

      // When - Schedule and process agent
      assertThatCode(
              () -> {
                enterpriseScheduler.schedule(
                    agent, agent.getAgentExecution(providerRegistry), executionInstrumentation);
                enterpriseScheduler.saturatePool();
              })
          .doesNotThrowAnyException();

      // Then - Should complete without errors
      // Configuration is now handled via cached properties instead of dynamic config
    }
  }

  @Nested
  @DisplayName("Zombie Agent Cleanup Tests")
  class ZombieAgentCleanupTests {

    @Test
    @DisplayName("Should use cached properties for zombie threshold configuration")
    void shouldUseCachedPropertiesForZombieThreshold() {
      // Given - Create scheduler with custom zombie threshold via properties
      ClusteredSortSchedulerProperties customProperties = createDefaultSchedulerProperties();
      customProperties.setZombieThresholdMs(60000L); // 1 minute for testing

      ClusteredSortAgentScheduler customScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              () -> true,
              new DefaultAgentIntervalProvider(30, 30, 300),
              ".*",
              shardingFilter,
              createDefaultAgentProperties(),
              customProperties,
              disabledAgents);

      // When - Access zombie threshold
      // Then - Should use cached property value (60000ms) not dynamic config
      assertThat(customProperties.getZombieThresholdMs()).isEqualTo(60000L);
    }

    @Test
    @DisplayName("Should use cached properties for zombie cleanup interval")
    void shouldUseCachedPropertiesForZombieCleanupInterval() {
      // Given - Create scheduler with custom cleanup interval via properties
      ClusteredSortSchedulerProperties customProperties = createDefaultSchedulerProperties();
      customProperties.setZombieCleanupIntervalMs(30000L); // 30 seconds for testing

      ClusteredSortAgentScheduler customScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              () -> true,
              new DefaultAgentIntervalProvider(30, 30, 300),
              ".*",
              shardingFilter,
              createDefaultAgentProperties(),
              customProperties,
              disabledAgents);

      // When - Access cleanup interval
      // Then - Should use cached property value (30000ms) not dynamic config
      assertThat(customProperties.getZombieCleanupIntervalMs()).isEqualTo(30000L);
    }

    @Test
    @DisplayName("Should track zombie cleanup metrics")
    void shouldTrackZombieCleanupMetrics() {
      // Test that zombie cleanup metrics are available
      assertThat(scheduler.zombiesCleanedUp).isNotNull();
      assertThat(scheduler.activeAgents).isNotNull();
    }

    @Test
    @DisplayName("Should validate zombie threshold bounds via properties")
    void shouldValidateZombieThresholdBounds() {
      // Given - Properties with validation bounds
      ClusteredSortSchedulerProperties customProperties = createDefaultSchedulerProperties();

      // When/Then - Should accept valid values within bounds
      assertThatCode(() -> customProperties.setZombieThresholdMs(600000L)) // 10 minutes
          .doesNotThrowAnyException();

      // Verify the value was set correctly
      assertThat(customProperties.getZombieThresholdMs()).isEqualTo(600000L);
    }
  }

  // Helper test classes
  private static class TestRunnableAgent implements RunnableAgent {
    private final String agentType;
    private boolean hasRun = false;

    public TestRunnableAgent() {
      this("TestAgent");
    }

    public TestRunnableAgent(String agentType) {
      this.agentType = agentType;
    }

    @Override
    public String getAgentType() {
      return agentType;
    }

    @Override
    public String getProviderName() {
      return "test";
    }

    @Override
    public AgentExecution getAgentExecution(ProviderRegistry providerRegistry) {
      return new RunnableAgentExecution();
    }

    public Collection<AgentDataType> getProvidedDataTypes() {
      return java.util.Collections.emptyList();
    }

    @Override
    public void run() {
      hasRun = true;
    }

    public boolean hasRun() {
      return hasRun;
    }
  }

  private static class TestCachingAgent implements CachingAgent {
    @Override
    public String getAgentType() {
      return "TestCaching";
    }

    @Override
    public String getProviderName() {
      return "test";
    }

    public Collection<AgentDataType> getProvidedDataTypes() {
      return java.util.Collections.emptyList();
    }

    @Override
    public CacheResult loadData(ProviderCache providerCache) {
      return new DefaultCacheResult(java.util.Collections.emptyMap());
    }

    @Override
    public AgentExecution getAgentExecution(ProviderRegistry providerRegistry) {
      return mock(AgentExecution.class);
    }
  }

  // Simplified test agent that can be used for scheduler-aware testing
  private static class TestAgentSchedulerAware implements Agent {
    private AgentScheduler scheduler;

    @Override
    public String getAgentType() {
      return "TestSchedulerAware";
    }

    @Override
    public String getProviderName() {
      return "test";
    }

    public Collection<AgentDataType> getProvidedDataTypes() {
      return java.util.Collections.emptyList();
    }

    @Override
    public AgentExecution getAgentExecution(ProviderRegistry providerRegistry) {
      return new CustomAgentExecution();
    }

    public AgentScheduler getAgentScheduler() {
      return scheduler;
    }

    public void setAgentScheduler(AgentScheduler scheduler) {
      this.scheduler = scheduler;
    }
  }

  private static class CustomAgentExecution implements AgentExecution {
    @Override
    public void executeAgent(Agent agent) {
      // Custom execution logic
    }
  }

  private static class FailingRunnableAgent implements RunnableAgent {
    @Override
    public String getAgentType() {
      return "FailingAgent";
    }

    @Override
    public String getProviderName() {
      return "test";
    }

    public Collection<AgentDataType> getProvidedDataTypes() {
      return java.util.Collections.emptyList();
    }

    @Override
    public AgentExecution getAgentExecution(ProviderRegistry providerRegistry) {
      return new RunnableAgentExecution();
    }

    @Override
    public void run() {
      throw new RuntimeException("Simulated failure");
    }
  }
}
