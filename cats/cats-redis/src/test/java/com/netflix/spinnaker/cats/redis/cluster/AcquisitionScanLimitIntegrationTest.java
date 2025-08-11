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
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Acquisition ready-scan is capped by min(availableSlots, batchSize)")
class AcquisitionScanLimitIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private AgentAcquisitionService acquisitionService;
  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private ExecutorService agentWorkPool;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(32);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getFirstMappedPort());

    scriptManager = new RedisScriptManager(jedisPool);
    scriptManager.initializeScripts();

    intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(1000L, 2000L));

    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    agentProperties = new PriorityAgentProperties();
    agentProperties.setEnabledPattern(".*");
    agentProperties.setDisabledPattern("");
    agentProperties.setMaxConcurrentAgents(3); // concurrency bound

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.setRefreshPeriodSeconds(1);
    schedulerProperties.setBatchOperationsEnabled(true);
    schedulerProperties.setAgentAcquisitionBatchSize(10); // larger than concurrency

    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);

    agentWorkPool = Executors.newFixedThreadPool(8);

    try (Jedis j = jedisPool.getResource()) {
      j.flushDB();
    }
  }

  @AfterEach
  void tearDown() {
    if (agentWorkPool != null) {
      agentWorkPool.shutdownNow();
    }
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("Initial ready scan acquires at most min(maxConcurrent, batchSize)")
  void initialReadyScanIsCapped() {
    // Register many agents so WAITZ will contain far more than the cap
    AgentExecution execution = mock(AgentExecution.class);
    ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
    for (int i = 1; i <= 20; i++) {
      acquisitionService.registerAgent(createAgent("agent-" + i), execution, instrumentation);
    }

    int expectedCap =
        Math.min(
            agentProperties.getMaxConcurrentAgents(),
            schedulerProperties.getAgentAcquisitionBatchSize());

    // Run one cycle that includes repopulation and acquisition
    int acquired = acquisitionService.saturatePool(0L, null, agentWorkPool);

    assertThat(acquired)
        .as("acquired must be capped to min(maxConcurrent, batchSize)")
        .isEqualTo(expectedCap);
  }

  private Agent createAgent(String name) {
    Agent a = mock(Agent.class);
    when(a.getAgentType()).thenReturn(name);
    when(a.getProviderName()).thenReturn("test");
    return a;
  }
}
