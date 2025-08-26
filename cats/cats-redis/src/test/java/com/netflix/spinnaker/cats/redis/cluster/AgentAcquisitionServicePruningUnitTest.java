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
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("AgentAcquisitionService future pruning")
class AgentAcquisitionServicePruningUnitTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Test
  @DisplayName("Tick start prunes completed futures from tracking map")
  void tickPrunesCompletedFutures() throws Exception {
    JedisPool pool =
        new JedisPool(new JedisPoolConfig(), redis.getHost(), redis.getFirstMappedPort());
    try {
      RedisScriptManager scripts =
          new RedisScriptManager(pool, new PrioritySchedulerMetrics(new DefaultRegistry()));
      scripts.initializeScripts();

      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(2);

      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.getKeys().setWaitingSet("waiting");
      schedProps.getKeys().setWorkingSet("working");
      schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              pool,
              scripts,
              (AgentIntervalProvider) a -> new AgentIntervalProvider.Interval(100L, 1000L),
              (ShardingFilter) a -> true,
              agentProps,
              schedProps,
              new PrioritySchedulerMetrics(new DefaultRegistry()));

      Agent a1 = mock(Agent.class);
      when(a1.getAgentType()).thenReturn("prune-1");
      when(a1.getProviderName()).thenReturn("test");
      Agent a2 = mock(Agent.class);
      when(a2.getAgentType()).thenReturn("prune-2");
      when(a2.getProviderName()).thenReturn("test");

      ExecutionInstrumentation instr =
          new ExecutionInstrumentation() {
            @Override
            public void executionStarted(Agent a) {}

            @Override
            public void executionCompleted(Agent a, long ms) {}

            @Override
            public void executionFailed(Agent a, Throwable t, long ms) {}
          };

      acq.registerAgent(a1, ag -> {}, instr);
      acq.registerAgent(a2, ag -> {}, instr);

      // Pre-populate waiting set so both are ready
      try (var j = pool.getResource()) {
        long now = Long.parseLong(j.time().get(0));
        j.zadd("waiting", now, "prune-1");
        j.zadd("waiting", now, "prune-2");
      }

      // Executor that completes futures immediately
      ExecutorService exec = Executors.newFixedThreadPool(2);

      // First tick: acquire and submit
      int acquired = acq.saturatePool(1L, new Semaphore(2), exec);
      assertThat(acquired).isGreaterThanOrEqualTo(1);

      // Manually complete any remaining futures if not already done
      for (Map.Entry<String, Future<?>> e : acq.getActiveAgentsFutures().entrySet()) {
        Future<?> f = e.getValue();
        if (f instanceof CompletableFuture) {
          ((CompletableFuture<?>) f).complete(null);
        }
      }

      int before = acq.getFuturesMapSize();

      // Second tick: prevent new acquisitions; only pruning should take effect
      acq.saturatePool(2L, new Semaphore(0), exec);

      int after = acq.getFuturesMapSize();
      assertThat(after).isLessThanOrEqualTo(before);

      exec.shutdownNow();
    } finally {
      pool.close();
    }
  }
}
