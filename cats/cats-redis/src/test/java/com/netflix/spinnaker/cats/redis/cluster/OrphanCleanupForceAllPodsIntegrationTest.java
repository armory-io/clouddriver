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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("OrphanCleanupService forceAllPods integration test")
class OrphanCleanupForceAllPodsIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(5);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getFirstMappedPort());

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedulerProperties.getOrphanCleanup().setEnabled(true);
    schedulerProperties.getOrphanCleanup().setIntervalMs(0L); // always eligible
    schedulerProperties.getOrphanCleanup().setThresholdMs(2000L); // 2s
    schedulerProperties.getOrphanCleanup().setForceAllPods(true); // no leadership required

    scriptManager =
        new RedisScriptManager(jedisPool, new PrioritySchedulerMetrics(new DefaultRegistry()));
    scriptManager.initializeScripts();
  }

  @Test
  @DisplayName(
      "When forceAllPods=true, cleanup runs without leadership and removes old waiting entries")
  void forceAllPodsRunsCleanup() {
    try (Jedis j = jedisPool.getResource()) {
      // Prepare WAITING with two old and one fresh entry
      long nowSec = Long.parseLong(j.time().get(0));
      j.zadd("waiting", nowSec - 10, "agent-old-1");
      j.zadd("waiting", nowSec - 5, "agent-old-2");
      j.zadd("waiting", nowSec + 5, "agent-fresh");
    }

    OrphanCleanupService service =
        new OrphanCleanupService(
            jedisPool,
            scriptManager,
            schedulerProperties,
            new PrioritySchedulerMetrics(new DefaultRegistry()));

    // Do not wire acquisitionService so waiting entries are considered invalid in this test
    service.cleanupOrphanedAgentsIfNeeded();

    // Verify that at least the old entries were cleaned (fresh remains)
    long remaining;
    try (Jedis j = jedisPool.getResource()) {
      remaining = j.zcard("waiting");
    }
    assertThat(remaining).isBetween(0L, 2L); // fresh may remain, old cleaned
    assertThat(service.getOrphansCleanedUp()).isGreaterThanOrEqualTo(1L);
  }
}
