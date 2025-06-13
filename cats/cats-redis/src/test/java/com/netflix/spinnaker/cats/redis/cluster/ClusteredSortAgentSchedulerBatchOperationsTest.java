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

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Tests for batch Redis operations in ClusteredSortAgentScheduler to validate O(n) → O(1)
 * performance optimizations.
 */
class ClusteredSortAgentSchedulerBatchOperationsTest {

  private static final String WAITING_SET = "WAITZ";
  private static final String WORKING_SET = "WORKZ";

  @Mock private JedisPool jedisPool;
  @Mock private Jedis jedis;
  @Mock private NodeStatusProvider nodeStatusProvider;
  @Mock private DynamicConfigService dynamicConfigService;
  @Mock private ShardingFilter shardingFilter;

  private ClusteredSortAgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    when(jedisPool.getResource()).thenReturn(jedis);
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(true);
    when(dynamicConfigService.getConfig(eq(Boolean.class), anyString(), anyBoolean()))
        .thenReturn(false);
    when(dynamicConfigService.getConfig(eq(Integer.class), anyString(), anyInt())).thenReturn(30);
    when(dynamicConfigService.getConfig(eq(Long.class), anyString(), anyLong())).thenReturn(1000L);
    when(dynamicConfigService.getConfig(eq(Double.class), anyString(), anyDouble()))
        .thenReturn(10.0);

    // Mock batch operations flag specifically (disabled by default for safety)
    when(dynamicConfigService.getConfig(
            eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
        .thenReturn(false);

    // Mock Redis TIME command for score generation
    when(jedis.time()).thenReturn(Arrays.asList("1609459200", "0"));

    // Mock script loading to prevent actual Redis calls during construction
    when(jedis.scriptLoad(anyString())).thenReturn("mockedSHA");
    when(jedis.scriptExists(anyString())).thenReturn(true);

    scheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            null, // intervalProvider - not needed for these tests
            ".*", // enabledAgentPattern
            0, // parallelism
            30, // redisRefreshPeriod
            1000L, // schedulerIntervalMs
            shardingFilter,
            dynamicConfigService);
  }

  @Test
  void shouldUseBatchAddAgentsForRepopulation() {
    // GIVEN: Batch operations enabled and multiple agents to repopulate
    when(dynamicConfigService.getConfig(
            eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
        .thenReturn(true);
    Agent agent1 = mock(Agent.class);
    Agent agent2 = mock(Agent.class);
    Agent agent3 = mock(Agent.class);

    when(agent1.getAgentType()).thenReturn("TestAgent1");
    when(agent2.getAgentType()).thenReturn("TestAgent2");
    when(agent3.getAgentType()).thenReturn("TestAgent3");

    when(shardingFilter.filter(any())).thenReturn(true);

    // Schedule agents to populate internal map
    scheduler.schedule(agent1, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
    scheduler.schedule(agent2, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
    scheduler.schedule(agent3, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

    // Mock batch script returning number of agents added
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(3L);

    // WHEN: Repopulation runs (simulated by calling saturatePool with runCount = 0)
    scheduler.saturatePool();

    // THEN: Should use batch add script with all agents
    ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);

    verify(jedis, atLeastOnce()).evalsha(anyString(), keysCaptor.capture(), argsCaptor.capture());

    // Find the batch add operation (last call with multiple agents)
    List<List<String>> allKeys = keysCaptor.getAllValues();
    List<List<String>> allArgs = argsCaptor.getAllValues();

    boolean foundBatchAdd = false;
    for (int i = 0; i < allArgs.size(); i++) {
      List<String> args = allArgs.get(i);
      if (args.size() > 2) { // Batch operations have score + multiple agents
        foundBatchAdd = true;
        assertEquals(Arrays.asList(WAITING_SET, WORKING_SET), allKeys.get(i));
        assertTrue(args.size() >= 4, "Should have score + at least 3 agents");
        break;
      }
    }

    assertTrue(foundBatchAdd, "Should use batch add script for repopulation");
  }

  @Test
  void shouldUseBatchBoostForMultipleAgents() {
    // GIVEN: Batch operations enabled and multiple agents to boost
    when(dynamicConfigService.getConfig(
            eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
        .thenReturn(true);
    Set<String> agentsToBoost = Set.of("Agent1", "Agent2", "Agent3", "Agent4");

    // Mock batch boost script returning number of agents boosted
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(4L);

    // WHEN: Boosting priority
    boolean result = scheduler.boostAgentPriority(agentsToBoost);

    // THEN: Should use batch boost script
    assertTrue(result);

    ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);

    verify(jedis).evalsha(anyString(), keysCaptor.capture(), argsCaptor.capture());

    assertEquals(Arrays.asList(WAITING_SET, WORKING_SET), keysCaptor.getValue());

    List<String> args = argsCaptor.getValue();
    assertEquals(5, args.size()); // Score + 4 agents
    assertTrue(agentsToBoost.containsAll(args.subList(1, args.size())));
  }

  @Test
  void shouldFallbackToIndividualOperationsOnBatchFailure() {
    // GIVEN: Batch operations enabled but batch operation fails
    when(dynamicConfigService.getConfig(
            eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
        .thenReturn(true);

    Set<String> agentsToBoost = Set.of("Agent1", "Agent2");

    // Mock batch operation failure, then individual operations success
    when(jedis.evalsha(anyString(), anyList(), anyList()))
        .thenThrow(new RuntimeException("Batch operation failed"))
        .thenReturn("oldScore1") // Individual operation 1
        .thenReturn("oldScore2"); // Individual operation 2

    // WHEN: Boosting priority
    boolean result = scheduler.boostAgentPriority(agentsToBoost);

    // THEN: Should fallback to individual operations and still succeed
    assertTrue(result);

    // Should have 1 batch call + 2 individual calls = 3 total calls
    verify(jedis, times(3)).evalsha(anyString(), anyList(), anyList());
  }

  @Test
  void shouldHandleBatchZombieCleanup() {
    // GIVEN: Batch operations enabled and zombie agents in the scheduler
    when(dynamicConfigService.getConfig(
            eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
        .thenReturn(true);

    // Configure zombie threshold to trigger cleanup (make it very short)
    when(dynamicConfigService.getConfig(
            eq(Long.class), eq("redis.agent.zombie-threshold-ms"), anyLong()))
        .thenReturn(100L); // 100ms - very short to trigger cleanup
    when(dynamicConfigService.getConfig(
            eq(Long.class), eq("redis.agent.zombie-cleanup-interval-ms"), anyLong()))
        .thenReturn(0L); // 0ms - always allow cleanup

    String agent1 = "ZombieAgent1";
    String agent2 = "ZombieAgent2";

    // Add agents to active tracking with old timestamps (simulate they were executing long ago)
    long oldTime = System.currentTimeMillis() - 10000L; // 10 seconds ago
    scheduler.activeAgents.put(
        agent1,
        new ClusteredSortAgentScheduler.ActiveAgent(
            mock(java.util.concurrent.Future.class), oldTime, "score1"));
    scheduler.activeAgents.put(
        agent2,
        new ClusteredSortAgentScheduler.ActiveAgent(
            mock(java.util.concurrent.Future.class), oldTime, "score2"));

    // Initial zombie count
    long initialZombieCount = scheduler.zombiesCleanedUp.get();

    // WHEN: Zombie cleanup runs directly
    scheduler.cleanupZombieAgentsIfNeeded();

    // THEN: Zombies should be cleaned up (either batch or individual)
    long finalZombieCount = scheduler.zombiesCleanedUp.get();
    assertEquals(
        2, finalZombieCount - initialZombieCount, "Should have cleaned up 2 zombie agents");

    // Agents should be removed from active tracking
    assertFalse(scheduler.activeAgents.containsKey(agent1));
    assertFalse(scheduler.activeAgents.containsKey(agent2));

    // Should have attempted Redis operations (batch or individual)
    verify(jedis, atLeastOnce()).evalsha(anyString(), anyList(), anyList());
  }

  @Test
  void shouldRespectRateLimitingInBatchBoost() {
    // GIVEN: Batch operations enabled and some agents that should be rate limited
    when(dynamicConfigService.getConfig(
            eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
        .thenReturn(true);

    // Configure rate limiting to be very strict (1 second interval)
    when(dynamicConfigService.getConfig(
            eq(Double.class), eq("redis.agent.on-demand.max-boosts-per-second"), anyDouble()))
        .thenReturn(1.0); // 1 boost per second = 1000ms interval

    Set<String> agentsToBoost = Set.of("Agent1", "Agent2", "Agent3");

    // Set recent boost times to trigger rate limiting
    long now = System.currentTimeMillis();
    scheduler.lastBoostTimes.put("Agent1", now - 500); // 500ms ago - should be rate limited
    scheduler.lastBoostTimes.put("Agent2", now - 2000); // 2 seconds ago - should be allowed
    // Agent3 has no previous boost time - should be allowed

    // Mock batch boost script for 2 agents (Agent2 and Agent3)
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(2L);

    // WHEN: Boosting priority
    boolean result = scheduler.boostAgentPriority(agentsToBoost);

    // THEN: Should only boost non-rate-limited agents
    assertTrue(result);

    ArgumentCaptor<List<String>> argsCaptor = ArgumentCaptor.forClass(List.class);
    verify(jedis).evalsha(anyString(), anyList(), argsCaptor.capture());

    List<String> args = argsCaptor.getValue();
    assertEquals(3, args.size()); // Score + 2 agents (Agent1 should be rate limited)
    assertFalse(args.contains("Agent1")); // Should be rate limited
    assertTrue(args.contains("Agent2")); // Should be allowed
    assertTrue(args.contains("Agent3")); // Should be allowed
  }

  @Test
  void shouldHandleEmptyBatchOperations() {
    // GIVEN: Empty agent sets

    // WHEN: Boosting with empty set
    boolean boostResult = scheduler.boostAgentPriority(Collections.emptySet());

    // THEN: Should return false without calling Redis
    assertFalse(boostResult);
    verify(jedis, never()).evalsha(anyString(), anyList(), anyList());
  }

  @Test
  void shouldProvidePerformanceMetricsForBatchOperations() {
    // GIVEN: Batch operations enabled and large number of agents to test performance improvement
    when(dynamicConfigService.getConfig(
            eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
        .thenReturn(true);

    Set<String> manyAgents = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      manyAgents.add("Agent" + i);
    }

    // Mock batch boost returning all agents boosted
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(100L);

    long startTime = System.currentTimeMillis();

    // WHEN: Boosting many agents
    boolean result = scheduler.boostAgentPriority(manyAgents);

    long duration = System.currentTimeMillis() - startTime;

    // THEN: Should complete quickly with single Redis call
    assertTrue(result);
    assertTrue(duration < 1000, "Batch operation should complete in <1000ms");

    // Should only make 1 Redis call instead of 100
    verify(jedis, times(1)).evalsha(anyString(), anyList(), anyList());
  }

  @Test
  void shouldFallbackToIndividualOperationsWhenBatchDisabled() {
    // GIVEN: Batch operations are disabled (default behavior)
    when(dynamicConfigService.getConfig(
            eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
        .thenReturn(false);

    Set<String> agentsToBoost = Set.of("Agent1", "Agent2");

    // Mock individual operations success
    when(jedis.evalsha(anyString(), anyList(), anyList()))
        .thenReturn("oldScore1") // Individual operation 1
        .thenReturn("oldScore2"); // Individual operation 2

    // WHEN: Boosting priority (should use individual operations)
    boolean result = scheduler.boostAgentPriority(agentsToBoost);

    // THEN: Should succeed with individual operations
    assertTrue(result);

    // Should make individual calls for each agent (not batch)
    verify(jedis, times(2)).evalsha(anyString(), anyList(), anyList());
  }

  @Test
  void shouldUseBatchOperationsWhenEnabled() {
    // GIVEN: Batch operations are explicitly enabled
    when(dynamicConfigService.getConfig(
            eq(Boolean.class), eq("redis.agent.batch-operations-enabled"), eq(false)))
        .thenReturn(true);

    Set<String> agentsToBoost = Set.of("Agent1", "Agent2", "Agent3");

    // Mock batch operation success
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(3L);

    // WHEN: Boosting priority (should use batch operations)
    boolean result = scheduler.boostAgentPriority(agentsToBoost);

    // THEN: Should succeed with single batch call
    assertTrue(result);

    // Should make only 1 batch call instead of 3 individual calls
    verify(jedis, times(1)).evalsha(anyString(), anyList(), anyList());
  }
}
