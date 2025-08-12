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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
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
@DisplayName("Chunked acquisition fills up to available slots")
class InstantRetryCapIntegrationTest {

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
        .thenReturn(new AgentIntervalProvider.Interval(0L, 2000L));

    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    agentProperties = new PriorityAgentProperties();
    agentProperties.setEnabledPattern(".*");
    agentProperties.setDisabledPattern("");
    agentProperties.setMaxConcurrentAgents(5); // concurrency bound

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.setRefreshPeriodSeconds(30); // avoid repop triggering
    schedulerProperties.getBatchOperations().setEnabled(true);
    schedulerProperties.getBatchOperations().setBatchSize(2); // very small cap to observe retry cap

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
  @DisplayName("Chunked acquisition fills to min(availableSlots, ready)")
  void chunkedAcquisitionFillsToSlots() throws Exception {
    // Register 6 agents; cap is 2
    AgentExecution execution = mock(AgentExecution.class);
    ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
    for (int i = 1; i <= 6; i++) {
      acquisitionService.registerAgent(createAgent("A" + i), execution, instrumentation);
    }

    // Preload WAITZ with 2 ready agents that will be stolen by a competing pod
    try (Jedis j = jedisPool.getResource()) {
      j.zadd("WAITZ", 0, "A1");
      j.zadd("WAITZ", 0, "A2");
    }

    AtomicBoolean competingMoved = new AtomicBoolean(false);

    // Background thread simulates another pod moving A1/A2 to WORKZ and adding 5 new ready
    Thread competitor =
        new Thread(
            () -> {
              try {
                Thread.sleep(50); // allow initial scan to happen
                try (Jedis j = jedisPool.getResource()) {
                  // steal the initial ready agents so batch acquisition returns 0
                  j.zrem("WAITZ", "A1", "A2");
                  j.zadd("WORKZ", System.currentTimeMillis() / 1000.0, "A1");
                  j.zadd("WORKZ", System.currentTimeMillis() / 1000.0, "A2");

                  // add more than the cap to ensure retry must limit
                  j.zadd("WAITZ", 0, "A3");
                  j.zadd("WAITZ", 0, "A4");
                  j.zadd("WAITZ", 0, "A5");
                  j.zadd("WAITZ", 0, "A6");
                  j.zadd("WAITZ", 0, "A7");
                }
                competingMoved.set(true);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
            });

    competitor.start();

    int acquired = acquisitionService.saturatePool(1L, new Semaphore(10), agentWorkPool);

    competitor.join(1000);

    // With chunked acquisition, we fill available slots across multiple chunks
    // availableSlots = 5, ready >= 5 => expect 5
    assertThat(acquired).isEqualTo(5);
    assertThat(competingMoved.get()).isTrue();
  }

  private Agent createAgent(String name) {
    Agent a = mock(Agent.class);
    when(a.getAgentType()).thenReturn(name);
    when(a.getProviderName()).thenReturn("test");
    return a;
  }
}
