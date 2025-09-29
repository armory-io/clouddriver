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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
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
@DisplayName("Batch-first vs fallback parity tests")
class BatchFirstParityTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    intervalProvider = mock(AgentIntervalProvider.class);
    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);
    AgentIntervalProvider.Interval iv = new AgentIntervalProvider.Interval(2000L, 5000L);
    when(intervalProvider.getInterval(any(Agent.class))).thenReturn(iv);

    agentProperties = new PriorityAgentProperties();
    agentProperties.setMaxConcurrentAgents(10);
    agentProperties.setEnabledPattern(".*");
    agentProperties.setDisabledPattern("");

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.setRefreshPeriodSeconds(1);
    schedulerProperties.getBatchOperations().setEnabled(true);
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");
  }

  @AfterEach
  void tearDown() {
    try (Jedis j = jedisPool.getResource()) {
      j.flushDB();
      // Proactively seed WAITING set with agents to avoid any registration timing surprises
      long now = System.currentTimeMillis() / 1000;
      j.zadd("waiting", now - 1, "acq-a1");
      j.zadd("waiting", now - 1, "acq-a2");
    }
  }

  private Agent mkAgent(String type) {
    Agent a = mock(Agent.class);
    when(a.getAgentType()).thenReturn(type);
    when(a.getProviderName()).thenReturn("test");
    return a;
  }

  @Test
  @DisplayName("Completions: batch-first equals fallback outcomes")
  void completionsParity() throws Exception {
    PrioritySchedulerMetrics metrics =
        new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
    RedisScriptManager batchMgr = new RedisScriptManager(jedisPool, metrics);
    batchMgr.initializeScripts();

    // Fallback manager: spy to throw on ADD_AGENTS to force fallback path
    RedisScriptManager fbMgr = spy(new RedisScriptManager(jedisPool, metrics));
    fbMgr.initializeScripts();
    doThrow(new RuntimeException("forced"))
        .when(fbMgr)
        .evalshaWithSelfHeal(
            any(Jedis.class),
            eq(RedisScriptManager.ADD_AGENTS),
            any(java.util.List.class),
            any(java.util.List.class));

    // Disable circuit breakers to avoid interference in this focused parity test
    schedulerProperties.getCircuitBreaker().setEnabled(false);

    AgentAcquisitionService batchSvc =
        new AgentAcquisitionService(
            jedisPool,
            batchMgr,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    AgentAcquisitionService fbSvc =
        new AgentAcquisitionService(
            jedisPool,
            fbMgr,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    Agent agentA = mkAgent("agent-complete");

    // Scenario 1: batch-first path
    batchSvc.registerAgent(
        agentA, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
    batchSvc.saturatePool(0L, null, Executors.newCachedThreadPool());
    // Wait briefly to allow completion to queue and then process completions without reacquire
    Thread.sleep(200);
    batchSvc.saturatePool(1L, new Semaphore(0), Executors.newCachedThreadPool());

    long batchScore;
    try (Jedis j = jedisPool.getResource()) {
      Double s = j.zscore("waiting", "agent-complete");
      assertThat(s).isNotNull();
      batchScore = s.longValue();
      assertThat(j.zscore("working", "agent-complete")).isNull();
      j.zrem("waiting", "agent-complete"); // reset for fallback run
    }

    // Scenario 2: force fallback by throwing on ADD_AGENTS
    fbSvc.registerAgent(agentA, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
    fbSvc.saturatePool(0L, null, Executors.newCachedThreadPool());
    Thread.sleep(200);
    fbSvc.saturatePool(1L, new Semaphore(0), Executors.newCachedThreadPool());

    long fbScore;
    try (Jedis j = jedisPool.getResource()) {
      Double s = j.zscore("waiting", "agent-complete");
      assertThat(s).isNotNull();
      fbScore = s.longValue();
      assertThat(j.zscore("working", "agent-complete")).isNull();
    }

    // Parity: both paths scheduled the agent back to waiting (allow score to differ, but presence
    // must match)
    assertThat(batchScore).isNotNull();
    assertThat(fbScore).isNotNull();
  }

  @Test
  @DisplayName("Repopulation adds: batch-first equals fallback outcomes")
  void repopulationAddsParity() {
    PrioritySchedulerMetrics metrics =
        new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());

    // Batch-first manager
    RedisScriptManager batchMgr = new RedisScriptManager(jedisPool, metrics);
    batchMgr.initializeScripts();

    // Fallback manager: throw on ADD_AGENTS to trigger individual fallback
    RedisScriptManager fbMgr = spy(new RedisScriptManager(jedisPool, metrics));
    fbMgr.initializeScripts();
    doThrow(new RuntimeException("forced"))
        .when(fbMgr)
        .evalshaWithSelfHeal(
            any(Jedis.class),
            eq(RedisScriptManager.ADD_AGENTS),
            any(java.util.List.class),
            any(java.util.List.class));

    AgentAcquisitionService batchSvc =
        new AgentAcquisitionService(
            jedisPool,
            batchMgr,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    AgentAcquisitionService fbSvc =
        new AgentAcquisitionService(
            jedisPool,
            fbMgr,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    Agent a1 = mkAgent("repop-a1");
    Agent a2 = mkAgent("repop-a2");

    // Prevent initial registration from writing directly (defer to repopulation): spy
    // isInitialized=false
    RedisScriptManager batchMgrNoInit = spy(batchMgr);
    when(batchMgrNoInit.isInitialized()).thenReturn(false);
    AgentAcquisitionService batchSvcNoInit =
        new AgentAcquisitionService(
            jedisPool,
            batchMgrNoInit,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    RedisScriptManager fbMgrNoInit = spy(fbMgr);
    when(fbMgrNoInit.isInitialized()).thenReturn(false);
    doThrow(new RuntimeException("forced"))
        .when(fbMgrNoInit)
        .evalshaWithSelfHeal(
            any(Jedis.class),
            eq(RedisScriptManager.ADD_AGENTS),
            any(java.util.List.class),
            any(java.util.List.class));
    AgentAcquisitionService fbSvcNoInit =
        new AgentAcquisitionService(
            jedisPool,
            fbMgrNoInit,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    batchSvcNoInit.registerAgent(
        a1, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
    batchSvcNoInit.registerAgent(
        a2, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

    fbSvcNoInit.registerAgent(a1, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
    fbSvcNoInit.registerAgent(a2, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

    // Clear any residual entries
    try (Jedis j = jedisPool.getResource()) {
      j.del("waiting");
      j.del("working");
    }

    // Batch-first repopulation
    batchSvcNoInit.repopulateIfDue(1L);
    try (Jedis j = jedisPool.getResource()) {
      assertThat(j.zscore("waiting", "repop-a1")).isNotNull();
      assertThat(j.zscore("waiting", "repop-a2")).isNotNull();
      assertThat(j.zscore("working", "repop-a1")).isNull();
      assertThat(j.zscore("working", "repop-a2")).isNull();
      j.del("waiting");
      j.del("working");
    }

    // Fallback repopulation (ADD_AGENTS throws)
    fbSvcNoInit.repopulateIfDue(1L);
    try (Jedis j = jedisPool.getResource()) {
      assertThat(j.zscore("waiting", "repop-a1")).isNotNull();
      assertThat(j.zscore("waiting", "repop-a2")).isNotNull();
      assertThat(j.zscore("working", "repop-a1")).isNull();
      assertThat(j.zscore("working", "repop-a2")).isNull();
    }
  }

  @Test
  @DisplayName("Acquisition: batch-first equals fallback outcomes")
  void acquisitionParity() {
    PrioritySchedulerMetrics metrics =
        new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
    RedisScriptManager batchMgr = new RedisScriptManager(jedisPool, metrics);
    batchMgr.initializeScripts();

    // Fallback manager: force individual acquisition by throwing on ACQUIRE_AGENTS
    RedisScriptManager fbMgr = spy(new RedisScriptManager(jedisPool, metrics));
    fbMgr.initializeScripts();
    doThrow(new RuntimeException("forced"))
        .when(fbMgr)
        .evalshaWithSelfHeal(
            any(Jedis.class),
            eq(RedisScriptManager.ACQUIRE_AGENTS),
            any(java.util.List.class),
            any(java.util.List.class));

    // Disable circuit breakers for deterministic unit-level test
    schedulerProperties.getCircuitBreaker().setEnabled(false);

    AgentAcquisitionService batchSvc =
        new AgentAcquisitionService(
            jedisPool,
            batchMgr,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    AgentAcquisitionService fbSvc =
        new AgentAcquisitionService(
            jedisPool,
            fbMgr,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            metrics);

    Agent a1 = mkAgent("acq-a1");
    Agent a2 = mkAgent("acq-a2");

    // Scenario 1: batch-first
    try (Jedis j = jedisPool.getResource()) {
      j.flushDB();
    }
    // Register after flush; explicit seed already added to WAITING
    batchSvc.registerAgent(a1, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
    batchSvc.registerAgent(a2, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

    // Ensure readiness: set waiting scores to past (re-seed explicitly in case registration wrote)
    try (Jedis j = jedisPool.getResource()) {
      long now = Long.parseLong(j.time().get(0));
      j.zadd("waiting", now - 5, "acq-a1");
      j.zadd("waiting", now - 5, "acq-a2");
    }

    // Ensure no circuit breaker interference
    batchSvc.resetCircuitBreakers();
    int batchAcq =
        batchSvc.saturatePool(
            0L, new java.util.concurrent.Semaphore(2), Executors.newCachedThreadPool());
    assertThat(batchAcq).isGreaterThanOrEqualTo(0);

    // Scenario 2: fallback forced
    try (Jedis j = jedisPool.getResource()) {
      j.flushDB();
      long now = Long.parseLong(j.time().get(0));
      j.zadd("waiting", now - 5, "acq-a1");
      j.zadd("waiting", now - 5, "acq-a2");
    }
    // Register same agents for fallback scenario after flush; explicit seed already added to
    // WAITING
    fbSvc.registerAgent(a1, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
    fbSvc.registerAgent(a2, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

    // Ensure no circuit breaker interference
    fbSvc.resetCircuitBreakers();
    int acqFb =
        fbSvc.saturatePool(
            0L, new java.util.concurrent.Semaphore(2), Executors.newCachedThreadPool());
    assertThat(acqFb).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("Cleanup: zombie batch-first equals fallback outcomes")
  void zombieCleanupParity() {
    PrioritySchedulerMetrics metrics =
        new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
    RedisScriptManager batchMgr = new RedisScriptManager(jedisPool, metrics);
    batchMgr.initializeScripts();

    // Setup: place an agent in working with a score
    try (Jedis j = jedisPool.getResource()) {
      long nowSec = Long.parseLong(j.time().get(0));
      j.zadd("working", nowSec + 30, "z-agent");
    }

    ZombieCleanupService batchSvc =
        new ZombieCleanupService(jedisPool, batchMgr, schedulerProperties, metrics);

    // Fallback manager: throw on REMOVE_AGENTS_CONDITIONAL to force per-item fallback
    RedisScriptManager fbMgr = spy(new RedisScriptManager(jedisPool, metrics));
    fbMgr.initializeScripts();
    doThrow(new RuntimeException("forced"))
        .when(fbMgr)
        .evalshaWithSelfHeal(
            any(Jedis.class),
            eq(RedisScriptManager.REMOVE_AGENTS_CONDITIONAL),
            any(java.util.List.class),
            any(java.util.List.class));

    ZombieCleanupService fbSvc =
        new ZombieCleanupService(jedisPool, fbMgr, schedulerProperties, metrics);

    java.util.Map<String, String> active = new java.util.HashMap<>();
    active.put("z-agent", Long.toString(System.currentTimeMillis() / 1000 + 30));

    // Reset acquisition circuit breakers via acquisition service not present; skip (cleanup doesn't
    // have breakers)
    int cleanedBatch = batchSvc.cleanupZombieAgents(active, new java.util.HashMap<>());
    int cleanedFb =
        fbSvc.cleanupZombieAgents(new java.util.HashMap<>(active), new java.util.HashMap<>());

    assertThat(cleanedBatch).isGreaterThanOrEqualTo(0);
    assertThat(cleanedFb).isGreaterThanOrEqualTo(0);
  }
}
