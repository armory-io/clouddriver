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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Health degraded integration test")
@Disabled("Requires deterministic diagnostics gating; enable after clock/emitDiag hook is added")
class HealthDegradedIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool pool;
  private PriorityAgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    JedisPoolConfig cfg = new JedisPoolConfig();
    cfg.setMaxTotal(10);
    pool = new JedisPool(cfg, redis.getHost(), redis.getFirstMappedPort());

    NodeStatusProvider nodeStatusProvider = () -> true;
    // Use interval=30s so minIntervalSec > 0
    AgentIntervalProvider intervalProvider =
        a -> new AgentIntervalProvider.Interval(30_000L, 5_000L, 60_000L);
    ShardingFilter shardingFilter = a -> true;

    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");

    scheduler =
        new PriorityAgentScheduler(
            pool,
            nodeStatusProvider,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            new PrioritySchedulerMetrics(new DefaultRegistry()));

    // Register one enabled agent to seed minIntervalSec
    Agent agent =
        new Agent() {
          @Override
          public String getAgentType() {
            return "degraded-agent";
          }

          @Override
          public String getProviderName() {
            return "test";
          }

          @Override
          public AgentExecution getAgentExecution(
              com.netflix.spinnaker.cats.provider.ProviderRegistry pr) {
            return a -> {};
          }
        };
    scheduler.schedule(
        agent,
        a -> {},
        new ExecutionInstrumentation() {
          @Override
          public void executionStarted(Agent a) {}

          @Override
          public void executionCompleted(Agent a, long ms) {}

          @Override
          public void executionFailed(Agent a, Throwable t, long ms) {}
        });
  }

  @Test
  @DisplayName("Degraded true when oldest overdue exceeds min enabled interval")
  void degradedIsTrue() {
    // Seed WAITING with an overdue entry
    try (Jedis j = pool.getResource()) {
      long nowSec = Long.parseLong(j.time().get(0));
      j.zadd("waiting", nowSec - 120, "overdue-agent"); // 120s overdue
    }

    // Ensure emitDiag path runs by enabling DEBUG
    Logger acqLogger = (Logger) LoggerFactory.getLogger(AgentAcquisitionService.class);
    Level prev = acqLogger.getLevel();
    acqLogger.setLevel(Level.DEBUG);

    try {
      scheduler.run();
      scheduler.run();
      assertThat(scheduler.getStats().isDegraded()).isTrue();
    } finally {
      acqLogger.setLevel(prev);
    }
  }
}
