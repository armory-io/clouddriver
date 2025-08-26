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

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.AgentScheduler;
import com.netflix.spinnaker.cats.agent.AgentSchedulerAware;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.cats.provider.ProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@DisplayName("PriorityAgentScheduler Unit Tests")
class PriorityAgentSchedulerUnitTest {

  private PriorityAgentScheduler newScheduler(JedisPool jedisPool) {
    NodeStatusProvider nodeStatusProvider = mock(NodeStatusProvider.class);
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(true);

    AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(1000L, 5000L));

    ShardingFilter shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");
    agentProps.setMaxConcurrentAgents(10);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");

    return new PriorityAgentScheduler(
        jedisPool,
        nodeStatusProvider,
        intervalProvider,
        shardingFilter,
        agentProps,
        schedProps,
        new PrioritySchedulerMetrics(new DefaultRegistry()));
  }

  @Test
  @DisplayName("schedule() sets AgentSchedulerAware when applicable")
  void scheduleSetsAgentSchedulerAware() {
    JedisPool jedisPool = new JedisPool(new JedisPoolConfig(), "localhost");
    try {
      PriorityAgentScheduler scheduler = newScheduler(jedisPool);

      class AwareAgent extends AgentSchedulerAware implements Agent {
        @Override
        public String getAgentType() {
          return "aware-agent";
        }

        @Override
        public String getProviderName() {
          return "test";
        }

        @Override
        public AgentExecution getAgentExecution(ProviderRegistry providerRegistry) {
          return null;
        }
      }

      AwareAgent agent = new AwareAgent();
      AgentExecution exec = mock(AgentExecution.class);
      ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);

      scheduler.schedule(agent, exec, instr);

      AgentScheduler<?> set = agent.getAgentScheduler();
      assertThat(set).isNotNull();
    } finally {
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("Manual lock helpers return null/false (not supported)")
  void manualLockHelpersNotSupported() {
    JedisPool jedisPool = new JedisPool(new JedisPoolConfig(), "localhost");
    try {
      PriorityAgentScheduler scheduler = newScheduler(jedisPool);

      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("x");
      assertThat(scheduler.tryLock(agent)).isNull();

      AgentLock fakeLock = new AgentLock(agent, "0", "0");
      assertThat(scheduler.tryRelease(fakeLock)).isFalse();
      assertThat(scheduler.lockValid(fakeLock)).isFalse();
    } finally {
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("SchedulerStats toString contains health fields")
  void schedulerStatsToStringContainsHealth() {
    JedisPool jedisPool = new JedisPool(new JedisPoolConfig(), "localhost");
    try {
      PriorityAgentScheduler scheduler = newScheduler(jedisPool);
      PriorityAgentScheduler.SchedulerStats stats = scheduler.getStats();
      String s = stats.toString();
      assertThat(s).contains("SchedulerStats{");
      assertThat(s).contains("health=");
    } finally {
      jedisPool.close();
    }
  }
}
