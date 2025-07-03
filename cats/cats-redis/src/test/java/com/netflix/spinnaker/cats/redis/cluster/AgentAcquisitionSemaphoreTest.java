package com.netflix.spinnaker.cats.redis.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.cats.redis.cluster.AgentAcquisitionService.AgentWorker;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Tests for semaphore management in AgentAcquisitionService.
 *
 * <p>Verifies that semaphore permits are properly acquired and released during agent execution,
 * preventing the semaphore leak bug that was causing agent acquisition to stop working.
 */
public class AgentAcquisitionSemaphoreTest {

  private JedisPool jedisPool;
  private Jedis jedis;
  private RedisScriptManager scriptManager;
  private AgentIntervalProvider intervalProvider;
  private ShardingFilter shardingFilter;
  private ClusteredSortAgentProperties agentProperties;
  private ClusteredSortSchedulerProperties schedulerProperties;
  private AgentExecution agentExecution;
  private ExecutionInstrumentation executionInstrumentation;

  private AgentAcquisitionService service;
  private Agent testAgent;
  private Semaphore testSemaphore;
  private ExecutorService testExecutor;

  @BeforeEach
  void setUp() {
    // Create test semaphore with limited permits
    testSemaphore = new Semaphore(2); // Allow max 2 concurrent agents
    testExecutor = Executors.newFixedThreadPool(5);

    // Mock basic dependencies
    jedisPool = mock(JedisPool.class);
    jedis = mock(Jedis.class);
    scriptManager = mock(RedisScriptManager.class);
    intervalProvider = mock(AgentIntervalProvider.class);
    shardingFilter = mock(ShardingFilter.class);
    agentProperties = mock(ClusteredSortAgentProperties.class);
    schedulerProperties = mock(ClusteredSortSchedulerProperties.class);
    agentExecution = mock(AgentExecution.class);
    executionInstrumentation = mock(ExecutionInstrumentation.class);

    when(jedisPool.getResource()).thenReturn(jedis);
    when(jedis.time()).thenReturn(Arrays.asList("1751564649", "0"));
    when(jedis.zrangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(new HashSet<>());
    when(shardingFilter.filter(any())).thenReturn(true);
    when(agentProperties.getMaxConcurrentAgents()).thenReturn(10);
    when(agentProperties.getEnabledPattern()).thenReturn(".*");
    when(agentProperties.getDisabledPattern()).thenReturn("");
    when(schedulerProperties.getRefreshPeriodSeconds()).thenReturn(10);

    // Create service
    service =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);

    // Create test agent
    testAgent = createMockAgent("test-agent", "test-provider");
    service.registerAgent(testAgent, agentExecution, executionInstrumentation);
  }

  @Test
  void shouldAcquireSemaphorePermitWhenAgentIsScheduled() throws Exception {
    // Given: Mock Redis to return ready agents
    when(jedis.zrangeByScore(anyString(), anyDouble(), anyDouble()))
        .thenReturn(Set.of("test-agent"));
    when(scriptManager.getScriptSha(anyString())).thenReturn("script-sha");
    when(jedis.evalsha(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn("1751564649"); // Successful acquisition

    // Initial semaphore state
    assertThat(testSemaphore.availablePermits()).isEqualTo(2);

    // When: Saturate pool with semaphore
    int acquired = service.saturatePool(1L, testSemaphore, testExecutor);

    // Then: Semaphore permit should be acquired
    assertThat(acquired).isEqualTo(1);
    assertThat(testSemaphore.availablePermits()).isEqualTo(1);
  }

  @Test
  void shouldNotAcquireAgentWhenSemaphoreIsExhausted() throws Exception {
    // Given: Mock Redis to return ready agents
    when(jedis.zrangeByScore(anyString(), anyDouble(), anyDouble()))
        .thenReturn(Set.of("test-agent-1", "test-agent-2", "test-agent-3"));

    // Exhaust semaphore permits
    testSemaphore.acquire(2); // Take all permits
    assertThat(testSemaphore.availablePermits()).isEqualTo(0);

    // When: Try to saturate pool with no permits
    int acquired = service.saturatePool(1L, testSemaphore, testExecutor);

    // Then: No agents should be acquired
    assertThat(acquired).isEqualTo(0);
    assertThat(testSemaphore.availablePermits()).isEqualTo(0);
  }

  @Test
  void shouldReleaseSemaphorePermitWhenAgentExecutionCompletes() throws Exception {
    // Given: Setup agent execution that will succeed
    AtomicInteger executionCount = new AtomicInteger(0);
    doAnswer(
            invocation -> {
              executionCount.incrementAndGet();
              return null;
            })
        .when(agentExecution)
        .executeAgent(any());

    // Create and configure agent worker
    AgentWorker worker =
        new AgentWorker(testAgent, agentExecution, executionInstrumentation, service);
    worker.acquireScore = "1751564649";
    worker.setRunningAgents(testSemaphore);

    // Acquire semaphore permit (simulate what saturatePool does)
    testSemaphore.acquire();
    assertThat(testSemaphore.availablePermits()).isEqualTo(1);

    // When: Execute agent
    CompletableFuture<Void> execution = CompletableFuture.runAsync(worker, testExecutor);
    execution.get(5, TimeUnit.SECONDS); // Wait for completion

    // Then: Semaphore permit should be released
    assertThat(testSemaphore.availablePermits()).isEqualTo(2);
    assertThat(executionCount.get()).isEqualTo(1);
  }

  @Test
  void shouldReleaseSemaphorePermitEvenWhenAgentExecutionFails() throws Exception {
    // Given: Setup agent execution that will fail
    RuntimeException testException = new RuntimeException("Test execution failure");
    doThrow(testException).when(agentExecution).executeAgent(any());

    // Create and configure agent worker
    AgentWorker worker =
        new AgentWorker(testAgent, agentExecution, executionInstrumentation, service);
    worker.acquireScore = "1751564649";
    worker.setRunningAgents(testSemaphore);

    // Acquire semaphore permit (simulate what saturatePool does)
    testSemaphore.acquire();
    assertThat(testSemaphore.availablePermits()).isEqualTo(1);

    // When: Execute agent (will fail)
    CompletableFuture<Void> execution = CompletableFuture.runAsync(worker, testExecutor);
    execution.get(5, TimeUnit.SECONDS); // Wait for completion (exception handled internally)

    // Then: Semaphore permit should still be released despite exception
    assertThat(testSemaphore.availablePermits()).isEqualTo(2);
  }

  @Test
  void shouldReleaseSemaphorePermitWhenAgentExecutionIsInterrupted() throws Exception {
    // Given: Setup agent execution that will be interrupted
    CountDownLatch executionStarted = new CountDownLatch(1);
    CountDownLatch interruptSignal = new CountDownLatch(1);

    doAnswer(
            invocation -> {
              executionStarted.countDown();
              try {
                interruptSignal.await(); // Wait for interrupt
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e; // Re-throw to simulate interrupted execution
              }
              return null;
            })
        .when(agentExecution)
        .executeAgent(any());

    // Create and configure agent worker
    AgentWorker worker =
        new AgentWorker(testAgent, agentExecution, executionInstrumentation, service);
    worker.acquireScore = "1751564649";
    worker.setRunningAgents(testSemaphore);

    // Acquire semaphore permit
    testSemaphore.acquire();
    assertThat(testSemaphore.availablePermits()).isEqualTo(1);

    // When: Execute agent in separate thread and interrupt it
    Future<Void> execution =
        testExecutor.submit(
            () -> {
              worker.run();
              return null;
            });

    // Wait for execution to start, then interrupt
    executionStarted.await(2, TimeUnit.SECONDS);
    execution.cancel(true); // Interrupt the execution
    interruptSignal.countDown(); // Allow execution to proceed to interrupt handling

    // Wait a bit for cleanup to complete
    Thread.sleep(100);

    // Then: Semaphore permit should be released even after interruption
    assertThat(testSemaphore.availablePermits()).isEqualTo(2);
  }

  @Test
  void shouldHandleMultipleConcurrentAgentsWithSemaphoreCorrectly() throws Exception {
    // Given: Register multiple agents and mock Redis responses
    Agent agent1 = createMockAgent("agent-1", "test-provider");
    Agent agent2 = createMockAgent("agent-2", "test-provider");
    Agent agent3 = createMockAgent("agent-3", "test-provider");

    service.registerAgent(agent1, agentExecution, executionInstrumentation);
    service.registerAgent(agent2, agentExecution, executionInstrumentation);
    service.registerAgent(agent3, agentExecution, executionInstrumentation);

    when(jedis.zrangeByScore(anyString(), anyDouble(), anyDouble()))
        .thenReturn(Set.of("agent-1", "agent-2", "agent-3"));
    when(scriptManager.getScriptSha(anyString())).thenReturn("script-sha");
    when(jedis.evalsha(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn("1751564649"); // All acquisitions succeed

    // Setup execution to complete quickly
    AtomicInteger completedCount = new AtomicInteger(0);
    doAnswer(
            invocation -> {
              Thread.sleep(50); // Brief execution time
              completedCount.incrementAndGet();
              return null;
            })
        .when(agentExecution)
        .executeAgent(any());

    assertThat(testSemaphore.availablePermits()).isEqualTo(2);

    // When: Saturate pool (should acquire max 2 agents due to semaphore limit)
    int acquired = service.saturatePool(1L, testSemaphore, testExecutor);

    // Then: Should acquire exactly 2 agents (semaphore limit)
    assertThat(acquired).isEqualTo(2);
    assertThat(testSemaphore.availablePermits()).isEqualTo(0);

    // Wait for executions to complete
    Thread.sleep(200);

    // All permits should be released after execution
    assertThat(testSemaphore.availablePermits()).isEqualTo(2);
    assertThat(completedCount.get()).isEqualTo(2);
  }

  @Test
  void shouldHandleNullSemaphoreGracefully() throws Exception {
    // Given: Mock Redis to return ready agents
    when(jedis.zrangeByScore(anyString(), anyDouble(), anyDouble()))
        .thenReturn(Set.of("test-agent"));
    when(scriptManager.getScriptSha(anyString())).thenReturn("script-sha");
    when(jedis.evalsha(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn("1751564649");

    // When: Saturate pool with null semaphore (no concurrency control)
    int acquired = service.saturatePool(1L, null, testExecutor);

    // Then: Should still work without semaphore
    assertThat(acquired).isEqualTo(1);

    // Agent should execute and complete without semaphore-related errors
    Thread.sleep(100); // Allow execution to complete
    // No assertions needed - just verify no exceptions are thrown
  }

  // Helper method to create mock agents
  private Agent createMockAgent(String agentType, String providerName) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    when(agent.getProviderName()).thenReturn(providerName);
    return agent;
  }
}
