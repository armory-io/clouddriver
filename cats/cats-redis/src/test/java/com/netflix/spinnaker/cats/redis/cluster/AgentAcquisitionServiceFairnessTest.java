/*
 * Copyright 2025 Netflix, Inc.
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("AgentAcquisitionService Fairness Tests")
class AgentAcquisitionServiceFairnessTest {

  @Container
  private static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private JedisPool jedisPool;
  private AgentAcquisitionService acquisitionService;
  private RedisScriptManager scriptManager;

  private ShardingFilter shardingFilter;
  private AgentIntervalProvider intervalProvider;
  private PrioritySchedulerMetrics metrics;
  private AgentExecution agentExecution;
  private ExecutionInstrumentation executionInstrumentation;

  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    // Initialize mocks
    shardingFilter = mock(ShardingFilter.class);
    intervalProvider = mock(AgentIntervalProvider.class);
    Registry registry = new DefaultRegistry();
    metrics = new PrioritySchedulerMetrics(registry);
    agentExecution = mock(AgentExecution.class);
    executionInstrumentation = mock(ExecutionInstrumentation.class);

    // Create JedisPool
    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(10);
    poolConfig.setMaxIdle(5);
    poolConfig.setMinIdle(1);
    jedisPool =
        new JedisPool(poolConfig, redis.getHost(), redis.getMappedPort(6379), 2000, null, 0);

    // Clear Redis
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushDB();
    }

    // Setup properties
    agentProperties = new PriorityAgentProperties();
    agentProperties.setEnabledPattern(".*");
    agentProperties.setMaxConcurrentAgents(100); // High limit for fairness testing

    schedulerProperties = new PrioritySchedulerProperties();
    PrioritySchedulerProperties.BatchOperations batchOps =
        new PrioritySchedulerProperties.BatchOperations();
    batchOps.setEnabled(true);
    batchOps.setBatchSize(10); // Small batch size to test chunking
    batchOps.setChunkAttemptMultiplier(
        5.0); // Need more attempts to handle 67% filtering with lexicographic ordering
    schedulerProperties.setBatchOperations(batchOps);
    schedulerProperties.setRefreshPeriodSeconds(30);

    PrioritySchedulerProperties.Keys keysProperties = new PrioritySchedulerProperties.Keys();
    keysProperties.setWaitingSet("waiting-test");
    keysProperties.setWorkingSet("working-test");
    schedulerProperties.setKeys(keysProperties);

    // Initialize script manager
    scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();

    // Setup mocks
    when(shardingFilter.filter(any())).thenReturn(true);
    AgentIntervalProvider.Interval interval = new AgentIntervalProvider.Interval(60000, 120000);
    when(intervalProvider.getInterval(any())).thenReturn(interval);

    // Create acquisition service
    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("Should continue acquiring from next chunk when first chunk yields zero")
  void testContinuesAfterZeroAcquisitionChunk() throws Exception {
    // Create more agents than batch size
    int totalAgents = 30; // 3 times the batch size
    List<Agent> agents = new ArrayList<>();

    for (int i = 1; i <= totalAgents; i++) {
      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("agent-" + i);
      agents.add(agent);
      acquisitionService.registerAgent(agent, agentExecution, executionInstrumentation);
    }

    // Create another acquisition service to simulate competition
    AgentAcquisitionService competingService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    // Register same agents with competing service
    for (Agent agent : agents) {
      competingService.registerAgent(agent, agentExecution, executionInstrumentation);
    }

    // Thread pool for execution
    ExecutorService threadPool = Executors.newFixedThreadPool(20);
    Semaphore runningAgents = new Semaphore(100);

    // Track successful acquisitions
    AtomicInteger service1Acquired = new AtomicInteger(0);
    AtomicInteger service2Acquired = new AtomicInteger(0);
    AtomicInteger executionCount = new AtomicInteger(0);

    // Mock agent execution to track acquisitions
    doAnswer(
            invocation -> {
              executionCount.incrementAndGet();
              // Don't sleep - it can cause hangs
              return null;
            })
        .when(agentExecution)
        .executeAgent(any());

    // Run both services simultaneously to create contention
    CompletableFuture<Integer> service1Future =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                int acquired = acquisitionService.saturatePool(1, runningAgents, threadPool);
                service1Acquired.set(acquired);
                return acquired;
              } catch (Exception e) {
                e.printStackTrace();
                return 0;
              }
            });

    // Small delay to ensure both services are competing
    Thread.sleep(50);

    CompletableFuture<Integer> service2Future =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                int acquired = competingService.saturatePool(1, runningAgents, threadPool);
                service2Acquired.set(acquired);
                return acquired;
              } catch (Exception e) {
                e.printStackTrace();
                return 0;
              }
            });

    // Wait for both to complete with timeout
    try {
      service1Future.get(10, TimeUnit.SECONDS);
      service2Future.get(10, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      fail("Test timed out waiting for acquisition to complete");
    }

    // Give a moment for executions to start
    Thread.sleep(500);

    // Verify that agents were acquired despite contention
    int totalAcquired = service1Acquired.get() + service2Acquired.get();

    // Should acquire all agents between the two services
    assertEquals(
        totalAgents, totalAcquired, "Total agents acquired should equal total agents available");

    // At least one service should have acquired agents
    assertTrue(
        service1Acquired.get() > 0 || service2Acquired.get() > 0,
        "At least one service should acquire agents");

    // With our fairness fix, if one service gets all from first chunk,
    // it should continue to acquire from subsequent chunks
    // Since batch size is 10 and we have 30 agents, at least one service
    // should get more than 10 (proving it continued past first chunk)
    assertTrue(
        service1Acquired.get() > 10 || service2Acquired.get() > 10,
        "At least one service should acquire more than one batch worth of agents");

    // Cleanup
    threadPool.shutdown();
    assertTrue(threadPool.awaitTermination(10, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("Should process agents beyond first batch when many are filtered")
  void testFairnessWithFilteredAgents() throws Exception {
    // Create agents where only some pass the filter
    int totalAgents = 50;
    int enabledInterval = 3; // Every 3rd agent is enabled

    AtomicInteger filterCalls = new AtomicInteger(0);

    // Mock sharding filter to only accept every 3rd agent
    when(shardingFilter.filter(any()))
        .thenAnswer(
            invocation -> {
              Agent agent = invocation.getArgument(0);
              if (agent == null) return false;
              String agentType = agent.getAgentType();
              if (agentType == null) return false;
              try {
                int agentNum = Integer.parseInt(agentType.split("-")[1]);
                return agentNum % enabledInterval == 0;
              } catch (Exception e) {
                return false;
              }
            });

    List<Agent> agents = new ArrayList<>();
    for (int i = 1; i <= totalAgents; i++) {
      Agent agent = mock(Agent.class);
      String agentType = "agent-" + i;
      when(agent.getAgentType()).thenReturn(agentType);
      agents.add(agent);

      // Override filter for registration
      when(shardingFilter.filter(agent)).thenReturn(true);
      acquisitionService.registerAgent(agent, agentExecution, executionInstrumentation);
    }

    // Restore filter behavior for acquisition
    when(shardingFilter.filter(any()))
        .thenAnswer(
            invocation -> {
              Agent agent = invocation.getArgument(0);
              if (agent == null) return false;
              String agentType = agent.getAgentType();
              if (agentType == null) return false;
              try {
                int agentNum = Integer.parseInt(agentType.split("-")[1]);
                boolean pass = agentNum % enabledInterval == 0;
                if (!pass) filterCalls.incrementAndGet();
                return pass;
              } catch (Exception e) {
                return false;
              }
            });

    // Thread pool and semaphore
    ExecutorService threadPool = Executors.newFixedThreadPool(20);
    Semaphore runningAgents = new Semaphore(100);

    // Track executions
    AtomicInteger executionCount = new AtomicInteger(0);
    doAnswer(
            invocation -> {
              executionCount.incrementAndGet();
              return null;
            })
        .when(agentExecution)
        .executeAgent(any());

    // Run acquisition
    int acquired = acquisitionService.saturatePool(1, runningAgents, threadPool);

    // Calculate expected acquisitions (every 3rd agent)
    // Agents numbered i where i % 3 == 0: 3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42, 45,
    // 48
    int expectedAcquired = 0;
    for (int i = 1; i <= totalAgents; i++) {
      if (i % enabledInterval == 0) {
        expectedAcquired++;
      }
    }

    // With our fairness fix, should acquire all enabled agents
    // even if they're spread across multiple chunks
    assertEquals(
        expectedAcquired,
        acquired,
        "Should acquire all enabled agents despite filtering. Expected agents: "
            + "3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42, 45, 48");

    // Should have checked many agents (shows we continued past filtered chunks)
    assertTrue(
        filterCalls.get() > schedulerProperties.getBatchOperations().getBatchSize(),
        "Should have checked agents beyond first batch due to filtering");

    // Cleanup
    threadPool.shutdown();
    assertTrue(threadPool.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("Should maintain FIFO order under normal conditions")
  void testFIFOOrdering() throws Exception {
    // Create agents with specific scores to test ordering
    int agentCount = 20;
    List<String> executionOrder = new ArrayList<>();
    AtomicInteger executedCount = new AtomicInteger(0);

    // Mock execution to track order
    doAnswer(
            invocation -> {
              Agent agent = invocation.getArgument(0);
              synchronized (executionOrder) {
                executionOrder.add(agent.getAgentType());
              }
              executedCount.incrementAndGet();
              return null;
            })
        .when(agentExecution)
        .executeAgent(any());

    // Register agents in specific order
    for (int i = 1; i <= agentCount; i++) {
      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("agent-" + String.format("%02d", i));
      acquisitionService.registerAgent(agent, agentExecution, executionInstrumentation);
    }

    // Execute with sufficient thread pool
    ExecutorService threadPool = Executors.newFixedThreadPool(agentCount);
    Semaphore runningAgents = new Semaphore(agentCount);

    // Acquire and execute all agents
    int acquired = acquisitionService.saturatePool(1, runningAgents, threadPool);

    assertEquals(agentCount, acquired, "Should acquire all agents");

    // Give time for executions to complete
    Thread.sleep(1000);

    // Verify FIFO order (agents should be acquired in order)
    // Note: Execution order might vary due to threading, but acquisition should be FIFO
    assertEquals(agentCount, executionOrder.size(), "All agents should have executed");

    // Verify first agents were in the first batch
    List<String> firstBatch =
        executionOrder.subList(
            0,
            Math.min(
                schedulerProperties.getBatchOperations().getBatchSize(), executionOrder.size()));

    for (String agentType : firstBatch) {
      int agentNum = Integer.parseInt(agentType.split("-")[1]);
      assertTrue(
          agentNum <= schedulerProperties.getBatchOperations().getBatchSize() * 2,
          "First batch should contain lower-numbered agents");
    }

    // Cleanup
    threadPool.shutdown();
    assertTrue(threadPool.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("Should handle empty chunks gracefully")
  void testEmptyChunkHandling() throws Exception {
    // Start with no agents
    ExecutorService threadPool = Executors.newFixedThreadPool(10);
    Semaphore runningAgents = new Semaphore(10);

    // First acquisition with empty Redis
    int acquired = acquisitionService.saturatePool(1, runningAgents, threadPool);
    assertEquals(0, acquired, "Should acquire 0 agents from empty Redis");

    // Add some agents
    for (int i = 1; i <= 5; i++) {
      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("agent-" + i);
      acquisitionService.registerAgent(agent, agentExecution, executionInstrumentation);
    }

    // Second acquisition should get the newly added agents
    acquired = acquisitionService.saturatePool(2, runningAgents, threadPool);
    assertEquals(5, acquired, "Should acquire all newly added agents");

    // Cleanup
    threadPool.shutdown();
    assertTrue(threadPool.awaitTermination(5, TimeUnit.SECONDS));
  }
}
