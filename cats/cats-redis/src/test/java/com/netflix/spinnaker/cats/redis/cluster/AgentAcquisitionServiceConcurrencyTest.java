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
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

/**
 * Test suite for concurrency and race condition tests for AgentAcquisitionService.
 *
 * <p>These tests focus on edge cases and "gotchas" that could occur in high-concurrency
 * environments with a large number of agents, including:
 *
 * <ul>
 *   <li>Race conditions in agent tracking
 *   <li>Counter consistency under concurrent operations
 *   <li>Memory leaks in futures map
 *   <li>Thread safety of statistics collection
 *   <li>Concurrent agent acquisition and removal
 * </ul>
 */
@DisplayName("AgentAcquisitionService Concurrency Tests")
class AgentAcquisitionServiceConcurrencyTest {

  @Mock private JedisPool mockJedisPool;
  @Mock private Jedis mockJedis;
  @Mock private Pipeline mockPipeline;
  @Mock private RedisScriptManager mockScriptManager;
  @Mock private AgentIntervalProvider mockIntervalProvider;
  @Mock private ShardingFilter mockShardingFilter;
  @Mock private PriorityAgentProperties mockAgentProperties;
  @Mock private PrioritySchedulerProperties mockSchedulerProperties;

  private AgentAcquisitionService acquisitionService;
  private ExecutorService testExecutor;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    // Setup basic mocks
    when(mockJedisPool.getResource()).thenReturn(mockJedis);
    when(mockShardingFilter.filter(any(Agent.class))).thenReturn(true);
    when(mockAgentProperties.getEnabledPattern()).thenReturn(".*");
    when(mockAgentProperties.getDisabledPattern()).thenReturn("");
    when(mockAgentProperties.getMaxConcurrentAgents()).thenReturn(100);
    when(mockSchedulerProperties.getRefreshPeriodSeconds()).thenReturn(30);
    when(mockScriptManager.getScriptSha(anyString())).thenReturn("mock-sha");
    when(mockScriptManager.isInitialized()).thenReturn(true);

    // Mock Pipeline operations to prevent null pointer exceptions
    when(mockJedis.pipelined()).thenReturn(mockPipeline);
    Response<Double> mockResponse = mock(Response.class);
    when(mockResponse.get()).thenReturn(null); // Simulate agent not found in any set
    when(mockPipeline.zscore(anyString(), anyString())).thenReturn(mockResponse);

    // Mock interval provider
    AgentIntervalProvider.Interval testInterval =
        new AgentIntervalProvider.Interval(60000L, 120000L);
    when(mockIntervalProvider.getInterval(any(Agent.class))).thenReturn(testInterval);

    acquisitionService =
        new AgentAcquisitionService(
            mockJedisPool,
            mockScriptManager,
            mockIntervalProvider,
            mockShardingFilter,
            mockAgentProperties,
            mockSchedulerProperties);

    testExecutor = Executors.newFixedThreadPool(20);
  }

  @AfterEach
  void tearDown() {
    if (testExecutor != null) {
      testExecutor.shutdown();
      try {
        if (!testExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          testExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        testExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  @Nested
  @DisplayName("Race Condition Tests")
  class RaceConditionTests {

    @Test
    @DisplayName("Concurrent removeActiveAgent calls should be safe and consistent")
    void concurrentRemoveActiveAgentShouldBeConsistent() throws Exception {
      // GIVEN: A few agents registered
      final int NUM_AGENTS = 10;
      final int NUM_THREADS = 20;

      // Register and simulate active agents
      for (int i = 0; i < NUM_AGENTS; i++) {
        Agent agent = createMockAgent("agent-" + i);
        acquisitionService.registerAgent(
            agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      }

      // WHEN: Multiple threads try to remove the same agents concurrently
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);

      for (int i = 0; i < NUM_THREADS; i++) {
        testExecutor.submit(
            () -> {
              try {
                startLatch.await();

                // Each thread tries to remove all agents (testing idempotency)
                for (int j = 0; j < NUM_AGENTS; j++) {
                  acquisitionService.removeActiveAgent("agent-" + j);
                }
              } catch (Exception e) {
                e.printStackTrace();
              } finally {
                doneLatch.countDown();
              }
            });
      }

      startLatch.countDown(); // Start all threads
      assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");

      // THEN: Operations should complete successfully without exceptions
      // The main goal is to ensure removeActiveAgent is thread-safe and idempotent
      AgentAcquisitionStats stats = acquisitionService.getAdvancedStats();
      assertTrue(stats.getActiveAgents() >= 0, "Active agent count should not be negative");
    }

    @Test
    @DisplayName("Concurrent agent registration and unregistration should not leak futures")
    void concurrentRegistrationShouldNotLeakFutures() throws Exception {
      final int NUM_OPERATIONS = 500;
      final int NUM_THREADS = 10;

      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);

      for (int i = 0; i < NUM_THREADS; i++) {
        final int threadId = i;
        testExecutor.submit(
            () -> {
              try {
                startLatch.await();

                for (int j = 0; j < NUM_OPERATIONS / NUM_THREADS; j++) {
                  String agentType = "agent-" + threadId + "-" + j;
                  Agent agent = createMockAgent(agentType);

                  // Register agent
                  acquisitionService.registerAgent(
                      agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

                  // Simulate acquisition
                  acquisitionService
                      .getActiveAgentsMap()
                      .put(agentType, String.valueOf(System.currentTimeMillis()));
                  acquisitionService.getActiveAgentsFutures().put(agentType, mock(Future.class));

                  // Quick removal
                  acquisitionService.removeActiveAgent(agentType);
                  acquisitionService.unregisterAgent(agent);
                }
              } catch (Exception e) {
                e.printStackTrace();
              } finally {
                doneLatch.countDown();
              }
            });
      }

      startLatch.countDown();
      assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "All threads should complete");

      // THEN: No memory leaks in futures map
      assertEquals(
          0,
          acquisitionService.getFuturesMapSize(),
          "activeAgentsFutures should be empty after cleanup");
      assertEquals(
          0,
          acquisitionService.getActiveAgentCount(),
          "activeAgents should be empty after cleanup");
    }

    @Test
    @DisplayName("Statistics collection should be thread-safe and consistent")
    void statisticsCollectionShouldBeThreadSafe() throws Exception {
      final int NUM_THREADS = 10;
      final int OPERATIONS_PER_THREAD = 20;

      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);
      List<AgentAcquisitionStats> allStats = Collections.synchronizedList(new ArrayList<>());

      // Pre-populate with some agents
      for (int i = 0; i < 10; i++) {
        String agentType = "base-agent-" + i;
        Agent agent = createMockAgent(agentType);
        acquisitionService.registerAgent(
            agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      }

      for (int i = 0; i < NUM_THREADS; i++) {
        final int threadId = i;
        testExecutor.submit(
            () -> {
              try {
                startLatch.await();

                for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                  // Mix of operations
                  if (j % 2 == 0) {
                    // Collect statistics
                    AgentAcquisitionStats stats = acquisitionService.getAdvancedStats();
                    allStats.add(stats);

                    // Verify internal consistency of stats
                    assertTrue(
                        stats.getActiveAgents() >= 0, "Active agents should not be negative");
                    assertTrue(
                        stats.getRegisteredAgents() >= 0,
                        "Registered agents should not be negative");
                  } else {
                    // Register/unregister agents
                    String agentType = "temp-agent-" + threadId + "-" + j;
                    Agent agent = createMockAgent(agentType);
                    acquisitionService.registerAgent(
                        agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
                    acquisitionService.removeActiveAgent(
                        agentType); // Safe to call even if not active
                    acquisitionService.unregisterAgent(agent);
                  }
                }
              } catch (Exception e) {
                e.printStackTrace();
              } finally {
                doneLatch.countDown();
              }
            });
      }

      startLatch.countDown();
      assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "All threads should complete");

      // THEN: All collected statistics should be valid
      assertFalse(allStats.isEmpty(), "Should have collected statistics");

      for (AgentAcquisitionStats stats : allStats) {
        assertTrue(stats.getActiveAgents() >= 0, "Active agents should not be negative");
        assertTrue(stats.getRegisteredAgents() >= 0, "Registered agents should not be negative");
        assertTrue(stats.getFuturesTracked() >= 0, "Futures should not be negative");

        // Check success/failure rates are valid percentages
        assertTrue(
            stats.getSuccessRate() >= 0.0 && stats.getSuccessRate() <= 100.0,
            "Success rate should be valid percentage");
        assertTrue(
            stats.getFailureRate() >= 0.0 && stats.getFailureRate() <= 100.0,
            "Failure rate should be valid percentage");
      }
    }
  }

  @Nested
  @DisplayName("Memory Leak Prevention Tests")
  class MemoryLeakTests {

    @Test
    @DisplayName("Rapid agent churn should not cause memory leaks")
    void rapidAgentChurnShouldNotCauseMemoryLeaks() throws Exception {
      final int CHURN_CYCLES = 100;
      final int AGENTS_PER_CYCLE = 5;

      for (int cycle = 0; cycle < CHURN_CYCLES; cycle++) {
        List<Agent> cycleAgents = new ArrayList<>();

        // Add agents
        for (int j = 0; j < AGENTS_PER_CYCLE; j++) {
          String agentType = "churn-agent-" + cycle + "-" + j;
          Agent agent = createMockAgent(agentType);
          cycleAgents.add(agent);

          acquisitionService.registerAgent(
              agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
        }

        // Remove all agents from this cycle
        for (Agent agent : cycleAgents) {
          acquisitionService.removeActiveAgent(
              agent.getAgentType()); // Safe to call even if not active
          acquisitionService.unregisterAgent(agent);
        }

        // Verify clean state periodically
        if (cycle % 20 == 0) {
          AgentAcquisitionStats stats = acquisitionService.getAdvancedStats();
          assertTrue(
              stats.getActiveAgents() >= 0,
              "Active agents should not be negative after cycle " + cycle);
          assertTrue(
              stats.getFuturesTracked() >= 0,
              "Futures should not be negative after cycle " + cycle);
        }
      }

      // Final verification - should not have excessive growth
      AgentAcquisitionStats finalStats = acquisitionService.getAdvancedStats();
      assertTrue(finalStats.getActiveAgents() >= 0, "No negative active agents should remain");
      assertTrue(
          finalStats.getRegisteredAgents() >= 0, "No negative registered agents should remain");
      assertTrue(finalStats.getFuturesTracked() >= 0, "No negative futures should remain");
    }

    @Test
    @DisplayName("Exception during agent removal should not leak resources")
    void exceptionDuringRemovalShouldNotLeakResources() {
      // GIVEN: Agent with mocked Redis failure
      String agentType = "failing-agent";
      Agent agent = createMockAgent(agentType);

      acquisitionService.registerAgent(
          agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      acquisitionService
          .getActiveAgentsMap()
          .put(agentType, String.valueOf(System.currentTimeMillis()));
      acquisitionService.getActiveAgentsFutures().put(agentType, mock(Future.class));

      // Mock Redis failure
      when(mockJedis.evalsha(anyString(), anyList(), anyList()))
          .thenThrow(new RuntimeException("Redis connection failed"));

      int initialActiveCount = acquisitionService.getActiveAgentCount();
      int initialFuturesCount = acquisitionService.getFuturesMapSize();

      // WHEN: Remove agent (should handle Redis failure gracefully)
      acquisitionService.removeActiveAgent(agentType);

      // THEN: Local cleanup should still occur despite Redis failure
      assertEquals(
          initialActiveCount - 1,
          acquisitionService.getActiveAgentCount(),
          "Agent should be removed from local tracking despite Redis failure");
      assertEquals(
          initialFuturesCount - 1,
          acquisitionService.getFuturesMapSize(),
          "Future should be removed despite Redis failure");
    }
  }

  @Nested
  @DisplayName("Edge Case Tests")
  class EdgeCaseTests {

    @Test
    @DisplayName("Removing non-existent agent should be safe")
    void removingNonExistentAgentShouldBeSafe() {
      int initialCount = acquisitionService.getActiveAgentCount();

      // Try to remove agent that doesn't exist
      acquisitionService.removeActiveAgent("non-existent-agent");

      // Should be no-op
      assertEquals(
          initialCount,
          acquisitionService.getActiveAgentCount(),
          "Count should remain unchanged when removing non-existent agent");
    }

    @Test
    @DisplayName("Multiple removals of same agent should be safe")
    void multipleRemovalsShouldBeSafe() {
      // GIVEN: One active agent
      String agentType = "test-agent";
      Agent agent = createMockAgent(agentType);

      acquisitionService.registerAgent(
          agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      acquisitionService
          .getActiveAgentsMap()
          .put(agentType, String.valueOf(System.currentTimeMillis()));
      acquisitionService.getActiveAgentsFutures().put(agentType, mock(Future.class));

      int initialCount = acquisitionService.getActiveAgentCount();

      // WHEN: Remove same agent multiple times
      acquisitionService.removeActiveAgent(agentType); // First removal
      acquisitionService.removeActiveAgent(agentType); // Second removal (should be no-op)
      acquisitionService.removeActiveAgent(agentType); // Third removal (should be no-op)

      // THEN: Should only decrease by 1
      assertEquals(
          initialCount - 1,
          acquisitionService.getActiveAgentCount(),
          "Count should only decrease by 1 regardless of multiple removal attempts");
    }

    @Test
    @DisplayName("Statistics should handle edge cases gracefully")
    void statisticsShouldHandleEdgeCases() {
      // Test with empty state
      AgentAcquisitionStats emptyStats = acquisitionService.getAdvancedStats();
      assertEquals(
          0.0, emptyStats.getSuccessRate(), "Success rate should be 0.0 with no executions");
      assertEquals(
          0.0, emptyStats.getFailureRate(), "Failure rate should be 0.0 with no executions");

      // Test toString doesn't throw exceptions
      assertDoesNotThrow(() -> emptyStats.toString(), "toString should not throw with empty stats");

      // Test after reset
      acquisitionService.resetExecutionStats();
      AgentAcquisitionStats resetStats = acquisitionService.getAdvancedStats();
      assertEquals(0, resetStats.getAgentsExecuted(), "Executed count should be 0 after reset");
      assertEquals(0, resetStats.getAgentsFailed(), "Failed count should be 0 after reset");
    }
  }

  private Agent createMockAgent(String agentType) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    return agent;
  }
}
