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
import com.netflix.spectator.api.Registry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Time offset gauge integration test")
class TimeOffsetIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool pool;
  private PrioritySchedulerMetrics metrics;

  @BeforeEach
  void setUp() {
    JedisPoolConfig cfg = new JedisPoolConfig();
    cfg.setMaxTotal(10);
    pool = new JedisPool(cfg, redis.getHost(), redis.getFirstMappedPort());
    Registry reg = new DefaultRegistry();
    metrics = new PrioritySchedulerMetrics(reg);

    new RedisScriptManager(pool, metrics).initializeScripts();
  }

  @Test
  @DisplayName("timeOffsetMs is exposed and finite")
  void timeOffsetGaugeExposed() {
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");

    PriorityAgentScheduler scheduler =
        new PriorityAgentScheduler(
            pool,
            () -> true,
            a ->
                new com.netflix.spinnaker.cats.cluster.AgentIntervalProvider.Interval(1000L, 5000L),
            a -> true,
            agentProps,
            schedProps,
            metrics);

    scheduler.run();

    try {
      java.lang.reflect.Field f =
          PriorityAgentScheduler.class.getDeclaredField("acquisitionService");
      f.setAccessible(true);
      Object acq = f.get(scheduler);
      long off = (long) acq.getClass().getMethod("getServerClientOffsetMs").invoke(acq);
      // Offset may be negative depending on local vs server time; just assert finite and within a
      // sane bound (|offset| < 5 minutes)
      assertThat(Math.abs(off)).isLessThan(5 * 60 * 1000L);
    } catch (Exception e) {
      throw new AssertionError("Failed to introspect time offset", e);
    }
  }
}
