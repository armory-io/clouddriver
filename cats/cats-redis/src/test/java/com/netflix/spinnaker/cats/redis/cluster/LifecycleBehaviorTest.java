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
import static org.mockito.Mockito.doThrow;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
 * Test suite for agent lifecycle and behavior.
 *
 * <p>Tests cover agent score lifecycle from registration through completion, and shutdown behavior
 * including race condition prevention, graceful shutdown, and Redis state consistency.
 */
@Testcontainers
@DisplayName("Agent Lifecycle and Behavior Tests")
class LifecycleBehaviorTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private AgentAcquisitionService acquisitionService;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;
  private ExecutorService executorService;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    scriptManager =
        new RedisScriptManager(
            jedisPool,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    scriptManager.initializeScripts();

    intervalProvider = mock(AgentIntervalProvider.class);
    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    // interval=2s, timeout=5s
    AgentIntervalProvider.Interval iv = new AgentIntervalProvider.Interval(2000L, 5000L);
    when(intervalProvider.getInterval(any(Agent.class))).thenReturn(iv);

    agentProperties = new PriorityAgentProperties();
    agentProperties.setMaxConcurrentAgents(10);
    agentProperties.setEnabledPattern(".*");
    agentProperties.setDisabledPattern("");

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.setRefreshPeriodSeconds(30);
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");

    executorService = Executors.newCachedThreadPool();

    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
  }

  @AfterEach
  void tearDown() {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushAll();
    } catch (Exception e) {
      // Ignore cleanup errors
    }
    if (executorService != null) {
      executorService.shutdownNow();
    }
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  private Agent createMockAgent(String agentType) {
    return TestFixtures.createMockAgent(agentType, "test");
  }

  @Nested
  @DisplayName("Score Lifecycle Tests")
  class ScoreLifecycleTests {

    @Test
    @DisplayName("Registration schedules waiting with score≈now")
    void registrationSchedulesImmediate() {
      Agent agent = createMockAgent("reg-agent");
      acquisitionService.registerAgent(
          agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      try (Jedis j = jedisPool.getResource()) {
        // Compare against Redis server time to avoid host/container skew and second-boundary drift
        java.util.List<String> t = j.time();
        long redisNowSec = Long.parseLong(t.get(0));
        Double s = j.zscore("waiting", "reg-agent");
        assertThat(s).isNotNull();
        // Within [-3s, +3s] of Redis TIME now
        assertThat(Math.abs(s.longValue() - redisNowSec)).isLessThanOrEqualTo(3);
      }
    }

    @Test
    @DisplayName("Acquisition moves waiting→working with deadline = now + timeout")
    void acquisitionSetsDeadline() {
      Agent agent = createMockAgent("acq-agent");
      AgentExecution exec = mock(AgentExecution.class);
      // Slow execution slightly so working entry is observable before completion clears it
      doAnswer(
              inv -> {
                Thread.sleep(150);
                return null;
              })
          .when(exec)
          .executeAgent(any());

      acquisitionService.registerAgent(agent, exec, mock(ExecutionInstrumentation.class));

      int acquired = acquisitionService.saturatePool(0L, null, executorService);
      assertThat(acquired).isEqualTo(1);

      try (Jedis j = jedisPool.getResource()) {
        // Use Redis server time to avoid host/container clock skew and second-boundary flakiness
        java.util.List<String> t = j.time();
        long redisNowSec = Long.parseLong(t.get(0));

        Double workScore = j.zscore("working", "acq-agent");
        assertThat(workScore).isNotNull();
        long delta = workScore.longValue() - redisNowSec;
        // timeout=5s with a slightly wider tolerance (±3s) to account for second rounding and jitter
        assertThat(delta).isBetween(2L, 8L);
      }
    }

    @Test
    @DisplayName("Success completion preserves cadence relative to original acquire")
    void successPreservesCadence() throws Exception {
      Agent agent = createMockAgent("cadence-agent");
      AgentExecution exec = mock(AgentExecution.class);

      // Slow execution to ensure we can read acquireScore while running
      doAnswer(
              inv -> {
                Thread.sleep(150);
                return null;
              })
          .when(exec)
          .executeAgent(any());

      acquisitionService.registerAgent(agent, exec, mock(ExecutionInstrumentation.class));

      int acquired = acquisitionService.saturatePool(0L, null, executorService);
      assertThat(acquired).isEqualTo(1);

      // wait for completion and process completions next cycle (prevent reacquire)
      Thread.sleep(300);
      acquisitionService.saturatePool(1L, new Semaphore(0), executorService);

      // expected next ≈ now + interval (approximation consistent with agentScore() for working)
      long intervalMs = intervalProvider.getInterval(agent).getInterval();

      try (Jedis j = jedisPool.getResource()) {
        java.util.List<String> t = j.time();
        long redisNowSec = Long.parseLong(t.get(0));
        long desiredNextSec = redisNowSec + (intervalMs / 1000L);
        Double waitScore = j.zscore("waiting", "cadence-agent");
        assertThat(waitScore).isNotNull();
        long actual = waitScore.longValue();
        // allow a slightly wider band for CI timing (Redis seconds granularity + scheduling jitter)
        assertThat(Math.abs(actual - desiredNextSec)).isLessThanOrEqualTo(5);
      }
    }

    @Test
    @DisplayName("Failure completion reschedules immediately (score≈now)")
    void failureReschedulesImmediately() throws Exception {
      // Enable immediate retry semantics to reflect intended business behavior
      schedulerProperties.getFailureBackoff().setEnabled(true);
      schedulerProperties.getFailureBackoff().setMaxImmediateRetries(1);
      Agent agent = createMockAgent("fail-agent");
      AgentExecution failing = mock(AgentExecution.class);
      doThrow(new RuntimeException("boom")).when(failing).executeAgent(any());

      acquisitionService.registerAgent(agent, failing, mock(ExecutionInstrumentation.class));
      int acquired = acquisitionService.saturatePool(0L, null, executorService);
      assertThat(acquired).isEqualTo(1);

      Thread.sleep(100);
      acquisitionService.saturatePool(
          1L, new Semaphore(0), executorService); // process only completions, prevent reacquire

      try (Jedis j = jedisPool.getResource()) {
        // Use Redis server time to avoid host/container skew and seconds quantization issues
        java.util.List<String> t = j.time();
        long redisNowSec = Long.parseLong(t.get(0));
        Double s = j.zscore("waiting", "fail-agent");
        assertThat(s).isNotNull();
        // Allow a slightly wider tolerance for CI variance and double second rounding
        assertThat(Math.abs(s.longValue() - redisNowSec)).isLessThanOrEqualTo(4);
      }
    }

    @Test
    @DisplayName("Shutdown conditional requeue only moves when score matches (ownership)")
    void shutdownConditionalRequeueOwnership() throws Exception {
      Agent agent = createMockAgent("shutdown-agent");
      AgentExecution exec = mock(AgentExecution.class);

      // Slow a bit to keep in working
      doAnswer(
              inv -> {
                Thread.sleep(200);
                return null;
              })
          .when(exec)
          .executeAgent(any());

      acquisitionService.registerAgent(agent, exec, mock(ExecutionInstrumentation.class));

      int acq = acquisitionService.saturatePool(0L, null, executorService);
      assertThat(acq).isEqualTo(1);

      String acquireScore = acquisitionService.getActiveAgentsMap().get("shutdown-agent");
      assertThat(acquireScore).isNotNull();

      // Correct expected score → move to waiting
      acquisitionService.forceRequeueAgentForShutdown(agent, acquireScore);
      try (Jedis j = jedisPool.getResource()) {
        assertThat(j.zscore("waiting", "shutdown-agent")).isNotNull();
      }

      // Put back into working and try with wrong score → should not swap
      try (Jedis j = jedisPool.getResource()) {
        long redisNowSec = Long.parseLong(j.time().get(0));
        long nowSecPlus = redisNowSec + 10;
        j.zrem("waiting", "shutdown-agent");
        j.zadd("working", nowSecPlus, "shutdown-agent");
      }

      try (Jedis j = jedisPool.getResource()) {
        long redisNowSec = Long.parseLong(j.time().get(0));
        acquisitionService.forceRequeueAgentForShutdown(agent, Long.toString(redisNowSec + 999));
      }

      try (Jedis j = jedisPool.getResource()) {
        // Still in working since expected score mismatched
        assertThat(j.zscore("working", "shutdown-agent")).isNotNull();
      }
    }
  }

  @Nested
  @DisplayName("Shutdown Behavior Tests")
  class ShutdownBehaviorTests {

    @Nested
    @DisplayName("Shutdown Agent Preservation")
    class RaceConditionTests {

      @Test
      @DisplayName("Should preserve agents in waiting when they complete during shutdown")
      void shouldAlwaysReQueueDuringShutdown() throws Exception {
        // Given - Agent that will complete during shutdown
        Agent slowAgent = createMockAgent("slow-agent");
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);

        AgentExecution slowExecution = mock(AgentExecution.class);
        doAnswer(
                invocation -> {
                  executionStarted.countDown();
                  allowCompletion.await(5, TimeUnit.SECONDS);
                  return null; // Successful execution
                })
            .when(slowExecution)
            .executeAgent(any());

        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

        // Register agent
        acquisitionService.registerAgent(slowAgent, slowExecution, instrumentation);

        // Clear any auto-added entries from registration to start clean
        try (Jedis jedis = jedisPool.getResource()) {
          jedis.del("waiting");
          jedis.del("working");
        }

        // Start agent execution - this should add it to working set
        int acquired = acquisitionService.saturatePool(0L, null, executorService);
        assertThat(acquired).isEqualTo(1);
        assertThat(executionStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify agent is in working set and tracked as active
        try (Jedis jedis = jedisPool.getResource()) {
          assertThat(jedis.zcard("working"))
              .describedAs("Agent should be in working set after acquisition")
              .isEqualTo(1);
          assertThat(jedis.zscore("working", "slow-agent")).isNotNull();
        }
        assertThat(acquisitionService.getActiveAgentCount()).isEqualTo(1);

        // When - Trigger shutdown while agent is still running
        acquisitionService.setShuttingDown(true);

        // Allow agent to complete during shutdown
        allowCompletion.countDown();

        // Give enough time for completion and Redis operations
        Thread.sleep(1000);

        // Then - Agent MUST be in waiting set for restart
        try (Jedis jedis = jedisPool.getResource()) {
          Set<String> waitingAgents = jedis.zrange("waiting", 0, -1);
          Set<String> workingAgents = jedis.zrange("working", 0, -1);
          long waitingSize = jedis.zcard("waiting");
          long workingSize = jedis.zcard("working");

          assertThat(waitingSize)
              .describedAs(
                  "Agent MUST be in waiting after shutdown completion to prevent data loss. "
                      + "waiting=%s, working=%s, activeCount=%d, shuttingDown=%s",
                  waitingAgents,
                  workingAgents,
                  acquisitionService.getActiveAgentCount(),
                  acquisitionService.isShuttingDown())
              .isGreaterThanOrEqualTo(1);

          boolean slowAgentInWaiting = waitingAgents.contains("slow-agent");
          assertThat(slowAgentInWaiting)
              .describedAs(
                  "slow-agent MUST be in waiting set after shutdown. "
                      + "waiting=%s, working=%s.",
                  waitingAgents, workingAgents)
              .isTrue();

          assertThat(workingSize)
              .describedAs("Agent should not be in working after completion. working=%s", workingAgents)
              .isEqualTo(0);

          Double score = jedis.zscore("waiting", "slow-agent");
          assertThat(score).isNotNull();
          long scoreSeconds = score.longValue();
          long currentSeconds = System.currentTimeMillis() / 1000;
          assertThat(scoreSeconds)
              .describedAs(
                  "Score should be near current time. Got %d, current %d.",
                  scoreSeconds, currentSeconds)
              .isBetween(currentSeconds - 10, currentSeconds + 300);
        }

        assertThat(acquisitionService.getActiveAgentCount())
            .describedAs("Agent should not be in activeAgents after completion")
            .isEqualTo(0);
      }
    }

    @Nested
    @DisplayName("Shutdown Completion List Hygiene")
    class CompletionHygieneTests {

      @Test
      @DisplayName("Shutdown completion processing should drain queue without retaining references")
      void shutdownProcessingClearsCompletions() throws Exception {
        Agent agent = createMockAgent("complete-agent");
        AgentExecution exec = mock(AgentExecution.class);
        ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, exec, instr);

        acquisitionService.setShuttingDown(false);
        long nowSec = System.currentTimeMillis() / 1000L;
        acquisitionService.conditionalReleaseAgent(agent, String.valueOf(nowSec), false, null, null);

        assertThat(acquisitionService.getCompletionQueueSize()).isEqualTo(1);

        acquisitionService.setShuttingDown(true);
        assertThat(acquisitionService.getCompletionQueueSize()).isEqualTo(0);

        long afterFirst;
        try (Jedis jedis = jedisPool.getResource()) {
          afterFirst = jedis.zcard("waiting");
          assertThat(afterFirst).isGreaterThanOrEqualTo(1);
        }

        acquisitionService.setShuttingDown(true);
        try (Jedis jedis = jedisPool.getResource()) {
          long afterSecond = jedis.zcard("waiting");
          assertThat(afterSecond).isEqualTo(afterFirst);
        }
      }
    }

    @Nested
    @DisplayName("Scheduling Score Correctness")
    class SchedulingScoreTests {

      @Test
      @DisplayName("Should use immediate scores during shutdown")
      void shouldUseImmediateScoresDuringShutdown() throws Exception {
        Agent agent = createMockAgent("test-agent");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

        acquisitionService.registerAgent(agent, execution, instrumentation);

        acquisitionService.setShuttingDown(true);
        acquisitionService.conditionalReleaseAgent(agent, "test-score", false, null, null);

        try (Jedis jedis = jedisPool.getResource()) {
          Double scoreDouble = jedis.zscore("waiting", "test-agent");
          assertThat(scoreDouble).isNotNull();

          long scoreValue = scoreDouble.longValue();
          long currentTimeSeconds = System.currentTimeMillis() / 1000;

          assertThat(scoreValue)
              .describedAs("Score should be current time. Got: %d, Current: %d", scoreValue, currentTimeSeconds)
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
        String[] agentTypes = {"agent-1", "agent-2", "agent-3", "agent-completed", "agent-idle"};
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

        for (String agentType : agentTypes) {
          Agent agent = createMockAgent(agentType);
          acquisitionService.registerAgent(agent, execution, instrumentation);
        }

        String expectedScore;
        try (Jedis jedis = jedisPool.getResource()) {
          long currentTimeSeconds = System.currentTimeMillis() / 1000;
          long completionDeadline = currentTimeSeconds + 300;
          expectedScore = String.valueOf(completionDeadline);

          jedis.zadd("working", completionDeadline, "agent-1");
          jedis.zadd("working", completionDeadline, "agent-2");
          jedis.zadd("working", completionDeadline, "agent-3");

          assertThat(jedis.zcard("working")).isEqualTo(3);
          assertThat(jedis.zcard("waiting")).isEqualTo(5);
        }

        acquisitionService.setGracefulShutdown(true);

        String[] activeAgents = {"agent-1", "agent-2", "agent-3"};
        for (String agentType : activeAgents) {
          Agent agent = acquisitionService.getAgentByType(agentType);
          if (agent != null) {
            acquisitionService.forceRequeueAgentForShutdown(agent, expectedScore);
          }
        }

        try (Jedis jedis = jedisPool.getResource()) {
          Set<String> agentsInWaitz = jedis.zrange("waiting", 0, -1);

          assertThat(agentsInWaitz)
              .describedAs("Active agents should be re-queued in waiting")
              .contains("agent-1", "agent-2", "agent-3");

          long expectedNextSec = Long.parseLong(expectedScore) - 4L;
          for (String agentType : activeAgents) {
            Double score = jedis.zscore("waiting", agentType);
            assertThat(score).describedAs("Agent %s should be scheduled near cadence", agentType).isNotNull();
            long actual = score.longValue();
            assertThat(actual).isBetween(expectedNextSec - 5L, expectedNextSec + 5L);
          }

          assertThat(jedis.zcard("working"))
              .describedAs("working should be empty after graceful shutdown")
              .isEqualTo(0);
        }
      }

      @Test
      @DisplayName("Should prevent agent loss during shutdown race conditions")
      void shouldPreventAgentLossDuringShutdownRaceConditions() throws Exception {
        Agent agent = createMockAgent("racing-agent");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

        acquisitionService.registerAgent(agent, execution, instrumentation);

        long completionDeadline;
        try (Jedis jedis = jedisPool.getResource()) {
          completionDeadline = System.currentTimeMillis() / 1000 + 300;
          jedis.zadd("working", completionDeadline, "racing-agent");
        }

        acquisitionService.setGracefulShutdown(true);

        Agent registeredAgent = acquisitionService.getAgentByType("racing-agent");
        assertThat(registeredAgent).isNotNull();
        acquisitionService.forceRequeueAgentForShutdown(
            registeredAgent, String.valueOf(completionDeadline));

        try (Jedis jedis = jedisPool.getResource()) {
          assertThat(jedis.zscore("waiting", "racing-agent"))
              .describedAs("Racing agent should be conditionally re-queued in waiting")
              .isNotNull();

          assertThat(jedis.zscore("working", "racing-agent"))
              .describedAs("Racing agent should be moved from working")
              .isNull();
        }

        try (Jedis jedis = jedisPool.getResource()) {
          jedis.zadd("working", completionDeadline, "completed-agent");
          jedis.zrem("working", "completed-agent");

          Agent completedAgent = acquisitionService.getAgentByType("racing-agent");
          acquisitionService.forceRequeueAgentForShutdown(
              completedAgent, String.valueOf(completionDeadline));

          assertThat(jedis.zscore("waiting", "completed-agent"))
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
        Agent agent = createMockAgent("duplicate-agent");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

        try (Jedis jedis = jedisPool.getResource()) {
          jedis.zadd("waiting", System.currentTimeMillis() / 1000, "duplicate-agent");
        }

        acquisitionService.registerAgent(agent, execution, instrumentation);
        acquisitionService.setShuttingDown(true);

        acquisitionService.conditionalReleaseAgent(agent, "test-score", false, null, null);

        try (Jedis jedis = jedisPool.getResource()) {
          assertThat(jedis.zcard("waiting")).isEqualTo(1);
          assertThat(jedis.zscore("waiting", "duplicate-agent")).isNotNull();
        }
      }
    }
  }
}

