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
@DisplayName("Acquisition fills to available slots using chunked scans")
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
    schedulerProperties.getBatchOperations().setEnabled(true);
    schedulerProperties.getBatchOperations().setBatchSize(10); // larger than concurrency
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");

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
  @DisplayName("Initial acquisition fills up to maxConcurrent in chunked batches")
  void initialAcquisitionFillsToSlots() {
    // Register many agents so waiting will contain far more than the cap
    AgentExecution execution = mock(AgentExecution.class);
    ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
    for (int i = 1; i <= 20; i++) {
      acquisitionService.registerAgent(createAgent("agent-" + i), execution, instrumentation);
    }

    int expectedCap = agentProperties.getMaxConcurrentAgents();

    // Run one cycle that includes repopulation and acquisition
    int acquired = acquisitionService.saturatePool(0L, null, agentWorkPool);

    assertThat(acquired)
        .as("acquired must fill up to available slots (maxConcurrent)")
        .isEqualTo(expectedCap);
  }

  @Test
  @DisplayName(
      "Multi-chunk acquisition issues multiple zrangeByScore scans when batch-size < slots")
  void multiChunkAcquisitionUsesMultipleScans() {
    // Given: maxConcurrent=3, batchSize=2 ensures at least 2 scans if 3+ ready
    agentProperties.setMaxConcurrentAgents(3);
    schedulerProperties.getBatchOperations().setEnabled(true);
    schedulerProperties.getBatchOperations().setBatchSize(2);

    // Spy on Jedis and JedisPool to count zrangeByScore calls
    Jedis spyJedis = org.mockito.Mockito.spy(jedisPool.getResource());
    JedisPool spyPool = org.mockito.Mockito.spy(jedisPool);
    org.mockito.Mockito.doReturn(spyJedis).when(spyPool).getResource();
    RedisScriptManager spyScripts = org.mockito.Mockito.spy(scriptManager);
    AgentAcquisitionService service =
        new AgentAcquisitionService(
            spyPool,
            spyScripts,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);

    // Register 5 agents so waiting has enough ready entries
    AgentExecution execution = mock(AgentExecution.class);
    ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
    for (int i = 1; i <= 5; i++) {
      service.registerAgent(createAgent("chunk-agent-" + i), execution, instrumentation);
    }

    // When
    int acquired = service.saturatePool(0L, null, agentWorkPool);

    // Then: acquire up to slots=3 and perform at least two zrangeByScore(count=2) scans
    assertThat(acquired).isEqualTo(3);
    org.mockito.Mockito.verify(spyJedis, org.mockito.Mockito.atLeast(2))
        .zrangeByScore(
            org.mockito.Mockito.eq("waiting"),
            org.mockito.Mockito.eq("-inf"),
            org.mockito.Mockito.anyString(),
            org.mockito.Mockito.eq(0),
            org.mockito.Mockito.anyInt());
    // And specifically, the second chunk should request count=1 (remaining slots)
    org.mockito.Mockito.verify(spyJedis, org.mockito.Mockito.atLeast(1))
        .zrangeByScore(
            org.mockito.Mockito.eq("waiting"),
            org.mockito.Mockito.eq("-inf"),
            org.mockito.Mockito.anyString(),
            org.mockito.Mockito.eq(0),
            org.mockito.Mockito.eq(1));
  }

  private Agent createAgent(String name) {
    Agent a = mock(Agent.class);
    when(a.getAgentType()).thenReturn(name);
    when(a.getProviderName()).thenReturn("test");
    return a;
  }
}
