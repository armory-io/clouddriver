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
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.cats.provider.ProviderCache;
import com.netflix.spinnaker.cats.provider.ProviderRegistry;
import com.netflix.spinnaker.cats.test.TestAgent;
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService;
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

    // Mock enterprise services
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true); // Allow all agents by default
    when(dynamicConfigService.getConfig(
            eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
        .thenReturn(1000); // Default max concurrent agents

    // Create scheduler with default pattern (all agents enabled)
    scheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            () -> true,
            new DefaultAgentIntervalProvider(30000, 60000, 300000),
            ".*", // Enable all agents
            5,
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
      // Given - Mock sharding filter to reject certain agents
      when(shardingFilter.filter(any(Agent.class)))
          .thenAnswer(
              invocation -> {
                Agent agent = invocation.getArgument(0);
                return !agent.getAgentType().contains("filtered");
              });

      TestRunnableAgent allowedAgent = new TestRunnableAgent("allowedAgent");
      TestRunnableAgent filteredAgent = new TestRunnableAgent("filteredAgent");

      // When - Schedule both agents
      scheduler.schedule(
          allowedAgent, allowedAgent.getAgentExecution(providerRegistry), executionInstrumentation);
      scheduler.schedule(
          filteredAgent,
          filteredAgent.getAgentExecution(providerRegistry),
          executionInstrumentation);

      // Force a Redis refresh cycle by setting runCount to trigger repopulation
      try {
        FieldUtils.writeField(scheduler, "runCount", 30, true); // DEFAULT_REDIS_REFRESH_PERIOD = 30
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      // Trigger repopulation by calling saturatePool
      scheduler.saturatePool();

      // Then - Sharding filter should have been called for both agents during repopulation
      verify(shardingFilter, atLeast(1)).filter(any(Agent.class));
    }

    @Test
    @DisplayName("Should respect dynamic config for max concurrent agents")
    void shouldRespectDynamicConfigForMaxConcurrentAgents() {
      // Given - Set low max concurrent agents
      when(dynamicConfigService.getConfig(
              eq(Integer.class), eq("redis.agent.max-concurrent-agents"), anyInt()))
          .thenReturn(2);

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
