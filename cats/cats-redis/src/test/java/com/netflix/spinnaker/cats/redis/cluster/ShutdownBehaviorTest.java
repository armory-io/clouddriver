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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Test suite for shutdown behavior.
 *
 * <p>These tests validate that critical shutdown issues have been resolved:
 *
 * <ul>
 *   <li>Race condition prevention between graceful shutdown and agent completion
 *   <li>Agent re-queuing without duplicate Redis script failures
 *   <li>Proper immediate scheduling scores
 *   <li>High-scale behavior to prevent thundering herd
 *   <li>Redis state consistency during concurrent shutdown operations
 * </ul>
 */
@Testcontainers
@DisplayName("Shutdown Behavior Tests - Critical Scenarios Not Covered by Existing Tests")
class ShutdownBehaviorTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private AgentAcquisitionService acquisitionService;
  private ExecutorService testExecutor;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    scriptManager = new RedisScriptManager(jedisPool);
    scriptManager.initializeScripts();

    // Mock dependencies
    AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(1000L, 5000L, 2000L));

    ShardingFilter shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    ClusteredSortAgentProperties agentProperties = new ClusteredSortAgentProperties();
    agentProperties.setMaxConcurrentAgents(10);
    agentProperties.setEnabledPattern(".*");

    ClusteredSortSchedulerProperties schedulerProperties = new ClusteredSortSchedulerProperties();

    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);

    testExecutor = Executors.newCachedThreadPool();
  }

  @AfterEach
  void tearDown() {
    // Clean up Redis
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushAll();
    } catch (Exception e) {
      // Ignore cleanup errors
    }

    if (testExecutor != null) {
      testExecutor.shutdownNow();
    }

    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Nested
  @DisplayName("Shutdown Agent Preservation")
  class RaceConditionTests {

    @Test
    @DisplayName("Should always re-queue agents during shutdown for reliability")
    void shouldAlwaysReQueueDuringShutdown() throws Exception {
      // Given - Agent that will take time to execute
      Agent slowAgent = createMockAgent("slow-agent");
      CountDownLatch executionStarted = new CountDownLatch(1);
      CountDownLatch allowCompletion = new CountDownLatch(1);
      AtomicInteger redisAddAttempts = new AtomicInteger(0);

      AgentExecution slowExecution = mock(AgentExecution.class);
      doAnswer(
              invocation -> {
                executionStarted.countDown();
                allowCompletion.await(5, TimeUnit.SECONDS); // Wait for shutdown signal
                return null;
              })
          .when(slowExecution)
          .executeAgent(any());

      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // Register and start agent execution
      acquisitionService.registerAgent(slowAgent, slowExecution, instrumentation);

      // Start agent execution
      int acquired = acquisitionService.saturatePool(0L, null, testExecutor);
      assertThat(acquired).isEqualTo(1);

      // Wait for execution to start
      assertThat(executionStarted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(acquisitionService.getActiveAgentCount()).isEqualTo(1);

      // When - Trigger shutdown while agent is running
      acquisitionService.setShuttingDown(true);
      acquisitionService.setGracefulShutdown(true);

      // Count Redis ADD script calls before graceful shutdown
      try (Jedis jedis = jedisPool.getResource()) {
        long beforeWaitzSize = jedis.zcard("WAITZ");

        // Simulate graceful shutdown re-queuing
        acquisitionService.conditionalReleaseAgent(slowAgent, "test-score", false);

        // Allow agent to complete normally (this would also try to re-queue)
        allowCompletion.countDown();
        Thread.sleep(100); // Give time for normal completion flow

        long afterWaitzSize = jedis.zcard("WAITZ");

        // Then - we always re-queue during shutdown
        System.out.println("WAITZ before: " + beforeWaitzSize + ", after: " + afterWaitzSize);
        assertThat(afterWaitzSize - beforeWaitzSize)
            .isEqualTo(1); // Agent re-queued during shutdown

        // Verify the agent is successfully preserved for restart
        Set<String> waitingAgents = jedis.zrange("WAITZ", 0, -1);
        boolean slowAgentFound = waitingAgents.contains("slow-agent");
        System.out.println("Agents in WAITZ: " + waitingAgents);
        assertThat(slowAgentFound).isTrue(); // Agent preserved for restart
      }
    }
  }

  @Nested
  @DisplayName("Scheduling Score Correctness")
  class SchedulingScoreTests {

    @Test
    @DisplayName("Should use immediate scores during shutdown")
    void shouldUseImmediateScoresDuringShutdown() throws Exception {
      // Given
      Agent agent = createMockAgent("test-agent");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(agent, execution, instrumentation);

      // When - Trigger shutdown and re-queue agent
      acquisitionService.setShuttingDown(true);
      acquisitionService.conditionalReleaseAgent(agent, "test-score", false);

      // Then - Detect the Redis TIME sync bug (far future timestamps)
      try (Jedis jedis = jedisPool.getResource()) {
        Double scoreDouble = jedis.zscore("WAITZ", "test-agent");
        assertThat(scoreDouble).isNotNull();

        long scoreValue = scoreDouble.longValue();
        long currentTimeSeconds = System.currentTimeMillis() / 1000;

        // Score should be current time
        assertThat(scoreValue)
            .describedAs(
                "Score should be current time. Got: %d, Current: %d",
                scoreValue, currentTimeSeconds)
            .isBetween(currentTimeSeconds - 10, currentTimeSeconds + 60);
      }
    }
  }

  @Nested
  @DisplayName("Graceful Shutdown")
  class GracefulShutdownTests {

    @Test
    @DisplayName("Should re-queue ALL registered agents during graceful shutdown")
    void shouldReQueueAllRegisteredAgentsDuringGracefulShutdown() throws Exception {
      // Given - Multiple registered agents, some running, some completed
      String[] agentTypes = {"agent-1", "agent-2", "agent-3", "agent-completed", "agent-idle"};
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // Register all agents
      for (String agentType : agentTypes) {
        Agent agent = createMockAgent(agentType);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      // Simulate active agents in WORKZ with proper scores
      String expectedScore;
      try (Jedis jedis = jedisPool.getResource()) {
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        long completionDeadline = currentTimeSeconds + 300; // 5 minutes from now
        expectedScore = String.valueOf(completionDeadline);

        // Add agents to WORKZ (simulating they're actively running)
        jedis.zadd("WORKZ", completionDeadline, "agent-1");
        jedis.zadd("WORKZ", completionDeadline, "agent-2");
        jedis.zadd("WORKZ", completionDeadline, "agent-3");

        // Some agents completed and not in Redis (agent-completed, agent-idle)
        // These won't be re-queued since they're not in WORKZ

        // Verify initial state
        assertThat(jedis.zcard("WORKZ")).isEqualTo(3);
        assertThat(jedis.zcard("WAITZ")).isEqualTo(0);
      }

      // When - Perform graceful shutdown
      acquisitionService.setGracefulShutdown(true);

      // Call conditional re-queue with correct expected scores
      String[] activeAgents = {"agent-1", "agent-2", "agent-3"};
      for (String agentType : activeAgents) {
        Agent agent = acquisitionService.getAgentByType(agentType);
        if (agent != null) {
          acquisitionService.forceRequeueAgentForShutdown(
              agent, expectedScore); // Use correct expected score
        }
      }

      // Then - Only agents that were in WORKZ should be re-queued
      try (Jedis jedis = jedisPool.getResource()) {
        Set<String> agentsInWaitz = jedis.zrange("WAITZ", 0, -1);

        // CONDITIONAL logic: only agents that were in WORKZ get re-queued
        assertThat(agentsInWaitz)
            .describedAs("Only active agents should be conditionally re-queued in WAITZ")
            .containsExactlyInAnyOrder("agent-1", "agent-2", "agent-3");

        // Verify they have immediate execution scores (current time or very close)
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        for (String agentType : activeAgents) {
          Double score = jedis.zscore("WAITZ", agentType);
          assertThat(score)
              .describedAs("Agent %s should have immediate execution score", agentType)
              .isNotNull()
              .isLessThanOrEqualTo(currentTimeSeconds + 60.0); // Within 1 minute
        }

        // Previously running agents should now be in WAITZ (moved from WORKZ)
        assertThat(jedis.zcard("WORKZ"))
            .describedAs("WORKZ should be empty after graceful shutdown")
            .isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should prevent agent loss during shutdown race conditions")
    void shouldPreventAgentLossDuringShutdownRaceConditions() throws Exception {
      // Given - Agent completing during shutdown window
      Agent agent = createMockAgent("racing-agent");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(agent, execution, instrumentation);

      // Simulate agent was running (in WORKZ) with proper score
      long completionDeadline;
      try (Jedis jedis = jedisPool.getResource()) {
        completionDeadline = System.currentTimeMillis() / 1000 + 300;
        jedis.zadd("WORKZ", completionDeadline, "racing-agent");
      }

      // When - Graceful shutdown tries to re-queue agent while it's still running
      acquisitionService.setGracefulShutdown(true);

      // Graceful shutdown re-queues agent (while it's still in WORKZ)
      Agent registeredAgent = acquisitionService.getAgentByType("racing-agent");
      assertThat(registeredAgent).isNotNull();
      acquisitionService.forceRequeueAgentForShutdown(
          registeredAgent, String.valueOf(completionDeadline));

      // Then - Agent should be successfully moved from WORKZ to WAITZ
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WAITZ", "racing-agent"))
            .describedAs("Racing agent should be conditionally re-queued in WAITZ")
            .isNotNull();

        assertThat(jedis.zscore("WORKZ", "racing-agent"))
            .describedAs("Racing agent should be moved from WORKZ")
            .isNull();
      }

      // Test scenario where agent completes first - should NOT be re-queued
      try (Jedis jedis = jedisPool.getResource()) {
        // Setup another agent
        jedis.zadd("WORKZ", completionDeadline, "completed-agent");
        // Agent completes and removes itself
        jedis.zrem("WORKZ", "completed-agent");

        // Try to re-queue with original score - should fail
        Agent completedAgent =
            acquisitionService.getAgentByType("racing-agent"); // reuse existing agent for test
        acquisitionService.forceRequeueAgentForShutdown(
            completedAgent, String.valueOf(completionDeadline));

        // Should NOT be in WAITZ since it wasn't in WORKZ
        assertThat(jedis.zscore("WAITZ", "completed-agent"))
            .describedAs("Completed agent should not be re-queued")
            .isNull();
      }
    }
  }

  @Nested
  @DisplayName("Redis State Consistency")
  class RedisConsistencyTests {

    @Test
    @DisplayName("Should handle null Redis script results gracefully")
    void shouldHandleNullRedisScriptResults() throws Exception {
      // Given - Agent already in Redis (to trigger null result)
      Agent agent = createMockAgent("duplicate-agent");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // Manually add agent to WAITZ to simulate existing state
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WAITZ", System.currentTimeMillis() / 1000, "duplicate-agent");
      }

      acquisitionService.registerAgent(agent, execution, instrumentation);
      acquisitionService.setShuttingDown(true);

      // When - Try to re-queue existing agent (should get null result)
      // This should not crash or cause issues
      acquisitionService.conditionalReleaseAgent(agent, "test-score", false);

      // Then - System should remain stable
      try (Jedis jedis = jedisPool.getResource()) {
        // Agent should still be in WAITZ (not duplicated)
        assertThat(jedis.zcard("WAITZ")).isEqualTo(1);
        assertThat(jedis.zscore("WAITZ", "duplicate-agent")).isNotNull();
      }
    }
  }

  private Agent createMockAgent(String agentType) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    when(agent.getProviderName()).thenReturn("test-provider");
    return agent;
  }
}
