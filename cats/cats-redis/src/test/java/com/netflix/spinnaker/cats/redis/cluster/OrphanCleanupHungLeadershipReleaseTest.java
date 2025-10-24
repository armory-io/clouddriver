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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@DisplayName("Orphan cleanup defers when leadership held by another instance")
@Testcontainers
class OrphanCleanupHungLeadershipReleaseTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;

  @BeforeEach
  void setUp() {
    jedisPool = new JedisPool(new JedisPoolConfig(), redis.getHost(), redis.getMappedPort(6379));
  }

  @AfterEach
  void tearDown() {
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("When leadership is held elsewhere, cleanup skips and leaves state unchanged")
  void leadershipHeld_skips_and_keepsTimestampAndKey() throws Exception {
    PrioritySchedulerProperties props = new PrioritySchedulerProperties();
    props.getKeys().setWaitingSet("waiting");
    props.getKeys().setWorkingSet("working");
    props.getKeys().setCleanupLeaderKey("cleanup-leader");
    props.getOrphanCleanup().setEnabled(true);
    props.getOrphanCleanup().setIntervalMs(10_000);
    props.getOrphanCleanup().setRunBudgetMs(100);
    props.getOrphanCleanup().setLeadershipTtlMs(5_000);

    RedisScriptManager scripts =
        new RedisScriptManager(jedisPool, new PrioritySchedulerMetrics(new DefaultRegistry()));
    scripts.initializeScripts();
    OrphanCleanupService svc =
        new OrphanCleanupService(
            jedisPool, scripts, props, new PrioritySchedulerMetrics(new DefaultRegistry()));

    java.lang.reflect.Field idField =
        OrphanCleanupService.class.getDeclaredField("currentLeadershipId");
    idField.setAccessible(true);
    idField.set(svc, "node-1");

    java.lang.reflect.Field lastField =
        OrphanCleanupService.class.getDeclaredField("lastOrphanCleanup");
    lastField.setAccessible(true);
    lastField.setLong(svc, System.currentTimeMillis() - 60_000);

    long before = lastField.getLong(svc);

    // Seed leadership key with current id (simulate we own it)
    try (Jedis j = jedisPool.getResource()) {
      j.set(props.getKeys().getCleanupLeaderKey(), "node-1");
    }

    // Since leadership key exists and we didn't acquire it, the service should skip
    svc.cleanupOrphanedAgentsIfNeeded();

    long after = lastField.getLong(svc);
    assertThat(after).isEqualTo(before);

    // Leadership key should remain (no release since we didn't acquire)
    try (Jedis j = jedisPool.getResource()) {
      String v = j.get(props.getKeys().getCleanupLeaderKey());
      assertThat(v).isEqualTo("node-1");
    }
  }
}
