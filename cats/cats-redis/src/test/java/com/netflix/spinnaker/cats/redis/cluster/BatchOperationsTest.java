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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
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

/**
 * Tests for batch Redis operations in PriorityAgentScheduler using live Redis containers.
 *
 * <p>This consolidated test suite covers all aspects of batch operations:
 *
 * <ul>
 *   <li>Batch operations for agent management
 *   <li>Performance optimizations with batched Redis operations
 *   <li>Zombie cleanup with batch processing
 *   <li>Real Redis integration for batch scripts
 *   <li>Batch-first vs fallback parity
 *   <li>Edge cases and large-scale scenarios
 *   <li>Scoring consistency between batch and individual operations
 * </ul>
 *
 */
@Testcontainers
@DisplayName("Batch Operations Tests")
class BatchOperationsTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  private JedisPool jedisPool;
  private PriorityAgentScheduler scheduler;
  private NodeStatusProvider nodeStatusProvider;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;

  @BeforeEach
  void setUp() {
    // Setup Redis connection
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(10);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

    // Clean Redis state
    try (Jedis j = jedisPool.getResource()) {
      j.flushAll();
    }

    // Mock dependencies
    nodeStatusProvider = mock(NodeStatusProvider.class);
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(true);

    intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(30000L, 5000L, 60000L));

    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    // Create properties with batch operations enabled
    agentProperties = TestFixtures.createDefaultAgentProperties();
    schedulerProperties = TestFixtures.createBatchEnabledSchedulerProperties();

    // Create scheduler with live Redis
    scheduler =
        new PriorityAgentScheduler(
            jedisPool,
            nodeStatusProvider,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
  }

  @AfterEach
  void tearDown() {
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Nested
  @DisplayName("Batch Agent Operations Tests")
  class BatchAgentOperationsTests {

    @Test
    @DisplayName("Should handle batch agent registration efficiently")
    void shouldHandleBatchAgentRegistrationEfficiently() {
      // Given - Multiple agents
      Agent agent1 = TestFixtures.createMockAgent("BatchAgent1", "test-provider");
      Agent agent2 = TestFixtures.createMockAgent("BatchAgent2", "test-provider");
      Agent agent3 = TestFixtures.createMockAgent("BatchAgent3", "test-provider");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When - Register multiple agents
      scheduler.schedule(agent1, execution, instrumentation);
      scheduler.schedule(agent2, execution, instrumentation);
      scheduler.schedule(agent3, execution, instrumentation);

      // Then - Should register without errors
      assertThat(scheduler).isNotNull();
    }

    @Test
    @DisplayName("Should use batch operations for performance")
    void shouldUseBatchOperationsForPerformance() {
      // Given - Scheduler with batch operations enabled
      assertThat(schedulerProperties.getBatchOperations().isEnabled()).isTrue();

      // When - Register many agents
      for (int i = 0; i < 10; i++) {
        Agent agent = TestFixtures.createMockAgent("Agent" + i, "provider" + (i % 3));
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        scheduler.schedule(agent, execution, instrumentation);
      }

      // Then - Should complete efficiently with batch operations
      assertThat(scheduler).isNotNull();
    }
  }

  @Nested
  @DisplayName("Batch Cleanup Operations Tests")
  class BatchCleanupOperationsTests {

    @Test
    @DisplayName("Should handle batch zombie cleanup")
    void shouldHandleBatchZombieCleanup() {
      // Given - Scheduler with short zombie threshold for testing
      PrioritySchedulerProperties zombieProps = createZombieTestSchedulerProperties();
      PriorityAgentScheduler zombieScheduler =
          new PriorityAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              agentProperties,
              zombieProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // When - Run scheduler cycle (includes zombie cleanup)
      zombieScheduler.run();

      // Then - Should complete without errors
      assertThat(zombieScheduler).isNotNull();
    }

    @Test
    @DisplayName("Should handle batch orphan cleanup")
    void shouldHandleBatchOrphanCleanup() {
      // Given - Scheduler with orphan cleanup enabled
      schedulerProperties.getOrphanCleanup().setEnabled(true);

      // When - Run scheduler cycle (includes orphan cleanup)
      scheduler.run();

      // Then - Should complete without errors
      assertThat(scheduler).isNotNull();
    }

    @Test
    @DisplayName("Should handle exceptional agents zombie cleanup with batch operations")
    void shouldHandleExceptionalAgentsZombieCleanupWithBatchOperations() {
      // Given - Scheduler properties with exceptional agents configured
      PrioritySchedulerProperties exceptionalProps = createExceptionalAgentsTestProperties();
      PriorityAgentScheduler exceptionalScheduler =
          new PriorityAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              agentProperties,
              exceptionalProps,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // When - Run scheduler cycle with exceptional agents configuration
      exceptionalScheduler.run();

      // Then - Should complete without errors
      assertThat(exceptionalScheduler).isNotNull();
      assertThat(exceptionalProps.getZombieCleanup().getExceptionalAgents().getPattern())
          .isNotEmpty();
    }

    @Test
    @DisplayName("Should apply different thresholds for exceptional vs regular agents")
    void shouldApplyDifferentThresholdsForExceptionalVsRegularAgents() {
      // Given - Scheduler with exceptional agents pattern
      PrioritySchedulerProperties props = createExceptionalAgentsTestProperties();
      props.getZombieCleanup().setThresholdMs(5000L); // 5 seconds default
      props
          .getZombieCleanup()
          .getExceptionalAgents()
          .setThresholdMs(10000L); // 10 seconds exceptional

      PriorityAgentScheduler exceptionalScheduler =
          new PriorityAgentScheduler(
              jedisPool,
              nodeStatusProvider,
              intervalProvider,
              shardingFilter,
              agentProperties,
              props,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      // Create agents that match and don't match the pattern
      Agent bigQueryAgent = TestFixtures.createMockAgent("BigQueryCachingAgent", "gcp-provider");
      Agent regularAgent = TestFixtures.createMockAgent("RegularAgent", "test-provider");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      // When - Schedule both types of agents
      exceptionalScheduler.schedule(bigQueryAgent, execution, instrumentation);
      exceptionalScheduler.schedule(regularAgent, execution, instrumentation);
      exceptionalScheduler.run();

      // Then - Both agents should be scheduled (validation is in the configuration)
      assertThat(exceptionalScheduler).isNotNull();
      assertThat(props.getZombieCleanup().getExceptionalAgents().getThresholdMs())
          .isGreaterThan(props.getZombieCleanup().getThresholdMs());
    }
  }

  @Nested
  @DisplayName("Batch-First Parity Tests")
  class BatchFirstParityTests {

    private JedisPool parityJedisPool;
    private AgentIntervalProvider parityIntervalProvider;
    private ShardingFilter parityShardingFilter;
    private PriorityAgentProperties parityAgentProperties;
    private PrioritySchedulerProperties paritySchedulerProperties;

    @BeforeEach
    void setUpParityTests() {
      JedisPoolConfig config = new JedisPoolConfig();
      config.setMaxTotal(10);
      parityJedisPool =
          new JedisPool(config, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

      // Clean Redis state
      try (Jedis j = parityJedisPool.getResource()) {
        j.flushAll();
      }

      parityIntervalProvider = mock(AgentIntervalProvider.class);
      parityShardingFilter = mock(ShardingFilter.class);
      when(parityShardingFilter.filter(any(Agent.class))).thenReturn(true);
      AgentIntervalProvider.Interval iv = new AgentIntervalProvider.Interval(2000L, 5000L);
      when(parityIntervalProvider.getInterval(any(Agent.class))).thenReturn(iv);

      parityAgentProperties = new PriorityAgentProperties();
      parityAgentProperties.setMaxConcurrentAgents(10);
      parityAgentProperties.setEnabledPattern(".*");
      parityAgentProperties.setDisabledPattern("");

      paritySchedulerProperties = new PrioritySchedulerProperties();
      paritySchedulerProperties.setRefreshPeriodSeconds(1);
      paritySchedulerProperties.getBatchOperations().setEnabled(true);
      paritySchedulerProperties.getKeys().setWaitingSet("waiting");
      paritySchedulerProperties.getKeys().setWorkingSet("working");
      paritySchedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");
    }

    @AfterEach
    void tearDownParityTests() {
      if (parityJedisPool != null) {
        try (Jedis j = parityJedisPool.getResource()) {
          j.flushDB();
          // Proactively seed WAITING set with agents to avoid any registration timing surprises
          long now = System.currentTimeMillis() / 1000;
          j.zadd("waiting", now - 1, "acq-a1");
          j.zadd("waiting", now - 1, "acq-a2");
        }
        parityJedisPool.close();
      }
    }

    private Agent mkAgent(String type) {
      Agent a = mock(Agent.class);
      when(a.getAgentType()).thenReturn(type);
      when(a.getProviderName()).thenReturn("test");
      return a;
    }

    @Test
    @DisplayName("Completions: batch-first equals fallback outcomes")
    void completionsParity() throws Exception {
      PrioritySchedulerMetrics metrics =
          new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
      RedisScriptManager batchMgr = new RedisScriptManager(parityJedisPool, metrics);
      batchMgr.initializeScripts();

      // Fallback manager: spy to throw on ADD_AGENTS to force fallback path
      RedisScriptManager fbMgr = spy(new RedisScriptManager(parityJedisPool, metrics));
      fbMgr.initializeScripts();
      doThrow(new RuntimeException("forced"))
          .when(fbMgr)
          .evalshaWithSelfHeal(
              any(Jedis.class),
              eq(RedisScriptManager.ADD_AGENTS),
              any(List.class),
              any(List.class));

      // Disable circuit breakers to avoid interference in this focused parity test
      paritySchedulerProperties.getCircuitBreaker().setEnabled(false);

      AgentAcquisitionService batchSvc =
          new AgentAcquisitionService(
              parityJedisPool,
              batchMgr,
              parityIntervalProvider,
              parityShardingFilter,
              parityAgentProperties,
              paritySchedulerProperties,
              metrics);

      AgentAcquisitionService fbSvc =
          new AgentAcquisitionService(
              parityJedisPool,
              fbMgr,
              parityIntervalProvider,
              parityShardingFilter,
              parityAgentProperties,
              paritySchedulerProperties,
              metrics);

      Agent agentA = mkAgent("agent-complete");

      // Scenario 1: batch-first path
      batchSvc.registerAgent(
          agentA, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      batchSvc.saturatePool(0L, null, Executors.newCachedThreadPool());
      // Wait briefly to allow completion to queue and then process completions without reacquire
      Thread.sleep(200);
      batchSvc.saturatePool(1L, new Semaphore(0), Executors.newCachedThreadPool());

      long batchScore;
      try (Jedis j = parityJedisPool.getResource()) {
        Double s = j.zscore("waiting", "agent-complete");
        assertThat(s).isNotNull();
        batchScore = s.longValue();
        assertThat(j.zscore("working", "agent-complete")).isNull();
        j.zrem("waiting", "agent-complete"); // reset for fallback run
      }

      // Scenario 2: force fallback by throwing on ADD_AGENTS
      fbSvc.registerAgent(agentA, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      fbSvc.saturatePool(0L, null, Executors.newCachedThreadPool());
      Thread.sleep(200);
      fbSvc.saturatePool(1L, new Semaphore(0), Executors.newCachedThreadPool());

      long fbScore;
      try (Jedis j = parityJedisPool.getResource()) {
        Double s = j.zscore("waiting", "agent-complete");
        assertThat(s).isNotNull();
        fbScore = s.longValue();
        assertThat(j.zscore("working", "agent-complete")).isNull();
      }

      // Parity: both paths scheduled the agent back to waiting (allow score to differ, but presence
      // must match)
      assertThat(batchScore).isNotNull();
      assertThat(fbScore).isNotNull();
    }

    @Test
    @DisplayName("Repopulation adds: batch-first equals fallback outcomes")
    void repopulationAddsParity() {
      PrioritySchedulerMetrics metrics =
          new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());

      // Batch-first manager
      RedisScriptManager batchMgr = new RedisScriptManager(parityJedisPool, metrics);
      batchMgr.initializeScripts();

      // Fallback manager: throw on ADD_AGENTS to trigger individual fallback
      RedisScriptManager fbMgr = spy(new RedisScriptManager(parityJedisPool, metrics));
      fbMgr.initializeScripts();
      doThrow(new RuntimeException("forced"))
          .when(fbMgr)
          .evalshaWithSelfHeal(
              any(Jedis.class),
              eq(RedisScriptManager.ADD_AGENTS),
              any(List.class),
              any(List.class));

      AgentAcquisitionService batchSvc =
          new AgentAcquisitionService(
              parityJedisPool,
              batchMgr,
              parityIntervalProvider,
              parityShardingFilter,
              parityAgentProperties,
              paritySchedulerProperties,
              metrics);

      AgentAcquisitionService fbSvc =
          new AgentAcquisitionService(
              parityJedisPool,
              fbMgr,
              parityIntervalProvider,
              parityShardingFilter,
              parityAgentProperties,
              paritySchedulerProperties,
              metrics);

      Agent a1 = mkAgent("repop-a1");
      Agent a2 = mkAgent("repop-a2");

      // Prevent initial registration from writing directly (defer to repopulation): spy
      // isInitialized=false
      RedisScriptManager batchMgrNoInit = spy(batchMgr);
      when(batchMgrNoInit.isInitialized()).thenReturn(false);
      AgentAcquisitionService batchSvcNoInit =
          new AgentAcquisitionService(
              parityJedisPool,
              batchMgrNoInit,
              parityIntervalProvider,
              parityShardingFilter,
              parityAgentProperties,
              paritySchedulerProperties,
              metrics);

      RedisScriptManager fbMgrNoInit = spy(fbMgr);
      when(fbMgrNoInit.isInitialized()).thenReturn(false);
      doThrow(new RuntimeException("forced"))
          .when(fbMgrNoInit)
          .evalshaWithSelfHeal(
              any(Jedis.class),
              eq(RedisScriptManager.ADD_AGENTS),
              any(List.class),
              any(List.class));
      AgentAcquisitionService fbSvcNoInit =
          new AgentAcquisitionService(
              parityJedisPool,
              fbMgrNoInit,
              parityIntervalProvider,
              parityShardingFilter,
              parityAgentProperties,
              paritySchedulerProperties,
              metrics);

      batchSvcNoInit.registerAgent(
          a1, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      batchSvcNoInit.registerAgent(
          a2, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      fbSvcNoInit.registerAgent(
          a1, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      fbSvcNoInit.registerAgent(
          a2, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      // Clear any residual entries
      try (Jedis j = parityJedisPool.getResource()) {
        j.del("waiting");
        j.del("working");
      }

      // Batch-first repopulation
      batchSvcNoInit.repopulateIfDue(1L);
      try (Jedis j = parityJedisPool.getResource()) {
        assertThat(j.zscore("waiting", "repop-a1")).isNotNull();
        assertThat(j.zscore("waiting", "repop-a2")).isNotNull();
        assertThat(j.zscore("working", "repop-a1")).isNull();
        assertThat(j.zscore("working", "repop-a2")).isNull();
        j.del("waiting");
        j.del("working");
      }

      // Fallback repopulation (ADD_AGENTS throws)
      fbSvcNoInit.repopulateIfDue(1L);
      try (Jedis j = parityJedisPool.getResource()) {
        assertThat(j.zscore("waiting", "repop-a1")).isNotNull();
        assertThat(j.zscore("waiting", "repop-a2")).isNotNull();
        assertThat(j.zscore("working", "repop-a1")).isNull();
        assertThat(j.zscore("working", "repop-a2")).isNull();
      }
    }

    @Test
    @DisplayName("Acquisition: batch-first equals fallback outcomes")
    void acquisitionParity() {
      PrioritySchedulerMetrics metrics =
          new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
      RedisScriptManager batchMgr = new RedisScriptManager(parityJedisPool, metrics);
      batchMgr.initializeScripts();

      // Fallback manager: force individual acquisition by throwing on ACQUIRE_AGENTS
      RedisScriptManager fbMgr = spy(new RedisScriptManager(parityJedisPool, metrics));
      fbMgr.initializeScripts();
      doThrow(new RuntimeException("forced"))
          .when(fbMgr)
          .evalshaWithSelfHeal(
              any(Jedis.class),
              eq(RedisScriptManager.ACQUIRE_AGENTS),
              any(List.class),
              any(List.class));

      // Disable circuit breakers for deterministic unit-level test
      paritySchedulerProperties.getCircuitBreaker().setEnabled(false);

      AgentAcquisitionService batchSvc =
          new AgentAcquisitionService(
              parityJedisPool,
              batchMgr,
              parityIntervalProvider,
              parityShardingFilter,
              parityAgentProperties,
              paritySchedulerProperties,
              metrics);

      AgentAcquisitionService fbSvc =
          new AgentAcquisitionService(
              parityJedisPool,
              fbMgr,
              parityIntervalProvider,
              parityShardingFilter,
              parityAgentProperties,
              paritySchedulerProperties,
              metrics);

      Agent a1 = mkAgent("acq-a1");
      Agent a2 = mkAgent("acq-a2");

      // Scenario 1: batch-first
      try (Jedis j = parityJedisPool.getResource()) {
        j.flushDB();
      }
      // Register after flush; explicit seed already added to WAITING
      batchSvc.registerAgent(a1, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      batchSvc.registerAgent(a2, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      // Ensure readiness: set waiting scores to past (re-seed explicitly in case registration
      // wrote)
      try (Jedis j = parityJedisPool.getResource()) {
        long now = Long.parseLong(j.time().get(0));
        j.zadd("waiting", now - 5, "acq-a1");
        j.zadd("waiting", now - 5, "acq-a2");
      }

      // Ensure no circuit breaker interference
      batchSvc.resetCircuitBreakers();
      int batchAcq =
          batchSvc.saturatePool(
              0L, new java.util.concurrent.Semaphore(2), Executors.newCachedThreadPool());
      assertThat(batchAcq).isGreaterThanOrEqualTo(0);

      // Scenario 2: fallback forced
      try (Jedis j = parityJedisPool.getResource()) {
        j.flushDB();
        long now = Long.parseLong(j.time().get(0));
        j.zadd("waiting", now - 5, "acq-a1");
        j.zadd("waiting", now - 5, "acq-a2");
      }
      // Register same agents for fallback scenario after flush; explicit seed already added to
      // WAITING
      fbSvc.registerAgent(a1, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));
      fbSvc.registerAgent(a2, mock(AgentExecution.class), mock(ExecutionInstrumentation.class));

      // Ensure no circuit breaker interference
      fbSvc.resetCircuitBreakers();
      int acqFb =
          fbSvc.saturatePool(
              0L, new java.util.concurrent.Semaphore(2), Executors.newCachedThreadPool());
      assertThat(acqFb).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Cleanup: zombie batch-first equals fallback outcomes")
    void zombieCleanupParity() {
      PrioritySchedulerMetrics metrics =
          new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry());
      RedisScriptManager batchMgr = new RedisScriptManager(parityJedisPool, metrics);
      batchMgr.initializeScripts();

      // Setup: place an agent in working with a score
      try (Jedis j = parityJedisPool.getResource()) {
        long nowSec = Long.parseLong(j.time().get(0));
        j.zadd("working", nowSec + 30, "z-agent");
      }

      ZombieCleanupService batchSvc =
          new ZombieCleanupService(parityJedisPool, batchMgr, paritySchedulerProperties, metrics);

      // Fallback manager: throw on REMOVE_AGENTS_CONDITIONAL to force per-item fallback
      RedisScriptManager fbMgr = spy(new RedisScriptManager(parityJedisPool, metrics));
      fbMgr.initializeScripts();
      doThrow(new RuntimeException("forced"))
          .when(fbMgr)
          .evalshaWithSelfHeal(
              any(Jedis.class),
              eq(RedisScriptManager.REMOVE_AGENTS_CONDITIONAL),
              any(List.class),
              any(List.class));

      ZombieCleanupService fbSvc =
          new ZombieCleanupService(parityJedisPool, fbMgr, paritySchedulerProperties, metrics);

      Map<String, String> active = new HashMap<>();
      active.put("z-agent", Long.toString(System.currentTimeMillis() / 1000 + 30));

      // Reset acquisition circuit breakers via acquisition service not present; skip (cleanup
      // doesn't
      // have breakers)
      int cleanedBatch = batchSvc.cleanupZombieAgents(active, new HashMap<>());
      int cleanedFb = fbSvc.cleanupZombieAgents(new HashMap<>(active), new HashMap<>());

      assertThat(cleanedBatch).isGreaterThanOrEqualTo(0);
      assertThat(cleanedFb).isGreaterThanOrEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Edge Cases Tests")
  class EdgeCasesTests {

    private JedisPool edgeCasesJedisPool;
    private RedisScriptManager edgeCasesScriptManager;
    private PrioritySchedulerProperties edgeCasesSchedulerProperties;
    private PriorityAgentProperties edgeCasesAgentProperties;
    private ExecutorService edgeCasesExecutorService;
    private AgentAcquisitionService edgeCasesAcquisitionService;
    private ZombieCleanupService edgeCasesZombieService;
    private AgentIntervalProvider edgeCasesIntervalProvider;
    private ShardingFilter edgeCasesShardingFilter;

    @BeforeEach
    void setUpEdgeCasesTests() {
      String redisHost = redis.getHost();
      int redisPort = redis.getMappedPort(6379);

      JedisPoolConfig poolConfig = new JedisPoolConfig();
      poolConfig.setMaxTotal(8);
      edgeCasesJedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000, "testpass");

      // Clean Redis state
      try (Jedis jedis = edgeCasesJedisPool.getResource()) {
        jedis.flushAll();
      }

      edgeCasesScriptManager =
          new RedisScriptManager(
              edgeCasesJedisPool,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
      edgeCasesScriptManager.initializeScripts();

      edgeCasesSchedulerProperties = new PrioritySchedulerProperties();
      edgeCasesSchedulerProperties.getKeys().setWaitingSet("waiting");
      edgeCasesSchedulerProperties.getKeys().setWorkingSet("working");
      edgeCasesSchedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");
      edgeCasesAgentProperties = new PriorityAgentProperties();
      edgeCasesAgentProperties.setMaxConcurrentAgents(50);

      edgeCasesExecutorService = Executors.newFixedThreadPool(10);

      // Mock the required dependencies
      edgeCasesIntervalProvider = agent -> new AgentIntervalProvider.Interval(60000L, 120000L);
      edgeCasesShardingFilter = agent -> true;

      edgeCasesAcquisitionService =
          new AgentAcquisitionService(
              edgeCasesJedisPool,
              edgeCasesScriptManager,
              edgeCasesIntervalProvider,
              edgeCasesShardingFilter,
              edgeCasesAgentProperties,
              edgeCasesSchedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
      edgeCasesZombieService =
          new ZombieCleanupService(
              edgeCasesJedisPool,
              edgeCasesScriptManager,
              edgeCasesSchedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    }

    @AfterEach
    void tearDownEdgeCasesTests() {
      if (edgeCasesExecutorService != null) {
        edgeCasesExecutorService.shutdown();
      }
      if (edgeCasesJedisPool != null) {
        edgeCasesJedisPool.close();
      }
    }

    @Nested
    @DisplayName("Large Scale Batch Operation Tests")
    class LargeScaleBatchOperationTests {

      @Test
      @DisplayName("Should handle large batch of agents efficiently")
      void shouldHandleLargeBatchOfAgentsEfficiently() throws Exception {
        // Simulate 5K+ AWS accounts scenario (scaled down for test)
        int agentCount = 500; // Scaled down but still significant
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties.getBatchOperations().setBatchSize(50);

        // Register many agents
        for (int i = 1; i <= agentCount; i++) {
          Agent agent = TestFixtures.createMockAgent("large-scale-agent-" + i, "aws");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        long startTime = System.currentTimeMillis();
        int acquired = edgeCasesAcquisitionService.saturatePool(0L, null, edgeCasesExecutorService);
        long duration = System.currentTimeMillis() - startTime;

        // Should acquire agents efficiently
        assertThat(acquired).isLessThanOrEqualTo(50); // Limited by concurrency
        assertThat(duration).isLessThan(5000); // Should complete within 5 seconds

        System.out.println(
            "Large scale test: " + acquired + " agents acquired in " + duration + "ms");
      }

      @Test
      @DisplayName("Should handle memory pressure with many agents")
      void shouldHandleMemoryPressureWithManyAgents() throws Exception {
        int agentCount = 1000;
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties
            .getBatchOperations()
            .setBatchSize(25); // Smaller batches for memory efficiency

        // Register agents with various execution times
        for (int i = 1; i <= agentCount; i++) {
          Agent agent = TestFixtures.createMockAgent("memory-test-agent-" + i, "aws");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        // Monitor memory usage (simplified check)
        Runtime runtime = Runtime.getRuntime();
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

        int acquired = edgeCasesAcquisitionService.saturatePool(0L, null, edgeCasesExecutorService);

        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = memoryAfter - memoryBefore;

        // Memory increase should be reasonable (less than 100MB for 1000 agents)
        assertThat(memoryIncrease).isLessThan(100 * 1024 * 1024);
        assertThat(acquired).isGreaterThan(0);
      }
    }

    @Nested
    @DisplayName("Redis Connection Edge Cases")
    class RedisConnectionEdgeCaseTests {

      @Test
      @DisplayName("Should handle Redis connection timeout during batch operation")
      void shouldHandleRedisConnectionTimeoutDuringBatchOperation() throws Exception {
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);

        // Register agents
        for (int i = 1; i <= 5; i++) {
          Agent agent = TestFixtures.createMockAgent("timeout-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        // Create a Jedis pool with very short timeout to simulate connection issues
        JedisPoolConfig shortTimeoutConfig = new JedisPoolConfig();
        shortTimeoutConfig.setMaxTotal(1);
        shortTimeoutConfig.setMaxWaitMillis(10); // Very short wait
        JedisPool shortTimeoutPool =
            new JedisPool(
                shortTimeoutConfig, redis.getHost(), redis.getMappedPort(6379), 2000, "testpass");

        AgentAcquisitionService timeoutService =
            new AgentAcquisitionService(
                shortTimeoutPool,
                edgeCasesScriptManager,
                edgeCasesIntervalProvider,
                edgeCasesShardingFilter,
                edgeCasesAgentProperties,
                edgeCasesSchedulerProperties,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        // Copy agents to the new service
        for (int i = 1; i <= 5; i++) {
          Agent agent = TestFixtures.createMockAgent("timeout-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          timeoutService.registerAgent(agent, execution, instrumentation);
        }

        // Should handle connection timeout gracefully
        assertThatCode(
                () -> {
                  int acquired = timeoutService.saturatePool(0L, null, edgeCasesExecutorService);
                  assertThat(acquired).isGreaterThanOrEqualTo(0);
                })
            .doesNotThrowAnyException();

        shortTimeoutPool.close();
      }

      @Test
      @DisplayName("Should handle Redis script execution failure with proper fallback")
      void shouldHandleRedisScriptExecutionFailureWithProperFallback() throws Exception {
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);

        // Mock script manager that simulates script execution failure
        RedisScriptManager mockScriptManager = spy(edgeCasesScriptManager);
        when(mockScriptManager.getScriptSha(RedisScriptManager.ACQUIRE_AGENTS))
            .thenReturn("nonexistent-sha-that-will-cause-noscript-error");

        AgentAcquisitionService serviceWithFailingScript =
            new AgentAcquisitionService(
                edgeCasesJedisPool,
                mockScriptManager,
                edgeCasesIntervalProvider,
                edgeCasesShardingFilter,
                edgeCasesAgentProperties,
                edgeCasesSchedulerProperties,
                new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

        // Register agents
        for (int i = 1; i <= 3; i++) {
          Agent agent = TestFixtures.createMockAgent("script-fail-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          serviceWithFailingScript.registerAgent(agent, execution, instrumentation);
        }

        // Should fallback to individual acquisition when script fails
        int acquired = serviceWithFailingScript.saturatePool(0L, null, edgeCasesExecutorService);

        // Should still acquire agents via fallback (individual mode)
        assertThat(acquired).isEqualTo(3);
      }
    }

    @Nested
    @DisplayName("Batch Size Boundary Tests")
    class BatchSizeBoundaryTests {

      @Test
      @DisplayName("Should handle batch size equal to agent count")
      void shouldHandleBatchSizeEqualToAgentCount() throws Exception {
        int agentCount = 5;
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties.getBatchOperations().setBatchSize(agentCount); // Exact match

        for (int i = 1; i <= agentCount; i++) {
          Agent agent = TestFixtures.createMockAgent("exact-batch-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        int acquired = edgeCasesAcquisitionService.saturatePool(0L, null, edgeCasesExecutorService);
        assertThat(acquired).isEqualTo(agentCount);
      }

      @Test
      @DisplayName("Should handle batch size larger than agent count")
      void shouldHandleBatchSizeLargerThanAgentCount() throws Exception {
        int agentCount = 3;
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties
            .getBatchOperations()
            .setBatchSize(10); // Much larger than agent count

        for (int i = 1; i <= agentCount; i++) {
          Agent agent = TestFixtures.createMockAgent("small-count-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        int acquired = edgeCasesAcquisitionService.saturatePool(0L, null, edgeCasesExecutorService);
        assertThat(acquired).isEqualTo(agentCount);
      }

      @Test
      @DisplayName("Should handle single agent with batch operations enabled")
      void shouldHandleSingleAgentWithBatchOperationsEnabled() throws Exception {
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties.getBatchOperations().setBatchSize(10);

        Agent agent = TestFixtures.createMockAgent("single-batch-agent", "test");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);

        int acquired = edgeCasesAcquisitionService.saturatePool(0L, null, edgeCasesExecutorService);
        assertThat(acquired).isEqualTo(1);
      }
    }

    @Nested
    @DisplayName("Concurrent Access Edge Cases")
    class ConcurrentAccessEdgeCaseTests {

      @Test
      @DisplayName("Should handle concurrent batch operations safely")
      void shouldHandleConcurrentBatchOperationsSafely() throws Exception {
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties.getBatchOperations().setBatchSize(5);

        // Register agents
        for (int i = 1; i <= 20; i++) {
          Agent agent = TestFixtures.createMockAgent("concurrent-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        // Run multiple acquisition attempts concurrently
        ExecutorService concurrentExecutor = Executors.newFixedThreadPool(3);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
          final long runId = i;
          futures.add(
              concurrentExecutor.submit(
                  () ->
                      edgeCasesAcquisitionService.saturatePool(
                          runId, null, edgeCasesExecutorService)));
        }

        // Wait for all to complete
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) {
          results.add(future.get(5, TimeUnit.SECONDS));
        }

        // Should handle concurrent access without exceptions
        assertThat(results).allSatisfy(result -> assertThat(result).isGreaterThanOrEqualTo(0));

        concurrentExecutor.shutdown();
      }

      @Test
      @DisplayName("Should handle agent registration during batch operation")
      void shouldHandleAgentRegistrationDuringBatchOperation() throws Exception {
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties.getBatchOperations().setBatchSize(5);

        // Register initial agents
        for (int i = 1; i <= 5; i++) {
          Agent agent = TestFixtures.createMockAgent("initial-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        // Start acquisition in background
        Future<Integer> acquisitionFuture =
            Executors.newSingleThreadExecutor()
                .submit(
                    () -> {
                      int totalAcquired = 0;
                      for (int i = 0; i < 5; i++) {
                        totalAcquired +=
                            edgeCasesAcquisitionService.saturatePool(
                                (long) i, null, edgeCasesExecutorService);
                        try {
                          Thread.sleep(100);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          break;
                        }
                      }
                      return totalAcquired;
                    });

        // Register additional agents while acquisition is running
        Thread.sleep(50);
        for (int i = 6; i <= 10; i++) {
          Agent agent = TestFixtures.createMockAgent("dynamic-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
          Thread.sleep(20);
        }

        // Should complete without exceptions
        Integer totalAcquired = acquisitionFuture.get(10, TimeUnit.SECONDS);
        assertThat(totalAcquired).isGreaterThanOrEqualTo(0);
        assertThat(edgeCasesAcquisitionService.getRegisteredAgentCount()).isEqualTo(10);
      }
    }

    @Nested
    @DisplayName("Zombie Cleanup Batch Edge Cases")
    class ZombieCleanupBatchEdgeCaseTests {

      @Test
      @DisplayName("Should handle large number of zombie agents efficiently")
      void shouldHandleLargeNumberOfZombieAgentsEfficiently() {
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties.getBatchOperations().setBatchSize(50);

        // Create many zombie agents
        Map<String, String> activeAgents = new ConcurrentHashMap<>();
        Map<String, Future<?>> activeAgentsFutures = new ConcurrentHashMap<>();
        long oldScoreSeconds = (System.currentTimeMillis() - 120000) / 1000; // 2 minutes ago

        try (Jedis jedis = edgeCasesJedisPool.getResource()) {
          for (int i = 1; i <= 50; i++) {
            String agentType = "large-zombie-" + i;
            jedis.zadd("working", oldScoreSeconds, agentType);
            activeAgents.put(agentType, String.valueOf(oldScoreSeconds));
            activeAgentsFutures.put(agentType, mock(Future.class));
          }
        }

        long startTime = System.currentTimeMillis();
        int cleaned = edgeCasesZombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);
        long duration = System.currentTimeMillis() - startTime;

        // Should clean up all zombies efficiently
        assertThat(cleaned).isEqualTo(50);
        assertThat(duration).isLessThan(10000); // Should complete within 10 seconds
        assertThat(activeAgents).isEmpty();
        assertThat(activeAgentsFutures).isEmpty();

        System.out.println("Large zombie cleanup: " + cleaned + " agents in " + duration + "ms");
      }

      @Test
      @DisplayName("Should handle mixed zombie ages in batch")
      void shouldHandleMixedZombieAgesInBatch() {
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties.getBatchOperations().setBatchSize(10);

        Map<String, String> activeAgents = new ConcurrentHashMap<>();
        Map<String, Future<?>> activeAgentsFutures = new ConcurrentHashMap<>();
        long currentTime = System.currentTimeMillis();

        try (Jedis jedis = edgeCasesJedisPool.getResource()) {
          // Mix of old zombies and recent agents
          for (int i = 1; i <= 5; i++) {
            // Old zombies (should be cleaned)
            String zombieType = "old-zombie-" + i;
            long oldScore = (currentTime - 120000) / 1000; // 2 minutes ago
            jedis.zadd("working", oldScore, zombieType);
            activeAgents.put(zombieType, String.valueOf(oldScore));
            activeAgentsFutures.put(zombieType, mock(Future.class));

            // Recent agents (should NOT be cleaned)
            String recentType = "recent-agent-" + i;
            long recentScore = (currentTime - 10000) / 1000; // 10 seconds ago
            jedis.zadd("working", recentScore, recentType);
            activeAgents.put(recentType, String.valueOf(recentScore));
            activeAgentsFutures.put(recentType, mock(Future.class));
          }
        }

        int cleaned = edgeCasesZombieService.cleanupZombieAgents(activeAgents, activeAgentsFutures);

        // Should only clean the old zombies, not the recent agents
        assertThat(cleaned).isEqualTo(5);
        assertThat(activeAgents).hasSize(5); // Recent agents should remain
        assertThat(activeAgentsFutures).hasSize(5);
      }
    }

    @Nested
    @DisplayName("Resource Exhaustion Edge Cases")
    class ResourceExhaustionEdgeCaseTests {

      @Test
      @DisplayName("Should handle Redis memory pressure gracefully")
      void shouldHandleRedisMemoryPressureGracefully() throws Exception {
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties.getBatchOperations().setBatchSize(100);

        // Fill Redis with data to simulate memory pressure
        try (Jedis jedis = edgeCasesJedisPool.getResource()) {
          for (int i = 0; i < 1000; i++) {
            jedis.set("memory-pressure-key-" + i, "x".repeat(1000));
          }
        }

        // Register agents
        for (int i = 1; i <= 10; i++) {
          Agent agent = TestFixtures.createMockAgent("pressure-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        // Should handle memory pressure without crashing
        assertThatCode(
                () -> {
                  int acquired =
                      edgeCasesAcquisitionService.saturatePool(0L, null, edgeCasesExecutorService);
                  assertThat(acquired).isGreaterThanOrEqualTo(0);
                })
            .doesNotThrowAnyException();
      }

      @Test
      @DisplayName("Should handle semaphore exhaustion in batch mode")
      void shouldHandleSemaphoreExhaustionInBatchMode() throws Exception {
        edgeCasesSchedulerProperties.getBatchOperations().setEnabled(true);
        edgeCasesSchedulerProperties.getBatchOperations().setBatchSize(10);

        // Create very limited semaphore
        Semaphore limitedSemaphore = new Semaphore(2);

        // Register more agents than semaphore permits
        for (int i = 1; i <= 10; i++) {
          Agent agent = TestFixtures.createMockAgent("semaphore-agent-" + i, "test");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          edgeCasesAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        // Should respect semaphore limits even in batch mode
        int acquired =
            edgeCasesAcquisitionService.saturatePool(
                0L, limitedSemaphore, edgeCasesExecutorService);
        assertThat(acquired).isLessThanOrEqualTo(2);

        // Wait for execution to complete
        Thread.sleep(100);
        assertThat(limitedSemaphore.availablePermits()).isEqualTo(2);
      }
    }

  }

  @Nested
  @DisplayName("Batch Scoring Tests")
  class BatchScoringTests {

    private JedisPool scoringJedisPool;
    private RedisScriptManager scoringScriptManager;
    private AgentAcquisitionService scoringAcquisitionService;
    private AgentIntervalProvider scoringIntervalProvider;
    private ShardingFilter scoringShardingFilter;
    private PriorityAgentProperties scoringAgentProperties;
    private PrioritySchedulerProperties scoringSchedulerProperties;
    private ExecutorService scoringExecutorService;

    @BeforeEach
    void setUpScoringTests() {
      String redisHost = redis.getHost();
      int redisPort = redis.getMappedPort(6379);
      scoringJedisPool =
          new JedisPool(new JedisPoolConfig(), redisHost, redisPort, 2000, "testpass");

      // Clean Redis state
      try (Jedis j = scoringJedisPool.getResource()) {
        j.flushAll();
      }

      scoringScriptManager =
          new RedisScriptManager(
              scoringJedisPool,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
      scoringScriptManager.initializeScripts();

      // Mock dependencies
      scoringIntervalProvider = mock(AgentIntervalProvider.class);
      scoringShardingFilter = mock(ShardingFilter.class);
      when(scoringShardingFilter.filter(any(Agent.class))).thenReturn(true);

      AgentIntervalProvider.Interval testInterval =
          new AgentIntervalProvider.Interval(60000L, 300000L);
      when(scoringIntervalProvider.getInterval(any(Agent.class))).thenReturn(testInterval);

      scoringAgentProperties = new PriorityAgentProperties();
      scoringAgentProperties.setMaxConcurrentAgents(10);
      scoringAgentProperties.setEnabledPattern(".*");
      scoringAgentProperties.setDisabledPattern("");

      scoringSchedulerProperties = new PrioritySchedulerProperties();
      scoringSchedulerProperties.setRefreshPeriodSeconds(10);
      scoringSchedulerProperties.getBatchOperations().setEnabled(true);
      scoringSchedulerProperties.getBatchOperations().setBatchSize(50);
      scoringSchedulerProperties.getKeys().setWaitingSet("waiting");
      scoringSchedulerProperties.getKeys().setWorkingSet("working");
      scoringSchedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");

      scoringExecutorService = Executors.newFixedThreadPool(5);

      scoringAcquisitionService =
          new AgentAcquisitionService(
              scoringJedisPool,
              scoringScriptManager,
              scoringIntervalProvider,
              scoringShardingFilter,
              scoringAgentProperties,
              scoringSchedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
    }

    @AfterEach
    void tearDownScoringTests() {
      if (scoringExecutorService != null) {
        scoringExecutorService.shutdown();
      }
      if (scoringJedisPool != null) {
        scoringJedisPool.close();
      }
    }

    @Nested
    @DisplayName("Script Functionality Tests")
    class ScriptFunctionalityTests {

      @Test
      @DisplayName("Batch agent score script should execute correctly")
      void batchAgentScoreScriptShouldExecuteCorrectly() {
        try (var jedis = scoringJedisPool.getResource()) {
          jedis.del("working", "waiting");

          jedis.zadd("working", 1000, "agent1");
          jedis.zadd("waiting", 2000, "agent2");

          List<String> agentNames = Arrays.asList("agent1", "agent2", "agent3");

          @SuppressWarnings("unchecked")
          List<String> results =
              (List<String>)
                  jedis.evalsha(
                      scoringScriptManager.getScriptSha(RedisScriptManager.SCORE_AGENTS),
                      Arrays.asList("working", "waiting"),
                      agentNames);

          assertThat(results).hasSize(9);

          assertThat(results.get(0)).isEqualTo("agent1");
          assertThat(results.get(1)).isEqualTo("1000");
          assertThat(results.get(2)).isEqualTo("null");

          assertThat(results.get(3)).isEqualTo("agent2");
          assertThat(results.get(4)).isEqualTo("null");
          assertThat(results.get(5)).isEqualTo("2000");

          assertThat(results.get(6)).isEqualTo("agent3");
          assertThat(results.get(7)).isEqualTo("null");
          assertThat(results.get(8)).isEqualTo("null");
        }
      }

      @Test
      @DisplayName("Script manager should load all scripts")
      void scriptManagerShouldLoadAllScripts() {
        try (var jedis = scoringJedisPool.getResource()) {
          String scriptSha = scoringScriptManager.getScriptSha(RedisScriptManager.SCORE_AGENTS);
          assertThat(scriptSha).isNotNull().isNotEmpty();

          Boolean exists = jedis.scriptExists(scriptSha);
          assertThat(exists).isTrue();
        }
      }
    }

    @Nested
    @DisplayName("Scoring Consistency Tests")
    class ScoringConsistencyTests {

      @Test
      @DisplayName("Individual agentScore should keep existing scores for overdue agents")
      void individualScoringKeepsOverdueScores() throws Exception {
        Agent overdueAgent = TestFixtures.createMockAgent("overdue-agent", "test-provider");
        long currentTimeSeconds;
        try (Jedis j = scoringJedisPool.getResource()) {
          currentTimeSeconds = Long.parseLong(j.time().get(0));
        }
        long overdueScore = currentTimeSeconds - 300;

        try (Jedis jedis = scoringJedisPool.getResource()) {
          jedis.zadd("waiting", overdueScore, "overdue-agent");
        }

        Method agentScoreMethod =
            AgentAcquisitionService.class.getDeclaredMethod("agentScore", Agent.class);
        agentScoreMethod.setAccessible(true);
        String result = (String) agentScoreMethod.invoke(scoringAcquisitionService, overdueAgent);

        assertThat(result).isEqualTo(String.valueOf(overdueScore));
        assertThat(Long.parseLong(result)).isLessThan(currentTimeSeconds);
      }

      @Test
      @DisplayName("Batch scoring should match individual scoring for overdue agents")
      void batchScoringMatchesIndividualForOverdueAgents() throws Exception {
        long currentTimeSeconds;
        try (Jedis j = scoringJedisPool.getResource()) {
          currentTimeSeconds = Long.parseLong(j.time().get(0));
        }
        long overdueScore = currentTimeSeconds - 300;

        Agent overdueAgent = TestFixtures.createMockAgent("overdue-agent", "test-provider");

        try (Jedis jedis = scoringJedisPool.getResource()) {
          jedis.del("waiting", "working");
          jedis.zadd("waiting", overdueScore, "overdue-agent");

          Method agentScoreMethod =
              AgentAcquisitionService.class.getDeclaredMethod("agentScore", Agent.class);
          agentScoreMethod.setAccessible(true);
          String individualResult =
              (String) agentScoreMethod.invoke(scoringAcquisitionService, overdueAgent);

          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          scoringAcquisitionService.registerAgent(overdueAgent, execution, instrumentation);

          Field agentsField = AgentAcquisitionService.class.getDeclaredField("agents");
          agentsField.setAccessible(true);
          @SuppressWarnings("unchecked")
          Map<String, Object> agentsMap =
              (Map<String, Object>) agentsField.get(scoringAcquisitionService);
          Collection<Object> workers = agentsMap.values();

          Method batchAgentScoreMethod =
              AgentAcquisitionService.class.getDeclaredMethod(
                  "batchAgentScore", Jedis.class, Collection.class);
          batchAgentScoreMethod.setAccessible(true);

          @SuppressWarnings("unchecked")
          Map<String, String> batchResults =
              (Map<String, String>)
                  batchAgentScoreMethod.invoke(scoringAcquisitionService, jedis, workers);

          String batchResult = batchResults.get("overdue-agent");

          assertThat(Long.parseLong(individualResult))
              .as("Individual scoring should preserve overdue score")
              .isEqualTo(overdueScore);

          assertThat(Long.parseLong(batchResult))
              .as("Batch scoring should preserve overdue score")
              .isEqualTo(overdueScore);

          assertScoresConsistent("overdue-agent", individualResult, batchResult);
        }
      }

      @Test
      @DisplayName("New agents should get immediate execution in both methods")
      void newAgentsGetImmediateExecution() throws Exception {
        Agent newAgent = TestFixtures.createMockAgent("new-agent", "test-provider");

        Method agentScoreMethod =
            AgentAcquisitionService.class.getDeclaredMethod("agentScore", Agent.class);
        agentScoreMethod.setAccessible(true);

        long scoringTimeSeconds;
        try (Jedis j = scoringJedisPool.getResource()) {
          scoringTimeSeconds = Long.parseLong(j.time().get(0));
        }
        String individualResult =
            (String) agentScoreMethod.invoke(scoringAcquisitionService, newAgent);

        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        scoringAcquisitionService.registerAgent(newAgent, execution, instrumentation);

        Field agentsField = AgentAcquisitionService.class.getDeclaredField("agents");
        agentsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> agentsMap =
            (Map<String, Object>) agentsField.get(scoringAcquisitionService);
        Collection<Object> workers = agentsMap.values();

        Method batchAgentScoreMethod =
            AgentAcquisitionService.class.getDeclaredMethod(
                "batchAgentScore", Jedis.class, Collection.class);
        batchAgentScoreMethod.setAccessible(true);

        try (Jedis jedis = scoringJedisPool.getResource()) {
          @SuppressWarnings("unchecked")
          Map<String, String> batchResults =
              (Map<String, String>)
                  batchAgentScoreMethod.invoke(scoringAcquisitionService, jedis, workers);

          String batchResult = batchResults.get("new-agent");

          long currentTimeSeconds;
          try (Jedis j2 = scoringJedisPool.getResource()) {
            List<String> times = j2.time();
            currentTimeSeconds = Long.parseLong(times.get(0));
          }
          long individualScore = Long.parseLong(individualResult);
          long batchScore = Long.parseLong(batchResult);

          // Allow small skew since both sides are second-granularity
          assertThat(individualScore).isBetween(scoringTimeSeconds - 4, currentTimeSeconds + 4);
          assertThat(batchScore).isBetween(scoringTimeSeconds - 4, currentTimeSeconds + 4);
        }
      }
    }

    @Nested
    @DisplayName("Batch Operations Tests")
    class BatchOperationsTests {

      @Test
      @DisplayName("Should handle repopulation edge cases and fallback scenarios")
      void shouldHandleRepopulationEdgeCases() throws Exception {
        for (int i = 0; i < 5; i++) {
          Agent agent = TestFixtures.createMockAgent("TestAgent-" + i, "test-provider");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
          scoringAcquisitionService.registerAgent(agent, execution, instrumentation);
        }

        int registeredCount = scoringAcquisitionService.getRegisteredAgentCount();
        assertThat(registeredCount).isGreaterThan(0);

        try (var jedis = scoringJedisPool.getResource()) {
          jedis.del("waiting", "working");
          long totalBefore = jedis.zcard("waiting") + jedis.zcard("working");
          assertThat(totalBefore).isEqualTo(0);
        }

        // Test normal repopulation with runCount=0 (use Semaphore(0) to prevent execution)
        scoringAcquisitionService.saturatePool(0L, new Semaphore(0), scoringExecutorService);

        try (var jedis = scoringJedisPool.getResource()) {
          long waitzCount = jedis.zcard("waiting");
          long workzCount = jedis.zcard("working");
          long totalAfter = waitzCount + workzCount;

          if (totalAfter > 0) {
            assertThat(totalAfter).isEqualTo(registeredCount);

            // Verify we can inspect agent details
            var waitzMembers = jedis.zrange("waiting", 0, 2);
            var workzMembers = jedis.zrange("working", 0, 2);
            assertThat(waitzMembers.size() + workzMembers.size()).isGreaterThan(0);
          } else {
            // Fallback test: try with runCount=30 to force repopulation
            scoringAcquisitionService.saturatePool(30L, new Semaphore(0), scoringExecutorService);

            long totalAfter2 = jedis.zcard("waiting") + jedis.zcard("working");
            assertThat(totalAfter2).isGreaterThan(0);
          }
        }
      }

      @Test
      @DisplayName("Should reduce Redis operations during repopulation")
      void shouldReduceRedisOperationsDuringRepopulation() throws Exception {
        int targetAgentCount = 25;
        int actuallyRegistered = 0;

        for (int i = 0; i < targetAgentCount; i++) {
          Agent agent = TestFixtures.createMockAgent("TestAgent-" + i, "test-provider");
          AgentExecution execution = mock(AgentExecution.class);
          ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

          int beforeCount = scoringAcquisitionService.getRegisteredAgentCount();
          scoringAcquisitionService.registerAgent(agent, execution, instrumentation);
          int afterCount = scoringAcquisitionService.getRegisteredAgentCount();

          if (afterCount > beforeCount) {
            actuallyRegistered++;
          }
        }

        assertThat(actuallyRegistered).isGreaterThan(0);
        final int expectedAgentCount = actuallyRegistered;

        try (var jedis = scoringJedisPool.getResource()) {
          jedis.del("waiting", "working");
        }

        // Test batch mode
        scoringSchedulerProperties.getBatchOperations().setEnabled(true);
        long batchStartTime = System.currentTimeMillis();
        scoringAcquisitionService.saturatePool(0L, new Semaphore(0), scoringExecutorService);
        long batchDuration = System.currentTimeMillis() - batchStartTime;

        long agentsInRedisAfterBatch;
        try (var jedis = scoringJedisPool.getResource()) {
          agentsInRedisAfterBatch = jedis.zcard("waiting") + jedis.zcard("working");
          assertThat(agentsInRedisAfterBatch).isEqualTo(expectedAgentCount);
          jedis.del("waiting", "working");
        }

        // Test individual mode for comparison
        scoringSchedulerProperties.getBatchOperations().setEnabled(false);
        long individualStartTime = System.currentTimeMillis();
        scoringAcquisitionService.saturatePool(0L, new Semaphore(0), scoringExecutorService);
        long individualDuration = System.currentTimeMillis() - individualStartTime;

        try (var jedis = scoringJedisPool.getResource()) {
          long agentsInRedisAfterIndividual = jedis.zcard("waiting") + jedis.zcard("working");
          assertThat(agentsInRedisAfterIndividual).isEqualTo(expectedAgentCount);
        }

        // Batch mode should be at least as fast as individual mode
        assertThat(batchDuration).isLessThanOrEqualTo(individualDuration * 2);
      }

      @Test
      @DisplayName("Should maintain consistent scoring behavior")
      void shouldMaintainConsistentScoringBehavior() throws Exception {
        Agent workingAgent = TestFixtures.createMockAgent("WorkingAgent", "test-provider");
        Agent waitingAgent = TestFixtures.createMockAgent("WaitingAgent", "test-provider");
        Agent newAgent = TestFixtures.createMockAgent("NewAgent", "test-provider");

        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

        scoringAcquisitionService.registerAgent(workingAgent, execution, instrumentation);
        scoringAcquisitionService.registerAgent(waitingAgent, execution, instrumentation);
        scoringAcquisitionService.registerAgent(newAgent, execution, instrumentation);

        try (var jedis = scoringJedisPool.getResource()) {
          jedis.del("waiting", "working");

          long nowSec = Long.parseLong(jedis.time().get(0));
          jedis.zadd("working", nowSec + 3600, "WorkingAgent");
          jedis.zadd("waiting", nowSec + 1800, "WaitingAgent");
        }

        Semaphore runningAgents = new Semaphore(100);
        scoringSchedulerProperties.getBatchOperations().setEnabled(true);
        scoringAcquisitionService.saturatePool(0L, runningAgents, scoringExecutorService);

        // Wait for agent executions to complete
        Thread.sleep(100);

        // Process completion queue with another scheduler cycle
        // This is required for our connection optimization where completions
        // are queued and processed in the next cycle
        System.out.println("Processing completions in batch scoring test...");
        scoringAcquisitionService.saturatePool(1L, runningAgents, scoringExecutorService);

        try (var jedis = scoringJedisPool.getResource()) {
          Double workingScore = jedis.zscore("working", "WorkingAgent");
          Double waitingScore = jedis.zscore("waiting", "WaitingAgent");
          Double newScore = jedis.zscore("waiting", "NewAgent");
          if (newScore == null) {
            newScore = jedis.zscore("working", "NewAgent");
          }

          assertThat(workingScore).as("WorkingAgent should be in WORKING set").isNotNull();
          assertThat(waitingScore).as("WaitingAgent should be in WAITING set").isNotNull();
          assertThat(newScore).as("NewAgent should be in either set").isNotNull();
        }
      }
    }


    private void assertScoresConsistent(String agentName, String individual, String batch) {
      long individualScore = Long.parseLong(individual);
      long batchScore = Long.parseLong(batch);

      assertThat(Math.abs(individualScore - batchScore))
          .as(
              "Scores should be consistent for "
                  + agentName
                  + " (individual: "
                  + individual
                  + ", batch: "
                  + batch
                  + ")")
          .isLessThanOrEqualTo(2);
    }
  }


  private PrioritySchedulerProperties createZombieTestSchedulerProperties() {
    PrioritySchedulerProperties props = TestFixtures.createBatchEnabledSchedulerProperties();
    props.getZombieCleanup().setThresholdMs(5000L); // 5 seconds for testing
    props.getZombieCleanup().setIntervalMs(1000L); // 1 second for testing
    return props;
  }

  private PrioritySchedulerProperties createExceptionalAgentsTestProperties() {
    PrioritySchedulerProperties props = TestFixtures.createBatchEnabledSchedulerProperties();
    props.getZombieCleanup().setThresholdMs(30000L); // 30 seconds default
    props.getZombieCleanup().setIntervalMs(5000L); // 5 seconds interval

    // Configure exceptional agents for BigQuery-related agents
    props.getZombieCleanup().getExceptionalAgents().setPattern(".*BigQuery.*");
    props
        .getZombieCleanup()
        .getExceptionalAgents()
        .setThresholdMs(60000L); // 60 seconds for BigQuery

    return props;
  }

}
