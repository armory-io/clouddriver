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
import static org.mockito.Mockito.when;

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
 * Comprehensive test suite for OrphanCleanupService using testcontainers.
 *
 * <p>Tests cover: - Orphaned agent detection in both WORKING and WAITING sets - Different threshold
 * handling for each set - Batch processing of orphaned agents - Configuration-driven cleanup
 * enabling/disabling - Error handling and edge cases - Performance under various load conditions
 */
@Testcontainers
@DisplayName("OrphanCleanupService Tests")
class OrphanCleanupServiceTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private OrphanCleanupService orphanService;
  private ClusteredSortSchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    scriptManager = new RedisScriptManager(jedisPool);
    scriptManager.initializeScripts();

    schedulerProperties = new ClusteredSortSchedulerProperties();
    schedulerProperties.getOrphanCleanup().setThresholdMs(60000L); // 1 minute
    schedulerProperties.getOrphanCleanup().setIntervalMs(30000L); // 30 seconds
    schedulerProperties.getOrphanCleanup().setEnabled(true);
    schedulerProperties.getOrphanCleanup().setBatchSize(50);

    orphanService = new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties);
  }

  @Nested
  @DisplayName("Orphan Detection Tests")
  class OrphanDetectionTests {

    @Test
    @DisplayName("Should detect orphaned agents in WORKING set")
    void shouldDetectOrphanedAgentsInWorkingSet() {
      // Given - Clean up and add old agents to WORKING set
      long oldScore =
          System.currentTimeMillis() - 120000; // 2 minutes ago (older than 1 minute threshold)
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        jedis.zadd("WORKZ", oldScore, "orphan-1");
        jedis.zadd("WORKZ", oldScore - 1000, "orphan-2");
      }

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(2);
      assertThat(orphanService.getOrphansCleanedUp()).isEqualTo(2);

      // Verify agents were removed from Redis
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should detect orphaned agents in WAITING set with longer threshold")
    void shouldDetectOrphanedAgentsInWaitingSetWithLongerThreshold() {
      // Given - Clean up and add old agents to WAITING set
      // WAITING set uses 2x threshold (2 minutes)
      long oldScore = System.currentTimeMillis() - 150000; // 2.5 minutes ago
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        jedis.zadd("WAITZ", oldScore, "waiting-orphan-1");
        jedis.zadd("WAITZ", oldScore - 1000, "waiting-orphan-2");
      }

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(2);

      // Verify agents were removed from Redis
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WAITZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should not clean agents within threshold in WORKING set")
    void shouldNotCleanAgentsWithinThresholdInWorkingSet() {
      // Given - Add recent agents to WORKING set
      long recentScore =
          System.currentTimeMillis() - 30000; // 30 seconds ago (within 1 minute threshold)
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", recentScore, "recent-agent");
      }

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(0);

      // Verify agent was NOT removed
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WORKZ", "recent-agent")).isEqualTo(recentScore);
      }
    }

    @Test
    @DisplayName("Should not clean agents within threshold in WAITING set")
    void shouldNotCleanAgentsWithinThresholdInWaitingSet() {
      // Given - Add agents within WAITING threshold (2 minutes)
      long recentScore =
          System.currentTimeMillis() - 90000; // 1.5 minutes ago (within 2 minute threshold)
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WAITZ", recentScore, "waiting-recent");
      }

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(0);

      // Verify agent was NOT removed
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WAITZ", "waiting-recent")).isEqualTo(recentScore);
      }
    }

    @Test
    @DisplayName("Should clean orphans from both sets in single operation")
    void shouldCleanOrphansFromBothSetsInSingleOperation() {
      // Given - Add orphans to both sets
      long workingOrphanScore =
          System.currentTimeMillis() - 120000; // 2 minutes (WORKING threshold: 1 min)
      long waitingOrphanScore =
          System.currentTimeMillis() - 180000; // 3 minutes (WAITING threshold: 2 min)

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", workingOrphanScore, "working-orphan");
        jedis.zadd("WAITZ", waitingOrphanScore, "waiting-orphan");
      }

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(2);

      // Verify both sets are cleaned
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
        assertThat(jedis.zcard("WAITZ")).isEqualTo(0);
      }
    }
  }

  @Nested
  @DisplayName("Configuration Tests")
  class ConfigurationTests {

    @Test
    @DisplayName("Should skip cleanup when disabled")
    void shouldSkipCleanupWhenDisabled() {
      // Given - Disable orphan cleanup
      schedulerProperties.getOrphanCleanup().setEnabled(false);
      orphanService = new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties);

      // Add orphaned agents
      long oldScore = System.currentTimeMillis() - 120000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScore, "orphan");
      }

      // When
      orphanService.cleanupOrphanedAgentsIfNeeded();

      // Then - Agent should still be there
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WORKZ", "orphan")).isEqualTo(oldScore);
      }
    }

    @Test
    @DisplayName("Should respect batch size configuration")
    void shouldRespectBatchSizeConfiguration() {
      // Given - Set small batch size
      schedulerProperties.getOrphanCleanup().setBatchSize(2);
      orphanService = new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties);

      // Add more orphans than batch size
      long oldScore = System.currentTimeMillis() - 120000;
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        for (int i = 0; i < 5; i++) {
          jedis.zadd("WORKZ", oldScore - i, "orphan-" + i);
        }
      }

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then - Should clean all orphans (multiple batches)
      assertThat(cleaned).isEqualTo(5);

      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should use configurable thresholds")
    void shouldUseConfigurableThresholds() {
      // Given - Set very short threshold
      schedulerProperties.getOrphanCleanup().setThresholdMs(5000L); // 5 seconds
      orphanService = new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties);

      // Add agent older than 5 seconds
      long oldScore = System.currentTimeMillis() - 10000; // 10 seconds ago
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        jedis.zadd("WORKZ", oldScore, "short-threshold-orphan");
      }

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Cleanup Interval Tests")
  class CleanupIntervalTests {

    @Test
    @DisplayName("Should respect cleanup interval timing")
    void shouldRespectCleanupIntervalTiming() {
      // Given
      orphanService.cleanupOrphanedAgentsIfNeeded();
      long firstCleanupTime = orphanService.getLastOrphanCleanup();

      // Immediate second call (should be skipped due to interval)
      orphanService.cleanupOrphanedAgentsIfNeeded();
      long secondCleanupTime = orphanService.getLastOrphanCleanup();

      // Then
      assertThat(firstCleanupTime).isEqualTo(secondCleanupTime);
    }

    @Test
    @DisplayName("Should perform cleanup after interval has elapsed")
    void shouldPerformCleanupAfterIntervalHasElapsed() throws InterruptedException {
      // Given - Set very short interval for testing
      schedulerProperties.getOrphanCleanup().setIntervalMs(100L); // 100ms
      orphanService = new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties);

      // First cleanup
      orphanService.cleanupOrphanedAgentsIfNeeded();
      long firstTime = orphanService.getLastOrphanCleanup();

      // Wait for interval to pass
      Thread.sleep(150);

      // Second cleanup
      orphanService.cleanupOrphanedAgentsIfNeeded();
      long secondTime = orphanService.getLastOrphanCleanup();

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

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then - Should not crash and return 0
      assertThat(cleaned).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle empty Redis sets gracefully")
    void shouldHandleEmptyRedisSetsGracefully() {
      // Given - Empty Redis (no agents)

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle script execution failures gracefully")
    void shouldHandleScriptExecutionFailuresGracefully() {
      // Given - Invalid script manager
      RedisScriptManager invalidScriptManager = mock(RedisScriptManager.class);
      when(invalidScriptManager.getScriptSha(RedisScriptManager.BATCH_ORPHAN_REMOVE_SCRIPT))
          .thenReturn("invalid-sha");

      OrphanCleanupService invalidService =
          new OrphanCleanupService(jedisPool, invalidScriptManager, schedulerProperties);

      // Add orphaned agent
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", System.currentTimeMillis() - 120000, "orphan");
      }

      // When
      int cleaned = invalidService.cleanupOrphanedAgents();

      // Then - Should handle error gracefully
      assertThat(cleaned).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Performance Tests")
  class PerformanceTests {

    @Test
    @DisplayName("Should handle large numbers of orphaned agents efficiently")
    void shouldHandleLargeNumbersOfOrphanedAgentsEfficiently() {
      // Given - Add many orphaned agents
      int orphanCount = 1000;
      long oldScore = System.currentTimeMillis() - 120000;

      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        for (int i = 0; i < orphanCount / 2; i++) {
          jedis.zadd("WORKZ", oldScore - i, "working-orphan-" + i);
          jedis.zadd(
              "WAITZ", oldScore - 120000 - i, "waiting-orphan-" + i); // Even older for WAITING
        }
      }

      long startTime = System.currentTimeMillis();

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      long duration = System.currentTimeMillis() - startTime;

      // Then
      assertThat(cleaned).isEqualTo(orphanCount);
      assertThat(duration).isLessThan(10000); // Should complete within 10 seconds

      // Verify all orphans were cleaned
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
        assertThat(jedis.zcard("WAITZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should handle mixed orphan and active agents efficiently")
    void shouldHandleMixedOrphanAndActiveAgentsEfficiently() {
      // Given - Mix of orphaned and active agents
      long orphanScore = System.currentTimeMillis() - 120000; // Orphans
      long activeScore = System.currentTimeMillis() - 30000; // Active (recent)

      try (Jedis jedis = jedisPool.getResource()) {
        // Add orphaned agents
        for (int i = 0; i < 250; i++) {
          jedis.zadd("WORKZ", orphanScore - i, "orphan-" + i);
        }
        // Add active agents
        for (int i = 0; i < 250; i++) {
          jedis.zadd("WORKZ", activeScore - i, "active-" + i);
        }
      }

      // When
      int cleaned = orphanService.cleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(250); // Only orphans cleaned

      // Verify only active agents remain
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(250);
      }
    }
  }

  @Nested
  @DisplayName("Metrics and Monitoring Tests")
  class MetricsAndMonitoringTests {

    @Test
    @DisplayName("Should track total orphans cleaned up")
    void shouldTrackTotalOrphansCleanedUp() {
      // Given
      long oldScore = System.currentTimeMillis() - 120000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScore, "orphan-1");
        jedis.zadd("WORKZ", oldScore - 1000, "orphan-2");
      }

      long initialCount = orphanService.getOrphansCleanedUp();

      // When
      orphanService.cleanupOrphanedAgents();

      // Then
      assertThat(orphanService.getOrphansCleanedUp()).isEqualTo(initialCount + 2);
    }

    @Test
    @DisplayName("Should track last cleanup timestamp")
    void shouldTrackLastCleanupTimestamp() {
      // Given
      long beforeCleanup = System.currentTimeMillis();

      // When
      orphanService.cleanupOrphanedAgentsIfNeeded();

      // Then
      long lastCleanup = orphanService.getLastOrphanCleanup();
      assertThat(lastCleanup).isGreaterThanOrEqualTo(beforeCleanup);
    }

    @Test
    @DisplayName("Should accumulate cleanup counts across multiple runs")
    void shouldAccumulateCleanupCountsAcrossMultipleRuns() {
      // Given - First batch of orphans
      long oldScore = System.currentTimeMillis() - 120000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScore, "batch1-orphan-1");
        jedis.zadd("WORKZ", oldScore - 1000, "batch1-orphan-2");
      }

      // When - First cleanup
      orphanService.cleanupOrphanedAgents();
      long firstCount = orphanService.getOrphansCleanedUp();

      // Add second batch
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScore, "batch2-orphan-1");
        jedis.zadd("WORKZ", oldScore - 1000, "batch2-orphan-2");
        jedis.zadd("WORKZ", oldScore - 2000, "batch2-orphan-3");
      }

      // Second cleanup
      orphanService.cleanupOrphanedAgents();
      long secondCount = orphanService.getOrphansCleanedUp();

      // Then
      assertThat(firstCount).isEqualTo(2);
      assertThat(secondCount).isEqualTo(5); // Accumulated total
    }
  }
}
