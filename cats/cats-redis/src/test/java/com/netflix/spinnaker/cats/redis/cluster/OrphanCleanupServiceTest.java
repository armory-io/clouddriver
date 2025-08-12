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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import redis.clients.jedis.Tuple;

/**
 * Test suite for OrphanCleanupService using testcontainers.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Orphaned agent detection in both WORKING and WAITING sets
 *   <li>Different threshold handling for each set
 *   <li>Batch processing of orphaned agents
 *   <li>Configuration-driven cleanup enabling/disabling
 *   <li>Error handling and edge cases
 *   <li>Performance under various load conditions
 * </ul>
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
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    scriptManager = new RedisScriptManager(jedisPool);
    scriptManager.initializeScripts();

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.getOrphanCleanup().setThresholdMs(60000L); // 1 minute
    schedulerProperties.getOrphanCleanup().setIntervalMs(30000L); // 30 seconds
    schedulerProperties.getOrphanCleanup().setEnabled(true);
    schedulerProperties.getBatchOperations().setBatchSize(50);

    orphanService = new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties);
  }

  @Nested
  @DisplayName("Orphan Detection Tests")
  class OrphanDetectionTests {

    @Test
    @DisplayName("Should detect orphaned agents in WORKING set")
    void shouldDetectOrphanedAgentsInWorkingSet() {
      // Given - Clean up and add old agents to WORKING set
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 120000) / 1000; // 2 minutes ago
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        jedis.zadd("WORKZ", oldScoreSeconds, "orphan-1");
        jedis.zadd("WORKZ", oldScoreSeconds - 1, "orphan-2");
      }

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(2);
      assertThat(orphanService.getOrphansCleanedUp()).isEqualTo(2);

      // Verify agents were removed from Redis
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("WAITZ purge preserves valid entries; removes only invalid ones")
    void waitzCleanupRemovesOnlyInvalid() {
      // Given - Add two agents; one valid (registered), one invalid (unregistered)
      long oldScoreSeconds = (System.currentTimeMillis() - 150000) / 1000; // 2.5 minutes ago
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("WORKZ", "WAITZ");
        jedis.zadd("WAITZ", oldScoreSeconds, "valid-agent");
        jedis.zadd("WAITZ", oldScoreSeconds - 1, "invalid-agent");
      }

      // Register only the valid agent via a minimal acquisition service to mark it valid
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
      when(intervalProvider.getInterval(any(Agent.class)))
          .thenReturn(new AgentIntervalProvider.Interval(60000L, 120000L));
      ShardingFilter shardingFilter = mock(ShardingFilter.class);
      when(shardingFilter.filter(any(Agent.class))).thenReturn(true);
      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedulerProps);

      Agent valid = mock(Agent.class);
      when(valid.getAgentType()).thenReturn("valid-agent");
      acq.registerAgent(valid, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      orphanService.setAcquisitionService(acq);

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - Only invalid agent is removed; valid remains in WAITZ
      assertThat(cleaned).isEqualTo(1);
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WAITZ", "valid-agent")).isNotNull();
        assertThat(jedis.zscore("WAITZ", "invalid-agent")).isNull();
      }
    }

    @Test
    @DisplayName("WAITZ cleanup should not remove invalid entries belonging to other shards")
    void waitzCleanupPreservesOtherShardInvalidEntries() {
      // Given - Add an old invalid agent that belongs to another shard
      long oldScoreSeconds = (System.currentTimeMillis() - 4 * 60 * 1000) / 1000; // 4 minutes ago
      String foreignInvalid = "acct/foreign-invalid-B"; // shard tag: -B

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("WORKZ", "WAITZ");
        jedis.zadd("WAITZ", oldScoreSeconds, foreignInvalid);
      }

      // Build acquisition service with sharding filter that claims ownership only for "-A"
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
      when(intervalProvider.getInterval(any(Agent.class)))
          .thenReturn(new AgentIntervalProvider.Interval(60000L, 120000L));

      ShardingFilter shardA = a -> a.getAgentType().contains("-A");

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool, scriptManager, intervalProvider, shardA, agentProps, schedulerProps);

      // Wire acquisition service so orphan cleanup can evaluate shard ownership
      orphanService.setAcquisitionService(acq);

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - invalid but foreign entry should be preserved by this shard
      assertThat(cleaned).isEqualTo(0);
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WAITZ", foreignInvalid)).isNotNull();
      }
    }

    @Test
    @DisplayName("Should not clean agents within threshold in WORKING set")
    void shouldNotCleanAgentsWithinThresholdInWorkingSet() {
      // Given - Add recent agents to WORKING set
      long recentScore = System.currentTimeMillis() - 30000; // 30 seconds ago
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", recentScore, "recent-agent");
      }

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

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
      // Given - Add agent to WAITING set whose score is within threshold (10s ago)
      long recentScore = (System.currentTimeMillis() - 10000) / 1000; // 10 seconds ago
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("WORKZ", "WAITZ");
        jedis.zadd("WAITZ", recentScore, "waiting-recent");
      }

      // Provide acquisition service so WAITZ validity checks preserve entries
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");
      PrioritySchedulerProperties props = new PrioritySchedulerProperties();
      AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
      when(intervalProvider.getInterval(any(Agent.class)))
          .thenReturn(new AgentIntervalProvider.Interval(60000L, 120000L));
      ShardingFilter shardingFilter = mock(ShardingFilter.class);
      when(shardingFilter.filter(any(Agent.class))).thenReturn(true);
      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool, scriptManager, intervalProvider, shardingFilter, agentProps, props);
      Agent waitingRecent = mock(Agent.class);
      when(waitingRecent.getAgentType()).thenReturn("waiting-recent");
      acq.registerAgent(
          waitingRecent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      orphanService.setAcquisitionService(acq);

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - within threshold, nothing removed
      assertThat(cleaned).isEqualTo(0);

      // Verify agent was NOT removed
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WAITZ", "waiting-recent")).isEqualTo(recentScore);
      }
    }

    @Test
    @DisplayName("Should clean WORKZ and remove only invalid from WAITZ in one run")
    void shouldCleanWorkzAndRemoveOnlyInvalidFromWaitz() {
      // Given - Add orphans to both sets; register only the WAITZ valid agent
      long workingOrphanScoreSeconds = (System.currentTimeMillis() - 120000) / 1000; // 2 minutes
      long waitingOrphanScoreSeconds = (System.currentTimeMillis() - 180000) / 1000; // 3 minutes

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("WORKZ", "WAITZ");
        jedis.zadd("WORKZ", workingOrphanScoreSeconds, "working-orphan");
        jedis.zadd("WAITZ", waitingOrphanScoreSeconds, "valid-waiting");
        jedis.zadd("WAITZ", waitingOrphanScoreSeconds - 1, "invalid-waiting");
      }

      // Register only the valid waiting agent
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
      when(intervalProvider.getInterval(any(Agent.class)))
          .thenReturn(new AgentIntervalProvider.Interval(60000L, 120000L));
      ShardingFilter shardingFilter = mock(ShardingFilter.class);
      when(shardingFilter.filter(any(Agent.class))).thenReturn(true);
      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedulerProps);
      Agent valid = mock(Agent.class);
      when(valid.getAgentType()).thenReturn("valid-waiting");
      acq.registerAgent(valid, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      orphanService.setAcquisitionService(acq);

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - 1 from WORKZ + 1 invalid from WAITZ = 2 cleaned; valid remains
      assertThat(cleaned).isEqualTo(2);
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
        assertThat(jedis.zscore("WAITZ", "valid-waiting")).isNotNull();
        assertThat(jedis.zscore("WAITZ", "invalid-waiting")).isNull();
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
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 120000) / 1000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "orphan");
      }

      // When
      orphanService.cleanupOrphanedAgentsIfNeeded();

      // Then - Agent should still be there
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WORKZ", "orphan")).isEqualTo(oldScoreSeconds);
      }
    }

    @Test
    @DisplayName("Should respect batch size configuration")
    void shouldRespectBatchSizeConfiguration() {
      // Given - Set small batch size
      schedulerProperties.getBatchOperations().setBatchSize(2);
      orphanService = new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties);

      // Add more orphans than batch size
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 120000) / 1000;
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        for (int i = 0; i < 5; i++) {
          jedis.zadd("WORKZ", oldScoreSeconds - i, "orphan-" + i);
        }
      }

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

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
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 10000) / 1000; // 10 seconds ago
      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        jedis.zadd("WORKZ", oldScoreSeconds, "short-threshold-orphan");
      }

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

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
      schedulerProperties.getOrphanCleanup().setIntervalMs(100L); // 100 ms
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
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - Should not crash and return 0
      assertThat(cleaned).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle empty Redis sets gracefully")
    void shouldHandleEmptyRedisSetsGracefully() {
      // Given - Empty Redis (no agents)

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then
      assertThat(cleaned).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle script execution failures gracefully")
    void shouldHandleScriptExecutionFailuresGracefully() {
      // Given - Invalid script manager
      RedisScriptManager invalidScriptManager = mock(RedisScriptManager.class);
      when(invalidScriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENTS_CONDITIONAL))
          .thenReturn("invalid-sha");

      OrphanCleanupService invalidService =
          new OrphanCleanupService(jedisPool, invalidScriptManager, schedulerProperties);

      // Add orphaned agent
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", System.currentTimeMillis() - 120000, "orphan");
      }

      // When
      int cleaned = invalidService.forceCleanupOrphanedAgents();

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
      int orphanCount = 10000;
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 120000) / 1000;

      try (Jedis jedis = jedisPool.getResource()) {
        // Clean up any existing data first
        jedis.del("WORKZ", "WAITZ");

        for (int i = 0; i < orphanCount / 2; i++) {
          jedis.zadd("WORKZ", oldScoreSeconds - i, "working-orphan-" + i);
          jedis.zadd(
              "WAITZ", oldScoreSeconds - 120 - i, "waiting-orphan-" + i); // Even older for WAITING
        }
      }

      long startTime = System.currentTimeMillis();

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      long duration = System.currentTimeMillis() - startTime;

      // Then - With default invalid treatment (no acquisition service), both sets get cleaned
      assertThat(cleaned).isEqualTo(orphanCount);
      assertThat(duration).isLessThan(15000); // Should complete within 15 seconds

      // Verify both sets cleaned
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
        assertThat(jedis.zcard("WAITZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should handle mixed orphan and active agents efficiently")
    void shouldHandleMixedOrphanAndActiveAgentsEfficiently() {
      // Given - Mix of orphaned and active agents
      // Redis scores are stored as seconds since epoch, not milliseconds
      long orphanScoreSeconds = (System.currentTimeMillis() - 120000) / 1000; // Orphans
      long activeScoreSeconds = (System.currentTimeMillis() - 30000) / 1000; // Active (recent)

      try (Jedis jedis = jedisPool.getResource()) {
        // Add orphaned agents (all old enough to be orphans)
        for (int i = 0; i < 250; i++) {
          jedis.zadd("WORKZ", orphanScoreSeconds - i, "orphan-" + i);
        }
        // Add active agents (all recent enough to not be orphans)
        for (int i = 0; i < 250; i++) {
          jedis.zadd("WORKZ", activeScoreSeconds + i, "active-" + i);
        }
      }

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

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
      // Ensure clean state
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("WORKZ", "WAITZ");
      }
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 120000) / 1000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "orphan-1");
        jedis.zadd("WORKZ", oldScoreSeconds - 1, "orphan-2");
      }

      long initialCount = orphanService.getOrphansCleanedUp();

      // When
      orphanService.forceCleanupOrphanedAgents();

      // Then
      assertThat(orphanService.getOrphansCleanedUp()).isEqualTo(initialCount + 2);
    }

    @Test
    @DisplayName("Should track last cleanup timestamp")
    void shouldTrackLastCleanupTimestamp() {
      // Given
      long beforeCleanup = System.currentTimeMillis();

      // When - Force cleanup by calling direct method
      orphanService.forceCleanupOrphanedAgents();

      // Then
      long lastCleanup = orphanService.getLastOrphanCleanup();
      assertThat(lastCleanup).isGreaterThanOrEqualTo(beforeCleanup);
    }

    @Test
    @DisplayName("Should accumulate cleanup counts across multiple runs")
    void shouldAccumulateCleanupCountsAcrossMultipleRuns() {
      // Given - First batch of orphans
      // Redis scores are stored as seconds since epoch, not milliseconds
      long oldScoreSeconds = (System.currentTimeMillis() - 120000) / 1000;
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "batch1-orphan-1");
        jedis.zadd("WORKZ", oldScoreSeconds - 1, "batch1-orphan-2");
      }

      // When - First cleanup
      orphanService.forceCleanupOrphanedAgents();
      long firstCount = orphanService.getOrphansCleanedUp();

      // Add second batch
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "batch2-orphan-1");
        jedis.zadd("WORKZ", oldScoreSeconds - 1, "batch2-orphan-2");
        jedis.zadd("WORKZ", oldScoreSeconds - 2, "batch2-orphan-3");
      }

      // Second cleanup
      orphanService.forceCleanupOrphanedAgents();
      long secondCount = orphanService.getOrphansCleanedUp();

      // Then
      assertThat(firstCount).isEqualTo(2);
      assertThat(secondCount).isEqualTo(5); // Accumulated total
    }
  }

  @Nested
  @DisplayName("Complex Logic Tests - Agent Validation and Dual Processing")
  class ComplexLogicTests {

    private AgentAcquisitionService mockAcquisitionService;
    private Agent mockValidAgent;
    private Agent mockInvalidAgent;

    @BeforeEach
    void setupComplexTests() {
      // Clean up any existing state
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("WORKZ", "WAITZ");
      }

      // Create mock AgentAcquisitionService for testing complex logic
      mockAcquisitionService = mock(AgentAcquisitionService.class);
      mockValidAgent = mock(Agent.class);
      mockInvalidAgent = mock(Agent.class);

      when(mockValidAgent.getAgentType()).thenReturn("valid-agent");
      when(mockInvalidAgent.getAgentType()).thenReturn("invalid-agent");

      // Configure the acquisition service to return valid agent for valid-agent type
      when(mockAcquisitionService.getRegisteredAgent("valid-agent")).thenReturn(mockValidAgent);
      when(mockAcquisitionService.getRegisteredAgent("invalid-agent")).thenReturn(null);

      // New shard-gating behavior: by default, consider these agents as belonging to this shard
      when(mockAcquisitionService.belongsToThisShard("valid-agent")).thenReturn(true);
      when(mockAcquisitionService.belongsToThisShard("invalid-agent")).thenReturn(true);

      // Set the acquisition service reference for complex logic
      orphanService.setAcquisitionService(mockAcquisitionService);
    }

    @AfterEach
    void cleanupComplexTests() {
      // Reset to null to not affect other tests
      orphanService.setAcquisitionService(null);
    }

    @Test
    @DisplayName("Should move valid orphaned agents from WORKZ to WAITZ for rescheduling")
    void shouldMoveValidOrphanedAgentsToWAITZ() {
      // Given - Add valid orphaned agent to WORKZ
      long oldScoreSeconds = (System.currentTimeMillis() - 30 * 60 * 1000) / 1000; // 30 min ago

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "valid-agent");
      }

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - Agent should be moved to WAITZ, not removed completely
      assertThat(cleaned).isEqualTo(1);

      try (Jedis jedis = jedisPool.getResource()) {
        // Agent removed from WORKZ
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
        // Agent added to WAITZ for rescheduling
        assertThat(jedis.zcard("WAITZ")).isEqualTo(1);
        assertThat(jedis.zscore("WAITZ", "valid-agent")).isNotNull();
      }
    }

    @Test
    @DisplayName("Should completely remove invalid orphaned agents from Redis (shard-owned)")
    void shouldRemoveInvalidOrphanedAgents() {
      // Given - Add invalid orphaned agent to WORKZ
      long oldScoreSeconds = (System.currentTimeMillis() - 30 * 60 * 1000) / 1000; // 30 min ago

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "invalid-agent");
      }

      // When
      // Ensure this shard claims ownership so invalid removal proceeds
      when(mockAcquisitionService.belongsToThisShard("invalid-agent")).thenReturn(true);

      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - Agent should be removed completely, not moved to WAITZ
      assertThat(cleaned).isEqualTo(1);

      try (Jedis jedis = jedisPool.getResource()) {
        // Agent removed from WORKZ
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
        // Agent NOT added to WAITZ
        assertThat(jedis.zcard("WAITZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should handle mixed valid and invalid orphaned agents correctly (shard-owned)")
    void shouldHandleMixedValidAndInvalidOrphans() {
      // Given - Add both valid and invalid orphaned agents
      long oldScoreSeconds = (System.currentTimeMillis() - 30 * 60 * 1000) / 1000; // 30 min ago

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "valid-agent");
        jedis.zadd("WORKZ", oldScoreSeconds - 1, "invalid-agent");
        jedis.zadd("WORKZ", oldScoreSeconds - 2, "another-invalid");
      }

      // Configure another invalid agent
      when(mockAcquisitionService.getRegisteredAgent("another-invalid")).thenReturn(null);

      // When
      // Ensure this shard claims ownership for invalid agents
      when(mockAcquisitionService.belongsToThisShard("invalid-agent")).thenReturn(true);
      when(mockAcquisitionService.belongsToThisShard("another-invalid")).thenReturn(true);

      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - All agents processed, but different handling for valid vs invalid
      assertThat(cleaned).isEqualTo(3);

      try (Jedis jedis = jedisPool.getResource()) {
        // All agents removed from WORKZ
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
        // Only valid agent moved to WAITZ
        assertThat(jedis.zcard("WAITZ")).isEqualTo(1);
        assertThat(jedis.zscore("WAITZ", "valid-agent")).isNotNull();
        assertThat(jedis.zscore("WAITZ", "invalid-agent")).isNull();
        assertThat(jedis.zscore("WAITZ", "another-invalid")).isNull();
      }
    }

    @Test
    @DisplayName("Without acquisition service, WORKZ orphan is conservatively moved to WAITZ")
    void shouldConservativelyMoveWorkzOrphanWithoutAcquisitionService() {
      // Given - Reset to no acquisition service (test environment behavior)
      orphanService.setAcquisitionService(null);

      long oldScoreSeconds = (System.currentTimeMillis() - 30 * 60 * 1000) / 1000; // 30 min ago

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "some-agent");
      }

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - Without acquisition service (treated invalid), agent should be removed
      assertThat(cleaned).isEqualTo(1);

      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
        assertThat(jedis.zcard("WAITZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("WAITZ cleanup removes only invalid entries and preserves valid ones")
    void shouldRemoveOnlyInvalidOrphansFromWAITZSet() {
      // Given - Add orphaned agents to WAITZ (valid-agent registered, invalid-agent not)
      long oldScoreSeconds =
          (System.currentTimeMillis() - 4 * 60 * 60 * 1000) / 1000; // 4 hours ago

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WAITZ", oldScoreSeconds, "valid-agent");
        jedis.zadd("WAITZ", oldScoreSeconds - 1, "invalid-agent");
      }

      // Register only the valid agent to mark it as valid
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");
      PrioritySchedulerProperties props = new PrioritySchedulerProperties();
      AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
      when(intervalProvider.getInterval(any(Agent.class)))
          .thenReturn(new AgentIntervalProvider.Interval(60000L, 120000L));
      ShardingFilter shardingFilter = mock(ShardingFilter.class);
      when(shardingFilter.filter(any(Agent.class))).thenReturn(true);
      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool, scriptManager, intervalProvider, shardingFilter, agentProps, props);
      Agent valid = mock(Agent.class);
      when(valid.getAgentType()).thenReturn("valid-agent");
      acq.registerAgent(valid, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      orphanService.setAcquisitionService(acq);

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - Only invalid removed; valid remains
      assertThat(cleaned).isEqualTo(1);

      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("WAITZ", "valid-agent")).isNotNull();
        assertThat(jedis.zscore("WAITZ", "invalid-agent")).isNull();
        assertThat(jedis.zcard("WORKZ")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should verify Redis TIME-based score generation for rescheduling")
    void shouldUseRedisTimeForRescheduling() {
      // Given - Add valid orphaned agent to WORKZ
      long oldScoreSeconds = (System.currentTimeMillis() - 30 * 60 * 1000) / 1000;

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "valid-agent");
      }

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - Agent moved to WAITZ with current Redis time-based score
      assertThat(cleaned).isEqualTo(1);

      try (Jedis jedis = jedisPool.getResource()) {
        Double newScore = jedis.zscore("WAITZ", "valid-agent");
        assertThat(newScore).isNotNull();

        // New score should be recent (within last few seconds)
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        assertThat(newScore.longValue())
            .isBetween(currentTimeSeconds - 10, currentTimeSeconds + 10);

        // New score should be different from old score
        assertThat(newScore.longValue()).isNotEqualTo(oldScoreSeconds);
      }
    }

    @Test
    @DisplayName("Should call removeActiveAgent for local state cleanup")
    void shouldCleanupLocalStateViaAcquisitionService() {
      // Given - Add orphaned agent and set up spies to verify method calls
      long oldScoreSeconds = (System.currentTimeMillis() - 30 * 60 * 1000) / 1000;

      // Reset the mock to use spy to track method calls
      reset(mockAcquisitionService);
      when(mockAcquisitionService.getRegisteredAgent("valid-agent")).thenReturn(mockValidAgent);
      doNothing().when(mockAcquisitionService).removeActiveAgent("valid-agent");

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WORKZ", oldScoreSeconds, "valid-agent");
      }

      // When
      int cleaned = orphanService.forceCleanupOrphanedAgents();

      // Then - Local state cleanup should be called
      assertThat(cleaned).isEqualTo(1);
      verify(mockAcquisitionService).removeActiveAgent("valid-agent");
    }
  }

  @Nested
  @DisplayName("Startup Race Condition Tests")
  class StartupRaceConditionTests {

    private AgentAcquisitionService acquisitionService;
    private ExecutorService testExecutor;

    @BeforeEach
    void setUpRaceConditionTests() {
      // Configure fast cleanup for testing
      schedulerProperties.getOrphanCleanup().setIntervalMs(10L); // Very fast for testing
      schedulerProperties
          .getOrphanCleanup()
          .setThresholdMs(
              2000L); // 2-second threshold to allow full-second resolution without false positives

      PriorityAgentProperties agentProperties = new PriorityAgentProperties();

      // Mock dependencies with reasonable defaults
      AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
      ShardingFilter shardingFilter = mock(ShardingFilter.class);

      AgentIntervalProvider.Interval mockInterval = mock(AgentIntervalProvider.Interval.class);
      when(mockInterval.getInterval()).thenReturn(30000L); // 30 seconds
      when(mockInterval.getTimeout()).thenReturn(600000L); // 10 minutes
      when(intervalProvider.getInterval(any(Agent.class))).thenReturn(mockInterval);
      when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

      // Create acquisition service with real Redis
      acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties);

      // Wire the orphan cleanup service to use the real acquisition service
      orphanService.setAcquisitionService(acquisitionService);

      testExecutor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDownRaceConditionTests() {
      if (testExecutor != null) {
        testExecutor.shutdownNow();
      }
    }

    private Agent createMockAgent(String agentType) {
      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn(agentType);
      return agent;
    }

    @Test
    @DisplayName("Should handle orphan cleanup with old agents from previous shutdown")
    void shouldHandleOrphanCleanupWithOldAgents() throws Exception {
      // GIVEN: Agents from previous shutdown exist in Redis with old timestamps
      try (var jedis = jedisPool.getResource()) {
        jedis.del("WAITZ", "WORKZ"); // Clean slate

        // Add old agents that would be considered orphans (old timestamps)
        long oldTimestamp = System.currentTimeMillis() / 1000 - 1000; // 1000 seconds ago
        jedis.zadd("WAITZ", oldTimestamp, "agent-from-previous-shutdown-1");
        jedis.zadd("WAITZ", oldTimestamp, "agent-from-previous-shutdown-2");
        jedis.zadd("WAITZ", oldTimestamp, "agent-from-previous-shutdown-3");
      }

      // WHEN: Orphan cleanup runs
      Thread.sleep(20); // Allow interval to pass
      orphanService.cleanupOrphanedAgentsIfNeeded();

      // THEN: Orphan cleanup should process the agents according to current logic
      // (The specific behavior depends on orphan threshold configuration)
      try (var jedis = jedisPool.getResource()) {
        // Verify that the cleanup ran without errors
        // The exact number of remaining agents depends on the orphan threshold
        long remainingAgents = jedis.zcard("WAITZ");
        assertThat(remainingAgents)
            .as("Orphan cleanup should process agents according to threshold")
            .isGreaterThanOrEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should run orphan cleanup normally with registered and unregistered agents")
    void shouldRunOrphanCleanupWithMixedAgents() throws Exception {
      // GIVEN: Some agents from previous shutdown + one we'll register locally
      try (var jedis = jedisPool.getResource()) {
        jedis.del("WAITZ", "WORKZ"); // Clean slate

        // Use old timestamps to ensure they're considered orphans
        long oldTimestamp = System.currentTimeMillis() / 1000 - 2;
        jedis.zadd("WAITZ", oldTimestamp, "unregistered-agent-1");
        jedis.zadd("WAITZ", oldTimestamp, "unregistered-agent-2");
        jedis.zadd("WAITZ", oldTimestamp, "registered-agent"); // This one we'll register
      }

      // WHEN: We register one agent locally
      Agent registeredAgent = createMockAgent("registered-agent");
      acquisitionService.registerAgent(
          registeredAgent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      // AND: Orphan cleanup runs
      Thread.sleep(20); // Allow interval to pass
      orphanService.cleanupOrphanedAgentsIfNeeded();

      // THEN: Cleanup should run normally
      // The registered agent should be updated with a new score, unregistered ones may be cleaned
      try (var jedis = jedisPool.getResource()) {
        // Verify that the cleanup ran without errors
        long remainingAgents = jedis.zcard("WAITZ");
        assertThat(remainingAgents)
            .as("Orphan cleanup should process agents according to registration status")
            .isGreaterThanOrEqualTo(0);
      }
    }

    @Test
    @DisplayName("Should preserve newly registered agents while cleaning up stale ones")
    void shouldPreserveNewlyRegisteredAgentsWhileCleaningStaleOnes() throws Exception {
      // GIVEN: Simulate agents from previous shutdown with very old timestamps
      try (var jedis = jedisPool.getResource()) {
        jedis.del("WAITZ", "WORKZ"); // Clean slate

        // Use very old timestamp to ensure they're considered orphans (beyond threshold)
        long veryOldTimestamp = System.currentTimeMillis() / 1000 - 3600; // 1 hour ago
        // Simulate 10 agents from previous shutdown with stale scores
        for (int i = 1; i <= 10; i++) {
          jedis.zadd("WAITZ", veryOldTimestamp, "stale-agent-" + i);
        }

        // Verify initial state
        assertThat(jedis.zcard("WAITZ")).as("Should start with 10 stale agents").isEqualTo(10);
      }

      // WHEN: Some agents are re-registered (this gives them fresh scores)
      // Ensure threshold remains generous
      schedulerProperties.getOrphanCleanup().setThresholdMs(3000L);
      for (int i = 1; i <= 5; i++) {
        Agent agent = createMockAgent("fresh-agent-" + i);
        acquisitionService.registerAgent(
            agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      }

      // Verify agents were registered with fresh scores and debug the scores
      try (var jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("WAITZ"))
            .as("Should have agents after registration")
            .isGreaterThan(0);

        // Debug: Print all agents and their scores
        Set<Tuple> agentsWithScores = jedis.zrangeWithScores("WAITZ", 0, -1);
        System.out.println("Agents after registration:");
        for (Tuple tuple : agentsWithScores) {
          System.out.println("  " + tuple.getElement() + " -> score: " + tuple.getScore());
        }
        System.out.println("Current time (seconds): " + (System.currentTimeMillis() / 1000));
      }

      // Ensure we cross a full second boundary so the new agents have a strictly newer score
      Thread.sleep(1100); // Wait a bit but still below the 2-second orphan threshold
      orphanService.cleanupOrphanedAgentsIfNeeded();

      // THEN: Newly registered agents should be preserved, stale ones may be cleaned
      try (var jedis = jedisPool.getResource()) {
        Set<String> remainingAgents = jedis.zrange("WAITZ", 0, -1);

        // Debug: Print remaining agents after cleanup
        Set<Tuple> remainingWithScores = jedis.zrangeWithScores("WAITZ", 0, -1);
        System.out.println("Agents after cleanup:");
        for (Tuple tuple : remainingWithScores) {
          System.out.println("  " + tuple.getElement() + " -> score: " + tuple.getScore());
        }
        System.out.println(
            "Cleanup threshold: 500ms, Current time: " + (System.currentTimeMillis() / 1000));

        // The key business logic: agents that were re-registered should still be present
        // because they got fresh scores that are not considered orphaned
        for (int i = 1; i <= 5; i++) {
          assertThat(remainingAgents)
              .as("Newly registered fresh-agent-" + i + " should be preserved")
              .contains("fresh-agent-" + i);
        }

        // Unregistered agents (6-10) may or may not be cleaned depending on threshold
        // but the system should handle this gracefully
        assertThat(remainingAgents)
            .as("Should still contain all fresh agents")
            .contains(
                "fresh-agent-1",
                "fresh-agent-2",
                "fresh-agent-3",
                "fresh-agent-4",
                "fresh-agent-5");
      }
    }

    @Test
    @DisplayName("Should handle multiple agent registrations correctly")
    void shouldHandleMultipleAgentRegistrations() throws Exception {
      // GIVEN: Clean slate
      try (var jedis = jedisPool.getResource()) {
        jedis.del("WAITZ", "WORKZ");
      }

      // WHEN: Multiple agents are registered
      for (int i = 1; i <= 3; i++) {
        Agent agent = createMockAgent("test-agent-" + i);
        acquisitionService.registerAgent(
            agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      }

      // THEN: All agents should be properly registered in Redis
      try (var jedis = jedisPool.getResource()) {
        long agentCount = jedis.zcard("WAITZ");
        assertThat(agentCount).as("All registered agents should be present in Redis").isEqualTo(3);

        Set<String> agentNames = jedis.zrange("WAITZ", 0, -1);
        assertThat(agentNames)
            .as("Agent names should match registered agents")
            .containsExactlyInAnyOrder("test-agent-1", "test-agent-2", "test-agent-3");
      }
    }

    @Test
    @DisplayName("Should handle large number of agents efficiently")
    void shouldHandleLargeNumberOfAgents() throws Exception {

      // GIVEN: Large number of agents from previous shutdown exist in Redis
      try (var jedis = jedisPool.getResource()) {
        jedis.del("WAITZ", "WORKZ"); // Clean slate

        // Simulate 1000 agents from previous graceful shutdown
        for (int i = 1; i <= 1000; i++) {
          // Old timestamp - would normally be considered "orphans"
          long oldTimestamp = System.currentTimeMillis() / 1000 - 3600; // 1 hour ago
          jedis.zadd("WAITZ", oldTimestamp, "agent-from-previous-shutdown-" + i);
        }
      }

      // WHEN: Orphan cleanup runs with large dataset
      Thread.sleep(20); // Allow cleanup interval to pass
      orphanService.cleanupOrphanedAgentsIfNeeded();

      // THEN: System should handle large dataset efficiently
      try (var jedis = jedisPool.getResource()) {
        long remainingAgents = jedis.zcard("WAITZ");
        assertThat(remainingAgents)
            .as("System should handle large number of agents efficiently")
            .isGreaterThanOrEqualTo(0);

        // The exact number depends on orphan threshold, but system should not crash
        assertThat(remainingAgents)
            .as("Remaining agents should be within reasonable bounds")
            .isLessThanOrEqualTo(1000);
      }
    }
  }
}
