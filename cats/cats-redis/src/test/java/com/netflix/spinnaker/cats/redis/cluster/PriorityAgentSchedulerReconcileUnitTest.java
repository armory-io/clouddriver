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
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@DisplayName("PriorityAgentScheduler reconcile unit test")
class PriorityAgentSchedulerReconcileUnitTest {

  private static class MutableShardingFilter implements ShardingFilter {
    private volatile boolean enabled = true;

    @Override
    public boolean filter(Agent agent) {
      return enabled;
    }

    void setEnabled(boolean v) {
      this.enabled = v;
    }
  }

  private static class RecordingAcquisitionService extends AgentAcquisitionService {
    private final Map<String, Agent> registered = new ConcurrentHashMap<>();
    private volatile int unregisterCalls = 0;

    RecordingAcquisitionService(JedisPool pool, PrioritySchedulerMetrics m) {
      super(
          pool,
          new RedisScriptManager(pool, m),
          (AgentIntervalProvider) a -> new AgentIntervalProvider.Interval(1000L, 5000L),
          (ShardingFilter) a -> true,
          new PriorityAgentProperties(),
          new PrioritySchedulerProperties(),
          m);
    }

    @Override
    public void registerAgent(
        Agent agent,
        AgentExecution agentExecution,
        ExecutionInstrumentation executionInstrumentation) {
      registered.put(agent.getAgentType(), agent);
    }

    @Override
    public void unregisterAgent(Agent agent) {
      registered.remove(agent.getAgentType());
      unregisterCalls++;
    }

    @Override
    public Agent getRegisteredAgent(String agentType) {
      return registered.get(agentType);
    }

    @Override
    public int getRegisteredAgentCount() {
      return registered.size();
    }

    @Override
    public Map<String, String> getActiveAgentsMap() {
      return java.util.Collections.emptyMap();
    }

    @Override
    public Map<String, Future<?>> getActiveAgentsFutures() {
      return java.util.Collections.emptyMap();
    }

    @Override
    public int saturatePool(
        long runCount,
        Semaphore runningAgents,
        java.util.concurrent.ExecutorService agentWorkPool) {
      return 0;
    }
  }

  @Test
  @DisplayName("reconcileKnownAgentsNow registers when enabled and unregisters when disabled")
  void reconcileRegistersAndUnregistersOnShardChanges() throws Exception {
    JedisPool pool = new JedisPool(new JedisPoolConfig(), "localhost");

    MutableShardingFilter shardingFilter = new MutableShardingFilter();
    NodeStatusProvider nodeStatusProvider = () -> true;
    AgentIntervalProvider intervalProvider = a -> new AgentIntervalProvider.Interval(1000L, 5000L);

    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();

    PrioritySchedulerMetrics metrics = new PrioritySchedulerMetrics(new DefaultRegistry());

    PriorityAgentScheduler scheduler =
        new PriorityAgentScheduler(
            pool,
            nodeStatusProvider,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    // Swap in recording acquisition service
    RecordingAcquisitionService ras = new RecordingAcquisitionService(pool, metrics);
    java.lang.reflect.Field acqField =
        PriorityAgentScheduler.class.getDeclaredField("acquisitionService");
    acqField.setAccessible(true);
    acqField.set(scheduler, ras);

    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn("recon-agent");
    when(agent.getProviderName()).thenReturn("test");

    AgentExecution exec = a -> {};
    ExecutionInstrumentation instr =
        new ExecutionInstrumentation() {
          @Override
          public void executionStarted(Agent a) {}

          @Override
          public void executionCompleted(Agent a, long ms) {}

          @Override
          public void executionFailed(Agent a, Throwable t, long ms) {}
        };

    // Initially enabled → register
    scheduler.schedule(agent, exec, instr);
    assertThat(ras.getRegisteredAgent("recon-agent")).isNotNull();

    // Flip shard ownership to disabled and reconcile → unregister
    shardingFilter.setEnabled(false);
    scheduler.reconcileKnownAgentsNow();

    assertThat(ras.getRegisteredAgent("recon-agent")).isNull();
    assertThat(ras.unregisterCalls).isGreaterThanOrEqualTo(1);

    pool.close();
  }
}
