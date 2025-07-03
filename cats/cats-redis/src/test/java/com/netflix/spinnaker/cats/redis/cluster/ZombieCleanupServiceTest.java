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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
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
 * Comprehensive test suite for ZombieCleanupService using testcontainers.
 *
 * <p>Tests cover: - Zombie agent detection and cleanup - Batch processing of zombie agents - Future
 * cancellation for zombie executions - Configurable thresholds and intervals - Error handling and
 * edge cases - Performance under various load conditions
 */
@Testcontainers
@DisplayName("ZombieCleanupService Tests")
class ZombieCleanupServiceTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private ZombieCleanupService zombieService;
  private ClusteredSortSchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    scriptManager = new RedisScriptManager(jedisPool);
    scriptManager.initializeScripts();

    schedulerProperties = new ClusteredSortSchedulerProperties();
    schedulerProperties.getZombieCleanup().setThresholdMs(30000L); // 30 seconds
    schedulerProperties.getZombieCleanup().setCleanupIntervalMs(10000L); // 10 seconds

    zombieService = new ZombieCleanupService(jedisPool, scriptManager, schedulerProperties);
  }

  @Nested
  @DisplayName("Zombie Detection Tests")
  class ZombieDetectionTests {

    @Test
    @DisplayName("Should detect zombie agents older than threshold")
    void shouldDetectZombieAgentsOlderThanThreshold() {
      // Given - Set up local tracking with an agent that has been running too long
      // Zombie cleanup scans LOCAL activeAgents map, not Redis
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000; // 1 minute ago

      // Also add to Redis for cleanup to work properly
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "zombie-agent");
      }

      // Populate the local activeAgents map with the zombie agent
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add the zombie agent to local tracking with old timestamp
      activeAgents.put("zombie-agent", String.valueOf(oldScoreSeconds));
      activeAgentsFutures.put("zombie-agent", mock(Future.class));

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(1);
      assertThat(zombieService.getZombiesCleanedUp()).isEqualTo(1);

      // Verify agent was removed from Redis
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WORKZ", "zombie-agent")).isNull();
      }
    }

    @Test
    @DisplayName("Should not clean up agents within threshold")
    void shouldNotCleanUpAgentsWithinThreshold() {
      // Given - Add recent agent to WORKING set
      long recentScore =
          System.currentTimeMillis() - 10000; // 10 seconds ago (within 30s threshold)
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", recentScore, "recent-agent");
      }

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(0);

      // Verify agent was NOT removed from Redis
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WORKZ", "recent-agent")).isEqualTo(recentScore);
      }
    }

    @Test
    @DisplayName("Should handle multiple zombie agents in batch")
    void shouldHandleMultipleZombieAgentsInBatch() {
      // Given - Clean up and add multiple old agents
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000;
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        jedis.zadd("WORKZ", oldScoreSeconds, "zombie-1");
        jedis.zadd("WORKZ", oldScoreSeconds - 1, "zombie-2");
        jedis.zadd("WORKZ", oldScoreSeconds - 2, "zombie-3");
      }

      // Populate local activeAgents map with zombie agents
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add zombie agents to local tracking
      activeAgents.put("zombie-1", String.valueOf(oldScoreSeconds));
      activeAgents.put("zombie-2", String.valueOf(oldScoreSeconds - 1));
      activeAgents.put("zombie-3", String.valueOf(oldScoreSeconds - 2));
      activeAgentsFutures.put("zombie-1", mock(Future.class));
      activeAgentsFutures.put("zombie-2", mock(Future.class));
      activeAgentsFutures.put("zombie-3", mock(Future.class));

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(3);
      assertThat(zombieService.getZombiesCleanedUp()).isEqualTo(3);

      // Verify all agents were removed
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
      }
    }
  }

  @Nested
  @DisplayName("Future Cancellation Tests")
  class FutureCancellationTests {

    @Test
    @DisplayName("Should cancel futures for zombie agents")
    void shouldCancelFuturesForZombieAgents() {
      // Given
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "zombie-agent");
      }

      Map<String, String> activeAgents = new HashMap<>();
      activeAgents.put("zombie-agent", String.valueOf(oldScoreSeconds));

      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();
      Future<?> mockFuture = mock(Future.class);
      when(mockFuture.isDone()).thenReturn(false);
      when(mockFuture.cancel(true)).thenReturn(true);
      activeAgentsFutures.put("zombie-agent", mockFuture);

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(1);
      verify(mockFuture).cancel(true);
      assertThat(activeAgents).isEmpty();
      assertThat(activeAgentsFutures).isEmpty();
    }

    @Test
    @DisplayName("Should handle already completed futures gracefully")
    void shouldHandleAlreadyCompletedFuturesGracefully() {
      // Given
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "zombie-agent");
      }

      // Populate local activeAgents map with zombie agent
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add zombie agent to local tracking
      activeAgents.put("zombie-agent", String.valueOf(oldScoreSeconds));
      Future<?> completedFuture = CompletableFuture.completedFuture(null);
      activeAgentsFutures.put("zombie-agent", completedFuture);

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(1);
      // Should not throw exception even though future is already done
    }
  }

  @Nested
  @DisplayName("Cleanup Interval Tests")
  class CleanupIntervalTests {

    @Test
    @DisplayName("Should respect cleanup interval timing")
    void shouldRespectCleanupIntervalTiming() {
      // Given
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // First cleanup call
      zombieService.cleanupZombieAgentsIfNeeded(activeAgents, activeAgentsFutures);
      long firstCleanupTime = zombieService.getLastZombieCleanup();

      // Immediate second call (should be skipped due to interval)
      zombieService.cleanupZombieAgentsIfNeeded(activeAgents, activeAgentsFutures);
      long secondCleanupTime = zombieService.getLastZombieCleanup();

      // Then
      assertThat(firstCleanupTime).isEqualTo(secondCleanupTime);
    }

    @Test
    @DisplayName("Should perform cleanup after interval has elapsed")
    void shouldPerformCleanupAfterIntervalHasElapsed() throws InterruptedException {
      // Given - Set very short interval for testing
      schedulerProperties.getZombieCleanup().setCleanupIntervalMs(100L); // 100ms
      zombieService = new ZombieCleanupService(jedisPool, scriptManager, schedulerProperties);

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // First cleanup
      zombieService.cleanupZombieAgentsIfNeeded(activeAgents, activeAgentsFutures);
      long firstTime = zombieService.getLastZombieCleanup();

      // Wait for interval to pass
      Thread.sleep(150);

      // Second cleanup
      zombieService.cleanupZombieAgentsIfNeeded(activeAgents, activeAgentsFutures);
      long secondTime = zombieService.getLastZombieCleanup();

      // Then
      assertThat(secondTime).isGreaterThan(firstTime);
    }
  }

  @Nested
  @DisplayName("Error Handling Tests")
  class ErrorHandlingTests {

    @Test
    @DisplayName("Should handle Redis connection failures gracefully")
    void shouldHandleRedisConnectionFailuresGracefully() {
      // Given
      jedisPool.close();
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Should not crash and return 0
      assertThat(cleaned).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle empty Redis sets gracefully")
    void shouldHandleEmptyRedisSetsGracefully() {
      // Given - Empty Redis (no agents)
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle script execution failures gracefully")
    void shouldHandleScriptExecutionFailuresGracefully() {
      // Given - Create service with invalid script manager
      RedisScriptManager invalidScriptManager = mock(RedisScriptManager.class);
      when(invalidScriptManager.getScriptSha(RedisScriptManager.BATCH_CLEANUP_AGENTS_SCRIPT))
          .thenReturn("invalid-sha");

      ZombieCleanupService invalidService =
          new ZombieCleanupService(jedisPool, invalidScriptManager, schedulerProperties);

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // When
      int cleaned = invalidService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Should handle error gracefully
      assertThat(cleaned).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Performance Tests")
  class PerformanceTests {

    @Test
    @DisplayName("Should handle large numbers of zombie agents efficiently")
    void shouldHandleLargeNumbersOfZombieAgentsEfficiently() {
      // Given - Add many zombie agents
      int zombieCount = 1000;
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000;

      try (Jedis jedis = jedisPool.getResource()) {
        for (int i = 0; i < zombieCount; i++) {
          jedis.zadd("WORKZ", oldScoreSeconds - i, "zombie-" + i);
        }
      }

      // Populate local activeAgents map with zombie agents
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add all zombie agents to local tracking
      for (int i = 0; i < zombieCount; i++) {
        String agentName = "zombie-" + i;
        activeAgents.put(agentName, String.valueOf(oldScoreSeconds - i));
        activeAgentsFutures.put(agentName, mock(Future.class));
      }

      long startTime = System.currentTimeMillis();

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      long duration = System.currentTimeMillis() - startTime;

      // Then
      assertThat(cleaned).isEqualTo(zombieCount);
      assertThat(duration).isLessThan(5000); // Should complete within 5 seconds

      // Verify all zombies were cleaned
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should handle mixed zombie and active agents efficiently")
    void shouldHandleMixedZombieAndActiveAgentsEfficiently() {
      // Given - Mix of zombie (old) and active (recent) agents
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000; // Zombies
      long recentScoreSeconds = (System.currentTimeMillis() - 10000) / 1000; // Active

      try (Jedis jedis = jedisPool.getResource()) {
        // Add zombie agents (all old enough to be zombies)
        for (int i = 0; i < 500; i++) {
          jedis.zadd("WORKZ", oldScoreSeconds - i, "zombie-" + i);
        }
        // Add active agents (all recent enough to not be zombies)
        for (int i = 0; i < 500; i++) {
          jedis.zadd("WORKZ", recentScoreSeconds + i, "active-" + i);
        }
      }

      // Populate local activeAgents map with both zombie and active agents
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add zombie agents to local tracking (only these should be cleaned)
      for (int i = 0; i < 500; i++) {
        String zombieName = "zombie-" + i;
        activeAgents.put(zombieName, String.valueOf(oldScoreSeconds - i));
        activeAgentsFutures.put(zombieName, mock(Future.class));
      }

      // Add active agents to local tracking (these should NOT be cleaned)
      for (int i = 0; i < 500; i++) {
        String activeName = "active-" + i;
        activeAgents.put(activeName, String.valueOf(recentScoreSeconds + i));
        activeAgentsFutures.put(activeName, mock(Future.class));
      }

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(500); // Only zombies cleaned

      // Verify only active agents remain
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(500);
      }
    }
  }

  @Nested
  @DisplayName("Metrics and Monitoring Tests")
  class MetricsAndMonitoringTests {

    @Test
    @DisplayName("Should track total zombies cleaned up")
    void shouldTrackTotalZombiesCleanedUp() {
      // Given
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "zombie-1");
        jedis.zadd("WORKZ", oldScoreSeconds - 1, "zombie-2");
      }

      // Populate local activeAgents map with zombie agents
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add zombie agents to local tracking
      activeAgents.put("zombie-1", String.valueOf(oldScoreSeconds));
      activeAgents.put("zombie-2", String.valueOf(oldScoreSeconds - 1));
      activeAgentsFutures.put("zombie-1", mock(Future.class));
      activeAgentsFutures.put("zombie-2", mock(Future.class));

      long initialCount = zombieService.getZombiesCleanedUp();

      // When
      zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(zombieService.getZombiesCleanedUp()).isEqualTo(initialCount + 2);
    }

    @Test
    @DisplayName("Should track last cleanup timestamp")
    void shouldTrackLastCleanupTimestamp() {
      // Given
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      long beforeCleanup = System.currentTimeMillis();

      // When
      zombieService.cleanupZombieAgentsIfNeeded(activeAgents, activeAgentsFutures);

      // Then
      long lastCleanup = zombieService.getLastZombieCleanup();
      assertThat(lastCleanup).isGreaterThanOrEqualTo(beforeCleanup);
    }

    @Test
    @DisplayName("Should handle stuck agent cleanup with futures")
    void shouldHandleStuckAgentCleanupWithFutures() throws Exception {
      // Given - Agent that will get stuck
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "stuck-agent");
      }

      // Simulate active agent with future
      Map<String, String> activeAgents = new HashMap<>();
      activeAgents.put("stuck-agent", String.valueOf(oldScoreSeconds));

      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();
      Future<?> mockFuture = mock(Future.class);
      when(mockFuture.isDone()).thenReturn(false);
      when(mockFuture.cancel(true)).thenReturn(true);
      activeAgentsFutures.put("stuck-agent", mockFuture);

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(1);
      verify(mockFuture).cancel(true);

      // Verify agent was removed from Redis
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WORKZ", "stuck-agent")).isNull();
      }
    }
  }

  @Nested
  @DisplayName("Complex Logic Tests - Local Detection and Future Management")
  class ComplexLogicTests {

    @Test
    @DisplayName("Should detect zombies from local activeAgents map, not Redis")
    void shouldDetectZombiesFromLocalMapNotRedis() {
      // Given - Agent exists in Redis but NOT in local activeAgents map
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000; // 1 minute ago

      try (Jedis jedis = jedisPool.getResource()) {
        // Add agent to Redis WORKZ
        jedis.zadd("WORKZ", oldScoreSeconds, "redis-only-agent");
      }

      // Local activeAgents map is empty (agent not tracked locally)
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // When - Run zombie cleanup
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - No zombies detected because agent not in local map
      assertThat(cleaned).isEqualTo(0);

      // Agent should still exist in Redis (not cleaned)
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WORKZ", "redis-only-agent")).isNotNull();
      }
    }

    @Test
    @DisplayName("Should detect zombies from local activeAgents even if not in Redis")
    void shouldDetectZombiesFromLocalMapEvenWithoutRedis() {
      // Given - Agent exists in local map but NOT in Redis
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000; // 1 minute ago

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add zombie agent to local tracking only
      activeAgents.put("local-only-zombie", String.valueOf(oldScoreSeconds));
      activeAgentsFutures.put("local-only-zombie", mock(Future.class));

      // Redis WORKZ is empty
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("WORKZ");
      }

      // When - Run zombie cleanup
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Zombie detected and cleaned from local map
      assertThat(cleaned).isEqualTo(1);
      assertThat(activeAgents).doesNotContainKey("local-only-zombie");
      assertThat(activeAgentsFutures).doesNotContainKey("local-only-zombie");
    }

    @Test
    @DisplayName("Should cancel zombie agent futures during cleanup")
    void shouldCancelZombieFuturesDuringCleanup() {
      // Given - Zombie agent with running future
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000; // 1 minute ago

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Create mock future that is still running
      Future<?> mockFuture = mock(Future.class);
      when(mockFuture.isDone()).thenReturn(false);
      when(mockFuture.cancel(true)).thenReturn(true);

      activeAgents.put("zombie-with-future", String.valueOf(oldScoreSeconds));
      activeAgentsFutures.put("zombie-with-future", mockFuture);

      // Also add to Redis for complete cleanup
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "zombie-with-future");
      }

      // When - Run zombie cleanup
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Future should be cancelled
      assertThat(cleaned).isEqualTo(1);
      verify(mockFuture).cancel(true);
    }

    @Test
    @DisplayName("Should handle mixed zombie and active agents correctly")
    void shouldHandleMixedZombieAndActiveAgents() {
      // Given - Mix of zombie and active agents
      long currentTimeSeconds = System.currentTimeMillis() / 1000;
      long zombieTimeSeconds = currentTimeSeconds - 60; // 1 minute ago (zombie)
      long activeTimeSeconds = currentTimeSeconds - 5; // 5 seconds ago (active)

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add zombie agents (old timestamp)
      activeAgents.put("zombie-1", String.valueOf(zombieTimeSeconds));
      activeAgents.put("zombie-2", String.valueOf(zombieTimeSeconds - 5));
      activeAgentsFutures.put("zombie-1", mock(Future.class));
      activeAgentsFutures.put("zombie-2", mock(Future.class));

      // Add active agents (recent timestamp)
      activeAgents.put("active-1", String.valueOf(activeTimeSeconds));
      activeAgents.put("active-2", String.valueOf(activeTimeSeconds - 2));
      activeAgentsFutures.put("active-1", mock(Future.class));
      activeAgentsFutures.put("active-2", mock(Future.class));

      // Add all to Redis
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", zombieTimeSeconds, "zombie-1");
        jedis.zadd("WORKZ", zombieTimeSeconds - 5, "zombie-2");
        jedis.zadd("WORKZ", activeTimeSeconds, "active-1");
        jedis.zadd("WORKZ", activeTimeSeconds - 2, "active-2");
      }

      // When - Run zombie cleanup
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Only zombies cleaned, actives preserved
      assertThat(cleaned).isEqualTo(2);

      // Zombies removed from local tracking
      assertThat(activeAgents).doesNotContainKey("zombie-1");
      assertThat(activeAgents).doesNotContainKey("zombie-2");
      assertThat(activeAgentsFutures).doesNotContainKey("zombie-1");
      assertThat(activeAgentsFutures).doesNotContainKey("zombie-2");

      // Active agents preserved in local tracking
      assertThat(activeAgents).containsKey("active-1");
      assertThat(activeAgents).containsKey("active-2");
      assertThat(activeAgentsFutures).containsKey("active-1");
      assertThat(activeAgentsFutures).containsKey("active-2");

      // Zombies removed from Redis
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WORKZ", "zombie-1")).isNull();
        assertThat(jedis.zscore("WORKZ", "zombie-2")).isNull();
        // Active agents might still be in Redis (that's normal)
      }
    }

    @Test
    @DisplayName("Should handle zombie detection with invalid acquire scores gracefully")
    void shouldHandleInvalidAcquireScoresGracefully() {
      // Given - Agent with invalid acquire score
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add agent with invalid score
      activeAgents.put("invalid-score-agent", "not-a-number");
      activeAgentsFutures.put("invalid-score-agent", mock(Future.class));

      // Add normal zombie for comparison
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000;
      activeAgents.put("normal-zombie", String.valueOf(oldScoreSeconds));
      activeAgentsFutures.put("normal-zombie", mock(Future.class));

      // When - Run zombie cleanup
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Only normal zombie cleaned, invalid score agent ignored
      assertThat(cleaned).isEqualTo(1);

      // Invalid score agent should still be in map (not cleaned)
      assertThat(activeAgents).containsKey("invalid-score-agent");
      assertThat(activeAgentsFutures).containsKey("invalid-score-agent");

      // Normal zombie should be cleaned
      assertThat(activeAgents).doesNotContainKey("normal-zombie");
      assertThat(activeAgentsFutures).doesNotContainKey("normal-zombie");
    }

    @Test
    @DisplayName("Should verify zombie detection uses configurable threshold")
    void shouldUseConfigurableThresholdForZombieDetection() {
      // Given - Agents with different ages
      long currentTimeSeconds = System.currentTimeMillis() / 1000;
      long justOverThresholdSeconds = currentTimeSeconds - 31; // 31 seconds ago
      long justUnderThresholdSeconds = currentTimeSeconds - 29; // 29 seconds ago

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add agents with different ages
      activeAgents.put("just-over-threshold", String.valueOf(justOverThresholdSeconds));
      activeAgents.put("just-under-threshold", String.valueOf(justUnderThresholdSeconds));
      activeAgentsFutures.put("just-over-threshold", mock(Future.class));
      activeAgentsFutures.put("just-under-threshold", mock(Future.class));

      // When - Run zombie cleanup (threshold is 30 seconds by default)
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Only agent over threshold cleaned
      assertThat(cleaned).isEqualTo(1);

      // Agent over threshold cleaned
      assertThat(activeAgents).doesNotContainKey("just-over-threshold");
      assertThat(activeAgentsFutures).doesNotContainKey("just-over-threshold");

      // Agent under threshold preserved
      assertThat(activeAgents).containsKey("just-under-threshold");
      assertThat(activeAgentsFutures).containsKey("just-under-threshold");
    }

    @Test
    @DisplayName("Should handle completed futures without cancellation")
    void shouldHandleCompletedFuturesWithoutCancellation() {
      // Given - Zombie with completed future
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000; // 1 minute ago

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Create mock future that is already done
      Future<?> completedFuture = mock(Future.class);
      when(completedFuture.isDone()).thenReturn(true);

      activeAgents.put("zombie-with-completed-future", String.valueOf(oldScoreSeconds));
      activeAgentsFutures.put("zombie-with-completed-future", completedFuture);

      // When - Run zombie cleanup
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Zombie cleaned but future not cancelled (already done)
      assertThat(cleaned).isEqualTo(1);
      verify(completedFuture, never()).cancel(true); // Should not attempt to cancel
    }
  }
}
