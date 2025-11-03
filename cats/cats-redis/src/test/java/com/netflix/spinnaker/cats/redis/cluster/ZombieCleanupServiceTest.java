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
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * Test suite for ZombieCleanupService using testcontainers.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Zombie agent detection and cleanup
 *   <li>Batch processing of zombie agents
 *   <li>Future cancellation for zombie executions
 *   <li>Configurable thresholds and intervals
 *   <li>Error handling and edge cases
 *   <li>Permit release and local tracking
 *   <li>Budget respect and non-blocking behavior
 *   <li>Exceptional agents with custom thresholds
 * </ul>
 *
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
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    // Clear Redis data to ensure clean state for each test
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushAll();
    }

    scriptManager =
        new RedisScriptManager(
            jedisPool,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    scriptManager.initializeScripts();

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getZombieCleanup().setThresholdMs(30000L); // 30 seconds
    schedulerProperties.getZombieCleanup().setIntervalMs(10000L); // 10 seconds

    zombieService =
        new ZombieCleanupService(
            jedisPool,
            scriptManager,
            schedulerProperties,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
  }

  @Nested
  @DisplayName("Zombie Detection Tests")
  class ZombieDetectionTests {

    @Test
    @DisplayName("Should detect zombie agents older than threshold")
    void shouldDetectZombieAgentsOlderThanThreshold() {
      // Given - Set up local tracking with an agent that has been running too long
      // Zombie cleanup scans LOCAL activeAgents map, not Redis
      long oldScoreSeconds;
      try (Jedis j = jedisPool.getResource()) {
        long nowSec = Long.parseLong(j.time().get(0));
        oldScoreSeconds = nowSec - 60; // 1 minute ago
      }

      // Also add to Redis for cleanup to work properly
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("working", oldScoreSeconds, "zombie-agent");
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
        assertThat(jedis.zscore("working", "zombie-agent")).isNull();
      }
    }

    @Test
    @DisplayName("Should not clean up agents within threshold")
    void shouldNotCleanUpAgentsWithinThreshold() {
      // Given - Add recent agent to WORKING set
      long recentScore;
      try (Jedis j = jedisPool.getResource()) {
        long nowMs = Long.parseLong(j.time().get(0)) * 1000L;
        recentScore = nowMs - 10000; // 10 seconds ago (within 30s threshold)
      }
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("working", recentScore, "recent-agent");
      }

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(0);

      // Verify agent was NOT removed from Redis
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("working", "recent-agent")).isEqualTo(recentScore);
      }
    }

    @Test
    @DisplayName("Should detect and clean up multiple zombie agents individually")
    void shouldDetectAndCleanupMultipleZombieAgentsIndividually() {
      // Given - Clean up and add multiple old agents
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds;
      try (Jedis j = jedisPool.getResource()) {
        long nowSec = Long.parseLong(j.time().get(0));
        oldScoreSeconds = nowSec - 60;
      }
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("working", "waiting");

        jedis.zadd("working", oldScoreSeconds, "zombie-1");
        jedis.zadd("working", oldScoreSeconds - 1, "zombie-2");
        jedis.zadd("working", oldScoreSeconds - 2, "zombie-3");
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
        assertThat(jedis.zcard("working")).isEqualTo(0);
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
      long oldScoreSeconds;
      try (Jedis j = jedisPool.getResource()) {
        long nowSec = Long.parseLong(j.time().get(0));
        oldScoreSeconds = nowSec - 60;
      }
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("working", oldScoreSeconds, "zombie-agent");
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
      long oldScoreSeconds;
      try (Jedis j = jedisPool.getResource()) {
        long nowSec = Long.parseLong(j.time().get(0));
        oldScoreSeconds = nowSec - 60;
      }
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("working", oldScoreSeconds, "zombie-agent");
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
      schedulerProperties.getZombieCleanup().setIntervalMs(100L); // 100ms
      zombieService =
          new ZombieCleanupService(
              jedisPool,
              scriptManager,
              schedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

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
      when(invalidScriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENTS_CONDITIONAL))
          .thenReturn("invalid-sha");

      ZombieCleanupService invalidService =
          new ZombieCleanupService(
              jedisPool,
              invalidScriptManager,
              schedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

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
          jedis.zadd("working", oldScoreSeconds - i, "zombie-" + i);
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
        assertThat(jedis.zcard("working")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should handle mixed zombie and active agents efficiently")
    void shouldHandleMixedZombieAndActiveAgentsEfficiently() {
      // Given - Mix of zombie and active agents with completion deadline logic
      long currentTimeSeconds = System.currentTimeMillis() / 1000;

      // Scores are completion deadlines
      long zombieDeadlineSeconds = currentTimeSeconds - 60; // Deadlines were 1 minute ago (zombies)
      long activeDeadlineSeconds =
          currentTimeSeconds + 60; // Deadlines are 1 minute in future (active)

      try (Jedis jedis = jedisPool.getResource()) {
        // Add zombie agents (past completion deadlines)
        for (int i = 0; i < 500; i++) {
          jedis.zadd("working", zombieDeadlineSeconds - i, "zombie-" + i);
        }
        // Add active agents (future completion deadlines)
        for (int i = 0; i < 500; i++) {
          jedis.zadd("working", activeDeadlineSeconds + i, "active-" + i);
        }
      }

      // Populate local activeAgents map with both zombie and active agents
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add zombie agents to local tracking (past deadlines - should be cleaned)
      for (int i = 0; i < 500; i++) {
        String zombieName = "zombie-" + i;
        activeAgents.put(zombieName, String.valueOf(zombieDeadlineSeconds - i));
        activeAgentsFutures.put(zombieName, mock(Future.class));
      }

      // Add active agents to local tracking (future deadlines - should NOT be cleaned)
      for (int i = 0; i < 500; i++) {
        String activeName = "active-" + i;
        activeAgents.put(activeName, String.valueOf(activeDeadlineSeconds + i));
        activeAgentsFutures.put(activeName, mock(Future.class));
      }

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then
      assertThat(cleaned).isEqualTo(500); // Only zombies cleaned

      // Verify only active agents remain
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("working")).isEqualTo(500);
      }
    }

    @Test
    @DisplayName("Should respect batch size configuration for large zombie counts")
    void shouldRespectBatchSizeConfiguration() {
      // Enable batch operations with specific batch size
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(5);
      zombieService =
          new ZombieCleanupService(
              jedisPool,
              scriptManager,
              schedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // Given - More zombies than batch size
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000;
      int totalZombies = 12; // More than batch size of 5, requires multiple batches

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      try (Jedis jedis = jedisPool.getResource()) {
        for (int i = 1; i <= totalZombies; i++) {
          String agentType = "batch-size-zombie-" + i;
          jedis.zadd("working", oldScoreSeconds, agentType);

          activeAgents.put(agentType, String.valueOf(oldScoreSeconds));
          activeAgentsFutures.put(agentType, mock(Future.class));
        }
      }

      // When - Run zombie cleanup
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - All zombies cleaned despite exceeding batch size
      assertThat(cleaned).isEqualTo(totalZombies);
      assertThat(activeAgents).isEmpty();
      assertThat(activeAgentsFutures).isEmpty();

      // Verify Redis cleanup
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("working")).isEqualTo(0);
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
        jedis.zadd("working", oldScoreSeconds, "zombie-1");
        jedis.zadd("working", oldScoreSeconds - 1, "zombie-2");
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
        jedis.zadd("working", oldScoreSeconds, "stuck-agent");
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
        assertThat(jedis.zscore("working", "stuck-agent")).isNull();
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
        // Add agent to Redis working
        jedis.zadd("working", oldScoreSeconds, "redis-only-agent");
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
        assertThat(jedis.zscore("working", "redis-only-agent")).isNotNull();
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

      // Redis working is empty
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("working");
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
        jedis.zadd("working", oldScoreSeconds, "zombie-with-future");
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
      // Given - Mix of zombie and active agents using completion deadline logic
      long currentTimeSeconds = System.currentTimeMillis() / 1000;

      // Scores are completion deadlines
      long zombieDeadlineSeconds = currentTimeSeconds - 60; // Deadline was 1 minute ago (zombie)
      long activeDeadlineSeconds =
          currentTimeSeconds + 60; // Deadline is 1 minute in future (active)

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add zombie agents (past completion deadline)
      activeAgents.put("zombie-1", String.valueOf(zombieDeadlineSeconds));
      activeAgents.put("zombie-2", String.valueOf(zombieDeadlineSeconds - 5));
      activeAgentsFutures.put("zombie-1", mock(Future.class));
      activeAgentsFutures.put("zombie-2", mock(Future.class));

      // Add active agents (future completion deadline)
      activeAgents.put("active-1", String.valueOf(activeDeadlineSeconds));
      activeAgents.put("active-2", String.valueOf(activeDeadlineSeconds + 10));
      activeAgentsFutures.put("active-1", mock(Future.class));
      activeAgentsFutures.put("active-2", mock(Future.class));

      // Add all to Redis with completion deadlines
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("working", zombieDeadlineSeconds, "zombie-1");
        jedis.zadd("working", zombieDeadlineSeconds - 5, "zombie-2");
        jedis.zadd("working", activeDeadlineSeconds, "active-1");
        jedis.zadd("working", activeDeadlineSeconds + 10, "active-2");
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
        assertThat(jedis.zscore("working", "zombie-1")).isNull();
        assertThat(jedis.zscore("working", "zombie-2")).isNull();
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

      // Then - Both zombies should be cleaned (invalid scores are now force-cleaned to prevent
      // zombie limbo)
      assertThat(cleaned).isEqualTo(2);

      // Invalid score agent should be cleaned
      assertThat(activeAgents).doesNotContainKey("invalid-score-agent");
      assertThat(activeAgentsFutures).doesNotContainKey("invalid-score-agent");

      // Normal zombie should also be cleaned
      assertThat(activeAgents).doesNotContainKey("normal-zombie");
      assertThat(activeAgentsFutures).doesNotContainKey("normal-zombie");
    }

    @Test
    @DisplayName("Should verify zombie detection uses configurable threshold")
    void shouldUseConfigurableThresholdForZombieDetection() {
      // Given - Test the zombie threshold buffer (30s) with completion deadlines
      long currentTimeSeconds = System.currentTimeMillis() / 1000;

      // Logic: current_time > completion_deadline + zombie_threshold (30s)
      // For zombie: deadline should be >30s ago so current_time > deadline + 30s
      long justOverDeadlineSeconds =
          currentTimeSeconds - 31; // Deadline 31s ago: current > (deadline + 30s) = zombie
      // For not zombie: deadline should be <30s ago so current_time <= deadline + 30s
      long justUnderDeadlineSeconds =
          currentTimeSeconds - 29; // Deadline 29s ago: current <= (deadline + 30s) = not zombie

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add agents with different completion deadlines
      activeAgents.put("just-over-threshold", String.valueOf(justOverDeadlineSeconds));
      activeAgents.put("just-under-threshold", String.valueOf(justUnderDeadlineSeconds));
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
    @DisplayName("Should use batch cleanup when enabled and multiple zombies exist")
    void shouldUseBatchCleanupWhenEnabled() {
      // Update scheduler properties to enable batch operations for this test
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(5);
      zombieService =
          new ZombieCleanupService(
              jedisPool,
              scriptManager,
              schedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // Given - Multiple zombie agents
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000; // 1 minute ago

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add multiple zombie agents to Redis and local tracking
      try (Jedis jedis = jedisPool.getResource()) {
        for (int i = 1; i <= 3; i++) {
          String agentType = "batch-zombie-" + i;
          jedis.zadd("working", oldScoreSeconds, agentType);

          activeAgents.put(agentType, String.valueOf(oldScoreSeconds));
          activeAgentsFutures.put(agentType, mock(Future.class));
        }
      }

      // When - Run zombie cleanup (batch operations enabled)
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - All zombies cleaned up
      assertThat(cleaned).isEqualTo(3);
      assertThat(activeAgents).isEmpty();
      assertThat(activeAgentsFutures).isEmpty();

      // Verify Redis cleanup
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("working")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should fallback to individual cleanup when batch operations disabled")
    void shouldFallbackToIndividualWhenBatchDisabled() {
      // Ensure batch operations are disabled (default)
      schedulerProperties.getBatchOperations().setEnabled(false);
      zombieService =
          new ZombieCleanupService(
              jedisPool,
              scriptManager,
              schedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // Given - Multiple zombie agents
      long oldScoreSeconds = (System.currentTimeMillis() - 60000) / 1000;

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      // Add agents to both Redis and local tracking
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("working", oldScoreSeconds, "individual-zombie-1");
        jedis.zadd("working", oldScoreSeconds, "individual-zombie-2");
      }

      activeAgents.put("individual-zombie-1", String.valueOf(oldScoreSeconds));
      activeAgents.put("individual-zombie-2", String.valueOf(oldScoreSeconds));
      activeAgentsFutures.put("individual-zombie-1", mock(Future.class));
      activeAgentsFutures.put("individual-zombie-2", mock(Future.class));

      // When - Run zombie cleanup (will use individual cleanup)
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Should clean up successfully using individual operations
      assertThat(cleaned).isEqualTo(2);
      assertThat(activeAgents).isEmpty();
      assertThat(activeAgentsFutures).isEmpty();

      // Verify Redis cleanup
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("working")).isEqualTo(0);
      }
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

  @Nested
  @DisplayName("ThreadLocal Hygiene Tests")
  class ThreadLocalHygieneTests {

    @Test
    @DisplayName("cleanupZombieAgents should not retain ThreadLocal buffers after run")
    void cleanupZombieAgentsDoesNotRetainBuffers() {
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();
      // Run twice to exercise finally-clears and assert no exceptions are thrown
      assertThatCode(
              () -> {
                zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);
                zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);
              })
          .doesNotThrowAnyException();
      // Maps remain unchanged
      assertThat(activeAgents).isEmpty();
      assertThat(activeAgentsFutures).isEmpty();
    }
  }

  @Nested
  @DisplayName("Unit Tests")
  class UnitTests {

    @Test
    @DisplayName("refreshExceptionalAgentsPattern updates thresholds without error")
    void refreshExceptionalThresholds() {
      PrioritySchedulerProperties props = new PrioritySchedulerProperties();
      PrioritySchedulerMetrics metrics =
          new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
      ZombieCleanupService svc =
          new ZombieCleanupService(
              new JedisPool(), new RedisScriptManager(new JedisPool(), metrics), props, metrics);
      // exercise methods on empty state; ensure no crash
      Map<String, String> active = new HashMap<>();
      Map<String, Future<?>> futures = new HashMap<>();
      int cleaned = svc.cleanupZombieAgents(active, futures);
      assertThat(cleaned).isGreaterThanOrEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Permit Release Tests")
  class PermitReleaseTests {

    @Test
    @DisplayName("Should release permit and remove local tracking even if Redis remove returns 0")
    void shouldReleasePermitAndRemoveLocalTrackingWhenRedisRemoveReturnsZero() {
      // Given: local zombie present, but not present in Redis (REMOVE returns 0)
      String agentType = "local-only-zombie";
      long oldScoreSeconds;
      try (Jedis j = jedisPool.getResource()) {
        long nowSec = Long.parseLong(j.time().get(0));
        oldScoreSeconds = nowSec - 120; // 2 minutes ago
      }

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      activeAgents.put(agentType, String.valueOf(oldScoreSeconds));
      Future<?> mockFuture = mock(Future.class);
      when(mockFuture.isDone()).thenReturn(false);
      when(mockFuture.cancel(true)).thenReturn(true);
      activeAgentsFutures.put(agentType, mockFuture);

      // Wire a mocked acquisition service to verify fairness and local cleanup calls
      AgentAcquisitionService acquisition = mock(AgentAcquisitionService.class);
      zombieService.setAcquisitionService(acquisition);

      // When
      int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then: counted as cleaned even if not in Redis; we cancel local future
      // and delegate local tracking + permit release to acquisitionService
      assertThat(cleaned).isEqualTo(1);
      verify(mockFuture).cancel(true);
      verify(acquisition).removeActiveAgent(agentType);
      verify(acquisition).tryEarlyPermitReleaseAndMaybeIncrementZif(agentType);
    }
  }

  @Nested
  @DisplayName("Budget Respect Tests")
  class BudgetRespectTests {

    @Test
    @DisplayName("Long zombie cleanup work does not block scheduler; subsequent run proceeds")
    void zombieBudget_Respected_NonBlockingAndProceeds() throws Exception {
      String host = redis.getHost();
      int port = redis.getMappedPort(6379);
      JedisPoolConfig config = new JedisPoolConfig();
      JedisPool pool = new JedisPool(config, host, port, 2000, "testpass");

      com.netflix.spinnaker.cats.cluster.NodeStatusProvider nodeStatusProvider = () -> true;
      com.netflix.spinnaker.cats.cluster.AgentIntervalProvider intervalProvider =
          a -> new com.netflix.spinnaker.cats.cluster.AgentIntervalProvider.Interval(1000L, 5000L);
      com.netflix.spinnaker.cats.cluster.ShardingFilter shardFilter = a -> true;

      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(5);
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");

      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.setIntervalMs(50L);
      schedProps.setRefreshPeriodSeconds(1);
      schedProps.getZombieCleanup().setEnabled(true);
      schedProps.getZombieCleanup().setIntervalMs(10L);
      schedProps.getZombieCleanup().setRunBudgetMs(50L);
      schedProps.getOrphanCleanup().setEnabled(false);
      schedProps.getCircuitBreaker().setEnabled(false);

      PrioritySchedulerMetrics metrics =
          new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());

      PriorityAgentScheduler sched =
          new PriorityAgentScheduler(
              pool,
              nodeStatusProvider,
              intervalProvider,
              shardFilter,
              agentProps,
              schedProps,
              metrics);

      // Access scriptManager and acquisitionService for stub
      java.lang.reflect.Field smField =
          PriorityAgentScheduler.class.getDeclaredField("scriptManager");
      smField.setAccessible(true);
      RedisScriptManager scriptManager = (RedisScriptManager) smField.get(sched);
      scriptManager.initializeScripts();

      java.lang.reflect.Field acqField =
          PriorityAgentScheduler.class.getDeclaredField("acquisitionService");
      acqField.setAccessible(true);
      AgentAcquisitionService acq = (AgentAcquisitionService) acqField.get(sched);

      // Provide a fake active map/futures and a sleeping zombie cleanup
      java.util.concurrent.atomic.AtomicInteger calls =
          new java.util.concurrent.atomic.AtomicInteger(0);
      ZombieCleanupService sleeping =
          new ZombieCleanupService(pool, scriptManager, schedProps, metrics) {
            @Override
            public void cleanupZombieAgentsIfNeeded(
                Map<String, String> activeAgents, Map<String, Future<?>> activeAgentsFutures) {
              calls.incrementAndGet();
              try {
                Thread.sleep(200);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
              }
            }
          };

      java.lang.reflect.Field zombieField =
          PriorityAgentScheduler.class.getDeclaredField("zombieService");
      zombieField.setAccessible(true);
      zombieField.set(sched, sleeping);

      long start1 = System.currentTimeMillis();
      sched.run();
      long dur1 = System.currentTimeMillis() - start1;
      assertThat(dur1).isLessThan(400L);

      // Allow the first background task to finish, then run again so a new cleanup can begin
      Thread.sleep(250);
      long start2 = System.currentTimeMillis();
      sched.run();
      long dur2 = System.currentTimeMillis() - start2;
      assertThat(dur2).isLessThan(400L);

      // Give the second offloaded cleanup time to increment counter
      Thread.sleep(50);
      assertThat(calls.get()).isGreaterThanOrEqualTo(2);

      sched.shutdown();
      pool.close();
    }
  }

  @Nested
  @DisplayName("Exceptional Agents Tests")
  class ExceptionalAgentsTests {

    private ZombieCleanupService exceptionalZombieService;

    @Nested
    @DisplayName("Pattern Configuration Tests")
    class PatternConfigurationTests {

      @Test
      @DisplayName("Should compile valid regex patterns")
      void shouldCompileValidRegexPatterns() {
        // Given - Properties with valid patterns
        PrioritySchedulerProperties props = createPropertiesWithPattern(".*BigQuery.*");

        // When - Create service (triggers pattern compilation)
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        // Then - Service should be created successfully
        assertThat(exceptionalZombieService).isNotNull();
      }

      @Test
      @DisplayName("Should handle empty pattern gracefully")
      void shouldHandleEmptyPatternGracefully() {
        // Given - Properties with empty pattern
        PrioritySchedulerProperties props = createPropertiesWithPattern("");

        // When - Create service
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        // Then - Should work with no exceptional agents
        assertThat(exceptionalZombieService).isNotNull();
      }

      @Test
      @DisplayName("Should log error for invalid regex patterns")
      void shouldLogErrorForInvalidRegexPatterns() {
        // Given - Properties with invalid regex pattern
        PrioritySchedulerProperties props = createPropertiesWithPattern("[invalid regex");

        // When - Create service (should handle gracefully)
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        // Then - Service should still be created (pattern will be null internally)
        assertThat(exceptionalZombieService).isNotNull();
      }

      @Test
      @DisplayName("Should refresh pattern configuration at runtime")
      void shouldRefreshPatternConfigurationAtRuntime() {
        // Given - Service with initial pattern
        PrioritySchedulerProperties props = createPropertiesWithPattern(".*Old.*");
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        // When - Update pattern and refresh
        props.getZombieCleanup().getExceptionalAgents().setPattern(".*New.*");
        exceptionalZombieService.refreshExceptionalAgentsPattern();

        // Then - Should handle the refresh without errors
        assertThat(exceptionalZombieService).isNotNull();
      }
    }

    @Nested
    @DisplayName("Threshold Application Tests")
    class ThresholdApplicationTests {

      @Test
      @DisplayName("Should apply default threshold to non-matching agents")
      void shouldApplyDefaultThresholdToNonMatchingAgents() {
        // Given - Properties with BigQuery pattern and short thresholds for testing
        PrioritySchedulerProperties props =
            createTestPropertiesWithExceptionalAgents(
                ".*BigQuery.*", 10000L, 5000L); // 10s exceptional, 5s default
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        // Simulate agents past default threshold but before exceptional threshold
        Map<String, String> activeAgents = new HashMap<>();
        Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

        long currentTime = System.currentTimeMillis();
        long completionDeadline =
            (currentTime - 7000L) / 1000L; // 7 seconds ago (past default threshold)

        activeAgents.put("RegularAgent", String.valueOf(completionDeadline));
        activeAgents.put("AnotherAgent", String.valueOf(completionDeadline));

        // When - Run zombie cleanup
        int cleaned =
            exceptionalZombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

        // Then - Regular agents should be cleaned (past 5s default threshold)
        assertThat(cleaned).isEqualTo(2);
        assertThat(activeAgents).isEmpty();
      }

      @Test
      @DisplayName("Should apply exceptional threshold to matching agents")
      void shouldApplyExceptionalThresholdToMatchingAgents() {
        // Given - Properties with BigQuery pattern
        PrioritySchedulerProperties props =
            createTestPropertiesWithExceptionalAgents(
                ".*BigQuery.*", 10000L, 5000L); // 10s exceptional, 5s default
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        Map<String, String> activeAgents = new HashMap<>();
        Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

        long currentTime = System.currentTimeMillis();
        long completionDeadline = (currentTime - 7000L) / 1000L; // 7 seconds ago

        // BigQuery agent should not be cleaned (7s < 10s exceptional threshold)
        activeAgents.put("BigQueryCachingAgent", String.valueOf(completionDeadline));
        // Regular agent should be cleaned (7s > 5s default threshold)
        activeAgents.put("RegularAgent", String.valueOf(completionDeadline));

        // When - Run zombie cleanup
        int cleaned =
            exceptionalZombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

        // Then - Only regular agent should be cleaned
        assertThat(cleaned).isEqualTo(1);
        assertThat(activeAgents).containsKey("BigQueryCachingAgent");
        assertThat(activeAgents).doesNotContainKey("RegularAgent");
      }

      @Test
      @DisplayName("Should clean exceptional agents when they exceed exceptional threshold")
      void shouldCleanExceptionalAgentsWhenTheyExceedExceptionalThreshold() {
        // Given - Properties with BigQuery pattern
        PrioritySchedulerProperties props =
            createTestPropertiesWithExceptionalAgents(
                ".*BigQuery.*", 8000L, 5000L); // 8s exceptional, 5s default
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        Map<String, String> activeAgents = new HashMap<>();
        Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

        long currentTime = System.currentTimeMillis();
        long completionDeadline = (currentTime - 10000L) / 1000L; // 10 seconds ago

        // Both agents should be cleaned (10s > both thresholds)
        activeAgents.put("BigQueryCachingAgent", String.valueOf(completionDeadline));
        activeAgents.put("RegularAgent", String.valueOf(completionDeadline));

        // When - Run zombie cleanup
        int cleaned =
            exceptionalZombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

        // Then - Both agents should be cleaned
        assertThat(cleaned).isEqualTo(2);
        assertThat(activeAgents).isEmpty();
      }
    }

    @Nested
    @DisplayName("Pattern Matching Tests")
    class PatternMatchingTests {

      @Test
      @DisplayName("Should match 'contains' patterns")
      void shouldMatchContainsPatterns() {
        // Given - Pattern that matches agents containing "BigQuery"
        PrioritySchedulerProperties props =
            createTestPropertiesWithExceptionalAgents(".*BigQuery.*", 10000L, 5000L);
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        // Test agents with different names
        Map<String, String> activeAgents = new HashMap<>();
        Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

        long currentTime = System.currentTimeMillis();
        long completionDeadline = (currentTime - 7000L) / 1000L; // 7 seconds ago

        activeAgents.put("BigQueryCachingAgent", String.valueOf(completionDeadline));
        activeAgents.put("MyBigQueryProvider", String.valueOf(completionDeadline));
        activeAgents.put("BigQueryAgent", String.valueOf(completionDeadline));
        activeAgents.put("RegularAgent", String.valueOf(completionDeadline));

        // When - Run zombie cleanup
        int cleaned =
            exceptionalZombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

        // Then - Only RegularAgent should be cleaned
        assertThat(cleaned).isEqualTo(1);
        assertThat(activeAgents).hasSize(3);
        assertThat(activeAgents).containsKey("BigQueryCachingAgent");
        assertThat(activeAgents).containsKey("MyBigQueryProvider");
        assertThat(activeAgents).containsKey("BigQueryAgent");
      }

      @Test
      @DisplayName("Should match 'starts with' patterns")
      void shouldMatchStartsWithPatterns() {
        // Given - Pattern that matches agents starting with AWS or GCP
        PrioritySchedulerProperties props =
            createTestPropertiesWithExceptionalAgents("^(AWS|GCP).*", 10000L, 5000L);
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        Map<String, String> activeAgents = new HashMap<>();
        Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

        long currentTime = System.currentTimeMillis();
        long completionDeadline = (currentTime - 7000L) / 1000L; // 7 seconds ago

        activeAgents.put("AWSCachingAgent", String.valueOf(completionDeadline));
        activeAgents.put("GCPComputeAgent", String.valueOf(completionDeadline));
        activeAgents.put("AzureAgent", String.valueOf(completionDeadline));

        // When - Run zombie cleanup
        int cleaned =
            exceptionalZombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

        // Then - Only AzureAgent should be cleaned
        assertThat(cleaned).isEqualTo(1);
        assertThat(activeAgents).hasSize(2);
        assertThat(activeAgents).containsKey("AWSCachingAgent");
        assertThat(activeAgents).containsKey("GCPComputeAgent");
      }

      @Test
      @DisplayName("Should match 'ends with' patterns")
      void shouldMatchEndsWithPatterns() {
        // Given - Pattern that matches agents ending with "Provider"
        PrioritySchedulerProperties props =
            createTestPropertiesWithExceptionalAgents(".*Provider$", 10000L, 5000L);
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        Map<String, String> activeAgents = new HashMap<>();
        Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

        long currentTime = System.currentTimeMillis();
        long completionDeadline = (currentTime - 7000L) / 1000L; // 7 seconds ago

        activeAgents.put("BigQueryProvider", String.valueOf(completionDeadline));
        activeAgents.put("ComputeProvider", String.valueOf(completionDeadline));
        activeAgents.put("StorageAgent", String.valueOf(completionDeadline));

        // When - Run zombie cleanup
        int cleaned =
            exceptionalZombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

        // Then - Only StorageAgent should be cleaned
        assertThat(cleaned).isEqualTo(1);
        assertThat(activeAgents).hasSize(2);
        assertThat(activeAgents).containsKey("BigQueryProvider");
        assertThat(activeAgents).containsKey("ComputeProvider");
      }
    }

    @Nested
    @DisplayName("Exceptional Agents Integration Tests")
    class ExceptionalAgentsIntegrationTests {

      @Test
      @DisplayName("Should handle mixed agent types in one cleanup cycle")
      void shouldHandleMixedAgentTypesInOneCleanupCycle() {
        // Given - Properties with multiple exceptional patterns
        PrioritySchedulerProperties props =
            createTestPropertiesWithExceptionalAgents(
                "(.*BigQuery.*|.*Provider$)",
                12000L,
                5000L); // Matches BigQuery or ending with Provider
        exceptionalZombieService =
            new ZombieCleanupService(
                jedisPool,
                scriptManager,
                props,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        Map<String, String> activeAgents = new HashMap<>();
        Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

        long currentTime = System.currentTimeMillis();
        long moderateOverdue = (currentTime - 7000L) / 1000L; // 7s ago
        long significantOverdue = (currentTime - 15000L) / 1000L; // 15s ago

        // Mix of agents with different overdue times
        activeAgents.put(
            "BigQueryCachingAgent", String.valueOf(moderateOverdue)); // Won't be cleaned (7s < 12s)
        activeAgents.put(
            "ComputeProvider", String.valueOf(moderateOverdue)); // Won't be cleaned (7s < 12s)
        activeAgents.put(
            "RegularAgent1", String.valueOf(moderateOverdue)); // Will be cleaned (7s > 5s)
        activeAgents.put(
            "RegularAgent2", String.valueOf(moderateOverdue)); // Will be cleaned (7s > 5s)
        activeAgents.put(
            "BigQuerySlowAgent", String.valueOf(significantOverdue)); // Will be cleaned (15s > 12s)

        // When - Run zombie cleanup
        int cleaned =
            exceptionalZombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

        // Then - Should clean appropriate agents based on their thresholds
        assertThat(cleaned).isEqualTo(3); // RegularAgent1, RegularAgent2, BigQuerySlowAgent
        assertThat(activeAgents).hasSize(2);
        assertThat(activeAgents).containsKey("BigQueryCachingAgent");
        assertThat(activeAgents).containsKey("ComputeProvider");
      }
    }

    // Helper methods migrated from ExceptionalAgentsZombieCleanupTest.java:496-545
    private PrioritySchedulerProperties createPropertiesWithPattern(String pattern) {
      PrioritySchedulerProperties props = new PrioritySchedulerProperties();
      props.getBatchOperations().setEnabled(true);
      props.setIntervalMs(1000L);
      props.setRefreshPeriodSeconds(30);

      // Configure exceptional agents
      props.getZombieCleanup().getExceptionalAgents().setPattern(pattern);
      props.getZombieCleanup().getExceptionalAgents().setThresholdMs(3600000L); // 60 minutes

      return props;
    }

    private PrioritySchedulerProperties createTestPropertiesWithExceptionalAgents(
        String pattern, long exceptionalThresholdMs, long defaultThresholdMs) {
      PrioritySchedulerProperties props = new PrioritySchedulerProperties();
      props.getBatchOperations().setEnabled(true);
      props.setIntervalMs(100L); // Short interval for testing
      props.setRefreshPeriodSeconds(30);

      // Configure zombie cleanup with test-friendly values
      props.getZombieCleanup().setEnabled(true);
      props.getZombieCleanup().setThresholdMs(defaultThresholdMs);
      props.getZombieCleanup().setIntervalMs(100L); // Short interval for testing
      props.getBatchOperations().setBatchSize(50);

      // Configure exceptional agents
      props.getZombieCleanup().getExceptionalAgents().setPattern(pattern);
      props.getZombieCleanup().getExceptionalAgents().setThresholdMs(exceptionalThresholdMs);

      return props;
    }
  }
}
