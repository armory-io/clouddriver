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
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Zombie cleanup run budget is respected")
class ZombieCleanupBudgetRespectedIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Test
  @DisplayName("Long zombie cleanup work does not block scheduler; subsequent run proceeds")
  void zombieBudget_Respected_NonBlockingAndProceeds() throws Exception {
    String host = redis.getHost();
    int port = redis.getFirstMappedPort();
    JedisPool pool = new JedisPool(new JedisPoolConfig(), host, port);

    NodeStatusProvider nodeStatusProvider = () -> true;
    AgentIntervalProvider intervalProvider = a -> new AgentIntervalProvider.Interval(1000L, 5000L);
    ShardingFilter shardFilter = a -> true;

    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(5);
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.setIntervalMs(50L);
    schedProps.setRefreshPeriodSeconds(1);
    schedProps.getZombieCleanup().setEnabled(true);
    schedProps.getZombieCleanup().setIntervalMs(10L);
    schedProps.getZombieCleanup().setRunBudgetMs(50L);
    schedProps.getOrphanCleanup().setEnabled(false);
    schedProps.getCircuitBreaker().setEnabled(false);

    PrioritySchedulerMetrics metrics =
        new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());

    PriorityAgentScheduler sched =
        new PriorityAgentScheduler(
            pool,
            nodeStatusProvider,
            intervalProvider,
            shardFilter,
            agentProps,
            schedProps,
            metrics);

    // Access scriptManager and acquisitionService for stub
    java.lang.reflect.Field smField =
        PriorityAgentScheduler.class.getDeclaredField("scriptManager");
    smField.setAccessible(true);
    RedisScriptManager scriptManager = (RedisScriptManager) smField.get(sched);
    scriptManager.initializeScripts();

    java.lang.reflect.Field acqField =
        PriorityAgentScheduler.class.getDeclaredField("acquisitionService");
    acqField.setAccessible(true);
    AgentAcquisitionService acq = (AgentAcquisitionService) acqField.get(sched);

    // Provide a fake active map/futures and a sleeping zombie cleanup
    AtomicInteger calls = new AtomicInteger(0);
    ZombieCleanupService sleeping =
        new ZombieCleanupService(pool, scriptManager, schedProps, metrics) {
          @Override
          public void cleanupZombieAgentsIfNeeded(
              Map<String, String> activeAgents, Map<String, Future<?>> activeAgentsFutures) {
            calls.incrementAndGet();
            try {
              Thread.sleep(200);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
          }
        };

    java.lang.reflect.Field zombieField =
        PriorityAgentScheduler.class.getDeclaredField("zombieService");
    zombieField.setAccessible(true);
    zombieField.set(sched, sleeping);

    long start1 = System.currentTimeMillis();
    sched.run();
    long dur1 = System.currentTimeMillis() - start1;
    assertThat(dur1).isLessThan(400L);

    // Allow the first background task to finish, then run again so a new cleanup can begin
    Thread.sleep(250);
    long start2 = System.currentTimeMillis();
    sched.run();
    long dur2 = System.currentTimeMillis() - start2;
    assertThat(dur2).isLessThan(400L);

    // Give the second offloaded cleanup time to increment counter
    Thread.sleep(50);
    assertThat(calls.get()).isGreaterThanOrEqualTo(2);

    sched.shutdown();
    pool.close();
  }
}
