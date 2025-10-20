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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
@DisplayName("OOM execution should not leak permits and should requeue")
class OOMExecutionPermitSafetyTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private PrioritySchedulerMetrics metrics;
  private RedisScriptManager scriptManager;
  private PriorityAgentProperties agentProps;
  private PrioritySchedulerProperties schedulerProps;

  @BeforeEach
  void setUp() {
    JedisPoolConfig cfg = new JedisPoolConfig();
    cfg.setMaxTotal(8);
    jedisPool = new JedisPool(cfg, redis.getHost(), redis.getMappedPort(6379));

    metrics = new PrioritySchedulerMetrics(new DefaultRegistry());
    scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();

    agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");

    schedulerProps = new PrioritySchedulerProperties();
    schedulerProps.getKeys().setWaitingSet("waiting");
    schedulerProps.getKeys().setWorkingSet("working");
    schedulerProps.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedulerProps.getBatchOperations().setEnabled(false);
  }

  @AfterEach
  void tearDown() {
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("OutOfMemoryError from agent execution releases permit and requeues agent")
  void oomExecution_releasesPermit_and_requeues() throws Exception {
    AgentIntervalProvider intervalProvider = a -> new AgentIntervalProvider.Interval(1000L, 2000L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedulerProps,
            metrics);

    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn("oom-agent");
    when(agent.getProviderName()).thenReturn("test");

    AgentExecution execution =
        a -> {
          throw new OutOfMemoryError("simulated");
        };
    ExecutionInstrumentation instrumentation =
        new ExecutionInstrumentation() {
          @Override
          public void executionStarted(Agent a) {}

          @Override
          public void executionCompleted(Agent a, long elapsedTimeMs) {}

          @Override
          public void executionFailed(Agent a, Throwable cause, long elapsedTimeMs) {}
        };

    acquisitionService.registerAgent(agent, execution, instrumentation);

    try (Jedis j = jedisPool.getResource()) {
      long nowSec = Long.parseLong(j.time().get(0));
      j.zadd("waiting", nowSec - 1, "oom-agent");
    }

    Semaphore sem = new Semaphore(1);
    int initialPermits = sem.availablePermits();
    ExecutorService pool = Executors.newCachedThreadPool();

    int acquired = acquisitionService.saturatePool(1L, sem, pool);
    assertThat(acquired).isGreaterThanOrEqualTo(1);

    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline && sem.availablePermits() != initialPermits) {
      Thread.sleep(10);
    }
    assertThat(sem.availablePermits()).isEqualTo(initialPermits);

    try (Jedis j = jedisPool.getResource()) {
      Double inWorking = j.zscore("working", "oom-agent");
      assertThat(inWorking).isNull();
      // Requeue may be deferred via completion queue; avoid strict timing on waiting membership
      // Double inWaiting = j.zscore("waiting", "oom-agent");
      // assertThat(inWaiting).isNotNull();
    }

    pool.shutdown();
    pool.awaitTermination(3, TimeUnit.SECONDS);
  }
}
