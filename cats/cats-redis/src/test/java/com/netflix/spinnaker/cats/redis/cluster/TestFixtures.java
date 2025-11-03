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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.cats.agent.Agent;

/**
 * Test fixtures and helper methods for Priority Scheduler tests.
 *
 * <p>Provides shared utility methods to reduce code duplication across test files. All methods are
 * static and thread-safe for use in parallel test execution.
 */
public final class TestFixtures {

  private TestFixtures() {
    // Utility class - prevent instantiation
  }

  /**
   * Creates a mock Agent with the specified type and provider name.
   *
   * @param agentType The agent type identifier
   * @param providerName The provider name
   * @return A mocked Agent instance
   */
  public static Agent createMockAgent(String agentType, String providerName) {
    Agent agent = mock(Agent.class);
    when(agent.getAgentType()).thenReturn(agentType);
    when(agent.getProviderName()).thenReturn(providerName);
    return agent;
  }

  /**
   * Creates a mock Agent with the specified type and default provider name.
   *
   * @param agentType The agent type identifier
   * @return A mocked Agent instance with provider name "test-provider"
   */
  public static Agent createMockAgent(String agentType) {
    return createMockAgent(agentType, "test-provider");
  }

  /**
   * Creates default PriorityAgentProperties for testing.
   *
   * @return PriorityAgentProperties with standard test configuration
   */
  public static PriorityAgentProperties createDefaultAgentProperties() {
    PriorityAgentProperties props = new PriorityAgentProperties();
    props.setMaxConcurrentAgents(100);
    props.setEnabledPattern(".*");
    props.setDisabledPattern("");
    return props;
  }

  /**
   * Creates default PriorityAgentProperties with custom concurrency limit.
   *
   * @param maxConcurrent Maximum concurrent agents
   * @return PriorityAgentProperties with specified concurrency limit
   */
  public static PriorityAgentProperties createDefaultAgentProperties(int maxConcurrent) {
    PriorityAgentProperties props = createDefaultAgentProperties();
    props.setMaxConcurrentAgents(maxConcurrent);
    return props;
  }

  /**
   * Creates default PrioritySchedulerProperties for testing.
   *
   * @return PrioritySchedulerProperties with standard test configuration
   */
  public static PrioritySchedulerProperties createDefaultSchedulerProperties() {
    PrioritySchedulerProperties props = new PrioritySchedulerProperties();
    props.setIntervalMs(1000L);
    props.setRefreshPeriodSeconds(30);
    props.getKeys().setWaitingSet("waiting");
    props.getKeys().setWorkingSet("working");
    props.getKeys().setCleanupLeaderKey("cleanup-leader");
    props.getZombieCleanup().setThresholdMs(1800000L); // 30 minutes
    props.getZombieCleanup().setIntervalMs(300000L); // 5 minutes
    props.getOrphanCleanup().setThresholdMs(7200000L); // 2 hours
    props.getOrphanCleanup().setIntervalMs(3600000L); // 1 hour
    props.getBatchOperations().setEnabled(false);
    return props;
  }

  /**
   * Creates PrioritySchedulerProperties with batch operations enabled.
   *
   * @return PrioritySchedulerProperties with batch operations enabled
   */
  public static PrioritySchedulerProperties createBatchEnabledSchedulerProperties() {
    PrioritySchedulerProperties props = createDefaultSchedulerProperties();
    props.getBatchOperations().setEnabled(true);
    props.getBatchOperations().setBatchSize(10);
    return props;
  }
}
