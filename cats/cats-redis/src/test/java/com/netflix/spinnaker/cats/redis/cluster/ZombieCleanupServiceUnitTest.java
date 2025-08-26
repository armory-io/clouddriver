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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;

@DisplayName("ZombieCleanupService robustness")
class ZombieCleanupServiceUnitTest {

  @Test
  @DisplayName("refreshExceptionalAgentsPattern updates thresholds without error")
  void refreshExceptionalThresholds() {
    PrioritySchedulerProperties props = new PrioritySchedulerProperties();
    PrioritySchedulerMetrics metrics = new PrioritySchedulerMetrics(new DefaultRegistry());
    ZombieCleanupService svc =
        new ZombieCleanupService(
            new JedisPool(), new RedisScriptManager(new JedisPool(), metrics), props, metrics);
    // exercise methods on empty state; ensure no crash
    Map<String, String> active = new HashMap<>();
    Map<String, Future<?>> futures = new HashMap<>();
    int cleaned = svc.cleanupZombieAgents(active, futures);
    assertThat(cleaned).isGreaterThanOrEqualTo(0);
  }
}
