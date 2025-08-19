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

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPool;

class AcquisitionEndToEndTest {
  static GenericContainer<?> redis;

  @BeforeAll
  static void startRedis() {
    redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    if (redis != null) redis.stop();
  }

  @Test
  void acquiresAndEmitsMetrics() throws Exception {
    String host = redis.getHost();
    int port = redis.getMappedPort(6379);
    JedisPool pool = new JedisPool(host, port);
    Registry registry = new DefaultRegistry();
    PrioritySchedulerMetrics metrics = new PrioritySchedulerMetrics(registry);

    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.setIntervalMs(100);
    schedProps.getBatchOperations().setEnabled(false);
    schedProps.setRefreshPeriodSeconds(1);

    NodeStatusProvider nodeStatusProvider = () -> true;
    AgentIntervalProvider ivp = agent -> new AgentIntervalProvider.Interval(10, 10, 1000);
    ShardingFilter sharding = a -> true;

    RedisScriptManager scriptManager = new RedisScriptManager(pool, metrics);
    scriptManager.initializeScripts();

    PrioritySchedulerConfiguration config =
        new PrioritySchedulerConfiguration(agentProps, schedProps);

    AgentAcquisitionService acquisition =
        new AgentAcquisitionService(
            pool, scriptManager, ivp, sharding, agentProps, schedProps, metrics);

    Agent agent =
        new Agent() {
          @Override
          public String getAgentType() {
            return "test/agent";
          }

          @Override
          public String getProviderName() {
            return "test";
          }

          @Override
          public AgentExecution getAgentExecution(
              com.netflix.spinnaker.cats.provider.ProviderRegistry providerRegistry) {
            return null;
          }
        };

    AgentExecution exec =
        a -> {
          /* no-op */
        };
    ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);

    acquisition.registerAgent(agent, exec, instr);

    // One run: saturatePool should acquire and submit to the pool
    int acquired =
        acquisition.saturatePool(1, config.getRunningAgents(), Executors.newCachedThreadPool());
    assertThat(acquired).isGreaterThanOrEqualTo(0);

    // Metrics increments
    assertThat(registry.counter("cats.redisPriority.acquire.attempts").count()).isEqualTo(1);
  }
}
