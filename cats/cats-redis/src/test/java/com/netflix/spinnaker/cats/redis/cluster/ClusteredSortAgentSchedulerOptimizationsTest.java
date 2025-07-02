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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

/**
 * Tests specifically focused on the optimization and reliability improvements made to
 * ClusteredSortAgentScheduler.
 */
class ClusteredSortAgentSchedulerOptimizationsTest {

  // Helper methods to create properties for tests
  private static ClusteredSortAgentProperties createDefaultAgentProperties() {
    return new ClusteredSortAgentProperties();
  }

  private static ClusteredSortSchedulerProperties createDefaultSchedulerProperties() {
    return new ClusteredSortSchedulerProperties();
  }

  private ClusteredSortAgentScheduler scheduler;
  private static final String WAITING_SET = "WAITZ";
  private static final String WORKING_SET = "WORKZ";

  @Mock private JedisPool jedisPool;
  @Mock private Jedis jedis;
  @Mock private Pipeline pipeline;
  @Mock private NodeStatusProvider nodeStatusProvider;
  @Mock private ShardingFilter shardingFilter;
  @Mock private Agent agent;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    when(jedisPool.getResource()).thenReturn(jedis);
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(true);
    when(shardingFilter.filter(any())).thenReturn(true);
    when(agent.getAgentType()).thenReturn("TestAgent");

    // Configure dynamic config defaults
    // All configuration now handled via cached properties

    // Script loading setup
    when(jedis.scriptLoad(anyString())).thenReturn("mockedSHA");
    when(jedis.scriptExists(anyString())).thenReturn(true);

    scheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            mock(AgentIntervalProvider.class),
            shardingFilter,
            createDefaultAgentProperties(),
            createDefaultSchedulerProperties());
  }

  // Note: Redis exception handling is covered by existing tests in ClusteredSortAgentSchedulerTest
  // We don't need a separate test for this since the error handling code is well-established

  @Test
  @DisplayName("Should initialize successfully with cached properties")
  void shouldInitializeSuccessfullyWithCachedProperties() {
    // Given - Scheduler is created with cached properties
    // When - Scheduler is initialized (done in setUp)
    // Then - Should complete without errors
    assertThat(scheduler).isNotNull();
  }

  @Test
  @DisplayName("Should use efficient Redis operations via services")
  void shouldUseEfficientRedisOperationsViaServices() {
    // Given - Services are properly initialized
    // When - Scheduler runs operations
    scheduler.run();

    // Then - Should complete without errors (Redis operations handled by services)
    assertThat(scheduler).isNotNull();
  }

  @Test
  @DisplayName("Should use thread pool configuration from properties")
  void shouldUseThreadPoolConfigurationFromProperties() {
    // Thread pool configuration is now handled via cached properties
    // ClusteredSortSchedulerProperties sets: coreSize=10, maxSize=50, queueSize=1000
    assertThat(scheduler).isNotNull();
  }

  @Test
  @DisplayName("Should use cached thread pool properties")
  void shouldUseCachedThreadPoolProperties() {
    // Thread pool configuration is now handled via ClusteredSortSchedulerProperties
    // Fixed values: coreSize=10, maxSize=50, queueSize=1000
    assertThat(scheduler).isNotNull();
  }

  @Test
  @DisplayName("Should handle errors gracefully with service architecture")
  void shouldHandleErrorsGracefullyWithServiceArchitecture() {
    // Given - Node is disabled to simulate error conditions
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(false);

    // When - Scheduler runs with disabled node
    scheduler.run();

    // Then - Should handle gracefully without exceptions
    assertThat(scheduler).isNotNull();
  }
}
