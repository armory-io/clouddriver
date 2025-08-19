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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Testcontainers
@DisplayName("Priority Scheduler Key Namespacing Integration Tests")
class PrioritySchedulerKeyNamespacingIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;

  @BeforeEach
  void setUp() {
    String redisHost = redis.getHost();
    int redisPort = redis.getFirstMappedPort();
    jedisPool = new JedisPool(new JedisPoolConfig(), redisHost, redisPort);

    scriptManager =
        new RedisScriptManager(
            jedisPool,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    scriptManager.initializeScripts();

    intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(60000L, 300000L));

    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Nested
  @DisplayName("Prefix-only namespacing")
  class PrefixOnly {
    @Test
    @DisplayName("waiting/working/cleanup-leader use configured prefix")
    void prefixAppliedToAllKeys() throws Exception {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(1);
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");

      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setPrefix("tenant:");
      schedulerProps.getKeys().setWaitingSet("waiting");
      schedulerProps.getKeys().setWorkingSet("working");
      schedulerProps.getKeys().setCleanupLeaderKey("cleanup-leader");

      AgentAcquisitionService acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("TestAgent-Prefix");
      when(agent.getProviderName()).thenReturn("test");
      acquisitionService.registerAgent(
          agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      try (Jedis jedis = jedisPool.getResource()) {
        // Verify data written to prefixed keys only
        assertThat(jedis.zscore("tenant:waiting", "TestAgent-Prefix")).isNotNull();
        assertThat(jedis.zscore("waiting", "TestAgent-Prefix")).isNull();
        assertThat(jedis.zcard("tenant:working")).isEqualTo(0);
        assertThat(jedis.zcard("working")).isEqualTo(0);
      }

      // Verify orphan cleanup leadership uses prefixed key by acquiring and checking existence
      // briefly
      OrphanCleanupService orphanService =
          new OrphanCleanupService(
              jedisPool,
              scriptManager,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
      Method tryAcquire =
          OrphanCleanupService.class.getDeclaredMethod("tryAcquireCleanupLeadership");
      tryAcquire.setAccessible(true);
      boolean acquired = (boolean) tryAcquire.invoke(orphanService);
      assertThat(acquired).isTrue();
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.exists("tenant:cleanup-leader")).isTrue();
      }
      Method release = OrphanCleanupService.class.getDeclaredMethod("releaseCleanupLeadership");
      release.setAccessible(true);
      release.invoke(orphanService);
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.exists("tenant:cleanup-leader")).isFalse();
      }
    }
  }

  @Nested
  @DisplayName("Hash-tag only namespacing")
  class HashTagOnly {
    @Test
    @DisplayName("waiting/working use configured hash-tag with braces")
    void hashTagAppliedToAllKeys() {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(1);
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");

      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setHashTag("ps");
      schedulerProps.getKeys().setWaitingSet("waiting");
      schedulerProps.getKeys().setWorkingSet("working");

      AgentAcquisitionService acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("TestAgent-Hash");
      when(agent.getProviderName()).thenReturn("test");
      acquisitionService.registerAgent(
          agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("waiting{ps}", "TestAgent-Hash")).isNotNull();
        assertThat(jedis.zscore("waiting", "TestAgent-Hash")).isNull();
        assertThat(jedis.zcard("working{ps}")).isEqualTo(0);
        assertThat(jedis.zcard("working")).isEqualTo(0);
      }
    }

    @Test
    @DisplayName("cleanup-leader uses configured hash-tag with braces (no prefix)")
    void hashTagAppliedToLeadershipKey() throws Exception {
      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setHashTag("ps");
      schedulerProps.getKeys().setCleanupLeaderKey("cleanup-leader");

      OrphanCleanupService orphanService =
          new OrphanCleanupService(
              jedisPool,
              scriptManager,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
      Method tryAcquire =
          OrphanCleanupService.class.getDeclaredMethod("tryAcquireCleanupLeadership");
      tryAcquire.setAccessible(true);
      boolean acquired = (boolean) tryAcquire.invoke(orphanService);
      assertThat(acquired).isTrue();
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.exists("cleanup-leader{ps}")).isTrue();
      }
      Method release = OrphanCleanupService.class.getDeclaredMethod("releaseCleanupLeadership");
      release.setAccessible(true);
      release.invoke(orphanService);
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.exists("cleanup-leader{ps}")).isFalse();
      }
    }
  }

  @Nested
  @DisplayName("Prefix + Hash-tag combined")
  class PrefixAndHashTag {
    @Test
    @DisplayName("waiting/working combine prefix and hash-tag")
    void prefixAndHashTagAppliedTogether() throws Exception {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(1);
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");

      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setPrefix("pfx:");
      schedulerProps.getKeys().setHashTag("ps");
      schedulerProps.getKeys().setWaitingSet("waiting");
      schedulerProps.getKeys().setWorkingSet("working");

      AgentAcquisitionService acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("TestAgent-Combo");
      when(agent.getProviderName()).thenReturn("test");
      acquisitionService.registerAgent(
          agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("pfx:waiting{ps}", "TestAgent-Combo")).isNotNull();
        assertThat(jedis.zscore("waiting", "TestAgent-Combo")).isNull();
        assertThat(jedis.zcard("pfx:working{ps}")).isEqualTo(0);
      }

      // Leadership key with prefix + hash-tag
      OrphanCleanupService orphanService =
          new OrphanCleanupService(
              jedisPool,
              scriptManager,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
      try {
        Method tryAcquire =
            OrphanCleanupService.class.getDeclaredMethod("tryAcquireCleanupLeadership");
        tryAcquire.setAccessible(true);
        boolean acquired = (boolean) tryAcquire.invoke(orphanService);
        assertThat(acquired).isTrue();
        try (Jedis jedis = jedisPool.getResource()) {
          assertThat(jedis.exists("pfx:cleanup-leader{ps}")).isTrue();
        }
      } finally {
        Method release = OrphanCleanupService.class.getDeclaredMethod("releaseCleanupLeadership");
        release.setAccessible(true);
        release.invoke(orphanService);
      }
    }

    @Test
    @DisplayName("Acquisition path uses namespaced keys (via private tryAcquireAgent)")
    void acquisitionUsesNamespacedKeys() throws Exception {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(1);
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");

      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setPrefix("acq:");
      schedulerProps.getKeys().setHashTag("ps");
      schedulerProps.getKeys().setWaitingSet("waiting");
      schedulerProps.getKeys().setWorkingSet("working");

      AgentAcquisitionService acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("Agent-Acq");
      when(agent.getProviderName()).thenReturn("test");

      // Place agent into namespaced waiting set
      acquisitionService.registerAgent(
          agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("acq:waiting{ps}", "Agent-Acq")).isNotNull();
        assertThat(jedis.zscore("waiting", "Agent-Acq")).isNull();

        // Use reflection to call private tryAcquireAgent to move waiting -> working
        Method tryAcquireAgent =
            AgentAcquisitionService.class.getDeclaredMethod(
                "tryAcquireAgent", Jedis.class, Agent.class);
        tryAcquireAgent.setAccessible(true);
        Object score = tryAcquireAgent.invoke(acquisitionService, jedis, agent);
        assertThat(score).isNotNull();

        // Verify keys affected are the namespaced ones
        assertThat(jedis.zscore("acq:working{ps}", "Agent-Acq")).isNotNull();
        assertThat(jedis.zscore("acq:waiting{ps}", "Agent-Acq")).isNull();
        assertThat(jedis.zscore("working", "Agent-Acq")).isNull();
      }
    }
  }

  @Nested
  @DisplayName("Cleanup services respect namespacing")
  class CleanupNamespacing {
    @Test
    @DisplayName("Orphan cleanup uses prefixed working/waiting sets")
    void orphanCleanupRespectsPrefix() {
      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setPrefix("ns:");
      schedulerProps.getKeys().setWaitingSet("waiting");
      schedulerProps.getKeys().setWorkingSet("working");
      // Use a small orphan threshold so our 5-minute-old entry qualifies
      schedulerProps.getOrphanCleanup().setThresholdMs(60_000L);

      OrphanCleanupService orphanService =
          new OrphanCleanupService(
              jedisPool,
              scriptManager,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // Add an old working orphan to the prefixed key only
      long oldScoreSeconds = (System.currentTimeMillis() - 5 * 60 * 1000) / 1000; // 5 minutes ago
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("ns:working", "ns:waiting", "working", "waiting");
        jedis.zadd("ns:working", oldScoreSeconds, "orphan-A");
      }

      int cleaned = orphanService.forceCleanupOrphanedAgents();
      assertThat(cleaned).isEqualTo(1);

      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zcard("ns:working")).isEqualTo(0);
        assertThat(jedis.zcard("working")).isEqualTo(0);
      }
    }
  }

  @Nested
  @DisplayName("Custom base key names")
  class CustomBaseNames {
    @Test
    @DisplayName("Custom waiting/working/leader names are used")
    void customBaseNamesApplied() throws Exception {
      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setMaxConcurrentAgents(1);
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");

      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setWaitingSet("ready-q");
      schedulerProps.getKeys().setWorkingSet("lease-q");
      schedulerProps.getKeys().setCleanupLeaderKey("leader-key");

      AgentAcquisitionService acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProps,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      Agent agent = mock(Agent.class);
      when(agent.getAgentType()).thenReturn("Agent-Custom");
      when(agent.getProviderName()).thenReturn("test");
      acquisitionService.registerAgent(
          agent, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("ready-q", "Agent-Custom")).isNotNull();
        assertThat(jedis.zscore("waiting", "Agent-Custom")).isNull();
        assertThat(jedis.zcard("lease-q")).isEqualTo(0);
      }

      OrphanCleanupService orphanService =
          new OrphanCleanupService(
              jedisPool,
              scriptManager,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
      Method tryAcquire =
          OrphanCleanupService.class.getDeclaredMethod("tryAcquireCleanupLeadership");
      tryAcquire.setAccessible(true);
      boolean acquired = (boolean) tryAcquire.invoke(orphanService);
      assertThat(acquired).isTrue();
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.exists("leader-key")).isTrue();
      }
      Method release = OrphanCleanupService.class.getDeclaredMethod("releaseCleanupLeadership");
      release.setAccessible(true);
      release.invoke(orphanService);
      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.exists("leader-key")).isFalse();
      }
    }
  }

  @Nested
  @DisplayName("Redis Cluster slot compatibility")
  class ClusterSlotCompatibility {
    @Test
    @DisplayName("Hash-tag forces all keys into same slot")
    void hashTagForcesSameSlot() {
      String tag = "ps";
      String waiting = "waiting{" + tag + "}";
      String working = "working{" + tag + "}";
      String leader = "cleanup-leader{" + tag + "}";

      int slotWaiting = slot(waiting);
      int slotWorking = slot(working);
      int slotLeader = slot(leader);

      assertThat(slotWaiting).isEqualTo(slotWorking);
      assertThat(slotWorking).isEqualTo(slotLeader);
    }

    // CRC16 (X25) as used by Redis Cluster slot hashing
    private int slot(String key) {
      String hashtag = extractHashTag(key);
      String toHash = hashtag != null ? hashtag : key;
      return crc16(toHash.getBytes(java.nio.charset.StandardCharsets.UTF_8)) % 16384;
    }

    private String extractHashTag(String key) {
      int start = key.indexOf('{');
      if (start >= 0) {
        int end = key.indexOf('}', start + 1);
        if (end > start + 1) {
          return key.substring(start + 1, end);
        }
      }
      return null;
    }

    private int crc16(byte[] bytes) {
      int[] table = CRC16_TABLE;
      int crc = 0x0000;
      for (byte b : bytes) {
        crc = ((crc << 8) ^ table[((crc >>> 8) ^ (b & 0xFF)) & 0xFF]) & 0xFFFF;
      }
      return crc & 0xFFFF;
    }

    // Precomputed CRC16 (IBM/ANSI) table used by Redis for cluster slot hashing
    private final int[] CRC16_TABLE =
        new int[] {
          0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7, 0x8108, 0x9129, 0xA14A,
              0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF,
          0x1231, 0x0210, 0x3273, 0x2252, 0x52B5, 0x4294, 0x72F7, 0x62D6, 0x9339, 0x8318, 0xB37B,
              0xA35A, 0xD3BD, 0xC39C, 0xF3FF, 0xE3DE,
          0x2462, 0x3443, 0x0420, 0x1401, 0x64E6, 0x74C7, 0x44A4, 0x5485, 0xA56A, 0xB54B, 0x8528,
              0x9509, 0xE5EE, 0xF5CF, 0xC5AC, 0xD58D,
          0x3653, 0x2672, 0x1611, 0x0630, 0x76D7, 0x66F6, 0x5695, 0x46B4, 0xB75B, 0xA77A, 0x9719,
              0x8738, 0xF7DF, 0xE7FE, 0xD79D, 0xC7BC,
          0x48C4, 0x58E5, 0x6886, 0x78A7, 0x0840, 0x1861, 0x2802, 0x3823, 0xC9CC, 0xD9ED, 0xE98E,
              0xF9AF, 0x8948, 0x9969, 0xA90A, 0xB92B,
          0x5AF5, 0x4AD4, 0x7AB7, 0x6A96, 0x1A71, 0x0A50, 0x3A33, 0x2A12, 0xDBFD, 0xCBDC, 0xFBBF,
              0xEB9E, 0x9B79, 0x8B58, 0xBB3B, 0xAB1A,
          0x6CA6, 0x7C87, 0x4CE4, 0x5CC5, 0x2C22, 0x3C03, 0x0C60, 0x1C41, 0xEDAE, 0xFD8F, 0xCDEC,
              0xDDCD, 0xAD2A, 0xBD0B, 0x8D68, 0x9D49,
          0x7E97, 0x6EB6, 0x5ED5, 0x4EF4, 0x3E13, 0x2E32, 0x1E51, 0x0E70, 0xFF9F, 0xEFBE, 0xDFDD,
              0xCFFC, 0xBF1B, 0xAF3A, 0x9F59, 0x8F78,
          0x9188, 0x81A9, 0xB1CA, 0xA1EB, 0xD10C, 0xC12D, 0xF14E, 0xE16F, 0x1080, 0x00A1, 0x30C2,
              0x20E3, 0x5004, 0x4025, 0x7046, 0x6067,
          0x83B9, 0x9398, 0xA3FB, 0xB3DA, 0xC33D, 0xD31C, 0xE37F, 0xF35E, 0x02B1, 0x1290, 0x22F3,
              0x32D2, 0x4235, 0x5214, 0x6277, 0x7256,
          0xB5EA, 0xA5CB, 0x95A8, 0x8589, 0xF56E, 0xE54F, 0xD52C, 0xC50D, 0x34E2, 0x24C3, 0x14A0,
              0x0481, 0x7466, 0x6447, 0x5424, 0x4405,
          0xA7DB, 0xB7FA, 0x8799, 0x97B8, 0xE75F, 0xF77E, 0xC71D, 0xD73C, 0x26D3, 0x36F2, 0x0691,
              0x16B0, 0x6657, 0x7676, 0x4615, 0x5634,
          0xD94C, 0xC96D, 0xF90E, 0xE92F, 0x99C8, 0x89E9, 0xB98A, 0xA9AB, 0x5844, 0x4865, 0x7806,
              0x6827, 0x18C0, 0x08E1, 0x3882, 0x28A3,
          0xCB7D, 0xDB5C, 0xEB3F, 0xFB1E, 0x8BF9, 0x9BD8, 0xABBB, 0xBB9A, 0x4A75, 0x5A54, 0x6A37,
              0x7A16, 0x0AF1, 0x1AD0, 0x2AB3, 0x3A92,
          0xFD2E, 0xED0F, 0xDD6C, 0xCD4D, 0xBDAA, 0xAD8B, 0x9DE8, 0x8DC9, 0x7C26, 0x6C07, 0x5C64,
              0x4C45, 0x3CA2, 0x2C83, 0x1CE0, 0x0CC1,
          0xEF1F, 0xFF3E, 0xCF5D, 0xDF7C, 0xAF9B, 0xBFBA, 0x8FD9, 0x9FF8, 0x6E17, 0x7E36, 0x4E55,
              0x5E74, 0x2E93, 0x3EB2, 0x0ED1, 0x1EF0
        };
  }

  @Nested
  @DisplayName("Zombie cleanup respects namespacing")
  class ZombieNamespacing {
    @Test
    @DisplayName("Zombie cleanup removes from prefixed + tagged working set")
    void zombieCleanupRespectsPrefixAndTag() {
      PrioritySchedulerProperties schedulerProps = new PrioritySchedulerProperties();
      schedulerProps.getKeys().setPrefix("z:");
      schedulerProps.getKeys().setHashTag("ps");

      ZombieCleanupService zombieService =
          new ZombieCleanupService(
              jedisPool,
              scriptManager,
              schedulerProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // Prepare an overdue agent in the namespaced working set
      String agentType = "Zombie-A";
      long acquireScoreSec = (System.currentTimeMillis() / 1000) - 300; // 5 minutes ago
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("z:working{ps}", "z:waiting{ps}");
        jedis.zadd("z:working{ps}", acquireScoreSec, agentType);
      }

      java.util.Map<String, String> active = new java.util.HashMap<>();
      active.put(agentType, String.valueOf(acquireScoreSec));

      java.util.Map<String, java.util.concurrent.Future<?>> futures = new java.util.HashMap<>();

      int cleaned = zombieService.cleanupZombieAgents(active, futures);
      assertThat(cleaned).isEqualTo(1);

      try (Jedis jedis = jedisPool.getResource()) {
        assertThat(jedis.zscore("z:working{ps}", agentType)).isNull();
        assertThat(jedis.zscore("z:waiting{ps}", agentType)).isNull();
        // Default keys remain unaffected
        assertThat(jedis.zscore("working", agentType)).isNull();
        assertThat(jedis.zscore("waiting", agentType)).isNull();
      }
    }
  }
}
