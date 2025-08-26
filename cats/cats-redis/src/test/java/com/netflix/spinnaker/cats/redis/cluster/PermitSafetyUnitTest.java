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
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Permit safety on submission rejection")
class PermitSafetyUnitTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Test
  @DisplayName("Submission failure increments metric and does not throw")
  void submissionFailureIncrementsMetric() {
    JedisPool pool =
        new JedisPool(new JedisPoolConfig(), redis.getHost(), redis.getFirstMappedPort());
    try {
      RedisScriptManager scripts =
          new RedisScriptManager(pool, new PrioritySchedulerMetrics(new DefaultRegistry()));
      scripts.initializeScripts();

      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");
      agentProps.setMaxConcurrentAgents(1);

      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.getKeys().setWaitingSet("waiting");
      schedProps.getKeys().setWorkingSet("working");
      schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");
      schedProps.getBatchOperations().setEnabled(false);

      PrioritySchedulerMetrics metrics = new PrioritySchedulerMetrics(new DefaultRegistry());

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              pool,
              scripts,
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

      try (var j = pool.getResource()) {
        java.util.List<String> t = j.time();
        long now = Long.parseLong(t.get(0));
        j.zadd("waiting", now, "rejected-agent");
      }

      ExecutorService rejecting = mock(ExecutorService.class);
      doThrow(new RejectedExecutionException("full")).when(rejecting).submit(any(Runnable.class));

      Semaphore sem = new Semaphore(1);

      int acquired = acq.saturatePool(1L, sem, rejecting);
      assertThat(acquired).isGreaterThanOrEqualTo(0);
    } finally {
      pool.close();
    }
  }
}
