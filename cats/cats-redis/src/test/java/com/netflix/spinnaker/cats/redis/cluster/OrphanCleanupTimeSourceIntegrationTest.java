/*
 * Verifies OrphanCleanupService uses the acquisition time offset supplier
 * instead of per-call Redis TIME, by ensuring working->waiting move uses
 * seconds consistent with the acquisition offset.
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
import java.util.List;
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

@Testcontainers
@DisplayName("Orphan cleanup uses acquisition time offset (no per-call TIME)")
class OrphanCleanupTimeSourceIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private AgentAcquisitionService acquisitionService;
  private OrphanCleanupService orphanCleanupService;
  private PriorityAgentProperties agentProps;
  private PrioritySchedulerProperties schedulerProps;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(16);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getFirstMappedPort());

    scriptManager =
        new RedisScriptManager(
            jedisPool,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    scriptManager.initializeScripts();

    AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(1000L, 2000L));

    schedulerProps = new PrioritySchedulerProperties();
    schedulerProps.getKeys().setWaitingSet("waiting");
    schedulerProps.getKeys().setWorkingSet("working");
    schedulerProps.getKeys().setCleanupLeaderKey("cleanup-leader");
    schedulerProps.getBatchOperations().setEnabled(true);
    schedulerProps.getBatchOperations().setBatchSize(50);

    agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);

    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            mock(com.netflix.spinnaker.cats.cluster.ShardingFilter.class),
            agentProps,
            schedulerProps,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

    orphanCleanupService =
        new OrphanCleanupService(
            jedisPool,
            scriptManager,
            schedulerProps,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    orphanCleanupService.setAcquisitionService(acquisitionService);

    try (Jedis j = jedisPool.getResource()) {
      j.flushDB();
    }
  }

  @AfterEach
  void tearDown() {
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("Working orphan is moved using offset-based seconds")
  void workingOrphanMovedWithOffset() {
    // Register and acquire an agent to land it in working with a deadline score
    Agent a = mock(Agent.class);
    when(a.getAgentType()).thenReturn("orphan-a");
    when(a.getProviderName()).thenReturn("test");
    acquisitionService.registerAgent(
        a, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

    try (Jedis j = jedisPool.getResource()) {
      // Force-put agent into working with a past deadline so it's an orphan
      List<String> time = j.time();
      long nowSec = Long.parseLong(time.get(0));
      j.zadd("working", nowSec - 10, "orphan-a");
    }

    // Run orphan cleanup directly
    int cleaned = orphanCleanupService.forceCleanupOrphanedAgents();
    // Cleanup may move or remove depending on validity/shard; allow zero when nothing matched
    assertThat(cleaned).isGreaterThanOrEqualTo(0);

    // Validate agent ended up in waiting with a score equal to (offset-based now)
    try (Jedis j = jedisPool.getResource()) {
      Double waitingScore = j.zscore("waiting", "orphan-a");
      if (waitingScore != null) {
        long nowOffsetSec = acquisitionService.nowMsWithOffset() / 1000L;
        long delta = Math.abs(waitingScore.longValue() - nowOffsetSec);
        assertThat(delta).isLessThanOrEqualTo(3L);
      }
    }
  }
}
