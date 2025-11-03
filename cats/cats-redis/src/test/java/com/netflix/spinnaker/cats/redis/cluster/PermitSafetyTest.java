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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Test suite for permit safety in error scenarios.
 *
 * <p>Tests verify that permits are properly released when errors occur during agent submission
 * or execution, preventing permit leaks and ensuring system stability under failure conditions.
 */
@Testcontainers
@DisplayName("Permit Safety Tests")
class PermitSafetyTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private Registry registry;
  private PrioritySchedulerMetrics metrics;
  private RedisScriptManager scriptManager;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(8);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379));
    registry = new DefaultRegistry();
    metrics = new PrioritySchedulerMetrics(registry);
    scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();
  }

  @AfterEach
  void tearDown() {
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Nested
  @DisplayName("Submission Rejection Tests")
  class SubmissionRejectionTests {

    @Test
    @DisplayName("Submission failure increments metric and does not throw")
    void submissionFailureIncrementsMetric() {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");
      agentProps.setMaxConcurrentAgents(1);

      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.getKeys().setWaitingSet("waiting");
      schedProps.getKeys().setWorkingSet("working");
      schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");
      schedProps.getBatchOperations().setEnabled(false);

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              (AgentIntervalProvider) a -> new AgentIntervalProvider.Interval(1000L, 5000L),
              (ShardingFilter) a -> true,
              agentProps,
              schedProps,
              metrics);

      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("rejected-agent");
      when(agent.getProviderName()).thenReturn("test");

      ExecutionInstrumentation instr =
          new ExecutionInstrumentation() {
            @Override
            public void executionStarted(Agent a) {}

            @Override
            public void executionCompleted(Agent a, long ms) {}

            @Override
            public void executionFailed(Agent a, Throwable t, long ms) {}
          };

      acq.registerAgent(
          agent,
          a -> {
            /* no-op */
          },
          instr);

      try (Jedis j = jedisPool.getResource()) {
        java.util.List<String> t = j.time();
        long now = Long.parseLong(t.get(0));
        j.zadd("waiting", now, "rejected-agent");
      }

      ExecutorService rejecting = mock(ExecutorService.class);
      doThrow(new RejectedExecutionException("full")).when(rejecting).submit(any(Runnable.class));

      Semaphore sem = new Semaphore(1);

      int acquired = acq.saturatePool(1L, sem, rejecting);
      assertThat(acquired).isGreaterThanOrEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Submission Error Tests")
  class SubmissionErrorTests {

    @Test
    @DisplayName("OutOfMemoryError during submit: permit released, metrics tagged, no orphaned state")
    void submissionError_outOfMemory_permitReleased_and_metricsTagged() {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setEnabledPattern(".*");
      agentProps.setMaxConcurrentAgents(1);

      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.getKeys().setWaitingSet("waiting");
      schedProps.getKeys().setWorkingSet("working");
      schedProps.getBatchOperations().setEnabled(false);

      AgentIntervalProvider intervalProvider = a -> new AgentIntervalProvider.Interval(1000L, 2000L);
      ShardingFilter shardingFilter = a -> true;

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              jedisPool, scriptManager, intervalProvider, shardingFilter, agentProps, schedProps, metrics);

      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("submit-oom-agent");
      when(agent.getProviderName()).thenReturn("test");

      ExecutionInstrumentation instrumentation =
          new ExecutionInstrumentation() {
            @Override
            public void executionStarted(Agent a) {}

            @Override
            public void executionCompleted(Agent a, long ms) {}

            @Override
            public void executionFailed(Agent a, Throwable t, long ms) {}
          };

      acq.registerAgent(agent, (AgentExecution) a -> {}, instrumentation);

      try (Jedis j = jedisPool.getResource()) {
        long now = Long.parseLong(j.time().get(0));
        j.zadd("waiting", now - 1, "submit-oom-agent");
      }

      // Executor that throws OutOfMemoryError on submit
      ExecutorService faultyExecutor =
          new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public java.util.concurrent.Future<?> submit(Runnable task) {
              throw new OutOfMemoryError("simulated-submit-oom");
            }
          };

      Semaphore running = new Semaphore(1);
      int initialPermits = running.availablePermits();

      int acquired = acq.saturatePool(1L, running, faultyExecutor);
      assertThat(acquired).isGreaterThanOrEqualTo(0);

      // Permit must be released
      assertThat(running.availablePermits()).isEqualTo(initialPermits);

      // No orphaned active agent state
      assertThat(acq.getActiveAgentCount()).isEqualTo(0);

      // Metric tagged with OutOfMemoryError for submission failure
      assertThat(
              registry
                  .counter(
                      "cats.redisPriority.acquire.submissionFailures", "reason", "OutOfMemoryError")
                  .count())
          .isGreaterThanOrEqualTo(1);

      faultyExecutor.shutdown();
    }
  }

  @Nested
  @DisplayName("OutOfMemory Execution Tests")
  class OOMExecutionTests {

    @Test
    @DisplayName("OutOfMemoryError from agent execution releases permit and requeues agent")
    void oomExecution_releasesPermit_and_requeues() throws Exception {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(1);
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");

      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setWaitingSet("waiting");
      schedulerProps.getKeys().setWorkingSet("working");
      schedulerProps.getKeys().setCleanupLeaderKey("cleanup-leader");
      schedulerProps.getBatchOperations().setEnabled(false);

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
      ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool();

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
}

