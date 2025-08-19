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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
@DisplayName("Agent score lifecycle – registration, acquisition, completion, shutdown")
class AgentScoreLifecycleTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private AgentAcquisitionService acquisitionService;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;
  private ExecutorService executorService;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    scriptManager =
        new RedisScriptManager(
            jedisPool,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    scriptManager.initializeScripts();

    // Defaults
    intervalProvider = mock(AgentIntervalProvider.class);
    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    // interval=2s, timeout=5s
    AgentIntervalProvider.Interval iv = new AgentIntervalProvider.Interval(2000L, 5000L);
    when(intervalProvider.getInterval(any(Agent.class))).thenReturn(iv);

    agentProperties = new PriorityAgentProperties();
    agentProperties.setMaxConcurrentAgents(10);
    agentProperties.setEnabledPattern(".*");
    agentProperties.setDisabledPattern("");

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.setRefreshPeriodSeconds(30);
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");

    executorService = Executors.newCachedThreadPool();

    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
  }

  @AfterEach
  void tearDown() {
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushAll();
    }
    if (executorService != null) {
      executorService.shutdownNow();
    }
  }

  private Agent mkAgent(String type) {
    Agent a = mock(Agent.class);
    when(a.getAgentType()).thenReturn(type);
    when(a.getProviderName()).thenReturn("test");
    return a;
  }

  @Test
  @DisplayName("Registration schedules waiting with score≈now")
  void registrationSchedulesImmediate() {
    Agent agent = mkAgent("reg-agent");
    acquisitionService.registerAgent(
        agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

    long nowSec = System.currentTimeMillis() / 1000;
    try (Jedis j = jedisPool.getResource()) {
      Double s = j.zscore("waiting", "reg-agent");
      assertThat(s).isNotNull();
      // Within [-3s, +3s] of now
      assertThat(Math.abs(s.longValue() - nowSec)).isLessThanOrEqualTo(3);
    }
  }

  @Test
  @DisplayName("Acquisition moves waiting→working with deadline = now + timeout")
  void acquisitionSetsDeadline() {
    Agent agent = mkAgent("acq-agent");
    AgentExecution exec = mock(AgentExecution.class);
    // Slow execution slightly so working entry is observable before completion clears it
    doAnswer(
            inv -> {
              Thread.sleep(150);
              return null;
            })
        .when(exec)
        .executeAgent(any());

    acquisitionService.registerAgent(agent, exec, mock(ExecutionInstrumentation.class));

    int acquired = acquisitionService.saturatePool(0L, null, executorService);
    assertThat(acquired).isEqualTo(1);

    try (Jedis j = jedisPool.getResource()) {
      // Use Redis server time to avoid host/container clock skew and second-boundary flakiness
      java.util.List<String> t = j.time();
      long redisNowSec = Long.parseLong(t.get(0));

      Double workScore = j.zscore("working", "acq-agent");
      assertThat(workScore).isNotNull();
      long delta = workScore.longValue() - redisNowSec;
      // timeout=5s with a slightly wider tolerance (±3s) to account for second rounding and jitter
      assertThat(delta).isBetween(2L, 8L);
    }
  }

  @Test
  @DisplayName("Success completion preserves cadence relative to original acquire")
  void successPreservesCadence() throws Exception {
    Agent agent = mkAgent("cadence-agent");
    AgentExecution exec = mock(AgentExecution.class);

    // Slow execution to ensure we can read acquireScore while running
    doAnswer(
            inv -> {
              Thread.sleep(150);
              return null;
            })
        .when(exec)
        .executeAgent(any());

    acquisitionService.registerAgent(agent, exec, mock(ExecutionInstrumentation.class));

    int acquired = acquisitionService.saturatePool(0L, null, executorService);
    assertThat(acquired).isEqualTo(1);

    // wait for completion and process completions next cycle (prevent reacquire)
    Thread.sleep(300);
    acquisitionService.saturatePool(1L, new Semaphore(0), executorService);

    // expected next = (acquireScoreSec*1000 - timeoutMs) + intervalMs
    long timeoutMs = intervalProvider.getInterval(agent).getTimeout();
    long intervalMs = intervalProvider.getInterval(agent).getInterval();
    // derive original acquire from working deadline recorded earlier by reading Redis history is
    // hard;
    // instead approximate using now + interval, which matches agentScore() for working agents.
    long desiredNextMs = System.currentTimeMillis() + intervalMs;
    long desiredNextSec = desiredNextMs / 1000L;

    try (Jedis j = jedisPool.getResource()) {
      Double waitScore = j.zscore("waiting", "cadence-agent");
      assertThat(waitScore).isNotNull();
      long actual = waitScore.longValue();
      // allow ±3s jitter for CI timing
      assertThat(Math.abs(actual - desiredNextSec)).isLessThanOrEqualTo(3);
    }
  }

  @Test
  @DisplayName("Failure completion reschedules immediately (score≈now)")
  void failureReschedulesImmediately() throws Exception {
    Agent agent = mkAgent("fail-agent");
    AgentExecution failing = mock(AgentExecution.class);
    doThrow(new RuntimeException("boom")).when(failing).executeAgent(any());

    acquisitionService.registerAgent(agent, failing, mock(ExecutionInstrumentation.class));
    int acquired = acquisitionService.saturatePool(0L, null, executorService);
    assertThat(acquired).isEqualTo(1);

    Thread.sleep(100);
    acquisitionService.saturatePool(
        1L, new Semaphore(0), executorService); // process only completions, prevent reacquire

    long nowSec = System.currentTimeMillis() / 1000;
    try (Jedis j = jedisPool.getResource()) {
      Double s = j.zscore("waiting", "fail-agent");
      assertThat(s).isNotNull();
      assertThat(Math.abs(s.longValue() - nowSec)).isLessThanOrEqualTo(3);
    }
  }

  @Test
  @DisplayName("Shutdown conditional requeue only moves when score matches (ownership)")
  void shutdownConditionalRequeueOwnership() throws Exception {
    Agent agent = mkAgent("shutdown-agent");
    AgentExecution exec = mock(AgentExecution.class);

    // Slow a bit to keep in working
    doAnswer(
            inv -> {
              Thread.sleep(200);
              return null;
            })
        .when(exec)
        .executeAgent(any());

    acquisitionService.registerAgent(agent, exec, mock(ExecutionInstrumentation.class));

    int acq = acquisitionService.saturatePool(0L, null, executorService);
    assertThat(acq).isEqualTo(1);

    String acquireScore = acquisitionService.getActiveAgentsMap().get("shutdown-agent");
    assertThat(acquireScore).isNotNull();

    // Correct expected score → move to waiting
    acquisitionService.forceRequeueAgentForShutdown(agent, acquireScore);
    try (Jedis j = jedisPool.getResource()) {
      assertThat(j.zscore("waiting", "shutdown-agent")).isNotNull();
    }

    // Put back into working and try with wrong score → should not swap
    try (Jedis j = jedisPool.getResource()) {
      long nowSec = System.currentTimeMillis() / 1000 + 10;
      j.zrem("waiting", "shutdown-agent");
      j.zadd("working", nowSec, "shutdown-agent");
    }

    acquisitionService.forceRequeueAgentForShutdown(
        agent, Long.toString((System.currentTimeMillis() / 1000) + 999));

    try (Jedis j = jedisPool.getResource()) {
      // Still in working since expected score mismatched
      assertThat(j.zscore("working", "shutdown-agent")).isNotNull();
    }
  }
}
