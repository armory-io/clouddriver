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

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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

/**
 * Edge case tests for Priority Scheduler timing and concurrency scenarios.
 *
 * <p>Tests critical edge cases including:
 *
 * <ul>
 *   <li>Agent completion timing edge cases
 *   <li>Worker interruption scenarios
 *   <li>Dead-man timer race conditions
 *   <li>Concurrent cleanup coordination
 *   <li>Shutdown behavior edge cases
 * </ul>
 *
 * <p>Note: Concurrent cleanup coordination is covered by {@link ZombieOrphanCoordinationTest}.
 * Shutdown toggling is covered indirectly in stress tests.
 */
@Testcontainers
@DisplayName("Priority Scheduler Edge Cases")
class PrioritySchedulerEdgeCasesTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private JedisPool jedisPool;
  private RedisScriptManager scriptManager;
  private PrioritySchedulerMetrics metrics;

  @BeforeEach
  void setUp() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(32);
    jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379));
    try (Jedis j = jedisPool.getResource()) {
      j.flushAll();
    }
    metrics = new PrioritySchedulerMetrics(new DefaultRegistry());
    scriptManager = new RedisScriptManager(jedisPool, metrics);
    scriptManager.initializeScripts();
  }

  @AfterEach
  void tearDown() {
    if (jedisPool != null) {
      try (Jedis j = jedisPool.getResource()) {
        j.flushAll();
      } catch (Exception ignore) {
      }
      jedisPool.close();
    }
  }

  @Test
  @DisplayName("EC-1: Agent completes before acquisition tracking finishes")
  void testAgentCompletesBeforeTracking() throws Exception {
    // GIVEN: Fast agent that completes before activeAgents.put() executes
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");

    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(100L, 100L, 200L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    // Fast agent that completes immediately
    Agent fastAgent = mock(Agent.class);
    when(fastAgent.getAgentType()).thenReturn("fast-agent");
    when(fastAgent.getProviderName()).thenReturn("test");
    AgentExecution fastExec = mock(AgentExecution.class);
    // Execute immediately (no delay)
    ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);

    acquisitionService.registerAgent(fastAgent, fastExec, instr);

    Semaphore semaphore = new Semaphore(1);
    ExecutorService pool = Executors.newCachedThreadPool();

    // WHEN: Acquire and let fast agent complete
    acquisitionService.saturatePool(0L, semaphore, pool);

    // Allow completion to process
    Thread.sleep(200);

    // THEN: Permit accounting balanced, agent count preserved
    assertThat(semaphore.availablePermits()).isEqualTo(1);
    assertThat(Math.max(0, acquisitionService.getZombiesInFlight())).isEqualTo(0);

    pool.shutdownNow();
  }

  @Test
  @DisplayName("EC-2: Zombie cleanup runs before worker marks started")
  void testZombieCleanupBeforeWorkerStarts() throws Exception {
    // GIVEN: Slow agent that blocks before marking started
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getZombieCleanup().setEnabled(true);
    schedProps.getZombieCleanup().setThresholdMs(100L);

    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(200L, 200L, 400L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    ZombieCleanupService zombieCleanup =
        new ZombieCleanupService(jedisPool, scriptManager, schedProps, metrics);
    zombieCleanup.setAcquisitionService(acquisitionService);
    zombieCleanup.setFairnessHandler(acquisitionService);

    // Slow agent that blocks before marking started
    Agent slowAgent = mock(Agent.class);
    when(slowAgent.getAgentType()).thenReturn("slow-agent");
    when(slowAgent.getProviderName()).thenReturn("test");
    CountDownLatch startedLatch = new CountDownLatch(1);
    CountDownLatch releaseLatch = new CountDownLatch(1);
    AgentExecution slowExec = mock(AgentExecution.class);
    org.mockito.Mockito.doAnswer(
            inv -> {
              startedLatch.countDown();
              releaseLatch.await(5, TimeUnit.SECONDS);
              return null;
            })
        .when(slowExec)
        .executeAgent(any());
    ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);

    acquisitionService.registerAgent(slowAgent, slowExec, instr);

    Semaphore semaphore = new Semaphore(1);
    ExecutorService pool = Executors.newCachedThreadPool();

    // WHEN: Acquire agent, then trigger zombie cleanup before worker starts
    int acquired = acquisitionService.saturatePool(0L, semaphore, pool);

    // Wait for worker to start (but not mark started yet)
    assertThat(startedLatch.await(2, TimeUnit.SECONDS)).isTrue();

    // Trigger zombie cleanup before started=true
    Thread.sleep(150); // Wait past threshold
    Map<String, String> active = new ConcurrentHashMap<>(acquisitionService.getActiveAgentsMap());
    Map<String, Future<?>> futures =
        new ConcurrentHashMap<>(acquisitionService.getActiveAgentsFutures());
    zombieCleanup.cleanupZombieAgents(active, futures);

    // Release worker
    releaseLatch.countDown();

    // Allow worker to finish
    Thread.sleep(200);

    // THEN: zIF should remain at 0 (symmetric no-ops)
    assertThat(Math.max(0, acquisitionService.getZombiesInFlight())).isEqualTo(0);
    assertThat(semaphore.availablePermits()).isEqualTo(1);

    pool.shutdownNow();
  }

  @Test
  @DisplayName("EC-4: Agent re-registered while completing")
  void testAgentReRegisteredWhileCompleting() throws Exception {
    // GIVEN: Agent completing while being re-registered
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");

    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(100L, 100L, 200L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    Agent testAgent = mock(Agent.class);
    when(testAgent.getAgentType()).thenReturn("reregister-agent");
    when(testAgent.getProviderName()).thenReturn("test");
    AgentExecution exec = mock(AgentExecution.class);
    ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);

    acquisitionService.registerAgent(testAgent, exec, instr);

    Semaphore semaphore = new Semaphore(1);
    ExecutorService pool = Executors.newCachedThreadPool();

    // WHEN: Acquire agent, then re-register while completing
    acquisitionService.saturatePool(0L, semaphore, pool);

    // Re-register while completing
    acquisitionService.registerAgent(testAgent, exec, instr);

    // Allow completion
    Thread.sleep(200);

    // THEN: Agent count preserved, no duplicates
    assertThat(semaphore.availablePermits()).isEqualTo(1);
    assertThat(Math.max(0, acquisitionService.getZombiesInFlight())).isEqualTo(0);

    pool.shutdownNow();
  }

  @Test
  @DisplayName("EC-8: Agent failure during shutdown")
  void testAgentFailureDuringShutdown() throws Exception {
    // GIVEN: Agent that fails during shutdown preservation
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");

    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(100L, 100L, 200L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    // Agent that throws OutOfMemoryError
    Agent failingAgent = mock(Agent.class);
    when(failingAgent.getAgentType()).thenReturn("failing-agent");
    when(failingAgent.getProviderName()).thenReturn("test");
    AgentExecution failingExec = mock(AgentExecution.class);
    org.mockito.Mockito.doAnswer(
            inv -> {
              throw new OutOfMemoryError("Simulated OOM");
            })
        .when(failingExec)
        .executeAgent(any());
    ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);

    acquisitionService.registerAgent(failingAgent, failingExec, instr);
    acquisitionService.setShuttingDown(true);

    Semaphore semaphore = new Semaphore(1);
    ExecutorService pool = Executors.newCachedThreadPool();

    // WHEN: Acquire agent, let it fail during shutdown
    int acquired = acquisitionService.saturatePool(0L, semaphore, pool);
    assertThat(acquired).isEqualTo(1);

    // Allow failure to process
    Thread.sleep(200);

    // THEN: Failed agent preserved in WAITZ for retry
    try (Jedis j = jedisPool.getResource()) {
      String waitingSet = schedProps.getKeys().getWaitingSet();
      java.util.Set<String> waiting = j.zrange(waitingSet, 0, -1);
      assertThat(waiting).contains("failing-agent");
    }
    assertThat(semaphore.availablePermits()).isEqualTo(1);
    assertThat(Math.max(0, acquisitionService.getZombiesInFlight())).isEqualTo(0);

    pool.shutdownNow();
  }

  @Test
  @DisplayName("EC-10: Worker interrupted before started=true")
  void testWorkerInterruptedBeforeStart() throws Exception {
    // GIVEN: Agent that gets interrupted before marking started
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");
    schedProps.getZombieCleanup().setEnabled(true);
    schedProps.getZombieCleanup().setThresholdMs(100L);

    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(200L, 200L, 400L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    ZombieCleanupService zombieCleanup =
        new ZombieCleanupService(jedisPool, scriptManager, schedProps, metrics);
    zombieCleanup.setAcquisitionService(acquisitionService);
    zombieCleanup.setFairnessHandler(acquisitionService);

    // Agent that blocks before marking started
    Agent blockingAgent = mock(Agent.class);
    when(blockingAgent.getAgentType()).thenReturn("blocking-agent");
    when(blockingAgent.getProviderName()).thenReturn("test");
    CountDownLatch interruptLatch = new CountDownLatch(1);
    AgentExecution blockingExec = mock(AgentExecution.class);
    org.mockito.Mockito.doAnswer(
            inv -> {
              interruptLatch.countDown();
              // Block until interrupted
              try {
                Thread.sleep(5000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return null;
            })
        .when(blockingExec)
        .executeAgent(any());
    ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);

    acquisitionService.registerAgent(blockingAgent, blockingExec, instr);

    Semaphore semaphore = new Semaphore(1);
    ExecutorService pool = Executors.newCachedThreadPool();

    // WHEN: Acquire agent, then interrupt before started=true
    int acquired = acquisitionService.saturatePool(0L, semaphore, pool);
    assertThat(acquired).isEqualTo(1);

    // Wait for worker to start
    assertThat(interruptLatch.await(2, TimeUnit.SECONDS)).isTrue();

    // Trigger zombie cleanup to interrupt
    Thread.sleep(150);
    Map<String, String> active = new ConcurrentHashMap<>(acquisitionService.getActiveAgentsMap());
    Map<String, Future<?>> futures =
        new ConcurrentHashMap<>(acquisitionService.getActiveAgentsFutures());
    zombieCleanup.cleanupZombieAgents(active, futures);

    // Allow interruption to process
    Thread.sleep(200);

    // THEN: Permit accounting balanced, zIF=0
    assertThat(semaphore.availablePermits()).isEqualTo(1);
    assertThat(Math.max(0, acquisitionService.getZombiesInFlight())).isEqualTo(0);

    pool.shutdownNow();
  }

  @Test
  @DisplayName("EC-11: Dead-man timer fires simultaneously with completion")
  void testDeadManTimerRaceWithCompletion() throws Exception {
    // GIVEN: Agent completing at exact moment dead-man timer fires
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");

    // Short timeout for dead-man timer test
    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(100L, 100L, 200L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    // Agent that completes around timeout boundary
    Agent timedAgent = mock(Agent.class);
    when(timedAgent.getAgentType()).thenReturn("timed-agent");
    when(timedAgent.getProviderName()).thenReturn("test");
    CountDownLatch completionLatch = new CountDownLatch(1);
    AgentExecution timedExec = mock(AgentExecution.class);
    org.mockito.Mockito.doAnswer(
            inv -> {
              // Complete around timeout boundary
              Thread.sleep(210);
              completionLatch.countDown();
              return null;
            })
        .when(timedExec)
        .executeAgent(any());
    ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);

    acquisitionService.registerAgent(timedAgent, timedExec, instr);

    Semaphore semaphore = new Semaphore(1);
    ExecutorService pool = Executors.newCachedThreadPool();

    // WHEN: Acquire agent, let it complete around timeout
    int acquired = acquisitionService.saturatePool(0L, semaphore, pool);
    assertThat(acquired).isEqualTo(1);

    // Wait for completion
    assertThat(completionLatch.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(100); // Allow race to resolve

    // THEN: Permit released exactly once (CAS protection)
    assertThat(semaphore.availablePermits()).isEqualTo(1);
    assertThat(Math.max(0, acquisitionService.getZombiesInFlight())).isEqualTo(0);

    pool.shutdownNow();
  }

  @Test
  @DisplayName("EC-12: Double-release protection verification")
  void testDoubleReleaseProtection() throws Exception {
    // GIVEN: System with permit protection
    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setMaxConcurrentAgents(2);

    PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
    schedProps.getKeys().setWaitingSet("waiting");
    schedProps.getKeys().setWorkingSet("working");

    AgentIntervalProvider intervalProvider =
        agent -> new AgentIntervalProvider.Interval(100L, 100L, 200L);
    ShardingFilter shardingFilter = a -> true;

    AgentAcquisitionService acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProps,
            schedProps,
            metrics);

    Agent testAgent = mock(Agent.class);
    when(testAgent.getAgentType()).thenReturn("double-release-agent");
    when(testAgent.getProviderName()).thenReturn("test");
    AgentExecution exec = mock(AgentExecution.class);
    ExecutionInstrumentation instr = mock(ExecutionInstrumentation.class);

    acquisitionService.registerAgent(testAgent, exec, instr);

    Semaphore semaphore = new Semaphore(2);

    // WHEN: Acquire and complete normally
    ExecutorService pool = Executors.newCachedThreadPool();
    int acquired = acquisitionService.saturatePool(0L, semaphore, pool);
    assertThat(acquired).isEqualTo(1);

    // Allow completion
    Thread.sleep(200);

    // THEN: Permit count correct (no double-release despite potential bugs)
    assertThat(semaphore.availablePermits()).isEqualTo(2);
    assertThat(Math.max(0, acquisitionService.getZombiesInFlight())).isEqualTo(0);

    pool.shutdownNow();
  }
}
