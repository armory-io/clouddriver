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

/*
 * Simple Redis-backed test for script metrics.
 */

package com.netflix.spinnaker.cats.redis.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

class RedisScriptManagerIntegrationTest {

  static GenericContainer<?> redis;

  @BeforeAll
  static void startRedis() {
    redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    if (redis != null) redis.stop();
  }

  @Test
  void recordsEvalAndReloadMetrics() {
    String host = redis.getHost();
    int port = redis.getMappedPort(6379);
    JedisPool pool = new JedisPool(host, port);
    Registry registry = new DefaultRegistry();
    PrioritySchedulerMetrics metrics = new PrioritySchedulerMetrics(registry);
    RedisScriptManager manager = new RedisScriptManager(pool, metrics);

    try (Jedis j = pool.getResource()) {
      manager.initializeScripts();
      // Call a small script to record eval
      manager.evalshaWithSelfHeal(
          j,
          RedisScriptManager.SCORE_AGENTS,
          java.util.Arrays.asList("working", "waiting"),
          java.util.Arrays.asList("agentA"));
      // Force flush to drive reload
      j.scriptFlush();
      manager.evalshaWithSelfHeal(
          j,
          RedisScriptManager.SCORE_AGENTS,
          java.util.Arrays.asList("working", "waiting"),
          java.util.Arrays.asList("agentB"));
    }

    long evalCount =
        registry
            .counter(
                registry
                    .createId("cats.redisPriority.scripts.eval")
                    .withTag("script", RedisScriptManager.SCORE_AGENTS))
            .count();
    assertThat(evalCount).isGreaterThanOrEqualTo(1);
    assertThat(registry.counter("cats.redisPriority.scripts.reloads").count())
        .isGreaterThanOrEqualTo(1);
  }
}
