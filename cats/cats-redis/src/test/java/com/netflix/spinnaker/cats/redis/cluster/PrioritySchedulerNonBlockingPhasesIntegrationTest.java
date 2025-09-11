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

import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@DisplayName("Priority scheduler non-blocking phases")
class PrioritySchedulerNonBlockingPhasesIntegrationTest {

  @Test
  @DisplayName("Reconcile is offloaded and does not block the scheduler loop")
  void reconcile_Offloaded_DoesNotBlock() {
    JedisPool pool = new JedisPool(new JedisPoolConfig(), "localhost");

    NodeStatusProvider nodeStatusProvider = () -> true;
    AgentIntervalProvider intervalProvider = a -> new AgentIntervalProvider.Interval(1000L, 5000L);

    // Sharding filter that sleeps to simulate slow repo reads
    ShardingFilter slowFilter =
        a -> {
          try {
            Thread.sleep(200);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
          return true;
        };

    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(10);
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.setIntervalMs(50L);
    schedProps.setRefreshPeriodSeconds(1); // trigger reconcile frequently

    PriorityAgentScheduler sched =
        new PriorityAgentScheduler(
            pool,
            nodeStatusProvider,
            intervalProvider,
            slowFilter,
            agentProps,
            schedProps,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

    // No agents are needed; run should stay fast despite slow reconcile off-thread
    long start = System.currentTimeMillis();
    sched.run();
    long durationMs = System.currentTimeMillis() - start;

    assertThat(durationMs).isLessThan(100L); // offloaded work should not block the loop

    pool.close();
  }

  @Test
  @DisplayName("Orphan cleanup is offloaded and long work does not block the scheduler loop")
  void orphanCleanup_LongWork_DoesNotBlock() throws Exception {
    JedisPool pool = new JedisPool(new JedisPoolConfig(), "localhost");

    NodeStatusProvider nodeStatusProvider = () -> true;
    AgentIntervalProvider intervalProvider = a -> new AgentIntervalProvider.Interval(1000L, 5000L);
    ShardingFilter filter = a -> true;

    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(10);
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.setIntervalMs(50L);
    schedProps.getOrphanCleanup().setRunBudgetMs(50L);

    PriorityAgentScheduler sched =
        new PriorityAgentScheduler(
            pool,
            nodeStatusProvider,
            intervalProvider,
            filter,
            agentProps,
            schedProps,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

    // Replace orphanService with a stub that sleeps
    Field orphanField = PriorityAgentScheduler.class.getDeclaredField("orphanService");
    orphanField.setAccessible(true);
    OrphanCleanupService sleeping =
        new OrphanCleanupService(
            pool,
            new RedisScriptManager(
                pool,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry())),
            schedProps,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry())) {
          @Override
          public void cleanupOrphanedAgentsIfNeeded() {
            try {
              Thread.sleep(200);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
          }
        };
    orphanField.set(sched, sleeping);

    long start = System.currentTimeMillis();
    sched.run();
    long durationMs = System.currentTimeMillis() - start;

    assertThat(durationMs).isLessThan(100L); // offloaded work should not block the loop

    pool.close();
  }
}
