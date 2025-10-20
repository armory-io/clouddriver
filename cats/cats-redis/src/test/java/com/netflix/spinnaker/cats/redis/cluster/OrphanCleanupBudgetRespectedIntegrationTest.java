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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Orphan cleanup run budget is respected")
class OrphanCleanupBudgetRespectedIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Test
  @DisplayName("Long orphan cleanup work does not block scheduler loop; next run proceeds")
  void orphanBudget_Respected_NonBlockingAndProceeds() throws Exception {
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
    schedProps.getZombieCleanup().setEnabled(false);
    schedProps.getOrphanCleanup().setEnabled(true);
    schedProps.getOrphanCleanup().setIntervalMs(10L);
    schedProps.getOrphanCleanup().setRunBudgetMs(50L);
    schedProps.getOrphanCleanup().setForceAllPods(true);
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

    // Access scriptManager to reuse in stub, ensuring scripts are initialized once
    java.lang.reflect.Field smField =
        PriorityAgentScheduler.class.getDeclaredField("scriptManager");
    smField.setAccessible(true);
    RedisScriptManager scriptManager = (RedisScriptManager) smField.get(sched);
    scriptManager.initializeScripts();

    // Replace orphanService with a stub that sleeps beyond budget and tracks calls
    AtomicInteger calls = new AtomicInteger(0);
    OrphanCleanupService sleeping =
        new OrphanCleanupService(pool, scriptManager, schedProps, metrics) {
          @Override
          public void cleanupOrphanedAgentsIfNeeded() {
            // Let superclass perform its quick interval/leadership bookkeeping
            super.cleanupOrphanedAgentsIfNeeded();
            calls.incrementAndGet();
            try {
              Thread.sleep(200); // exceed budget
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
          }
        };
    java.lang.reflect.Field orphanField =
        PriorityAgentScheduler.class.getDeclaredField("orphanService");
    orphanField.setAccessible(true);
    orphanField.set(sched, sleeping);

    // First run should return promptly despite long cleanup work (offloaded)
    long start1 = System.currentTimeMillis();
    sched.run();
    long dur1 = System.currentTimeMillis() - start1;
    assertThat(dur1).as("scheduler run should be fast").isLessThan(400L);

    // Wait for the background task to finish, then run again so a new cleanup can start
    Thread.sleep(250);
    long start2 = System.currentTimeMillis();
    sched.run();
    long dur2 = System.currentTimeMillis() - start2;
    assertThat(dur2).as("second run should be fast").isLessThan(400L);

    // Give the second offloaded cleanup time to start and increment our counter
    Thread.sleep(50);

    assertThat(calls.get()).isGreaterThanOrEqualTo(2);

    sched.shutdown();
    pool.close();
  }
}
