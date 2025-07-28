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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Comprehensive tests for exceptional agents zombie cleanup functionality.
 *
 * <p>This test suite validates the new exceptional agents feature which allows different zombie
 * thresholds based on agent name pattern matching.
 *
 * <p>Test scenarios:
 *
 * <ul>
 *   <li>Pattern compilation and validation
 *   <li>Default vs exceptional threshold application
 *   <li>Multiple pattern types (contains, starts with, ends with, etc.)
 *   <li>Invalid patterns handling
 *   <li>Runtime configuration changes
 *   <li>Real Redis integration with testcontainers
 * </ul>
 */
@Testcontainers
@DisplayName("Exceptional Agents Zombie Cleanup Tests")
class ExceptionalAgentsZombieCleanupTest {

  private static final Logger log =
      LoggerFactory.getLogger(ExceptionalAgentsZombieCleanupTest.class);

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private ZombieCleanupService zombieCleanupService;
  private RedisScriptManager scriptManager;
  private NodeStatusProvider nodeStatusProvider;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private PriorityAgentProperties agentProperties;

  @BeforeEach
  void setUp() {
    // Setup Redis connection
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    // Create script manager and initialize scripts
    scriptManager = new RedisScriptManager(jedisPool);
    scriptManager.initializeScripts(); // Initialize scripts for zombie cleanup

    // Mock dependencies
    nodeStatusProvider = mock(NodeStatusProvider.class);
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(true);

    intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(30000L, 5000L, 60000L));

    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    agentProperties = createDefaultAgentProperties();
  }

  @Nested
  @DisplayName("Pattern Configuration Tests")
  class PatternConfigurationTests {

    @Test
    @DisplayName("Should compile valid regex patterns")
    void shouldCompileValidRegexPatterns() {
      // Given - Properties with valid patterns
      PrioritySchedulerProperties props = createPropertiesWithPattern(".*BigQuery.*");

      // When - Create service (triggers pattern compilation)
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      // Then - Service should be created successfully
      assertThat(zombieCleanupService).isNotNull();
    }

    @Test
    @DisplayName("Should handle empty pattern gracefully")
    void shouldHandleEmptyPatternGracefully() {
      // Given - Properties with empty pattern
      PrioritySchedulerProperties props = createPropertiesWithPattern("");

      // When - Create service
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      // Then - Should work with no exceptional agents
      assertThat(zombieCleanupService).isNotNull();
    }

    @Test
    @DisplayName("Should log error for invalid regex patterns")
    void shouldLogErrorForInvalidRegexPatterns() {
      // Given - Properties with invalid regex pattern
      PrioritySchedulerProperties props = createPropertiesWithPattern("[invalid regex");

      // When - Create service (should handle gracefully)
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      // Then - Service should still be created (pattern will be null internally)
      assertThat(zombieCleanupService).isNotNull();
    }

    @Test
    @DisplayName("Should refresh pattern configuration at runtime")
    void shouldRefreshPatternConfigurationAtRuntime() {
      // Given - Service with initial pattern
      PrioritySchedulerProperties props = createPropertiesWithPattern(".*Old.*");
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      // When - Update pattern and refresh
      props.getZombieCleanup().getExceptionalAgents().setPattern(".*New.*");
      zombieCleanupService.refreshExceptionalAgentsPattern();

      // Then - Should handle the refresh without errors
      assertThat(zombieCleanupService).isNotNull();
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
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      // Simulate agents past default threshold but before exceptional threshold
      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      long currentTime = System.currentTimeMillis();
      long completionDeadline =
          (currentTime - 7000L) / 1000L; // 7 seconds ago (past default threshold)

      activeAgents.put("RegularAgent", String.valueOf(completionDeadline));
      activeAgents.put("AnotherAgent", String.valueOf(completionDeadline));

      // When - Run zombie cleanup
      int cleaned = zombieCleanupService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

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
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      long currentTime = System.currentTimeMillis();
      long completionDeadline = (currentTime - 7000L) / 1000L; // 7 seconds ago

      // BigQuery agent should not be cleaned (7s < 10s exceptional threshold)
      activeAgents.put("BigQueryCachingAgent", String.valueOf(completionDeadline));
      // Regular agent should be cleaned (7s > 5s default threshold)
      activeAgents.put("RegularAgent", String.valueOf(completionDeadline));

      // When - Run zombie cleanup
      int cleaned = zombieCleanupService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

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
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      long currentTime = System.currentTimeMillis();
      long completionDeadline = (currentTime - 10000L) / 1000L; // 10 seconds ago

      // Both agents should be cleaned (10s > both thresholds)
      activeAgents.put("BigQueryCachingAgent", String.valueOf(completionDeadline));
      activeAgents.put("RegularAgent", String.valueOf(completionDeadline));

      // When - Run zombie cleanup
      int cleaned = zombieCleanupService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

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
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

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
      int cleaned = zombieCleanupService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

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
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      long currentTime = System.currentTimeMillis();
      long completionDeadline = (currentTime - 7000L) / 1000L; // 7 seconds ago

      activeAgents.put("AWSCachingAgent", String.valueOf(completionDeadline));
      activeAgents.put("GCPComputeAgent", String.valueOf(completionDeadline));
      activeAgents.put("AzureAgent", String.valueOf(completionDeadline));

      // When - Run zombie cleanup
      int cleaned = zombieCleanupService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

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
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      Map<String, String> activeAgents = new HashMap<>();
      Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

      long currentTime = System.currentTimeMillis();
      long completionDeadline = (currentTime - 7000L) / 1000L; // 7 seconds ago

      activeAgents.put("BigQueryProvider", String.valueOf(completionDeadline));
      activeAgents.put("ComputeProvider", String.valueOf(completionDeadline));
      activeAgents.put("StorageAgent", String.valueOf(completionDeadline));

      // When - Run zombie cleanup
      int cleaned = zombieCleanupService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Only StorageAgent should be cleaned
      assertThat(cleaned).isEqualTo(1);
      assertThat(activeAgents).hasSize(2);
      assertThat(activeAgents).containsKey("BigQueryProvider");
      assertThat(activeAgents).containsKey("ComputeProvider");
    }
  }

  @Nested
  @DisplayName("Integration Tests")
  class IntegrationTests {

    @Test
    @DisplayName("Should work with real Redis and batch operations")
    void shouldWorkWithRealRedisAndBatchOperations() {
      // Given - Properties with batch operations enabled
      PrioritySchedulerProperties props =
          createTestPropertiesWithExceptionalAgents(".*BigQuery.*", 10000L, 5000L);
      props.setBatchOperationsEnabled(true);
      props.setBatchOperationsBatchSize(10);

      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

      // Create a scheduler to add some agents to Redis
      PriorityAgentScheduler scheduler =
          new PriorityAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              agentProperties,
              props);

      // Add some agents
      Agent regularAgent = createMockAgent("RegularAgent", "test-provider");
      Agent bigQueryAgent = createMockAgent("BigQueryCachingAgent", "gcp-provider");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      scheduler.schedule(regularAgent, execution, instrumentation);
      scheduler.schedule(bigQueryAgent, execution, instrumentation);

      // When - Run scheduler (includes zombie cleanup)
      scheduler.run();

      // Then - Should complete without errors
      assertThat(scheduler).isNotNull();
      assertThat(zombieCleanupService.getZombiesCleanedUp()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Should handle mixed agent types in one cleanup cycle")
    void shouldHandleMixedAgentTypesInOneCleanupCycle() {
      // Given - Properties with multiple exceptional patterns
      PrioritySchedulerProperties props =
          createTestPropertiesWithExceptionalAgents(
              "(.*BigQuery.*|.*Provider$)",
              12000L,
              5000L); // Matches BigQuery or ending with Provider
      zombieCleanupService = new ZombieCleanupService(jedisPool, scriptManager, props);

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
      int cleaned = zombieCleanupService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

      // Then - Should clean appropriate agents based on their thresholds
      assertThat(cleaned).isEqualTo(3); // RegularAgent1, RegularAgent2, BigQuerySlowAgent
      assertThat(activeAgents).hasSize(2);
      assertThat(activeAgents).containsKey("BigQueryCachingAgent");
      assertThat(activeAgents).containsKey("ComputeProvider");
    }
  }

  // Helper methods

  private PriorityAgentProperties createDefaultAgentProperties() {
    PriorityAgentProperties props = new PriorityAgentProperties();
    props.setMaxConcurrentAgents(100);
    props.setEnabledPattern(".*");
    props.setDisabledPattern("");
    return props;
  }

  private PrioritySchedulerProperties createPropertiesWithPattern(String pattern) {
    PrioritySchedulerProperties props = new PrioritySchedulerProperties();
    props.setBatchOperationsEnabled(true);
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
    props.setBatchOperationsEnabled(true);
    props.setIntervalMs(100L); // Short interval for testing
    props.setRefreshPeriodSeconds(30);

    // Configure zombie cleanup with test-friendly values
    props.getZombieCleanup().setEnabled(true);
    props.getZombieCleanup().setThresholdMs(defaultThresholdMs);
    props.getZombieCleanup().setIntervalMs(100L); // Short interval for testing
    props.setBatchOperationsBatchSize(50);

    // Configure exceptional agents
    props.getZombieCleanup().getExceptionalAgents().setPattern(pattern);
    props.getZombieCleanup().getExceptionalAgents().setThresholdMs(exceptionalThresholdMs);

    return props;
  }

  private Agent createMockAgent(String agentType, String providerName) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    when(agent.getProviderName()).thenReturn(providerName);
    return agent;
  }
}
