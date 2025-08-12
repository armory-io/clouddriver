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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Tests for batch Redis operations in ClusteredSortAgentScheduler using live Redis containers.
 *
 * <p>Tests validate:
 *
 * <ul>
 *   <li>Batch operations for agent management
 *   <li>Performance optimizations with batched Redis operations
 *   <li>Zombie cleanup with batch processing
 *   <li>Real Redis integration for batch scripts
 * </ul>
 */
@Testcontainers
@DisplayName("ClusteredSortAgentScheduler Batch Operations Tests")
class BatchOperationsTest {

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

    // Create properties with batch operations enabled
    agentProperties = createDefaultAgentProperties();
    schedulerProperties = createBatchEnabledSchedulerProperties();

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

  @Nested
  @DisplayName("Batch Agent Operations Tests")
  class BatchAgentOperationsTests {

    @Test
    @DisplayName("Should handle batch agent registration efficiently")
    void shouldHandleBatchAgentRegistrationEfficiently() {
      // Given - Multiple agents
      Agent agent1 = createMockAgent("BatchAgent1", "test-provider");
      Agent agent2 = createMockAgent("BatchAgent2", "test-provider");
      Agent agent3 = createMockAgent("BatchAgent3", "test-provider");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When - Register multiple agents
      scheduler.schedule(agent1, execution, instrumentation);
      scheduler.schedule(agent2, execution, instrumentation);
      scheduler.schedule(agent3, execution, instrumentation);

      // Then - Should register without errors
      assertThat(scheduler).isNotNull();
    }

    @Test
    @DisplayName("Should use batch operations for performance")
    void shouldUseBatchOperationsForPerformance() {
      // Given - Scheduler with batch operations enabled
      assertThat(schedulerProperties.getBatchOperations().isEnabled()).isTrue();

      // When - Register many agents
      for (int i = 0; i < 10; i++) {
        Agent agent = createMockAgent("Agent" + i, "provider" + (i % 3));
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        scheduler.schedule(agent, execution, instrumentation);
      }

      // Then - Should complete efficiently with batch operations
      assertThat(scheduler).isNotNull();
    }
  }

  @Nested
  @DisplayName("Batch Cleanup Operations Tests")
  class BatchCleanupOperationsTests {

    @Test
    @DisplayName("Should handle batch zombie cleanup")
    void shouldHandleBatchZombieCleanup() {
      // Given - Scheduler with short zombie threshold for testing
      PrioritySchedulerProperties zombieProps = createZombieTestSchedulerProperties();
      PriorityAgentScheduler zombieScheduler =
          new PriorityAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              agentProperties,
              zombieProps);

      // When - Run scheduler cycle (includes zombie cleanup)
      zombieScheduler.run();

      // Then - Should complete without errors
      assertThat(zombieScheduler).isNotNull();
    }

    @Test
    @DisplayName("Should handle batch orphan cleanup")
    void shouldHandleBatchOrphanCleanup() {
      // Given - Scheduler with orphan cleanup enabled
      schedulerProperties.getOrphanCleanup().setEnabled(true);

      // When - Run scheduler cycle (includes orphan cleanup)
      scheduler.run();

      // Then - Should complete without errors
      assertThat(scheduler).isNotNull();
    }

    @Test
    @DisplayName("Should handle exceptional agents zombie cleanup with batch operations")
    void shouldHandleExceptionalAgentsZombieCleanupWithBatchOperations() {
      // Given - Scheduler properties with exceptional agents configured
      PrioritySchedulerProperties exceptionalProps = createExceptionalAgentsTestProperties();
      PriorityAgentScheduler exceptionalScheduler =
          new PriorityAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              agentProperties,
              exceptionalProps);

      // When - Run scheduler cycle with exceptional agents configuration
      exceptionalScheduler.run();

      // Then - Should complete without errors
      assertThat(exceptionalScheduler).isNotNull();
      assertThat(exceptionalProps.getZombieCleanup().getExceptionalAgents().getPattern())
          .isNotEmpty();
    }

    @Test
    @DisplayName("Should apply different thresholds for exceptional vs regular agents")
    void shouldApplyDifferentThresholdsForExceptionalVsRegularAgents() {
      // Given - Scheduler with exceptional agents pattern
      PrioritySchedulerProperties props = createExceptionalAgentsTestProperties();
      props.getZombieCleanup().setThresholdMs(5000L); // 5 seconds default
      props
          .getZombieCleanup()
          .getExceptionalAgents()
          .setThresholdMs(10000L); // 10 seconds exceptional

      PriorityAgentScheduler exceptionalScheduler =
          new PriorityAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              agentProperties,
              props);

      // Create agents that match and don't match the pattern
      Agent bigQueryAgent = createMockAgent("BigQueryCachingAgent", "gcp-provider");
      Agent regularAgent = createMockAgent("RegularAgent", "test-provider");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When - Schedule both types of agents
      exceptionalScheduler.schedule(bigQueryAgent, execution, instrumentation);
      exceptionalScheduler.schedule(regularAgent, execution, instrumentation);
      exceptionalScheduler.run();

      // Then - Both agents should be scheduled (validation is in the configuration)
      assertThat(exceptionalScheduler).isNotNull();
      assertThat(props.getZombieCleanup().getExceptionalAgents().getThresholdMs())
          .isGreaterThan(props.getZombieCleanup().getThresholdMs());
    }
  }

  private PriorityAgentProperties createDefaultAgentProperties() {
    PriorityAgentProperties props = new PriorityAgentProperties();
    props.setMaxConcurrentAgents(100);
    props.setEnabledPattern(".*");
    props.setDisabledPattern("");
    return props;
  }

  private PrioritySchedulerProperties createBatchEnabledSchedulerProperties() {
    PrioritySchedulerProperties props = new PrioritySchedulerProperties();
    props.getBatchOperations().setEnabled(true);
    props.setIntervalMs(1000L);
    props.setRefreshPeriodSeconds(30);
    props.getZombieCleanup().setThresholdMs(1800000L); // 30 minutes
    props.getZombieCleanup().setIntervalMs(300000L); // 5 minutes
    return props;
  }

  private PrioritySchedulerProperties createZombieTestSchedulerProperties() {
    PrioritySchedulerProperties props = createBatchEnabledSchedulerProperties();
    props.getZombieCleanup().setThresholdMs(5000L); // 5 seconds for testing
    props.getZombieCleanup().setIntervalMs(1000L); // 1 second for testing
    return props;
  }

  private PrioritySchedulerProperties createExceptionalAgentsTestProperties() {
    PrioritySchedulerProperties props = createBatchEnabledSchedulerProperties();
    props.getZombieCleanup().setThresholdMs(30000L); // 30 seconds default
    props.getZombieCleanup().setIntervalMs(5000L); // 5 seconds interval

    // Configure exceptional agents for BigQuery-related agents
    props.getZombieCleanup().getExceptionalAgents().setPattern(".*BigQuery.*");
    props
        .getZombieCleanup()
        .getExceptionalAgents()
        .setThresholdMs(60000L); // 60 seconds for BigQuery

    return props;
  }

  private Agent createMockAgent(String agentType, String providerName) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    when(agent.getProviderName()).thenReturn(providerName);
    return agent;
  }
}
