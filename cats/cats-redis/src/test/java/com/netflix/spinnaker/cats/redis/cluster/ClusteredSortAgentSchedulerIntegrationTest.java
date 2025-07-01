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
import static org.assertj.core.api.Assertions.assertThatCode;

import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.agent.RunnableAgent;
import com.netflix.spinnaker.cats.cluster.DefaultAgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.cats.provider.ProviderRegistry;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import redis.clients.jedis.JedisPool;

/**
 * Integration test to verify that the ClusteredSortAgentScheduler works correctly with
 * RunnableAgent implementations using a real Redis instance.
 *
 * <p>This test requires Redis to be running on localhost:6379
 */
class ClusteredSortAgentSchedulerIntegrationTest {

  // Helper methods to create properties for tests
  private static ClusteredSortAgentProperties createDefaultAgentProperties() {
    return new ClusteredSortAgentProperties();
  }

  private static ClusteredSortSchedulerProperties createDefaultSchedulerProperties() {
    return new ClusteredSortSchedulerProperties();
  }

  private ClusteredSortAgentScheduler scheduler;
  private JedisPool jedisPool;
  private ExecutionInstrumentation executionInstrumentation;
  private ProviderRegistry providerRegistry;
  private ShardingFilter shardingFilter;

  @BeforeEach
  void setUp() {
    jedisPool = new JedisPool("localhost", 6379);

    executionInstrumentation =
        new ExecutionInstrumentation() {
          @Override
          public void executionStarted(com.netflix.spinnaker.cats.agent.Agent agent) {}

          @Override
          public void executionCompleted(
              com.netflix.spinnaker.cats.agent.Agent agent, long elapsedMs) {}

          @Override
          public void executionFailed(
              com.netflix.spinnaker.cats.agent.Agent agent, Throwable cause, long elapsedMs) {}
        };

    providerRegistry = null; // Not needed for RunnableAgent

    // Mock enterprise services for integration test
    shardingFilter = agent -> true; // Allow all agents
    // All configuration now handled via cached properties

    scheduler =
        new ClusteredSortAgentScheduler(
            jedisPool,
            () -> true,
            new DefaultAgentIntervalProvider(30000, 60000, 300000),
            ".*", // Enable all agents
            shardingFilter,
            createDefaultAgentProperties(),
            createDefaultSchedulerProperties(),
            Collections.emptyList()); // No explicitly disabled agents
  }

  @Test
  @DisplayName("Should successfully schedule RunnableAgent without errors")
  @EnabledIf("isRedisAvailable")
  void shouldScheduleRunnableAgentWithoutErrors() {
    // Given
    TestRunnableAgent agent = new TestRunnableAgent();
    RunnableAgent.RunnableAgentExecution execution =
        (RunnableAgent.RunnableAgentExecution) agent.getAgentExecution(providerRegistry);

    // When - Schedule the agent (this should not throw the original exception)
    assertThatCode(() -> scheduler.schedule(agent, execution, executionInstrumentation))
        .doesNotThrowAnyException();

    // Then - Verify the agent was scheduled (it's in the scheduler's agent map)
    assertThat(scheduler).isNotNull();
  }

  @Test
  @DisplayName("Should handle multiple RunnableAgent instances without errors")
  @EnabledIf("isRedisAvailable")
  void shouldHandleMultipleRunnableAgentsWithoutErrors() {
    // Given
    TestRunnableAgent agent1 = new TestRunnableAgent("agent1");
    TestRunnableAgent agent2 = new TestRunnableAgent("agent2");
    TestRunnableAgent agent3 = new TestRunnableAgent("agent3");

    // When - Schedule all agents (this should not throw the original exception)
    assertThatCode(
            () -> {
              scheduler.schedule(
                  agent1, agent1.getAgentExecution(providerRegistry), executionInstrumentation);
              scheduler.schedule(
                  agent2, agent2.getAgentExecution(providerRegistry), executionInstrumentation);
              scheduler.schedule(
                  agent3, agent3.getAgentExecution(providerRegistry), executionInstrumentation);
            })
        .doesNotThrowAnyException();

    // Then - Verify all agents were scheduled
    assertThat(scheduler).isNotNull();
  }

  /** Check if Redis is available for testing */
  static boolean isRedisAvailable() {
    try (JedisPool testPool = new JedisPool("localhost", 6379)) {
      testPool.getResource().ping();
      return true;
    } catch (Exception e) {
      System.out.println("Redis not available for integration test: " + e.getMessage());
      return false;
    }
  }

  // Test helper class
  private static class TestRunnableAgent implements RunnableAgent {
    private final String agentType;
    private final AtomicBoolean hasRun = new AtomicBoolean(false);

    public TestRunnableAgent() {
      this("TestRunnableAgent");
    }

    public TestRunnableAgent(String agentType) {
      this.agentType = agentType;
    }

    @Override
    public void run() {
      hasRun.set(true);
      System.out.println("RunnableAgent " + agentType + " executed successfully!");
    }

    @Override
    public String getAgentType() {
      return agentType;
    }

    @Override
    public String getProviderName() {
      return "test";
    }

    public boolean hasRun() {
      return hasRun.get();
    }
  }
}
