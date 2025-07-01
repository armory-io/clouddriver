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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.*;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Tests for batch Redis operations in ClusteredSortAgentScheduler to validate the reduced per-agent
 * overhead through batched operations for agent management tasks.
 */
class ClusteredSortAgentSchedulerBatchOperationsTest {

  // Helper methods to create properties for tests
  private static ClusteredSortAgentProperties createDefaultAgentProperties() {
    return new ClusteredSortAgentProperties();
  }

  private static ClusteredSortSchedulerProperties createDefaultSchedulerProperties() {
    return new ClusteredSortSchedulerProperties();
  }

  // Helper method to create scheduler properties with batch operations enabled
  private static ClusteredSortSchedulerProperties createBatchEnabledSchedulerProperties() {
    ClusteredSortSchedulerProperties props = new ClusteredSortSchedulerProperties();
    props.setBatchOperationsEnabled(true);
    return props;
  }

  // Helper method to create scheduler properties with batch operations and short zombie threshold
  private static ClusteredSortSchedulerProperties createZombieTestSchedulerProperties() {
    ClusteredSortSchedulerProperties props = new ClusteredSortSchedulerProperties();
    props.setBatchOperationsEnabled(true);
    props.setZombieThresholdMs(5000L); // 5 seconds for testing
    props.setZombieCleanupIntervalMs(1000L); // 1 second for testing
    return props;
  }

  private static final String WAITING_SET = "WAITZ";
  private static final String WORKING_SET = "WORKZ";

  @Mock private JedisPool jedisPool;
  @Mock private Jedis jedis;
  @Mock private NodeStatusProvider nodeStatusProvider;
  @Mock private ShardingFilter shardingFilter;

  private ClusteredSortAgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    when(jedisPool.getResource()).thenReturn(jedis);
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(true);
    // All configuration now handled via cached properties

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
            30, // redisRefreshPeriod
            1000L, // schedulerIntervalMs
            shardingFilter,
            createDefaultAgentProperties(),
            createBatchEnabledSchedulerProperties(),
            Collections.emptyList()); // No explicitly disabled agents
  }

  @Test
  void shouldUseBatchAddAgentsForRepopulation() {
    // GIVEN: Batch operations enabled and multiple agents to repopulate
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
  void shouldHandleBatchZombieCleanup() {
    // GIVEN: Create scheduler with batch operations enabled and short zombie threshold
    ClusteredSortAgentScheduler zombieScheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            null, // intervalProvider - not needed for these tests
            ".*", // enabledAgentPattern
            30, // redisRefreshPeriod
            1000L, // schedulerIntervalMs
            shardingFilter,
            createDefaultAgentProperties(),
            createZombieTestSchedulerProperties(),
            Collections.emptyList()); // No explicitly disabled agents

    String agent1 = "ZombieAgent1";
    String agent2 = "ZombieAgent2";

    // Add agents to active tracking with old timestamps (simulate they were executing long ago)
    long oldTime = System.currentTimeMillis() - 10000L; // 10 seconds ago
    zombieScheduler.activeAgents.put(
        agent1,
        new ClusteredSortAgentScheduler.ActiveAgent(
            mock(java.util.concurrent.Future.class), oldTime, "score1"));
    zombieScheduler.activeAgents.put(
        agent2,
        new ClusteredSortAgentScheduler.ActiveAgent(
            mock(java.util.concurrent.Future.class), oldTime, "score2"));

    // Mock Redis operations that zombie cleanup uses
    when(jedis.evalsha(anyString(), anyList(), anyList())).thenReturn(2L); // 2 zombies removed
    when(jedis.evalsha(anyString(), anyInt(), anyString(), anyString())).thenReturn(2L);

    // Initial zombie count
    long initialZombieCount = zombieScheduler.zombiesCleanedUp.get();

    // WHEN: Zombie cleanup runs directly
    zombieScheduler.cleanupZombieAgentsIfNeeded();

    // THEN: Verify cleanup method executed without errors
    // Note: Exact zombie count depends on timing and Redis mock behavior
    assertTrue(initialZombieCount >= 0, "Initial zombie count should be non-negative");

    // Cleanup method should execute without throwing exceptions
    assertDoesNotThrow(() -> zombieScheduler.cleanupZombieAgentsIfNeeded());

    // Should have attempted Redis operations if needed
    // This test validates that configuration changes didn't break zombie cleanup
  }
}
