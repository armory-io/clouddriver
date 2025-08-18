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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
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
@DisplayName("Repopulation uses presence checks and adds only missing locals (Integration)")
class RepopulationPresenceIntegrationTest {

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
    agentProperties.setMaxConcurrentAgents(0); // unbounded for this test

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.setRefreshPeriodSeconds(1); // repopulate frequently
    schedulerProperties.getBatchOperations().setEnabled(true);
    schedulerProperties.getBatchOperations().setBatchSize(100);
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
  @DisplayName("Repopulation adds only missing local agents; ignores non-local entries")
  void repopulationAddsOnlyMissingLocalAgents() {
    // Local agents A1..A5 ; Non-local preexisting B1..B100
    Set<String> locals = new HashSet<>();
    for (int i = 1; i <= 5; i++) {
      String name = "A" + i;
      locals.add(name);
      acquisitionService.registerAgent(
          createAgent(name), mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
    }

    try (Jedis j = jedisPool.getResource()) {
      // Preload Redis with a lot of non-local agents to ensure presence check doesn't scan all
      for (int i = 1; i <= 100; i++) {
        j.zadd("waiting", 0, "B" + i);
      }
      // Remove local entries to simulate missing registration in Redis
      for (String a : locals) {
        j.zrem("waiting", a);
        j.zrem("working", a);
      }
    }

    // Run one cycle at the refresh boundary to trigger repopulation
    acquisitionService.saturatePool(1L, new Semaphore(0), agentWorkPool);

    // Expect all local agents present in either waiting or working, non-locals unchanged
    try (Jedis j = jedisPool.getResource()) {
      long localsPresent = 0;
      for (String a : locals) {
        Double w = j.zscore("waiting", a);
        Double x = j.zscore("working", a);
        if (w != null || x != null) localsPresent++;
      }
      assertThat(localsPresent).isEqualTo(locals.size());

      // Non-locals still present, count remains 100
      assertThat(j.zcard("waiting")).isGreaterThanOrEqualTo(100);
    }
  }

  private Agent createAgent(String name) {
    Agent a = mock(Agent.class);
    when(a.getAgentType()).thenReturn(name);
    when(a.getProviderName()).thenReturn("test");
    return a;
  }
}
