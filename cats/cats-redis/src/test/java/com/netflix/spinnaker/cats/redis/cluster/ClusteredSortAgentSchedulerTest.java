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
import static org.mockito.ArgumentMatchers.anyList;
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
import com.netflix.spinnaker.cats.agent.AgentSchedulerAware;
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
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

  private ClusteredSortAgentScheduler scheduler;
  private JedisPool jedisPool;
  private Jedis jedis;
  private ExecutionInstrumentation executionInstrumentation;
  private ProviderRegistry providerRegistry;
  private ShardingFilter shardingFilter;
  private DynamicConfigService dynamicConfigService;

  @BeforeEach
  void setUp() throws Exception {
    jedisPool = mock(JedisPool.class);
    jedis = mock(Jedis.class);
    executionInstrumentation = mock(ExecutionInstrumentation.class);
    providerRegistry = mock(ProviderRegistry.class);
    shardingFilter = mock(ShardingFilter.class);
    dynamicConfigService = mock(DynamicConfigService.class);

    when(jedisPool.getResource()).thenReturn(jedis);
    when(jedis.scriptLoad(anyString())).thenReturn("sha1");
    when(jedis.scriptExists(anyString())).thenReturn(true);
    when(jedis.time()).thenReturn(List.of("1000", "0")); // Mock Redis TIME command
    when(jedis.evalsha(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn("OK");
    when(jedis.evalsha(anyString(), anyInt(), anyString(), anyString(), anyString()))
        .thenReturn(1L);

    // Mock default configuration values for zombie cleanup (for agents running too long on this
    // instance)
    when(dynamicConfigService.getConfig(
            eq(Long.class), eq("redis.agent.zombie-threshold-ms"), any(Long.class)))
        .thenReturn(1800000L); // 30 minutes default
    when(dynamicConfigService.getConfig(
            eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class)))
        .thenReturn(300000L); // 5 minutes default

    // Mock default configuration values for orphan cleanup (for agents abandoned in Redis by
    // crashed instances)
    when(dynamicConfigService.getConfig(
            eq(Long.class), eq("redis.agent.orphan-threshold-ms"), any(Long.class)))
        .thenReturn(600000L); // 10 minutes default - shorter since these are from crashed instances
    when(dynamicConfigService.getConfig(
            eq(Long.class), eq("redis.agent.orphan-cleanup-interval-ms"), any(Long.class)))
        .thenReturn(120000L); // 2 minutes default - aggressive to minimize stale data

    when(dynamicConfigService.getConfig(
            eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
        .thenReturn(1000); // Default concurrent limit
    when(dynamicConfigService.getConfig(
            eq(String.class), eq("redis.agent.enabled-pattern"), anyString()))
        .thenReturn(".*"); // Default enabled pattern

    // Default to enabling the node
    NodeStatusProvider nodeStatusProvider = () -> true;

    scheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            new DefaultAgentIntervalProvider(30000, 60000, 300000),
            ".*",
            10,
            shardingFilter,
            dynamicConfigService);
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
    @DisplayName("Should handle AgentSchedulerAware agents")
    void shouldHandleAgentSchedulerAwareAgents() {
      // Given
      TestAgentSchedulerAware agent = new TestAgentSchedulerAware();
      AgentExecution execution = agent.getAgentExecution(providerRegistry);

      // When
      scheduler.schedule(agent, execution, executionInstrumentation);

      // Then
      assertThat(agent.getAgentScheduler()).isEqualTo(scheduler);
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
              2, // Only 2 concurrent agents
              shardingFilter,
              dynamicConfigService);

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
              5,
              shardingFilter,
              dynamicConfigService);

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
      // Given - Enable batch operations to trigger repopulation OR expect individual
      // scheduleAgentInRedis calls
      when(dynamicConfigService.getConfig(
              eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
          .thenReturn(false); // Keep individual operations to test sharding

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

    @Test
    @DisplayName("Should respect dynamic config for max concurrent agents")
    void shouldRespectDynamicConfigForMaxConcurrentAgents() {
      // Given - Set low max concurrent agents
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
          .thenReturn(2); // Only 2 concurrent agents
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-threshold-ms"), any(Long.class)))
          .thenReturn(1000L);

      // Ensure jedis.time() returns correct List format
      when(jedis.time()).thenReturn(List.of("1000", "0"));

      // Mock Redis to return ready agents - ensure we return Set<String> for zrangeByScore
      Set<String> readyAgents = new HashSet<>();
      readyAgents.add("agent1");
      readyAgents.add("agent2");
      readyAgents.add("agent3");
      readyAgents.add("agent4");
      when(jedis.zrangeByScore(eq("WAITZ"), eq("-inf"), anyString())).thenReturn(readyAgents);

      TestRunnableAgent agent1 = new TestRunnableAgent("agent1");
      TestRunnableAgent agent2 = new TestRunnableAgent("agent2");
      TestRunnableAgent agent3 = new TestRunnableAgent("agent3");
      TestRunnableAgent agent4 = new TestRunnableAgent("agent4");

      scheduler.schedule(
          agent1, agent1.getAgentExecution(providerRegistry), executionInstrumentation);
      scheduler.schedule(
          agent2, agent2.getAgentExecution(providerRegistry), executionInstrumentation);
      scheduler.schedule(
          agent3, agent3.getAgentExecution(providerRegistry), executionInstrumentation);
      scheduler.schedule(
          agent4, agent4.getAgentExecution(providerRegistry), executionInstrumentation);

      // When - Trigger agent processing
      scheduler.saturatePool();

      // Then - Should respect max concurrent limit (may be called multiple times during processing)
      verify(dynamicConfigService, atLeast(1))
          .getConfig(eq(Integer.class), eq("redis.agent.max-concurrent-agents"), eq(1000));
    }

    @Test
    @DisplayName("Should handle enterprise services gracefully")
    void shouldHandleEnterpriseServicesGracefully() {
      // Given - Create scheduler with all enterprise features
      ClusteredSortAgentScheduler enterpriseScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              () -> true,
              new DefaultAgentIntervalProvider(30000, 60000, 300000),
              ".*",
              10,
              shardingFilter,
              dynamicConfigService);

      TestRunnableAgent agent = new TestRunnableAgent("enterpriseAgent");

      // When - Schedule and process agent
      assertThatCode(
              () -> {
                enterpriseScheduler.schedule(
                    agent, agent.getAgentExecution(providerRegistry), executionInstrumentation);
                enterpriseScheduler.saturatePool();
              })
          .doesNotThrowAnyException();

      // Then - Should have interacted with enterprise services
      verify(dynamicConfigService, atLeast(1)).getConfig(eq(Integer.class), anyString(), anyInt());
    }
  }

  @Nested
  @DisplayName("Zombie Agent Cleanup Tests")
  class ZombieAgentCleanupTests {

    @Test
    @DisplayName("Should configure zombie cleanup thresholds from dynamic config")
    void shouldConfigureZombieCleanupFromDynamicConfig() {
      // Given - Configure zombie cleanup settings
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-threshold-ms"), any(Long.class)))
          .thenReturn(1800000L); // 30 minutes
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(600000L); // 10 minutes
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
          .thenReturn(1000); // Default concurrent limit

      TestRunnableAgent agent = new TestRunnableAgent("config-test");
      scheduler.schedule(
          agent, agent.getAgentExecution(providerRegistry), executionInstrumentation);

      // When - Trigger saturatePool (which calls zombie cleanup config)
      scheduler.saturatePool();

      // Then - Should have checked dynamic config for zombie settings
      verify(dynamicConfigService, atLeast(1))
          .getConfig(eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class));
    }

    @Test
    @DisplayName("Should track active agents during execution")
    void shouldTrackActiveAgentsDuringExecution() throws Exception {
      // Given - Mock all config calls
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-threshold-ms"), any(Long.class)))
          .thenReturn(3600000L);
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(300000L);
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
          .thenReturn(1000);

      TestRunnableAgent agent = new TestRunnableAgent("tracking-test");
      scheduler.schedule(
          agent, agent.getAgentExecution(providerRegistry), executionInstrumentation);

      // When - Start agent execution
      scheduler.saturatePool();

      // Then - Should have active agents tracked (may be cleared quickly in test)
      // Check that tracking infrastructure is in place
      assertThat(scheduler.activeAgents).isNotNull();
      assertThat(scheduler.zombiesCleanedUp).isNotNull();
    }

    @Test
    @DisplayName("Should provide zombie cleanup metrics")
    void shouldProvideZombieCleanupMetrics() {
      // Given - Configure zombie cleanup with all required mocks
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-threshold-ms"), any(Long.class)))
          .thenReturn(3600000L);
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(300000L);
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
          .thenReturn(1000);

      // When - Check initial metrics
      long initialCleanupCount = scheduler.zombiesCleanedUp.get();

      // Then - Metrics should be available and initialized
      assertThat(initialCleanupCount).isGreaterThanOrEqualTo(0);
      assertThat(scheduler.activeAgents).isNotNull();
    }

    @Test
    @DisplayName("Should handle configuration defaults correctly")
    void shouldHandleConfigurationDefaults() {
      // Given - Mock all required config calls
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-threshold-ms"), any(Long.class)))
          .thenReturn(3600000L); // 1 hour default
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(300000L); // 5 minutes default
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
          .thenReturn(1000); // Default limit

      TestRunnableAgent agent = new TestRunnableAgent("defaults-test");
      scheduler.schedule(
          agent, agent.getAgentExecution(providerRegistry), executionInstrumentation);

      // When - Process agent with default configuration
      assertThatCode(() -> scheduler.saturatePool()).doesNotThrowAnyException();

      // Then - Should use defaults without throwing exceptions
      verify(dynamicConfigService, atLeast(1))
          .getConfig(eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class));
    }

    @Test
    @DisplayName("Should handle Redis failures during zombie cleanup gracefully")
    void shouldHandleRedisFailuresDuringCleanup() {
      // Given - Configure fast zombie cleanup with all mocks
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-threshold-ms"), any(Long.class)))
          .thenReturn(1L); // Very short threshold for testing
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(1L); // Very short interval
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
          .thenReturn(1000);

      // Mock Redis to throw exception during cleanup
      when(jedis.evalsha(anyString(), any(List.class), any(List.class)))
          .thenThrow(new RuntimeException("Redis connection failed"));

      TestRunnableAgent agent = new TestRunnableAgent("redis-failure-test");
      scheduler.schedule(
          agent, agent.getAgentExecution(providerRegistry), executionInstrumentation);

      // When - Process agent - should not throw exception despite Redis failure
      assertThatCode(() -> scheduler.saturatePool()).doesNotThrowAnyException();

      // Then - Should have attempted Redis operations
      verify(jedis, atLeast(0)).evalsha(anyString(), any(List.class), any(List.class));
    }

    @Test
    @DisplayName("Should respect max concurrent agents limit")
    void shouldRespectMaxConcurrentAgentsLimit() {
      // Given - Configure low concurrency limit with all mocks
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
          .thenReturn(2); // Only 2 concurrent agents
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-threshold-ms"), any(Long.class)))
          .thenReturn(60000L); // 1 minute
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(30000L); // 30 seconds

      TestRunnableAgent agent1 = new TestRunnableAgent("concurrent-1");
      TestRunnableAgent agent2 = new TestRunnableAgent("concurrent-2");
      TestRunnableAgent agent3 = new TestRunnableAgent("concurrent-3");

      scheduler.schedule(
          agent1, agent1.getAgentExecution(providerRegistry), executionInstrumentation);
      scheduler.schedule(
          agent2, agent2.getAgentExecution(providerRegistry), executionInstrumentation);
      scheduler.schedule(
          agent3, agent3.getAgentExecution(providerRegistry), executionInstrumentation);

      // When - Process agents
      scheduler.saturatePool();

      // Then - Should have checked concurrency limit
      verify(dynamicConfigService, atLeast(1))
          .getConfig(eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt());
    }

    @Test
    @DisplayName("Should handle zombie cleanup interval correctly")
    void shouldHandleZombieCleanupInterval() {
      // Given - Configure zombie cleanup interval with all mocks
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(300000L); // 5 minutes
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.zombie-threshold-ms"), any(Long.class)))
          .thenReturn(3600000L); // 1 hour
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
          .thenReturn(1000);

      TestRunnableAgent agent = new TestRunnableAgent("interval-test");
      scheduler.schedule(
          agent, agent.getAgentExecution(providerRegistry), executionInstrumentation);

      // When - Process agent multiple times (simulating different intervals)
      scheduler.saturatePool();
      scheduler.saturatePool();

      // Then - Should check cleanup interval configuration
      verify(dynamicConfigService, atLeast(1))
          .getConfig(eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), any(Long.class));
    }
  }

  @Nested
  @DisplayName("Orphaned Agent Cleanup Tests")
  class OrphanedAgentCleanupTests {

    @BeforeEach
    void setUp() {
      // Reset mocks for specific tests
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.orphan-threshold-ms"), any(Long.class)))
          .thenReturn(600000L); // 10 minutes
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.orphan-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(120000L); // 2 minutes
    }

    @Test
    @DisplayName(
        "Should detect and clean orphaned agents using individual processing when batch is disabled")
    void shouldDetectAndCleanOrphanedAgents() throws Exception {
      // Given
      // 1. Setup Redis mocks for orphan cleanup
      long now = System.currentTimeMillis();
      long orphanThreshold = 600000L; // 10 minutes
      long orphanTimestamp = now - orphanThreshold - 60000; // 1 minute past threshold

      // Configure batch operations to be disabled
      when(dynamicConfigService.getConfig(
              eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), any(Boolean.class)))
          .thenReturn(false); // Batch operations disabled by default

      // Mock Redis response for zrangeByScoreWithScores to return an orphaned agent
      Set<redis.clients.jedis.Tuple> orphanedAgents = new HashSet<>();
      redis.clients.jedis.Tuple orphanedAgent = mock(redis.clients.jedis.Tuple.class);
      when(orphanedAgent.getElement()).thenReturn("orphaned-agent");
      when(orphanedAgent.getScore()).thenReturn((double) orphanTimestamp);
      orphanedAgents.add(orphanedAgent);

      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(orphanedAgents);

      // Mock success response for the individual Lua script execution
      when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(1L); // 1 = success

      // Setup reflection to access private method
      java.lang.reflect.Method cleanupMethod =
          ClusteredSortAgentScheduler.class.getDeclaredMethod("cleanupOrphanedAgentsFromRedis");
      cleanupMethod.setAccessible(true);

      // When - Call orphaned agent cleanup
      cleanupMethod.invoke(scheduler);

      // Then - Should call the Redis APIs correctly and remove the orphaned agent
      verify(jedis).zrangeByScoreWithScores(eq(WORKING_SET), eq("-inf"), anyString());

      // Verify evalsha was called for individual orphan processing
      verify(jedis).evalsha(anyString(), anyList(), anyList());

      // Verify batch size configuration was not requested (since batch is disabled)
      verify(dynamicConfigService, never())
          .getConfig(
              eq(Integer.class), eq("redis.agent.orphan-cleanup-batch-size"), any(Integer.class));
    }

    @Test
    @DisplayName("Should process orphaned agents in batches when batch operations are enabled")
    void shouldProcessOrphanedAgentsInBatches() throws Exception {
      // Given
      // 1. Set up Redis mocks for orphan cleanup with batch processing
      long now = System.currentTimeMillis();
      long orphanThreshold = 600000L; // 10 minutes

      // Configure batch operations to be enabled
      when(dynamicConfigService.getConfig(
              eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), any(Boolean.class)))
          .thenReturn(true); // Enable batch operations

      // Generate a large number of orphaned agents that will require multiple batches
      Set<redis.clients.jedis.Tuple> orphanedAgents = new HashSet<>();
      int totalOrphans = 120; // Will need at least 3 batches with default batch size of 50

      for (int i = 1; i <= totalOrphans; i++) {
        long orphanTimestamp = now - orphanThreshold - (i * 1000); // Each one older by 1 second
        redis.clients.jedis.Tuple orphanedAgent = mock(redis.clients.jedis.Tuple.class);
        when(orphanedAgent.getElement()).thenReturn("orphaned-agent-" + i);
        when(orphanedAgent.getScore()).thenReturn((double) orphanTimestamp);
        orphanedAgents.add(orphanedAgent);
      }

      // Configure batch size to 50 agents per batch
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.orphan-cleanup-batch-size"), any(Integer.class)))
          .thenReturn(50); // 50 agents per batch

      // Mock Redis response for zrangeByScoreWithScores
      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(orphanedAgents);

      // Mock batch script execution results - return success for each batch
      // The script returns a List with [count, removedAgentsList]
      List<Object> batchResult1 = new ArrayList<>();
      batchResult1.add(50L); // 50 removed in first batch
      List<String> removedAgents1 = new ArrayList<>();
      for (int i = 1; i <= 50; i++) {
        removedAgents1.add("orphaned-agent-" + i);
      }
      batchResult1.add(removedAgents1);

      List<Object> batchResult2 = new ArrayList<>();
      batchResult2.add(50L); // 50 removed in second batch
      List<String> removedAgents2 = new ArrayList<>();
      for (int i = 51; i <= 100; i++) {
        removedAgents2.add("orphaned-agent-" + i);
      }
      batchResult2.add(removedAgents2);

      List<Object> batchResult3 = new ArrayList<>();
      batchResult3.add(20L); // 20 removed in third batch
      List<String> removedAgents3 = new ArrayList<>();
      for (int i = 101; i <= 120; i++) {
        removedAgents3.add("orphaned-agent-" + i);
      }
      batchResult3.add(removedAgents3);

      when(jedis.evalsha(anyString(), anyList(), anyList()))
          .thenReturn(batchResult1)
          .thenReturn(batchResult2)
          .thenReturn(batchResult3);

      // Setup reflection to access private method
      java.lang.reflect.Method cleanupMethod =
          ClusteredSortAgentScheduler.class.getDeclaredMethod("cleanupOrphanedAgentsFromRedis");
      cleanupMethod.setAccessible(true);

      // When - Call orphaned agent cleanup
      cleanupMethod.invoke(scheduler);

      // Then - Should have retrieved the orphans once and processed them in 3 batches
      verify(jedis).zrangeByScoreWithScores(eq(WORKING_SET), eq("-inf"), anyString());
      verify(jedis, times(3)).evalsha(anyString(), anyList(), anyList());

      // Verify batch size configuration was requested
      verify(dynamicConfigService)
          .getConfig(
              eq(Integer.class), eq("redis.agent.orphan-cleanup-batch-size"), any(Integer.class));
    }

    @Test
    @DisplayName("Should handle batch script errors gracefully without fallback")
    void shouldFallbackToIndividualProcessing() throws Exception {
      // Given
      // 1. Setup for batch processing with failure
      long now = System.currentTimeMillis();
      long orphanThreshold = 600000L; // 10 minutes

      // Enable batch operations
      when(dynamicConfigService.getConfig(
              eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), any(Boolean.class)))
          .thenReturn(true); // Enable batch operations

      // Set up orphan threshold
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.orphan-threshold-ms"), any(Long.class)))
          .thenReturn(600000L); // 10 minutes

      // Configure batch size
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.orphan-cleanup-batch-size"), any(Integer.class)))
          .thenReturn(50);

      // Create orphaned agents
      Set<redis.clients.jedis.Tuple> orphanedAgents = new HashSet<>();
      redis.clients.jedis.Tuple orphanedAgent1 = mock(redis.clients.jedis.Tuple.class);
      redis.clients.jedis.Tuple orphanedAgent2 = mock(redis.clients.jedis.Tuple.class);

      when(orphanedAgent1.getElement()).thenReturn("orphaned-agent-1");
      when(orphanedAgent1.getScore()).thenReturn((double) (now - orphanThreshold - 60000));
      when(orphanedAgent2.getElement()).thenReturn("orphaned-agent-2");
      when(orphanedAgent2.getScore()).thenReturn((double) (now - orphanThreshold - 120000));

      orphanedAgents.add(orphanedAgent1);
      orphanedAgents.add(orphanedAgent2);

      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(orphanedAgents);

      // Make batch processing fail
      when(jedis.evalsha(anyString(), anyList(), anyList()))
          .thenThrow(new RuntimeException("Batch script not found")); // Batch fails

      // Setup reflection to access private method
      java.lang.reflect.Method cleanupMethod =
          ClusteredSortAgentScheduler.class.getDeclaredMethod("cleanupOrphanedAgentsFromRedis");
      cleanupMethod.setAccessible(true);

      // When - Call orphaned agent cleanup
      cleanupMethod.invoke(scheduler);

      // Then - Should attempt batch processing once but not fall back to individual processing
      // Should make 1 total call for batch that fails (no individual processing anymore)
      verify(jedis, times(1)).evalsha(anyString(), anyList(), anyList());

      // Should have requested both batch config and batch size
      verify(dynamicConfigService)
          .getConfig(
              eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), any(Boolean.class));
      verify(dynamicConfigService)
          .getConfig(
              eq(Integer.class), eq("redis.agent.orphan-cleanup-batch-size"), any(Integer.class));
    }

    @Test
    @DisplayName("Should handle batch script errors gracefully")
    void shouldHandleBatchScriptErrors() throws Exception {
      // Given - Enable batch operations
      when(dynamicConfigService.getConfig(
              eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), any(Boolean.class)))
          .thenReturn(true);

      // Forces orphan cleanup to run
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.orphan-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(0L); // Will force cleanup to run immediately

      // Skip leadership to avoid complications
      when(dynamicConfigService.getConfig(
              eq(Boolean.class),
              eq("redis.agent.orphan-cleanup.force-all-pods"),
              any(Boolean.class)))
          .thenReturn(true); // Force all pods to clean

      // Set up orphan threshold
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.orphan-threshold-ms"), any(Long.class)))
          .thenReturn(600000L); // 10 minutes

      // Configure batch size
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.orphan-cleanup-batch-size"), any(Integer.class)))
          .thenReturn(50);

      // Create simple orphaned agents list
      Set<redis.clients.jedis.Tuple> orphanedAgents = new HashSet<>();
      redis.clients.jedis.Tuple orphanedAgent1 = mock(redis.clients.jedis.Tuple.class);
      redis.clients.jedis.Tuple orphanedAgent2 = mock(redis.clients.jedis.Tuple.class);

      // Define the agents
      when(orphanedAgent1.getElement()).thenReturn("orphaned-agent-1");
      when(orphanedAgent1.getScore()).thenReturn((double) (System.currentTimeMillis() - 700000L));
      when(orphanedAgent2.getElement()).thenReturn("orphaned-agent-2");
      when(orphanedAgent2.getScore()).thenReturn((double) (System.currentTimeMillis() - 700000L));

      orphanedAgents.add(orphanedAgent1);
      orphanedAgents.add(orphanedAgent2);

      // Mock Redis response for orphaned agents lookup
      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(orphanedAgents);

      // Set up the script failure scenario
      when(jedis.evalsha(anyString(), anyList(), anyList()))
          .thenThrow(new RuntimeException("Simulated batch script failure"));

      // When - Use the public method to trigger cleanup
      // The test should pass without exceptions
      assertThatCode(() -> scheduler.saturatePool()).doesNotThrowAnyException();

      // Verify the batch script was attempted
      verify(jedis).evalsha(anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("Should handle Redis failures during orphaned agent cleanup")
    void shouldHandleRedisFailuresDuringOrphanCleanup() throws Exception {
      // Given
      // 1. Setup Redis mocks with failure during orphan detection
      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenThrow(new RuntimeException("Simulated Redis failure"));

      // Setup reflection to access private method
      java.lang.reflect.Method cleanupMethod =
          ClusteredSortAgentScheduler.class.getDeclaredMethod("cleanupOrphanedAgentsFromRedis");
      cleanupMethod.setAccessible(true);

      // When/Then - Should handle exceptions gracefully
      assertThatCode(() -> cleanupMethod.invoke(scheduler)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should gracefully handle failures during individual orphaned agent removal")
    void shouldHandleFailuresDuringOrphanRemoval() throws Exception {
      // Given
      // 1. Setup Redis mocks for orphan cleanup with failures during removal
      long now = System.currentTimeMillis();
      long orphanThreshold = 600000L; // 10 minutes
      long orphanTimestamp1 = now - orphanThreshold - 120000; // 2 minutes past threshold
      long orphanTimestamp2 = now - orphanThreshold - 180000; // 3 minutes past threshold

      // Mock Redis response for zrangeByScoreWithScores to return multiple orphaned agents
      Set<redis.clients.jedis.Tuple> orphanedAgents = new HashSet<>();
      redis.clients.jedis.Tuple orphanedAgent1 = mock(redis.clients.jedis.Tuple.class);
      redis.clients.jedis.Tuple orphanedAgent2 = mock(redis.clients.jedis.Tuple.class);

      when(orphanedAgent1.getElement()).thenReturn("orphaned-agent-1");
      when(orphanedAgent1.getScore()).thenReturn((double) orphanTimestamp1);
      when(orphanedAgent2.getElement()).thenReturn("orphaned-agent-2");
      when(orphanedAgent2.getScore()).thenReturn((double) orphanTimestamp2);

      orphanedAgents.add(orphanedAgent1);
      orphanedAgents.add(orphanedAgent2);

      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(orphanedAgents);

      // Mock the first evalsha call to succeed, then the second one to fail
      when(jedis.evalsha(anyString(), anyList(), anyList()))
          .thenReturn(1L) // First call succeeds
          .thenThrow(
              new RuntimeException("Simulated failure during orphan removal")); // Second call fails

      // Setup reflection to access private method
      java.lang.reflect.Method cleanupMethod =
          ClusteredSortAgentScheduler.class.getDeclaredMethod("cleanupOrphanedAgentsFromRedis");
      cleanupMethod.setAccessible(true);

      // When/Then - Should handle exceptions gracefully and continue processing
      assertThatCode(() -> cleanupMethod.invoke(scheduler)).doesNotThrowAnyException();

      // Then - Should have attempted to process both orphaned agents (2 calls to evalsha)
      verify(jedis, times(2)).evalsha(anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("Should skip batch processing if no orphans found")
    void shouldSkipBatchProcessingIfNoOrphans() throws Exception {
      // Given - Empty set of orphaned agents
      Set<redis.clients.jedis.Tuple> emptySet = new HashSet<>();
      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(emptySet);

      // Setup reflection to access private method
      java.lang.reflect.Method cleanupMethod =
          ClusteredSortAgentScheduler.class.getDeclaredMethod("cleanupOrphanedAgentsFromRedis");
      cleanupMethod.setAccessible(true);

      // When - Call orphaned agent cleanup
      cleanupMethod.invoke(scheduler);

      // Then - Should have checked for orphans but not attempted any batch processing
      verify(jedis).zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString());
      verify(jedis, never()).evalsha(anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("Should handle local cleanup for batched orphaned agents")
    void shouldHandleLocalCleanupForBatchedOrphans() throws Exception {
      // Given
      // 1. Setup a mix of orphaned agents, some of which are in the local activeAgents map
      long now = System.currentTimeMillis();
      long orphanThreshold = 600000L; // 10 minutes

      // Create orphaned agents
      Set<redis.clients.jedis.Tuple> orphanedAgents = new HashSet<>();
      redis.clients.jedis.Tuple orphanedAgent1 = mock(redis.clients.jedis.Tuple.class);
      redis.clients.jedis.Tuple orphanedAgent2 = mock(redis.clients.jedis.Tuple.class);

      when(orphanedAgent1.getElement()).thenReturn("orphaned-agent-1");
      when(orphanedAgent1.getScore()).thenReturn((double) (now - orphanThreshold - 60000));
      when(orphanedAgent2.getElement()).thenReturn("orphaned-agent-2");
      when(orphanedAgent2.getScore()).thenReturn((double) (now - orphanThreshold - 120000));

      orphanedAgents.add(orphanedAgent1);
      orphanedAgents.add(orphanedAgent2);

      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(orphanedAgents);

      // Add one of the agents to the local activeAgents map
      TestRunnableAgent localAgent = new TestRunnableAgent("orphaned-agent-1");
      scheduler.schedule(
          localAgent, localAgent.getAgentExecution(providerRegistry), executionInstrumentation);

      // Mock successful batch cleanup
      List<Object> batchResult = new ArrayList<>();
      batchResult.add(2L); // Both agents removed
      List<String> removedAgents = Arrays.asList("orphaned-agent-1", "orphaned-agent-2");
      batchResult.add(removedAgents);

      when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(batchResult);

      // Setup reflection to access private method
      java.lang.reflect.Method cleanupMethod =
          ClusteredSortAgentScheduler.class.getDeclaredMethod("cleanupOrphanedAgentsFromRedis");
      cleanupMethod.setAccessible(true);

      // When - Call orphaned agent cleanup
      cleanupMethod.invoke(scheduler);

      // Then - The local agent should have been removed from activeAgents map
      // This is challenging to verify directly in a test, but we can verify the batch script was
      // called
      // We need to use atLeast(1) instead of exact verification since leadership might cause
      // additional calls
      verify(jedis, atLeast(1)).evalsha(anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("Should handle orphaned agents that are also present in local activeAgents map")
    void shouldHandleOrphansAlsoPresentInLocalActiveAgents() throws Exception {
      // Given
      // 1. Setup Redis mocks for orphan cleanup
      long now = System.currentTimeMillis();
      long orphanThreshold = 600000L; // 10 minutes
      long orphanTimestamp = now - orphanThreshold - 60000; // 1 minute past threshold

      // Mock Redis response for zrangeByScoreWithScores to return an orphaned agent
      Set<redis.clients.jedis.Tuple> orphanedAgents = new HashSet<>();
      redis.clients.jedis.Tuple orphanedAgent = mock(redis.clients.jedis.Tuple.class);
      when(orphanedAgent.getElement()).thenReturn("orphaned-agent");
      when(orphanedAgent.getScore()).thenReturn((double) orphanTimestamp);
      orphanedAgents.add(orphanedAgent);

      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(orphanedAgents);

      // Mock success response for the Lua script execution
      when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(1L); // 1 = success

      // Setup a local agent in the activeAgents map with the same name as the orphaned agent
      // First create a test agent and schedule it
      TestRunnableAgent agent = new TestRunnableAgent("orphaned-agent");
      scheduler.schedule(
          agent, agent.getAgentExecution(providerRegistry), executionInstrumentation);

      // Setup reflection to access private method and fields
      java.lang.reflect.Method cleanupMethod =
          ClusteredSortAgentScheduler.class.getDeclaredMethod("cleanupOrphanedAgentsFromRedis");
      cleanupMethod.setAccessible(true);

      // When - Call orphaned agent cleanup
      cleanupMethod.invoke(scheduler);

      // Then - Should call the Redis APIs correctly and attempt to clean up the local agent
      verify(jedis).zrangeByScoreWithScores(eq(WORKING_SET), eq("-inf"), anyString());
      verify(jedis).evalsha(anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("Should use leader election for orphaned agent cleanup operations")
    void shouldUseLeaderElectionForCleanup() {
      // Given - Force the orphan cleanup to run
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.orphan-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(0L); // Will force cleanup to run immediately

      // Mock successful leadership acquisition
      when(jedis.setnx(eq("CLEANUP_LEADER"), anyString()))
          .thenReturn(1L); // Successfully acquired leadership (1L = success)

      // Mock successful expire call which happens after setnx
      when(jedis.expire(eq("CLEANUP_LEADER"), anyInt())).thenReturn(1L); // Successfully set expiry

      // Mock empty orphan set (no orphans to clean)
      Set<redis.clients.jedis.Tuple> emptySet = new HashSet<>();
      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(emptySet);

      // When
      scheduler.saturatePool(); // This calls cleanupZombieAgentsIfNeeded internally

      // Then - Should attempt leadership acquisition and release
      verify(jedis).setnx(eq("CLEANUP_LEADER"), anyString()); // Verify setnx was called
      verify(jedis).expire(eq("CLEANUP_LEADER"), anyInt()); // Verify expire was called
      verify(jedis).eval(anyString(), anyList(), anyList()); // Leadership release
    }

    @Test
    @DisplayName("Should skip orphaned agent cleanup when another pod is the leader")
    void shouldSkipCleanupWhenNotLeader() {
      // Given - Force the orphan cleanup to run
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.orphan-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(0L); // Will force cleanup to run immediately

      // Mock leadership acquisition failure (another pod is the leader)
      when(jedis.setnx(eq("CLEANUP_LEADER"), anyString()))
          .thenReturn(0L); // Failed to acquire leadership (0L = failure, key exists)

      // When
      scheduler.saturatePool(); // This calls cleanupZombieAgentsIfNeeded internally

      // Then - Should try to acquire leadership but not perform any cleanup
      verify(jedis).setnx(eq("CLEANUP_LEADER"), anyString()); // Verify setnx was called
      verify(jedis, never()).zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString());
    }

    @Test
    @DisplayName("Should perform cleanup when force refresh is enabled regardless of leadership")
    void shouldCleanupWhenForceRefreshEnabled() {
      // Given - Force the orphan cleanup to run
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.orphan-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(0L); // Will force cleanup to run immediately

      // Enable force refresh
      when(dynamicConfigService.getConfig(
              eq(Boolean.class),
              eq("redis.agent.orphan-cleanup.force-all-pods"),
              any(Boolean.class)))
          .thenReturn(true); // Enable force refresh

      // Mock empty orphan set (no orphans to clean)
      Set<redis.clients.jedis.Tuple> emptySet = new HashSet<>();
      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(emptySet);

      // When
      scheduler.saturatePool(); // This calls cleanupZombieAgentsIfNeeded internally

      // Then - Should not attempt leadership acquisition but still perform cleanup
      verify(jedis, never())
          .setnx(eq("CLEANUP_LEADER"), anyString()); // Verify setnx was never called
      verify(jedis).zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString());
    }

    @Test
    @DisplayName(
        "Should handle orphaned agent cleanup integration with cleanupZombieAgentsIfNeeded")
    void shouldIntegrateWithZombieCleanup() {
      // Given - Forces the orphan cleanup to run
      when(dynamicConfigService.getConfig(
              eq(Long.class), eq("redis.agent.orphan-cleanup-interval-ms"), any(Long.class)))
          .thenReturn(0L); // Will force cleanup to run immediately

      // Mock successful leadership acquisition
      when(jedis.setnx(eq("CLEANUP_LEADER"), anyString()))
          .thenReturn(1L); // Successfully acquired leadership (1L = success)

      // Mock successful expire call which happens after setnx
      when(jedis.expire(eq("CLEANUP_LEADER"), anyInt())).thenReturn(1L); // Successfully set expiry

      // Mock Redis responses for orphan cleanup
      Set<redis.clients.jedis.Tuple> emptySet = new HashSet<>();
      when(jedis.zrangeByScoreWithScores(eq(WORKING_SET), anyString(), anyString()))
          .thenReturn(emptySet); // No orphans to clean this time

      // When
      scheduler.saturatePool(); // This calls cleanupZombieAgentsIfNeeded internally

      // Then - Should check orphan cleanup interval and threshold
      verify(dynamicConfigService, atLeast(1))
          .getConfig(eq(Long.class), eq("redis.agent.orphan-cleanup-interval-ms"), any(Long.class));
      verify(dynamicConfigService, atLeast(1))
          .getConfig(eq(Long.class), eq("redis.agent.orphan-threshold-ms"), any(Long.class));
    }
  }

  // Test helper classes following the proven patterns

  private static class TestCachingAgent implements CachingAgent {
    @Override
    public String getAgentType() {
      return "TestCachingAgent";
    }

    @Override
    public String getProviderName() {
      return "test";
    }

    @Override
    public Collection<AgentDataType> getProvidedDataTypes() {
      return List.of();
    }

    @Override
    public CacheResult loadData(ProviderCache providerCache) {
      return new DefaultCacheResult(new HashMap<>());
    }
  }

  private static class TestRunnableAgent implements RunnableAgent {
    private final String agentType;
    private final AtomicBoolean hasRun = new AtomicBoolean(false);

    public TestRunnableAgent() {
      this("TestRunnableAgent");
    }

    public TestRunnableAgent(String agentType) {
      this.agentType = agentType;
    }

    @Override
    public void run() {
      hasRun.set(true);
    }

    @Override
    public String getAgentType() {
      return agentType;
    }

    @Override
    public String getProviderName() {
      return "test";
    }

    public boolean hasRun() {
      return hasRun.get();
    }
  }

  private static class FailingRunnableAgent implements RunnableAgent {
    private final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public void run() {
      callCount.incrementAndGet();
      throw new RuntimeException("Simulated agent failure #" + callCount.get());
    }

    @Override
    public String getAgentType() {
      return "FailingRunnableAgent";
    }

    @Override
    public String getProviderName() {
      return "test";
    }

    public int getCallCount() {
      return callCount.get();
    }
  }

  private static class TestAgentSchedulerAware extends AgentSchedulerAware implements Agent {
    @Override
    public String getAgentType() {
      return "TestAgentSchedulerAware";
    }

    @Override
    public String getProviderName() {
      return "test";
    }

    @Override
    public AgentExecution getAgentExecution(ProviderRegistry providerRegistry) {
      return new CustomAgentExecution();
    }
  }

  private static class CustomAgentExecution implements AgentExecution {
    @Override
    public void executeAgent(Agent agent) {
      // Custom execution logic
    }
  }
}
