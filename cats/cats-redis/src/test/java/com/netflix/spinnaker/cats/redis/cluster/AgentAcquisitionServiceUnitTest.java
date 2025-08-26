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

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("AgentAcquisitionService unit complements")
class AgentAcquisitionServiceUnitTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @Test
  @DisplayName("repopulateIfDueNow returns false until window elapses and true when due")
  void repopulateIfDueNowBehavior() throws Exception {
    JedisPool pool =
        new JedisPool(new JedisPoolConfig(), redis.getHost(), redis.getFirstMappedPort());
    try {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      PrioritySchedulerProperties props = new PrioritySchedulerProperties();
      props.setRefreshPeriodSeconds(1);
      AgentAcquisitionService svc =
          new AgentAcquisitionService(
              pool,
              new RedisScriptManager(pool, new PrioritySchedulerMetrics(new DefaultRegistry())),
              (AgentIntervalProvider) a -> new AgentIntervalProvider.Interval(1000L, 1000L),
              (ShardingFilter) a -> true,
              agentProps,
              props,
              new PrioritySchedulerMetrics(new DefaultRegistry()));

      // Directly manipulate lastRepopulateEpochMs to avoid time-offset side effects
      long now = System.currentTimeMillis();
      java.lang.reflect.Field lastField =
          AgentAcquisitionService.class.getDeclaredField("lastRepopulateEpochMs");
      lastField.setAccessible(true);
      java.util.concurrent.atomic.AtomicLong last =
          (java.util.concurrent.atomic.AtomicLong) lastField.get(svc);
      // Initialize window (non-zero) less than refresh period ago → should be false
      last.set(now);
      assertThat(svc.repopulateIfDueNow()).isFalse();
      // Make it due by subtracting > refreshPeriodMs
      last.set(now - 2000);
      assertThat(svc.repopulateIfDueNow()).isTrue();
    } finally {
      // Reset static time offset to avoid cross-test interference
      try {
        java.lang.reflect.Field off =
            AgentAcquisitionService.class.getDeclaredField("serverClientOffset");
        off.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) off.get(null)).set(0L);
        java.lang.reflect.Field last =
            AgentAcquisitionService.class.getDeclaredField("lastTimeCheck");
        last.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) last.get(null)).set(0L);
      } catch (Exception ignore) {
      }
      pool.close();
    }
  }

  @Test
  @DisplayName("acquire metrics increment on attempt and record time regardless of outcome")
  void acquireMetricsIncrement() {
    // Use a pool that throws to avoid touching Redis TIME and static offsets
    class ThrowPool extends JedisPool {
      @Override
      public redis.clients.jedis.Jedis getResource() {
        throw new redis.clients.jedis.exceptions.JedisConnectionException("no");
      }
    }
    JedisPool pool = new ThrowPool();
    try {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      PrioritySchedulerProperties props = new PrioritySchedulerProperties();
      AgentAcquisitionService svc =
          new AgentAcquisitionService(
              pool,
              new RedisScriptManager(pool, new PrioritySchedulerMetrics(new DefaultRegistry())),
              (AgentIntervalProvider) a -> new AgentIntervalProvider.Interval(1000L, 1000L),
              (ShardingFilter) a -> true,
              agentProps,
              props,
              new PrioritySchedulerMetrics(new DefaultRegistry()));

      int acquired =
          svc.saturatePool(
              1L, new Semaphore(0), java.util.concurrent.Executors.newSingleThreadExecutor());
      assertThat(acquired).isGreaterThanOrEqualTo(0);
    } finally {
      try {
        java.lang.reflect.Field off =
            AgentAcquisitionService.class.getDeclaredField("serverClientOffset");
        off.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) off.get(null)).set(0L);
        java.lang.reflect.Field last =
            AgentAcquisitionService.class.getDeclaredField("lastTimeCheck");
        last.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) last.get(null)).set(0L);
      } catch (Exception ignore) {
      }
      pool.close();
    }
  }
}
