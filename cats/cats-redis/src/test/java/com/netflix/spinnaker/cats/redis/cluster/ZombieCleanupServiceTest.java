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
      // Given - Add old agent to WORKING set
      long oldScore = System.currentTimeMillis() - 60000; // 1 minute ago
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScore, "zombie-agent");
      }

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

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
      long oldScore = System.currentTimeMillis() - 60000;
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        jedis.zadd("WORKZ", oldScore, "zombie-1");
        jedis.zadd("WORKZ", oldScore - 1000, "zombie-2");
        jedis.zadd("WORKZ", oldScore - 2000, "zombie-3");
      }

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

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
      long oldScore = System.currentTimeMillis() - 60000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScore, "zombie-agent");
      }

      Map<String, String> activeAgents = new HashMap<>();
      activeAgents.put("zombie-agent", String.valueOf(oldScore));

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
      long oldScore = System.currentTimeMillis() - 60000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScore, "zombie-agent");
      }

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();
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
      long oldScore = System.currentTimeMillis() - 60000;

      try (Jedis jedis = jedisPool.getResource()) {
        for (int i = 0; i < zombieCount; i++) {
          jedis.zadd("WORKZ", oldScore - i, "zombie-" + i);
        }
      }

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

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
      long oldScore = System.currentTimeMillis() - 60000; // Zombies
      long recentScore = System.currentTimeMillis() - 10000; // Active

      try (Jedis jedis = jedisPool.getResource()) {
        // Add zombie agents
        for (int i = 0; i < 500; i++) {
          jedis.zadd("WORKZ", oldScore - i, "zombie-" + i);
        }
        // Add active agents
        for (int i = 0; i < 500; i++) {
          jedis.zadd("WORKZ", recentScore - i, "active-" + i);
        }
      }

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

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
      long oldScore = System.currentTimeMillis() - 60000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScore, "zombie-1");
        jedis.zadd("WORKZ", oldScore - 1000, "zombie-2");
      }

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

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
  }
}
