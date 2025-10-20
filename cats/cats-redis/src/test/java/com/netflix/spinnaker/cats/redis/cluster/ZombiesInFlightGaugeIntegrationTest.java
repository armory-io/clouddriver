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
import com.netflix.spectator.api.Measurement;
import com.netflix.spectator.api.Meter;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.patterns.PolledMeter;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;

@DisplayName("Zombies-in-flight gauge integration")
class ZombiesInFlightGaugeIntegrationTest {

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
  @DisplayName("Gauge reflects early permit release and cleanup")
  void gaugeReflectsEarlyReleaseAndCleanup() throws Exception {
    // Prepare minimal dependencies (no Redis operations executed in this test)
    Registry registry = new DefaultRegistry();
    PrioritySchedulerMetrics metrics = new PrioritySchedulerMetrics(registry);
    JedisPool mockPool = mock(JedisPool.class);
    RedisScriptManager scriptManager = new RedisScriptManager(mockPool, metrics);
    AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
    ShardingFilter shardingFilter = mock(ShardingFilter.class);
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();

    AgentAcquisitionService acq =
        new AgentAcquisitionService(
            mockPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    // Wire a semaphore reference used by earlyRelease
    Semaphore sem = new Semaphore(0);
    Field runningSem = AgentAcquisitionService.class.getDeclaredField("runningAgentsRef");
    runningSem.setAccessible(true);
    runningSem.set(acq, sem);

    // Insert a run-state for the agent and mark as started
    Field rsField = AgentAcquisitionService.class.getDeclaredField("runStates");
    rsField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> runStates = (Map<String, Object>) rsField.get(acq);
    Class<?> rsClass =
        Class.forName("com.netflix.spinnaker.cats.redis.cluster.AgentAcquisitionService$RunState");
    java.lang.reflect.Constructor<?> ctor = rsClass.getDeclaredConstructor();
    ctor.setAccessible(true);
    Object rs = ctor.newInstance();
    Field started = rsClass.getDeclaredField("started");
    started.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicBoolean) started.get(rs)).set(true);
    runStates.put("agent/zif-test", rs);

    // Register gauges (supplier for zIF comes from acquisition service)
    java.util.function.Supplier<Number> zero = () -> 0;
    metrics.registerGauges(
        null,
        zero,
        zero,
        zero,
        zero,
        zero,
        zero,
        zero,
        zero,
        zero,
        zero,
        zero,
        acq::getZombiesInFlight);

    // Early release should increment zIF (and release a semaphore permit)
    acq.earlyReleasePermitIfHeld("agent/zif-test");
    assertThat(acq.getZombiesInFlight()).isEqualTo(1);
    assertThat(sem.availablePermits()).isEqualTo(1);
    assertThat(gaugeValue(registry, "cats.redisPriority.zombiesInFlight")).isEqualTo(1.0d);

    // Simulate worker completion decrement (mirror AgentWorker finally path)
    Field zifField = AgentAcquisitionService.class.getDeclaredField("zombiesInFlight");
    zifField.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicInteger) zifField.get(acq)).decrementAndGet();

    assertThat(acq.getZombiesInFlight()).isEqualTo(0);
    assertThat(gaugeValue(registry, "cats.redisPriority.zombiesInFlight")).isEqualTo(0.0d);
  }
}
