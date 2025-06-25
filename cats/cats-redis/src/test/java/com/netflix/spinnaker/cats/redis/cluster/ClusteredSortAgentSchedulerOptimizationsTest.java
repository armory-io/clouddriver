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
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Tests specifically focused on the optimization and reliability improvements made to
 * ClusteredSortAgentScheduler.
 */
class ClusteredSortAgentSchedulerOptimizationsTest {

  private static final String WAITING_SET = "WAITZ";
  private static final String WORKING_SET = "WORKZ";

  @Mock private JedisPool jedisPool;
  @Mock private Jedis jedis;
  @Mock private Pipeline pipeline;
  @Mock private NodeStatusProvider nodeStatusProvider;
  @Mock private DynamicConfigService dynamicConfigService;
  @Mock private ShardingFilter shardingFilter;
  @Mock private Agent agent;

  private ClusteredSortAgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    when(jedisPool.getResource()).thenReturn(jedis);
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(true);
    when(shardingFilter.filter(any())).thenReturn(true);
    when(agent.getAgentType()).thenReturn("TestAgent");

    // Configure dynamic config defaults
    when(dynamicConfigService.getConfig(eq(Boolean.class), anyString(), anyBoolean()))
        .thenReturn(false);
    when(dynamicConfigService.getConfig(eq(Integer.class), anyString(), anyInt())).thenReturn(30);
    when(dynamicConfigService.getConfig(eq(Long.class), anyString(), anyLong())).thenReturn(1000L);

    // Script loading setup
    when(jedis.scriptLoad(anyString())).thenReturn("mockedSHA");
    when(jedis.scriptExists(anyString())).thenReturn(true);

    scheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            null, // intervalProvider
            ".*", // enabledAgentPattern
            10, // parallelism
            30, // redisRefreshPeriod
            1000L, // schedulerIntervalMs
            shardingFilter,
            dynamicConfigService,
            Collections.emptyList()); // disabledAgents
  }

  // Note: Redis exception handling is covered by existing tests in ClusteredSortAgentSchedulerTest
  // We don't need a separate test for this since the error handling code is well-established

  @Test
  @DisplayName("Should use configurable cache for Redis time offset")
  void shouldUseCacheForRedisTimeOffset() throws Exception {
    // Set up time caching configuration
    long timeCacheDurationMs = 5000L;
    when(dynamicConfigService.getConfig(
            eq(Long.class), eq("redis.agent.time-cache-duration-ms"), anyLong()))
        .thenReturn(timeCacheDurationMs);

    // Mock time responses
    List<String> timeResponse =
        Arrays.asList("1609459200", "0"); // 2021-01-01 00:00:00 UTC in seconds
    when(jedis.time()).thenReturn(timeResponse);

    // Use reflection to access the score method and reset the time cache
    Method scoreMethod =
        ClusteredSortAgentScheduler.class.getDeclaredMethod("score", Jedis.class, long.class);
    scoreMethod.setAccessible(true);

    // Reset cache state
    Field lastTimeCheckField = ClusteredSortAgentScheduler.class.getDeclaredField("lastTimeCheck");
    lastTimeCheckField.setAccessible(true);
    AtomicLong lastTimeCheck = (AtomicLong) lastTimeCheckField.get(null);
    lastTimeCheck.set(0);

    // First call should update the cache
    scoreMethod.invoke(scheduler, jedis, 60000L); // 1 minute offset
    verify(jedis, times(1)).time();

    // Second call within cache duration should use cached value
    scoreMethod.invoke(scheduler, jedis, 120000L); // 2 minute offset
    verify(jedis, times(1)).time(); // Still only 1 call

    // Mock time elapsing beyond cache duration
    lastTimeCheck.set(
        System.currentTimeMillis() - timeCacheDurationMs - 1000); // Past cache duration

    // Call again should refresh cache
    scoreMethod.invoke(scheduler, jedis, 180000L); // 3 minute offset
    verify(jedis, times(2)).time(); // Now 2 calls
  }

  @Test
  @DisplayName("Should use pipelined Redis operations in agentScore")
  void shouldUsePipelinedRedisOperationsInAgentScore() throws Exception {
    // Setup pipeline mocks
    when(jedis.pipelined()).thenReturn(pipeline);

    // Mock response objects
    @SuppressWarnings("unchecked")
    Response<Double> workingScoreResponse = mock(Response.class);
    @SuppressWarnings("unchecked")
    Response<Double> waitingScoreResponse = mock(Response.class);

    when(pipeline.zscore(WORKING_SET, "TestAgent")).thenReturn(workingScoreResponse);
    when(pipeline.zscore(WAITING_SET, "TestAgent")).thenReturn(waitingScoreResponse);

    // Set up for first test case - score found in WORKING_SET
    when(workingScoreResponse.get()).thenReturn(1000.0);

    // Use reflection to access the private method
    Method agentScoreMethod =
        ClusteredSortAgentScheduler.class.getDeclaredMethod("agentScore", Agent.class);
    agentScoreMethod.setAccessible(true);

    // First case: score found in WORKING_SET
    String result = (String) agentScoreMethod.invoke(scheduler, agent);
    verify(pipeline).sync(); // Verify pipeline was executed
    assertNotNull(result);
    assertEquals("1000.0", result);

    // Second case: score found in WAITING_SET
    when(workingScoreResponse.get()).thenReturn(null);
    when(waitingScoreResponse.get()).thenReturn(2000.0);

    result = (String) agentScoreMethod.invoke(scheduler, agent);
    assertEquals("2000.0", result);
  }

  @Test
  @DisplayName("Should configure thread pool correctly with percentage-based core size")
  void shouldConfigureThreadPoolCorrectlyWithPercentageBasedCoreSize() throws Exception {
    // Configure thread pool parameters
    int maxPoolSize = 40;
    int corePercentage = 25; // Should result in corePoolSize = 10 (25% of 40)
    int queueSize = 500;

    when(dynamicConfigService.getConfig(
            eq(Integer.class), eq("redis.agent.thread-pool-size"), anyInt()))
        .thenReturn(maxPoolSize);

    when(dynamicConfigService.getConfig(
            eq(Integer.class), eq("redis.agent.thread-pool-core-size-percentage"), anyInt()))
        .thenReturn(corePercentage);

    when(dynamicConfigService.getConfig(
            eq(Integer.class), eq("redis.agent.thread-pool-queue-size"), anyInt()))
        .thenReturn(queueSize);

    // Create a new scheduler instance to capture the thread pool configuration
    ClusteredSortAgentScheduler testScheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            null, // intervalProvider
            ".*", // enabledAgentPattern
            10, // parallelism
            30, // redisRefreshPeriod
            1000L, // schedulerIntervalMs
            shardingFilter,
            dynamicConfigService,
            Collections.emptyList()); // disabledAgents

    // Get access to the thread pool and verify its configuration
    Field agentWorkPoolField = ClusteredSortAgentScheduler.class.getDeclaredField("agentWorkPool");
    agentWorkPoolField.setAccessible(true);
    ThreadPoolExecutor threadPool = (ThreadPoolExecutor) agentWorkPoolField.get(testScheduler);

    assertEquals(10, threadPool.getCorePoolSize()); // 25% of 40
    assertEquals(40, threadPool.getMaximumPoolSize());
    assertEquals(500, threadPool.getQueue().remainingCapacity());

    // Verify the rejection handler is present but we can't easily check its implementation
    // since we're using an anonymous inner class
    assertNotNull(threadPool.getRejectedExecutionHandler());
  }

  @Test
  @DisplayName("Should enforce minimum percentage for core pool size")
  void shouldEnforceMinimumPercentageForCorePoolSize() throws Exception {
    // Configure thread pool parameters with invalid percentage
    int maxPoolSize = 40;
    int corePercentage = 5; // Below minimum of 10%

    when(dynamicConfigService.getConfig(
            eq(Integer.class), eq("redis.agent.thread-pool-size"), anyInt()))
        .thenReturn(maxPoolSize);

    when(dynamicConfigService.getConfig(
            eq(Integer.class), eq("redis.agent.thread-pool-core-size-percentage"), anyInt()))
        .thenReturn(corePercentage);

    // Create a new scheduler instance
    ClusteredSortAgentScheduler testScheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            null, // intervalProvider
            ".*", // enabledAgentPattern
            10, // parallelism
            30, // redisRefreshPeriod
            1000L, // schedulerIntervalMs
            shardingFilter,
            dynamicConfigService,
            Collections.emptyList()); // disabledAgents

    // Verify it enforced the minimum core pool size (10% of 40 = 4)
    Field agentWorkPoolField = ClusteredSortAgentScheduler.class.getDeclaredField("agentWorkPool");
    agentWorkPoolField.setAccessible(true);
    ThreadPoolExecutor threadPool = (ThreadPoolExecutor) agentWorkPoolField.get(testScheduler);

    assertEquals(4, threadPool.getCorePoolSize()); // 10% of 40
  }

  @Test
  @DisplayName("Should handle Redis time failure gracefully")
  void shouldHandleRedisTimeFailureGracefully() throws Exception {
    // Mock Redis time failure
    when(jedis.time()).thenThrow(new JedisConnectionException("Connection error"));

    // Configure time cache duration
    when(dynamicConfigService.getConfig(
            eq(Long.class), eq("redis.agent.time-cache-duration-ms"), anyLong()))
        .thenReturn(10000L);

    // Use reflection to access the score method
    Method scoreMethod =
        ClusteredSortAgentScheduler.class.getDeclaredMethod("score", Jedis.class, long.class);
    scoreMethod.setAccessible(true);

    // This should not throw an exception
    String result = (String) scoreMethod.invoke(scheduler, jedis, 60000L);

    // Result should still be a valid string representing a timestamp
    assertNotNull(result);

    // Try to parse as a long to ensure it's a valid timestamp
    long timestamp = Long.parseLong(result);
    assertTrue(timestamp > 0);
  }
}
