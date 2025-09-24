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
import com.netflix.spectator.api.Measurement;
import com.netflix.spectator.api.Meter;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.patterns.PolledMeter;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@DisplayName("PrioritySchedulerMetrics gauges")
class PrioritySchedulerGaugesTest {

  private static double gaugeValue(Registry registry, String name) {
    PolledMeter.update(registry);
    for (Meter m : registry) {
      if (m.id().name().equals(name)) {
        for (Measurement ms : m.measure()) {
          return ms.value();
        }
      }
    }
    return Double.NaN;
  }

  @Test
  @DisplayName("readyToCapacityRatio computes expected value")
  void readyToCapacityRatio_ComputesExpectedValue() {
    Registry registry = new DefaultRegistry();
    PrioritySchedulerMetrics m = new PrioritySchedulerMetrics(registry);

    Supplier<Number> zero = () -> 0;
    Supplier<Number> ready = () -> 4;
    Supplier<Number> capacity = () -> 2;
    Supplier<Number> ratio =
        () -> {
          double cap = capacity.get().doubleValue();
          double r = ready.get().doubleValue();
          return cap > 0 ? (r / cap) : 0.0d;
        };

    m.registerGauges(
        null,
        zero, // registeredAgents
        zero, // activeAgents
        ready, // readyCount
        zero, // oldestOverdueSeconds
        zero, // degraded
        capacity, // capacityPerCycle
        zero, // queueDepth
        zero, // semaphoreAvailable
        zero, // completionQueueSize
        zero, // timeOffsetMs
        ratio, // readyToCapacityRatio
        zero // zombiesInFlight
        );

    double v = gaugeValue(registry, "cats.redisPriority.readyToCapacityRatio");
    assertThat(v).isEqualTo(2.0d);
  }

  @Test
  @DisplayName("JedisPool gauges (active/idle/waiters) are registered and readable")
  void jedisPoolGaugesRegistered() {
    Registry registry = new DefaultRegistry();
    PrioritySchedulerMetrics m = new PrioritySchedulerMetrics(registry);

    JedisPool pool = new JedisPool(new JedisPoolConfig(), "localhost");
    try {
      Supplier<Number> zero = () -> 0;
      m.registerGauges(
          pool, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero);

      double active = gaugeValue(registry, "cats.redisPriority.redisPool.active");
      double idle = gaugeValue(registry, "cats.redisPriority.redisPool.idle");
      double waiters = gaugeValue(registry, "cats.redisPriority.redisPool.waiters");

      assertThat(active).isGreaterThanOrEqualTo(0.0d);
      assertThat(idle).isGreaterThanOrEqualTo(0.0d);
      assertThat(waiters).isGreaterThanOrEqualTo(0.0d);
    } finally {
      pool.close();
    }
  }
}
