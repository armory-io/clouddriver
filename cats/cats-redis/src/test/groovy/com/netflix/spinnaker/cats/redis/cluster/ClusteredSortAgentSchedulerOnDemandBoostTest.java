/*
 * Copyright 2024 Armory, Inc.
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.withSettings;

import com.netflix.spinnaker.cats.agent.AccountAware;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent;
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

class ClusteredSortAgentSchedulerOnDemandBoostTest {

  // Redis set constants from ClusteredSortAgentScheduler
  private static final String WAITING_SET = "WAITZ";
  private static final String WORKING_SET = "WORKZ";

  private JedisPool jedisPool;
  private Jedis jedis;
  private NodeStatusProvider nodeStatusProvider;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private DynamicConfigService dynamicConfigService;
  private OnDemandAgent onDemandAgent;
  private OnDemandAgent.OnDemandResult onDemandResult;

  private ClusteredSortAgentScheduler scheduler;
  private static final String REDIS_TIME_SECONDS = "1609459200"; // Fixed timestamp for testing
  private static final String REDIS_TIME_MICROS = "0";

  @BeforeEach
  void setUp() {
    // Create mocks
    jedisPool = mock(JedisPool.class);
    jedis = mock(Jedis.class);
    nodeStatusProvider = mock(NodeStatusProvider.class);
    intervalProvider = mock(AgentIntervalProvider.class);
    shardingFilter = mock(ShardingFilter.class);
    dynamicConfigService = mock(DynamicConfigService.class);
    onDemandAgent = mock(OnDemandAgent.class);
    onDemandResult = mock(OnDemandAgent.OnDemandResult.class);

    // Setup basic mock behavior
    when(jedisPool.getResource()).thenReturn(jedis);
    when(jedis.time()).thenReturn(Arrays.asList(REDIS_TIME_SECONDS, REDIS_TIME_MICROS));

    // Mock script loading to return valid SHA strings
    when(jedis.scriptLoad(anyString())).thenReturn("mock-sha-" + System.currentTimeMillis());

    // Mock DynamicConfigService for feature flags and configuration
    when(dynamicConfigService.getConfig(
            Boolean.class, "redis.agent.on-demand.boost-enabled", false))
        .thenReturn(true);
    when(dynamicConfigService.getConfig(
            Double.class, "redis.agent.on-demand.max-boosts-per-second", 10.0))
        .thenReturn(10.0);
    when(dynamicConfigService.getConfig(String.class, "redis.agent.enabled-pattern", ".*"))
        .thenReturn(".*");
    when(dynamicConfigService.getConfig(Integer.class, "redis.agent.refresh-period-seconds", 30))
        .thenReturn(30);
    when(dynamicConfigService.getConfig(Long.class, "redis.agent.scheduler-interval-ms", 1000L))
        .thenReturn(1000L);

    scheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            intervalProvider,
            ".*", // enabledAgentPattern
            100, // parallelism
            30, // redisRefreshPeriod
            1000L, // schedulerIntervalMs
            shardingFilter,
            dynamicConfigService);
  }

  @Test
  void shouldBoostAgentPriorityToRunImmediately() {
    // GIVEN: Agent exists in WAITING_SET with future execution time
    String agentType = "AmazonServerGroupCachingAgent";
    String oldScore = "1609459500"; // 5 minutes in future
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(oldScore);

    // WHEN: We boost its priority
    boolean result = scheduler.boostAgentPriority(Set.of(agentType));

    // THEN: Agent should be rescheduled to run now
    assertTrue(result);

    ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);

    verify(jedis).evalsha(anyString(), keysCaptor.capture(), argsCaptor.capture());

    assertEquals(Arrays.asList(WAITING_SET, WORKING_SET), keysCaptor.getValue());
    assertEquals(Arrays.asList(agentType, REDIS_TIME_SECONDS), argsCaptor.getValue());
  }

  @Test
  void shouldReturnFalseWhenAgentNotInWaitingSet() {
    // GIVEN: Agent doesn't exist in WAITING_SET
    String agentType = "NonExistentAgent";
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(null);

    // WHEN: We try to boost its priority
    boolean result = scheduler.boostAgentPriority(Set.of(agentType));

    // THEN: Should return false
    assertFalse(result);
  }

  @Test
  void shouldBoostMultipleAgents() {
    // GIVEN: Multiple agents in WAITING_SET
    Set<String> agentTypes = Set.of("AmazonServerGroupCachingAgent", "AmazonInstanceCachingAgent");
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn("old_score");

    // WHEN: We boost their priorities
    boolean result = scheduler.boostAgentPriority(agentTypes);

    // THEN: All agents should be boosted
    assertTrue(result);
    verify(jedis, times(2))
        .evalsha(anyString(), anyList(), anyList()); // ServerGroup + Instance agents
  }

  @Test
  void shouldIdentifyRelatedCachingAgentsForAWSServerGroup() {
    // GIVEN: AWS ServerGroup OnDemand agent
    when(onDemandAgent.getProviderName()).thenReturn("aws");
    when(onDemandAgent.getOnDemandAgentType()).thenReturn("ServerGroup");

    // WHEN: We find related caching agents
    Set<String> relatedAgents = scheduler.findRelatedCachingAgents(onDemandAgent);

    // THEN: Should return ServerGroup and Instance agents
    assertEquals(2, relatedAgents.size());
    assertTrue(relatedAgents.contains("AmazonServerGroupCachingAgent"));
    assertTrue(relatedAgents.contains("AmazonInstanceCachingAgent"));
  }

  @Test
  void shouldIdentifyRelatedCachingAgentsForAWSLoadBalancer() {
    // GIVEN: AWS LoadBalancer OnDemand agent
    when(onDemandAgent.getProviderName()).thenReturn("aws");
    when(onDemandAgent.getOnDemandAgentType()).thenReturn("LoadBalancer");

    // WHEN: We find related caching agents
    Set<String> relatedAgents = scheduler.findRelatedCachingAgents(onDemandAgent);

    // THEN: Should return LoadBalancer agent
    assertEquals(1, relatedAgents.size());
    assertTrue(relatedAgents.contains("AmazonLoadBalancerCachingAgent"));
  }

  @Test
  void shouldReturnEmptySetForUnknownProvider() {
    // GIVEN: Unknown provider OnDemand agent
    when(onDemandAgent.getProviderName()).thenReturn("unknown");
    when(onDemandAgent.getOnDemandAgentType()).thenReturn("ServerGroup");

    // WHEN: We find related caching agents
    Set<String> relatedAgents = scheduler.findRelatedCachingAgents(onDemandAgent);

    // THEN: Should return empty set
    assertTrue(relatedAgents.isEmpty());
  }

  @Test
  void shouldRateLimitPriorityBoosts() {
    // GIVEN: Multiple rapid boost requests for same agent
    String agentType = "AmazonServerGroupCachingAgent";
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn("old_score");

    // WHEN: We boost same agent multiple times rapidly
    boolean result1 = scheduler.boostAgentPriority(Set.of(agentType));
    boolean result2 = scheduler.boostAgentPriority(Set.of(agentType));

    // THEN: First should succeed, second should be rate limited
    assertTrue(result1);
    assertFalse(result2); // Rate limited

    // Only one actual Redis call should be made
    verify(jedis, times(1)).evalsha(anyString(), anyList(), anyList());
  }

  @Test
  void shouldIntegrateWithOnDemandCompletion() {
    // GIVEN: OnDemand agent completion with account/region information
    when(onDemandAgent.getProviderName()).thenReturn("aws");
    when(onDemandAgent.getOnDemandAgentType()).thenReturn("ServerGroup");
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn("old_score");

    // Mock the OnDemand agent as both AccountAware and Agent
    AccountAware mockAccountAware = mock(AccountAware.class);
    Agent mockAgent = mock(Agent.class);
    when(mockAccountAware.getAccountName()).thenReturn("test-account");
    when(mockAgent.getAgentType()).thenReturn("test-account/us-east-1/TestOnDemandAgent");

    // Create a combined mock that implements both interfaces
    OnDemandAgent combinedAgent =
        mock(OnDemandAgent.class, withSettings().extraInterfaces(AccountAware.class, Agent.class));
    when(combinedAgent.getProviderName()).thenReturn("aws");
    when(combinedAgent.getOnDemandAgentType()).thenReturn("ServerGroup");
    when(((AccountAware) combinedAgent).getAccountName()).thenReturn("test-account");
    when(((Agent) combinedAgent).getAgentType())
        .thenReturn("test-account/us-east-1/TestOnDemandAgent");

    // WHEN: OnDemand completion triggers boost
    scheduler.handleOnDemandCompletion(combinedAgent, onDemandResult);

    // THEN: Related agents should be boosted with account/region scoping
    verify(jedis, times(2))
        .evalsha(anyString(), anyList(), anyList()); // ServerGroup + Instance agents
  }

  @Test
  void shouldHandleRedisExceptionsGracefully() {
    // GIVEN: Redis connection failure
    String agentType = "AmazonServerGroupCachingAgent";
    when(jedis.evalsha(anyString(), anyList(), anyList()))
        .thenThrow(new RuntimeException("Redis connection failed"));

    // WHEN: We try to boost priority
    boolean result = scheduler.boostAgentPriority(Set.of(agentType));

    // THEN: Should handle exception gracefully and return false
    assertFalse(result);
  }

  @Test
  void shouldHandleEmptyAgentTypeSet() {
    // GIVEN: Empty agent type set
    Set<String> emptySet = Collections.emptySet();

    // Reset the mock to ignore constructor interactions
    reset(jedis);
    when(jedisPool.getResource()).thenReturn(jedis);

    // WHEN: We try to boost priority with empty set
    boolean result = scheduler.boostAgentPriority(emptySet);

    // THEN: Should return false and not call Redis for boosting
    assertFalse(result);
    verifyNoMoreInteractions(jedis);
  }
}
