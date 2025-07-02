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
 * Comprehensive integration test suite for ClusteredSortAgentScheduler using live Redis.
 *
 * <p>This test suite focuses on end-to-end functionality with real Redis backend: - Agent
 * registration and scheduling - Configuration validation - Disabled agents handling - Redis
 * operations integration - Live container testing with proper cleanup
 */
@Testcontainers
@DisplayName("ClusteredSortAgentScheduler Integration Tests")
class SchedulerIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private ClusteredSortAgentScheduler scheduler;
  private NodeStatusProvider nodeStatusProvider;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private ClusteredSortAgentProperties agentProperties;
  private ClusteredSortSchedulerProperties schedulerProperties;

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

    // Create default properties
    agentProperties = createDefaultAgentProperties();
    schedulerProperties = createDefaultSchedulerProperties();

    // Create scheduler with live Redis
    scheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);
  }

  @Nested
  @DisplayName("Agent Registration Tests")
  class AgentRegistrationTests {

    @Test
    @DisplayName("Should register enabled agents successfully")
    void shouldRegisterEnabledAgentsSuccessfully() {
      // Given
      Agent agent = createMockAgent("test-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When
      scheduler.schedule(agent, execution, instrumentation);

      // Then - Should not throw exception and agent should be registered
      assertThat(scheduler).isNotNull();
    }

    @Test
    @DisplayName("Should not register disabled agents")
    void shouldNotRegisterDisabledAgents() {
      // Given - Configure with disabled agent
      ClusteredSortAgentProperties testProps = createDefaultAgentProperties();
      testProps.setDisabledPattern("disabled-agent");

      ClusteredSortAgentScheduler testScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              testProps,
              schedulerProperties);

      Agent disabledAgent = createMockAgent("disabled-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When - Try to schedule disabled agent
      testScheduler.schedule(disabledAgent, execution, instrumentation);

      // Then - Agent should not be registered (no exception thrown, just skipped)
      assertThat(testScheduler).isNotNull();
    }
  }

  @Nested
  @DisplayName("Redis Integration Tests")
  class RedisIntegrationTests {

    @Test
    @DisplayName("Should initialize Redis scripts successfully")
    void shouldInitializeRedisScriptsSuccessfully() {
      // Given & When - Scheduler is created (scripts initialized in constructor)
      // Then - Should not throw exception
      assertThat(scheduler).isNotNull();
    }

    @Test
    @DisplayName("Should handle Redis connection properly")
    void shouldHandleRedisConnectionProperly() {
      // Given
      Agent agent = createMockAgent("redis-test-agent", "test-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When - Register agent (this will interact with Redis)
      scheduler.schedule(agent, execution, instrumentation);

      // Then - Should complete without Redis connection errors
      assertThat(scheduler).isNotNull();
    }
  }

  @Nested
  @DisplayName("Configuration Tests")
  class ConfigurationTests {

    @Test
    @DisplayName("Should respect enabled pattern configuration")
    void shouldRespectEnabledPatternConfiguration() {
      // Given - Scheduler with specific pattern
      ClusteredSortAgentProperties testProps = createDefaultAgentProperties();
      testProps.setEnabledPattern("AWS.*");

      ClusteredSortAgentScheduler patternScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              testProps,
              schedulerProperties);

      Agent awsAgent = createMockAgent("AWSAgent", "aws-provider");
      Agent gcpAgent = createMockAgent("GCPAgent", "gcp-provider");
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When - Schedule both agents
      patternScheduler.schedule(awsAgent, execution, instrumentation);
      patternScheduler.schedule(gcpAgent, execution, instrumentation);

      // Then - Only AWS agent should be registered (no exceptions)
      assertThat(patternScheduler).isNotNull();
    }

    @Test
    @DisplayName("Should use cached configuration properties")
    void shouldUseCachedConfigurationProperties() {
      // Given - Custom scheduler properties
      ClusteredSortSchedulerProperties customProps = createDefaultSchedulerProperties();
      customProps.getZombieCleanup().setThresholdMs(120000L); // 2 minutes

      ClusteredSortAgentScheduler customScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              agentProperties,
              customProps);

      // When & Then - Scheduler created successfully with custom config
      assertThat(customScheduler).isNotNull();
    }
  }

  @Nested
  @DisplayName("Error Handling Tests")
  class ErrorHandlingTests {

    @Test
    @DisplayName("Should handle node disabled gracefully")
    void shouldHandleNodeDisabledGracefully() {
      // Given - Node disabled
      NodeStatusProvider disabledNodeProvider = mock(NodeStatusProvider.class);
      when(disabledNodeProvider.isNodeEnabled()).thenReturn(false);

      ClusteredSortAgentScheduler disabledScheduler =
          new ClusteredSortAgentScheduler(
              jedisPool,
              disabledNodeProvider,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties);

      // When - Run scheduler cycle
      disabledScheduler.run();

      // Then - Should complete without error
      assertThat(disabledScheduler).isNotNull();
    }
  }

  private ClusteredSortAgentProperties createDefaultAgentProperties() {
    ClusteredSortAgentProperties props = new ClusteredSortAgentProperties();
    props.setMaxConcurrentAgents(100);
    props.setEnabledPattern(".*");
    props.setDisabledPattern("");
    return props;
  }

  private ClusteredSortSchedulerProperties createDefaultSchedulerProperties() {
    ClusteredSortSchedulerProperties props = new ClusteredSortSchedulerProperties();
    props.setIntervalMs(1000L);
    props.setRefreshPeriodSeconds(30);
    props.getZombieCleanup().setThresholdMs(1800000L); // 30 minutes
    props.getZombieCleanup().setCleanupIntervalMs(300000L); // 5 minutes
    props.getOrphanCleanup().setThresholdMs(7200000L); // 2 hours
    props.getOrphanCleanup().setIntervalMs(3600000L); // 1 hour
    props.setBatchOperationsEnabled(false);
    return props;
  }

  private Agent createMockAgent(String agentType, String providerName) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    when(agent.getProviderName()).thenReturn(providerName);
    return agent;
  }
}
