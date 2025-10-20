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
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
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
@DisplayName("ZombieCleanupService Permit Release Tests")
class ZombieCleanupPermitReleaseTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private ZombieCleanupService zombieService;
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    try (Jedis jedis = jedisPool.getResource()) {
      jedis.flushAll();
    }

    scriptManager =
        new RedisScriptManager(
            jedisPool,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    scriptManager.initializeScripts();

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getZombieCleanup().setThresholdMs(30_000L);
    schedulerProperties.getZombieCleanup().setIntervalMs(10_000L);

    zombieService =
        new ZombieCleanupService(
            jedisPool,
            scriptManager,
            schedulerProperties,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
  }

  @Test
  @DisplayName("Should release permit and remove local tracking even if Redis remove returns 0")
  void shouldReleasePermitAndRemoveLocalTrackingWhenRedisRemoveReturnsZero() {
    // Given: local zombie present, but not present in Redis (REMOVE returns 0)
    String agentType = "local-only-zombie";
    long oldScoreSeconds;
    try (Jedis j = jedisPool.getResource()) {
      long nowSec = Long.parseLong(j.time().get(0));
      oldScoreSeconds = nowSec - 120; // 2 minutes ago
    }

    Map<String, String> activeAgents = new HashMap<>();
    Map<String, Future<?>> activeAgentsFutures = new HashMap<>();

    activeAgents.put(agentType, String.valueOf(oldScoreSeconds));
    Future<?> mockFuture = mock(Future.class);
    when(mockFuture.isDone()).thenReturn(false);
    when(mockFuture.cancel(true)).thenReturn(true);
    activeAgentsFutures.put(agentType, mockFuture);

    // Wire a mocked acquisition service to verify fairness and local cleanup calls
    AgentAcquisitionService acquisition = mock(AgentAcquisitionService.class);
    zombieService.setAcquisitionService(acquisition);

    // When
    int cleaned = zombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

    // Then: counted as cleaned even if not in Redis; we cancel local future
    // and delegate local tracking + permit release to acquisitionService
    assertThat(cleaned).isEqualTo(1);
    verify(mockFuture).cancel(true);
    verify(acquisition).removeActiveAgent(agentType);
    verify(acquisition).tryEarlyPermitReleaseAndMaybeIncrementZif(agentType);
  }
}
