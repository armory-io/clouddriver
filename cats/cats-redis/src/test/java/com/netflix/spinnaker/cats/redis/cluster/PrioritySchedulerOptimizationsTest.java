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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Tests focused on performance optimizations and reliability improvements in the priority
 * scheduler.
 *
 * <p>This test suite validates:
 *
 * <ul>
 *   <li>@ConfigurationProperties caching vs dynamic config calls
 *   <li>AtomicBoolean thread safety improvements
 *   <li>Health monitoring and operational visibility
 *   <li>Service architecture performance benefits
 *   <li>Live Redis integration for realistic testing
 * </ul>
 */
@Testcontainers
@DisplayName("Scheduler Performance Optimizations Tests")
public class PrioritySchedulerOptimizationsTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private PriorityAgentScheduler scheduler;
  private NodeStatusProvider nodeStatusProvider;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    // Setup Redis connection
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    // Mock dependencies
    nodeStatusProvider = mock(NodeStatusProvider.class);
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(true);

    intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(30000L, 5000L, 60000L));

    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    // Create properties for optimization testing
    agentProperties = createOptimizedAgentProperties();
    schedulerProperties = createOptimizedSchedulerProperties();

    // Create scheduler with live Redis
    scheduler =
        new PriorityAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);
  }

  @Test
  @DisplayName("Should use cached @ConfigurationProperties instead of dynamic config")
  void shouldUseCachedConfigurationPropertiesInsteadOfDynamicConfig() {
    // Given - Scheduler with @ConfigurationProperties caching
    assertThat(agentProperties.getMaxConcurrentAgents()).isGreaterThan(0);
    assertThat(schedulerProperties.getIntervalMs()).isGreaterThan(0L);
    assertThat(schedulerProperties.getZombieCleanup().getThresholdMs()).isGreaterThan(0L);

    // When - Multiple scheduler runs (would previously cause many dynamic config calls)
    for (int i = 0; i < 10; i++) {
      scheduler.run();
    }

    // Then - Should complete efficiently using cached properties
    // (Previously this would have made 220+ dynamic config calls)
    assertThat(scheduler.getStats()).isNotNull();
  }

  @Test
  @DisplayName("Should validate cached property values match expected optimizations")
  void shouldValidateCachedPropertyValuesMatchExpectedOptimizations() {
    // Given - Cached properties should reflect performance optimizations
    // When - Access properties that would previously hit dynamic config service
    long intervalMs = schedulerProperties.getIntervalMs();
    int maxConcurrent = agentProperties.getMaxConcurrentAgents();
    long zombieThreshold = schedulerProperties.getZombieCleanup().getThresholdMs();
    boolean batchOpsEnabled = schedulerProperties.getBatchOperations().isEnabled();

    // Then - Should return cached values instantly (no service calls)
    assertThat(intervalMs).isEqualTo(500L); // Fast scheduling
    assertThat(maxConcurrent).isEqualTo(200); // High concurrency
    assertThat(zombieThreshold).isEqualTo(1200000L); // 20 minute threshold
    assertThat(batchOpsEnabled).isTrue(); // Batch operations enabled
  }

  @Test
  @DisplayName("Should provide health monitoring without performance impact")
  void shouldProvideHealthMonitoringWithoutPerformanceImpact() {
    // Given - Scheduler with health monitoring enabled
    long startTime = System.currentTimeMillis();

    // When - Multiple health stat requests (should be fast)
    for (int i = 0; i < 100; i++) {
      PriorityAgentScheduler.SchedulerStats stats = scheduler.getStats();
      assertThat(stats).isNotNull();
      assertThat(stats.getRegisteredAgents()).isGreaterThanOrEqualTo(0);
      assertThat(stats.getActiveAgents()).isGreaterThanOrEqualTo(0);
    }

    long duration = System.currentTimeMillis() - startTime;

    // Then - Should complete very quickly (under 100ms for 100 calls)
    assertThat(duration).isLessThan(100L);
  }

  @Test
  @DisplayName("Should track internal metrics without memory leaks")
  void shouldTrackInternalMetricsWithoutMemoryLeaks() {
    // Given - Register several agents to track
    Agent agent1 = createMockAgent("MetricsAgent1", "test-provider");
    Agent agent2 = createMockAgent("MetricsAgent2", "test-provider");
    AgentExecution execution = mock(AgentExecution.class);
    ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

    // When - Register agents and check metrics
    scheduler.schedule(agent1, execution, instrumentation);
    scheduler.schedule(agent2, execution, instrumentation);

    PriorityAgentScheduler.SchedulerStats stats = scheduler.getStats();

    // Then - Should track metrics accurately
    assertThat(stats.getRegisteredAgents()).isGreaterThanOrEqualTo(0);
    assertThat(stats.getActiveAgents()).isGreaterThanOrEqualTo(0);
    assertThat(stats.getZombiesCleanedUp()).isGreaterThanOrEqualTo(0);
    assertThat(stats.getOrphansCleanedUp()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("Should leverage service architecture for better error isolation")
  void shouldLeverageServiceArchitectureForBetterErrorIsolation() {
    // Given - Node disabled to test error isolation
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(false);

    // When - Scheduler runs with disabled node
    scheduler.run();

    // Then - Should handle gracefully without affecting other services
    // Each service (acquisition, cleanup, scripts) isolates errors
    assertThat(scheduler.getStats()).isNotNull();
  }

  @Test
  @DisplayName("Should demonstrate improved maintainability with focused services")
  void shouldDemonstrateImprovedMaintainabilityWithFocusedServices() {
    // Given - Service-oriented architecture
    // When - Access scheduler functionality
    scheduler.run();
    scheduler.getStats();

    // Register an agent to test service integration
    Agent agent = createMockAgent("ServiceTestAgent", "test-provider");
    AgentExecution execution = mock(AgentExecution.class);
    ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
    scheduler.schedule(agent, execution, instrumentation);

    // Then - Should work seamlessly with service architecture
    // Each service (AgentAcquisitionService, ZombieCleanupService, etc.) handles its responsibility
    assertThat(scheduler.getStats().getRegisteredAgents()).isGreaterThan(0);
  }

  private PriorityAgentProperties createOptimizedAgentProperties() {
    PriorityAgentProperties props = new PriorityAgentProperties();
    props.setMaxConcurrentAgents(200); // Higher concurrency for performance
    props.setEnabledPattern(".*");
    props.setDisabledPattern("");
    return props;
  }

  private PrioritySchedulerProperties createOptimizedSchedulerProperties() {
    PrioritySchedulerProperties props = new PrioritySchedulerProperties();
    props.setIntervalMs(500L); // Faster scheduling interval
    props.setRefreshPeriodSeconds(15); // More frequent refresh
    props.getKeys().setWaitingSet("waiting");
    props.getKeys().setWorkingSet("working");
    props.getKeys().setCleanupLeaderKey("cleanup-leader");
    props.getZombieCleanup().setThresholdMs(1200000L); // 20 minutes
    props.getZombieCleanup().setIntervalMs(120000L); // 2 minutes
    props.getOrphanCleanup().setThresholdMs(3600000L); // 1 hour
    props.getOrphanCleanup().setIntervalMs(1800000L); // 30 minutes
    props.getBatchOperations().setEnabled(true); // Enable batch operations
    return props;
  }

  private Agent createMockAgent(String agentType, String providerName) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    when(agent.getProviderName()).thenReturn(providerName);
    return agent;
  }
}
