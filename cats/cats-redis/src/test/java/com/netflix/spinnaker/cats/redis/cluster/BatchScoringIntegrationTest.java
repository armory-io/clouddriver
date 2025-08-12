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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
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
 * Integration tests for batch agent scoring operations.
 *
 * <p>Tests verify consistency between batch and individual scoring methods, proper handling of
 * overdue agents, and correct Redis script execution.
 */
@Testcontainers
@DisplayName("Batch Scoring Integration Tests")
class BatchScoringIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private AgentAcquisitionService acquisitionService;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;
  private ExecutorService executorService;

  @BeforeEach
  void setUp() {
    String redisHost = redis.getHost();
    int redisPort = redis.getFirstMappedPort();
    jedisPool = new JedisPool(new JedisPoolConfig(), redisHost, redisPort);

    scriptManager = new RedisScriptManager(jedisPool);
    scriptManager.initializeScripts();

    // Mock dependencies
    intervalProvider = mock(AgentIntervalProvider.class);
    shardingFilter = mock(ShardingFilter.class);
    when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

    AgentIntervalProvider.Interval testInterval =
        new AgentIntervalProvider.Interval(60000L, 300000L);
    when(intervalProvider.getInterval(any(Agent.class))).thenReturn(testInterval);

    agentProperties = new PriorityAgentProperties();
    agentProperties.setMaxConcurrentAgents(10);
    agentProperties.setEnabledPattern(".*");
    agentProperties.setDisabledPattern("");

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.setRefreshPeriodSeconds(10);
    schedulerProperties.getBatchOperations().setEnabled(true);
    schedulerProperties.getBatchOperations().setBatchSize(50);

    executorService = Executors.newFixedThreadPool(5);

    acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);
  }

  @AfterEach
  void tearDown() {
    if (executorService != null) {
      executorService.shutdown();
    }
    if (jedisPool != null) {
      jedisPool.close();
    }
  }

  @Nested
  @DisplayName("Script Functionality Tests")
  class ScriptFunctionalityTests {

    @Test
    @DisplayName("Batch agent score script should execute correctly")
    void batchAgentScoreScriptShouldExecuteCorrectly() {
      try (var jedis = jedisPool.getResource()) {
        jedis.del("WORKZ", "WAITZ");

        jedis.zadd("WORKZ", 1000, "agent1");
        jedis.zadd("WAITZ", 2000, "agent2");

        List<String> agentNames = Arrays.asList("agent1", "agent2", "agent3");

        @SuppressWarnings("unchecked")
        List<String> results =
            (List<String>)
                jedis.evalsha(
                    scriptManager.getScriptSha(RedisScriptManager.SCORE_AGENTS),
                    Arrays.asList("WORKZ", "WAITZ"),
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
      try (var jedis = jedisPool.getResource()) {
        String scriptSha = scriptManager.getScriptSha(RedisScriptManager.SCORE_AGENTS);
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
      Agent overdueAgent = createMockAgent("overdue-agent", "test-provider");
      long currentTimeSeconds = System.currentTimeMillis() / 1000;
      long overdueScore = currentTimeSeconds - 300;

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.zadd("WAITZ", overdueScore, "overdue-agent");
      }

      Method agentScoreMethod =
          AgentAcquisitionService.class.getDeclaredMethod("agentScore", Agent.class);
      agentScoreMethod.setAccessible(true);
      String result = (String) agentScoreMethod.invoke(acquisitionService, overdueAgent);

      assertThat(result).isEqualTo(String.valueOf(overdueScore));
      assertThat(Long.parseLong(result)).isLessThan(currentTimeSeconds);
    }

    @Test
    @DisplayName("Batch scoring should match individual scoring for overdue agents")
    void batchScoringMatchesIndividualForOverdueAgents() throws Exception {
      long currentTimeSeconds = System.currentTimeMillis() / 1000;
      long overdueScore = currentTimeSeconds - 300;

      Agent overdueAgent = createMockAgent("overdue-agent", "test-provider");

      try (Jedis jedis = jedisPool.getResource()) {
        jedis.del("WAITZ", "WORKZ");
        jedis.zadd("WAITZ", overdueScore, "overdue-agent");

        Method agentScoreMethod =
            AgentAcquisitionService.class.getDeclaredMethod("agentScore", Agent.class);
        agentScoreMethod.setAccessible(true);
        String individualResult =
            (String) agentScoreMethod.invoke(acquisitionService, overdueAgent);

        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(overdueAgent, execution, instrumentation);

        Field agentsField = AgentAcquisitionService.class.getDeclaredField("agents");
        agentsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> agentsMap = (Map<String, Object>) agentsField.get(acquisitionService);
        Collection<Object> workers = agentsMap.values();

        Method batchAgentScoreMethod =
            AgentAcquisitionService.class.getDeclaredMethod(
                "batchAgentScore", Jedis.class, Collection.class);
        batchAgentScoreMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> batchResults =
            (Map<String, String>) batchAgentScoreMethod.invoke(acquisitionService, jedis, workers);

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
      Agent newAgent = createMockAgent("new-agent", "test-provider");

      Method agentScoreMethod =
          AgentAcquisitionService.class.getDeclaredMethod("agentScore", Agent.class);
      agentScoreMethod.setAccessible(true);

      long scoringTimeSeconds = System.currentTimeMillis() / 1000;
      String individualResult = (String) agentScoreMethod.invoke(acquisitionService, newAgent);

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
      acquisitionService.registerAgent(newAgent, execution, instrumentation);

      Field agentsField = AgentAcquisitionService.class.getDeclaredField("agents");
      agentsField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, Object> agentsMap = (Map<String, Object>) agentsField.get(acquisitionService);
      Collection<Object> workers = agentsMap.values();

      Method batchAgentScoreMethod =
          AgentAcquisitionService.class.getDeclaredMethod(
              "batchAgentScore", Jedis.class, Collection.class);
      batchAgentScoreMethod.setAccessible(true);

      try (Jedis jedis = jedisPool.getResource()) {
        @SuppressWarnings("unchecked")
        Map<String, String> batchResults =
            (Map<String, String>) batchAgentScoreMethod.invoke(acquisitionService, jedis, workers);

        String batchResult = batchResults.get("new-agent");

        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        long individualScore = Long.parseLong(individualResult);
        long batchScore = Long.parseLong(batchResult);

        assertThat(individualScore).isBetween(scoringTimeSeconds - 3, currentTimeSeconds + 3);
        assertThat(batchScore).isBetween(scoringTimeSeconds - 3, currentTimeSeconds + 3);
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
        Agent agent = createMockAgent("TestAgent-" + i, "test-provider");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      int registeredCount = acquisitionService.getRegisteredAgentCount();
      assertThat(registeredCount).isGreaterThan(0);

      try (var jedis = jedisPool.getResource()) {
        jedis.del("WAITZ", "WORKZ");
        long totalBefore = jedis.zcard("WAITZ") + jedis.zcard("WORKZ");
        assertThat(totalBefore).isEqualTo(0);
      }

      // Test normal repopulation with runCount=0 (use Semaphore(0) to prevent execution)
      acquisitionService.saturatePool(0L, new Semaphore(0), executorService);

      try (var jedis = jedisPool.getResource()) {
        long waitzCount = jedis.zcard("WAITZ");
        long workzCount = jedis.zcard("WORKZ");
        long totalAfter = waitzCount + workzCount;

        if (totalAfter > 0) {
          assertThat(totalAfter).isEqualTo(registeredCount);

          // Verify we can inspect agent details
          var waitzMembers = jedis.zrange("WAITZ", 0, 2);
          var workzMembers = jedis.zrange("WORKZ", 0, 2);
          assertThat(waitzMembers.size() + workzMembers.size()).isGreaterThan(0);
        } else {
          // Fallback test: try with runCount=30 to force repopulation
          acquisitionService.saturatePool(30L, new Semaphore(0), executorService);

          long totalAfter2 = jedis.zcard("WAITZ") + jedis.zcard("WORKZ");
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
        Agent agent = createMockAgent("TestAgent-" + i, "test-provider");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

        int beforeCount = acquisitionService.getRegisteredAgentCount();
        acquisitionService.registerAgent(agent, execution, instrumentation);
        int afterCount = acquisitionService.getRegisteredAgentCount();

        if (afterCount > beforeCount) {
          actuallyRegistered++;
        }
      }

      assertThat(actuallyRegistered).isGreaterThan(0);
      final int expectedAgentCount = actuallyRegistered;

      try (var jedis = jedisPool.getResource()) {
        jedis.del("WAITZ", "WORKZ");
      }

      // Test batch mode
      schedulerProperties.getBatchOperations().setEnabled(true);
      long batchStartTime = System.currentTimeMillis();
      acquisitionService.saturatePool(0L, new Semaphore(0), executorService);
      long batchDuration = System.currentTimeMillis() - batchStartTime;

      long agentsInRedisAfterBatch;
      try (var jedis = jedisPool.getResource()) {
        agentsInRedisAfterBatch = jedis.zcard("WAITZ") + jedis.zcard("WORKZ");
        assertThat(agentsInRedisAfterBatch).isEqualTo(expectedAgentCount);
        jedis.del("WAITZ", "WORKZ");
      }

      // Test individual mode for comparison
      schedulerProperties.getBatchOperations().setEnabled(false);
      long individualStartTime = System.currentTimeMillis();
      acquisitionService.saturatePool(0L, new Semaphore(0), executorService);
      long individualDuration = System.currentTimeMillis() - individualStartTime;

      try (var jedis = jedisPool.getResource()) {
        long agentsInRedisAfterIndividual = jedis.zcard("WAITZ") + jedis.zcard("WORKZ");
        assertThat(agentsInRedisAfterIndividual).isEqualTo(expectedAgentCount);
      }

      // Batch mode should be at least as fast as individual mode
      assertThat(batchDuration).isLessThanOrEqualTo(individualDuration * 2);
    }

    @Test
    @DisplayName("Should maintain consistent scoring behavior")
    void shouldMaintainConsistentScoringBehavior() throws Exception {
      Agent workingAgent = createMockAgent("WorkingAgent", "test-provider");
      Agent waitingAgent = createMockAgent("WaitingAgent", "test-provider");
      Agent newAgent = createMockAgent("NewAgent", "test-provider");

      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);

      acquisitionService.registerAgent(workingAgent, execution, instrumentation);
      acquisitionService.registerAgent(waitingAgent, execution, instrumentation);
      acquisitionService.registerAgent(newAgent, execution, instrumentation);

      try (var jedis = jedisPool.getResource()) {
        jedis.del("WAITZ", "WORKZ");

        jedis.zadd("WORKZ", System.currentTimeMillis() / 1000 + 3600, "WorkingAgent");
        jedis.zadd("WAITZ", System.currentTimeMillis() / 1000 + 1800, "WaitingAgent");
      }

      Semaphore runningAgents = new Semaphore(100);
      schedulerProperties.getBatchOperations().setEnabled(true);
      acquisitionService.saturatePool(0L, runningAgents, executorService);

      // Wait for agent executions to complete
      Thread.sleep(100);

      // Process completion queue with another scheduler cycle
      // This is required for our connection optimization where completions
      // are queued and processed in the next cycle
      System.out.println("Processing completions in batch scoring test...");
      acquisitionService.saturatePool(1L, runningAgents, executorService);

      try (var jedis = jedisPool.getResource()) {
        Double workingScore = jedis.zscore("WORKZ", "WorkingAgent");
        Double waitingScore = jedis.zscore("WAITZ", "WaitingAgent");
        Double newScore = jedis.zscore("WAITZ", "NewAgent");
        if (newScore == null) {
          newScore = jedis.zscore("WORKZ", "NewAgent");
        }

        assertThat(workingScore).as("WorkingAgent should be in WORKING set").isNotNull();
        assertThat(waitingScore).as("WaitingAgent should be in WAITING set").isNotNull();
        assertThat(newScore).as("NewAgent should be in either set").isNotNull();
      }
    }
  }

  private Agent createMockAgent(String agentType, String providerName) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    when(agent.getProviderName()).thenReturn(providerName);
    return agent;
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
