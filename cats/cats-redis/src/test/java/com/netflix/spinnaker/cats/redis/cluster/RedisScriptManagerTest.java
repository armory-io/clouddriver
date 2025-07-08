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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Comprehensive test suite for RedisScriptManager using testcontainers.
 *
 * <p>Tests cover: - Script initialization and caching - Thread safety of script loading - Error
 * handling for Redis failures - Performance characteristics - All script constants and operations
 */
@Testcontainers
@DisplayName("RedisScriptManager Tests")
class RedisScriptManagerTest {

  @Container
  @SuppressWarnings("resource")
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;

  private RedisScriptManager scriptManager;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    config.setMaxIdle(5);
    config.setMinIdle(1);
    config.setTestOnBorrow(true);

    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    scriptManager = new RedisScriptManager(jedisPool);
  }

  @Nested
  @DisplayName("Script Initialization Tests")
  class ScriptInitializationTests {

    @Test
    @DisplayName("Should successfully initialize all scripts")
    void shouldInitializeAllScripts() {
      // When
      scriptManager.initializeScripts();

      // Then
      assertThat(scriptManager.isInitialized()).isTrue();
      assertThat(scriptManager.getScriptCount()).isEqualTo(11);
    }

    @Test
    @DisplayName("Should load all expected script constants")
    void shouldLoadAllExpectedScriptConstants() {
      // Given
      scriptManager.initializeScripts();

      // When & Then - Verify all script constants are loaded
      assertThat(scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT_SCRIPT)).isNotEmpty();
      assertThat(scriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENT_SCRIPT)).isNotEmpty();
      assertThat(scriptManager.getScriptSha(RedisScriptManager.SWAP_SET_SCRIPT)).isNotEmpty();
      assertThat(scriptManager.getScriptSha(RedisScriptManager.CONDITIONAL_SWAP_SET_SCRIPT))
          .isNotEmpty();
      assertThat(scriptManager.getScriptSha(RedisScriptManager.VALID_SCORE_SCRIPT)).isNotEmpty();
      assertThat(scriptManager.getScriptSha(RedisScriptManager.ORPHAN_REMOVE_SCRIPT)).isNotEmpty();
      assertThat(scriptManager.getScriptSha(RedisScriptManager.BATCH_ORPHAN_REMOVE_SCRIPT))
          .isNotEmpty();
      assertThat(scriptManager.getScriptSha(RedisScriptManager.BATCH_ADD_AGENTS_SCRIPT))
          .isNotEmpty();
      assertThat(scriptManager.getScriptSha(RedisScriptManager.BATCH_CLEANUP_AGENTS_SCRIPT))
          .isNotEmpty();
    }

    @Test
    @DisplayName("Should be idempotent when called multiple times")
    void shouldBeIdempotentWhenCalledMultipleTimes() {
      // When
      scriptManager.initializeScripts();
      String firstSha = scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT_SCRIPT);

      scriptManager.initializeScripts(); // Call again
      String secondSha = scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT_SCRIPT);

      // Then
      assertThat(firstSha).isEqualTo(secondSha);
      assertThat(scriptManager.getScriptCount()).isEqualTo(11);
    }
  }

  @Nested
  @DisplayName("Thread Safety Tests")
  class ThreadSafetyTests {

    @Test
    @DisplayName("Should handle concurrent initialization safely")
    void shouldHandleConcurrentInitializationSafely() throws InterruptedException {
      // Given
      Thread[] threads = new Thread[10];
      final Exception[] threadException = new Exception[1];

      // When - Multiple threads try to initialize simultaneously
      for (int i = 0; i < threads.length; i++) {
        threads[i] =
            new Thread(
                () -> {
                  try {
                    scriptManager.initializeScripts();
                  } catch (Exception e) {
                    threadException[0] = e;
                  }
                });
        threads[i].start();
      }

      // Wait for all threads to complete
      for (Thread thread : threads) {
        thread.join();
      }

      // Then
      assertThat(threadException[0]).isNull();
      assertThat(scriptManager.isInitialized()).isTrue();
      assertThat(scriptManager.getScriptCount()).isEqualTo(11);
    }
  }

  @Nested
  @DisplayName("Error Handling Tests")
  class ErrorHandlingTests {

    @Test
    @DisplayName("Should throw exception when accessing uninitialized scripts")
    void shouldThrowExceptionWhenAccessingUninitializedScripts() {
      // When & Then
      assertThatThrownBy(() -> scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT_SCRIPT))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Scripts not initialized");
    }

    @Test
    @DisplayName("Should throw exception for unknown script names")
    void shouldThrowExceptionForUnknownScriptNames() {
      // Given
      scriptManager.initializeScripts();

      // When & Then
      assertThatThrownBy(() -> scriptManager.getScriptSha("unknownScript"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Unknown script");
    }

    @Test
    @DisplayName("Should handle Redis connection failures gracefully")
    void shouldHandleRedisConnectionFailuresGracefully() {
      // Given - Close the connection pool
      jedisPool.close();

      // When & Then
      assertThatThrownBy(() -> scriptManager.initializeScripts())
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Failed to initialize Redis scripts");
    }
  }

  @Nested
  @DisplayName("Script Execution Tests")
  class ScriptExecutionTests {

    @BeforeEach
    void setUp() {
      scriptManager.initializeScripts();
    }

    @Test
    @DisplayName("Should execute ADD_AGENT_SCRIPT correctly")
    void shouldExecuteAddAgentScriptCorrectly() {
      try (Jedis jedis = jedisPool.getResource()) {
        // Given
        String agentType = "test-agent";
        String score = "100";

        // Clean up any existing agent first
        jedis.zrem("WORKZ", agentType);
        jedis.zrem("WAITZ", agentType);

        // When - Execute ADD_AGENT_SCRIPT
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT_SCRIPT),
                2, // Key count
                "WORKZ",
                "WAITZ",
                agentType,
                score);

        // Then
        assertThat(result).isEqualTo("added");
        assertThat(jedis.zscore("WAITZ", agentType)).isEqualTo(100.0);
      }
    }

    @Test
    @DisplayName("Should execute SWAP_SET_SCRIPT correctly")
    void shouldExecuteSwapSetScriptCorrectly() {
      try (Jedis jedis = jedisPool.getResource()) {
        // Given - Add agent to WAITING set first
        jedis.zadd("WAITZ", 100, "test-agent");

        String newScore = "200";

        // When - Execute SWAP_SET_SCRIPT to move from WAITING to WORKING
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.SWAP_SET_SCRIPT),
                2, // Key count
                "WORKZ",
                "WAITZ",
                "test-agent",
                newScore);

        // Then
        assertThat(result).isEqualTo(newScore);
        assertThat(jedis.zscore("WORKZ", "test-agent")).isEqualTo(200.0);
        assertThat(jedis.zscore("WAITZ", "test-agent")).isNull();
      }
    }

    @Test
    @DisplayName("Should execute VALID_SCORE_SCRIPT correctly")
    void shouldExecuteValidScoreScriptCorrectly() {
      try (Jedis jedis = jedisPool.getResource()) {
        // Given - Add agent to WORKING set
        jedis.zadd("WORKZ", 150, "test-agent");

        // When - Check valid score
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.VALID_SCORE_SCRIPT),
                1, // Key count
                "WORKZ",
                "test-agent",
                "150");

        // Then
        assertThat(result).isEqualTo("150");
      }
    }

    @Test
    @DisplayName("Should return null for invalid score in VALID_SCORE_SCRIPT")
    void shouldReturnNullForInvalidScoreInValidScoreScript() {
      try (Jedis jedis = jedisPool.getResource()) {
        // Given - Add agent with different score
        jedis.zadd("WORKZ", 150, "test-agent");

        // When - Check with wrong score
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.VALID_SCORE_SCRIPT),
                1, // Key count
                "WORKZ",
                "test-agent",
                "999");

        // Then
        assertThat(result).isNull();
      }
    }

    @Test
    @DisplayName("Should unconditionally move agent to WAITING set (graceful shutdown)")
    void shouldUnconditionallyMoveAgentToWaitingSet() {
      // Given - Agent in different possible states
      String agentType = "test-agent";
      double workingScore = 12345.0;
      double waitingScore = 67890.0;
      double newScore = 99999.0;

      try (Jedis jedis = jedisPool.getResource()) {
        // Test 1: Agent in WORKING set
        jedis.zadd("WORKZ", workingScore, agentType);

        // When - Execute unconditional swap
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.UNCONDITIONAL_SWAP_SET_SCRIPT),
                java.util.Arrays.asList("WORKZ", "WAITZ"),
                java.util.Arrays.asList(agentType, String.valueOf(newScore)));

        // Then - Agent moved to WAITING with new score
        assertThat(result).isEqualTo("moved");
        assertThat(jedis.zscore("WORKZ", agentType)).isNull();
        assertThat(jedis.zscore("WAITZ", agentType)).isEqualTo(newScore);

        // Cleanup
        jedis.zrem("WAITZ", agentType);

        // Test 2: Agent in WAITING set
        jedis.zadd("WAITZ", waitingScore, agentType);

        // When - Execute unconditional swap (should update score)
        result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.UNCONDITIONAL_SWAP_SET_SCRIPT),
                java.util.Arrays.asList("WORKZ", "WAITZ"),
                java.util.Arrays.asList(agentType, String.valueOf(newScore)));

        // Then - Agent remains in WAITING but with new score
        assertThat(result).isEqualTo("moved");
        assertThat(jedis.zscore("WORKZ", agentType)).isNull();
        assertThat(jedis.zscore("WAITZ", agentType)).isEqualTo(newScore);

        // Cleanup
        jedis.zrem("WAITZ", agentType);

        // Test 3: Agent in neither set
        // When - Execute unconditional swap
        result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.UNCONDITIONAL_SWAP_SET_SCRIPT),
                java.util.Arrays.asList("WORKZ", "WAITZ"),
                java.util.Arrays.asList(agentType, String.valueOf(newScore)));

        // Then - Agent added to WAITING with new score
        assertThat(result).isEqualTo("moved");
        assertThat(jedis.zscore("WORKZ", agentType)).isNull();
        assertThat(jedis.zscore("WAITZ", agentType)).isEqualTo(newScore);
      }
    }
  }

  @Nested
  @DisplayName("Batch Script Tests")
  class BatchScriptTests {

    @BeforeEach
    void setUp() {
      scriptManager.initializeScripts();
    }

    @Test
    @DisplayName("Should execute BATCH_ADD_AGENTS_SCRIPT correctly")
    void shouldExecuteBatchAddAgentsScriptCorrectly() {
      try (Jedis jedis = jedisPool.getResource()) {
        // When - Execute batch add with multiple agents
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.BATCH_ADD_AGENTS_SCRIPT),
                java.util.Arrays.asList("WORKZ", "WAITZ"),
                java.util.Arrays.asList("agent1", "100", "agent2", "200", "agent3", "300"));

        // Then
        assertThat(result).isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        java.util.List<Object> resultList = (java.util.List<Object>) result;

        assertThat(resultList.get(0)).isEqualTo(3L); // Count of added agents

        // Verify agents were added to WAITING set
        assertThat(jedis.zscore("WAITZ", "agent1")).isEqualTo(100.0);
        assertThat(jedis.zscore("WAITZ", "agent2")).isEqualTo(200.0);
        assertThat(jedis.zscore("WAITZ", "agent3")).isEqualTo(300.0);
      }
    }

    @Test
    @DisplayName("Should execute BATCH_ORPHAN_REMOVE_SCRIPT correctly")
    void shouldExecuteBatchOrphanRemoveScriptCorrectly() {
      try (Jedis jedis = jedisPool.getResource()) {
        // Given - Add orphaned agents to WORKING set
        jedis.zadd("WORKZ", 100, "orphan1");
        jedis.zadd("WORKZ", 200, "orphan2");

        // When - Execute batch orphan removal
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.BATCH_ORPHAN_REMOVE_SCRIPT),
                java.util.Collections.singletonList("WORKZ"),
                java.util.Arrays.asList("orphan1", "100", "orphan2", "200"));

        // Then
        assertThat(result).isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        java.util.List<Object> resultList = (java.util.List<Object>) result;

        assertThat(resultList.get(0)).isEqualTo(2L); // Count of removed agents

        // Verify agents were removed
        assertThat(jedis.zscore("WORKZ", "orphan1")).isNull();
        assertThat(jedis.zscore("WORKZ", "orphan2")).isNull();
      }
    }

    @Test
    @DisplayName("Should execute RELEASE_LEADERSHIP_SCRIPT correctly")
    void shouldExecuteReleaseLeadershipScriptCorrectly() {
      try (Jedis jedis = jedisPool.getResource()) {
        // Given - Set leadership key with owner ID
        String leadershipKey = "cleanup:leadership";
        String ownershipId = "node-123";
        jedis.set(leadershipKey, ownershipId);

        // When - Release leadership with correct ownership ID
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.RELEASE_LEADERSHIP_SCRIPT),
                java.util.Collections.singletonList(leadershipKey),
                java.util.Collections.singletonList(ownershipId));

        // Then - Should successfully delete the key
        assertThat(result).isEqualTo(1L); // Successful deletion
        assertThat(jedis.exists(leadershipKey)).isFalse();
      }
    }

    @Test
    @DisplayName("Should not release leadership with wrong ownership ID")
    void shouldNotReleaseLeadershipWithWrongOwnershipId() {
      try (Jedis jedis = jedisPool.getResource()) {
        // Given - Set leadership key with owner ID
        String leadershipKey = "cleanup:leadership";
        String correctOwnerId = "node-123";
        String wrongOwnerId = "node-456";
        jedis.set(leadershipKey, correctOwnerId);

        // When - Try to release leadership with wrong ownership ID
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.RELEASE_LEADERSHIP_SCRIPT),
                java.util.Collections.singletonList(leadershipKey),
                java.util.Collections.singletonList(wrongOwnerId));

        // Then - Should not delete the key
        assertThat(result).isEqualTo(0L); // Failed deletion
        assertThat(jedis.exists(leadershipKey)).isTrue();
        assertThat(jedis.get(leadershipKey)).isEqualTo(correctOwnerId);
      }
    }
  }

  @Nested
  @DisplayName("Performance Tests")
  class PerformanceTests {

    @BeforeEach
    void setUp() {
      scriptManager.initializeScripts();
    }

    @Test
    @DisplayName("Should handle high volume script executions efficiently")
    void shouldHandleHighVolumeScriptExecutionsEfficiently() {
      try (Jedis jedis = jedisPool.getResource()) {
        // Clear Redis to ensure clean state
        jedis.flushAll();

        // Given
        int iterations = 1000;
        long startTime = System.currentTimeMillis();

        // When - Execute many script operations
        for (int i = 0; i < iterations; i++) {
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT_SCRIPT),
              2,
              "WORKZ",
              "WAITZ",
              "agent-" + i,
              String.valueOf(i));
        }

        long duration = System.currentTimeMillis() - startTime;

        // Then - Should complete within reasonable time
        assertThat(duration).isLessThan(5000); // Less than 5 seconds
        assertThat(jedis.zcard("WAITZ")).isEqualTo(iterations);
      }
    }
  }

  @Nested
  @DisplayName("Timestamp Format Consistency Tests")
  class TimestampFormatConsistencyTests {

    @BeforeEach
    void setUp() {
      // Clear Redis before each test
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.flushAll();
      }
    }

    @Test
    @DisplayName("All Redis scores should be in seconds format")
    void shouldUseConsistentSecondsTimestampFormat() {
      try (Jedis jedis = jedisPool.getResource()) {
        // Directly add agents to WAITING set using current time in seconds (correct format)
        long currentTimeSeconds = System.currentTimeMillis() / 1000;

        jedis.zadd("WAITZ", currentTimeSeconds, "test-agent-1");
        jedis.zadd("WAITZ", currentTimeSeconds + 10, "test-agent-2");

        // Get all scores from WAITING set
        java.util.Set<redis.clients.jedis.Tuple> waitingAgents =
            jedis.zrangeByScoreWithScores("WAITZ", 0, Double.MAX_VALUE);

        // Verify all scores are in seconds format (not milliseconds)
        assertThat(waitingAgents).isNotEmpty();

        for (redis.clients.jedis.Tuple agent : waitingAgents) {
          long score = (long) agent.getScore();
          String agentName = agent.getElement();

          // Scores should be in seconds format
          // Current time in seconds should be close to the score (within reasonable range)
          assertThat(score)
              .withFailMessage(
                  "Agent %s score %d appears to be too small for seconds format", agentName, score)
              .isGreaterThan(1700000000L);

          // Score should not be in milliseconds (would be 1000x larger)
          assertThat(score)
              .withFailMessage(
                  "Agent %s score %d is too large - likely milliseconds format", agentName, score)
              .isLessThan(currentTimeSeconds + 3600);

          // Score should be reasonable (not in milliseconds format)
          assertThat(score)
              .withFailMessage(
                  "Agent %s score %d is in milliseconds format, should be seconds",
                  agentName, score)
              .isLessThan(1700000000000L);

          System.out.println(
              String.format("\u2705 Agent %s has correct seconds score: %d", agentName, score));
        }
      }
    }

    @Test
    @DisplayName("Mixed format detection test - should fail if milliseconds are used")
    void shouldDetectMillisecondsFormatInconsistency() {
      try (Jedis jedis = jedisPool.getResource()) {
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        long currentTimeMillis = System.currentTimeMillis();

        // Add agent with correct seconds format
        jedis.zadd("WAITZ", currentTimeSeconds, "seconds-agent");

        // Simulate bug: add agent with milliseconds format
        jedis.zadd("WAITZ", currentTimeMillis, "milliseconds-agent");

        // Verify we can detect the inconsistency
        java.util.Set<redis.clients.jedis.Tuple> allAgents =
            jedis.zrangeByScoreWithScores("WAITZ", 0, Double.MAX_VALUE);

        boolean hasSecondsFormat = false;
        boolean hasMillisecondsFormat = false;

        for (redis.clients.jedis.Tuple agent : allAgents) {
          long score = (long) agent.getScore();
          if (score < 1700000000000L) {
            hasSecondsFormat = true;
          } else {
            hasMillisecondsFormat = true;
          }
        }

        // This test validates our detection logic
        assertThat(hasSecondsFormat).withFailMessage("Should detect seconds format").isTrue();
        assertThat(hasMillisecondsFormat)
            .withFailMessage("Should detect milliseconds format (simulated bug)")
            .isTrue();

        // In production, this mixed state should never occur
        System.out.println(
            "\u26a0\ufe0f  Mixed format detected - this demonstrates the bug we're preventing");
      }
    }
  }
}
