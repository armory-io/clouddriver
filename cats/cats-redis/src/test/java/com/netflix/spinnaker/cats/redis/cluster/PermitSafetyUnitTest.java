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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * Unit tests for permit safety on submission rejection.
 *
 * <p>Verifies that semaphore permits are properly released when executor submission fails,
 * preventing permit leaks and ensuring agents are requeued for retry.
 */
@Testcontainers
@DisplayName("Permit safety on submission rejection")
@SuppressWarnings("resource") // GenericContainer lifecycle managed by @Testcontainers
class PermitSafetyUnitTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine")
          .withExposedPorts(6379)
          .withCommand("redis-server", "--requirepass", "testpass");

  /**
   * Tests that submission rejection releases permit, increments metric, and requeues agent.
   *
   * <p>When the executor rejects agent submission (RejectedExecutionException), verifies: 1) permit
   * is released to prevent leaks, 2) submission failure metric is incremented with "rejected" tag,
   * 3) agent is requeued to waiting set, and 4) no orphaned state in working set.
   */
  @Test
  @DisplayName("Submission failure increments metric and does not throw")
  void submissionFailureIncrementsMetric() {
    JedisPool pool = TestFixtures.createTestJedisPool(redis);
    try {
      RedisScriptManager scripts = TestFixtures.createTestScriptManager(pool);

      PriorityAgentProperties agentProps = new PriorityAgentProperties();
      agentProps.setEnabledPattern(".*");
      agentProps.setDisabledPattern("");
      agentProps.setMaxConcurrentAgents(1);

      PrioritySchedulerProperties schedProps = new PrioritySchedulerProperties();
      schedProps.getKeys().setWaitingSet("waiting");
      schedProps.getKeys().setWorkingSet("working");
      schedProps.getKeys().setCleanupLeaderKey("cleanup-leader");
      schedProps.getBatchOperations().setEnabled(false);

      DefaultRegistry registry = new DefaultRegistry();
      PrioritySchedulerMetrics metrics = new PrioritySchedulerMetrics(registry);

      AgentAcquisitionService acq =
          new AgentAcquisitionService(
              pool,
              scripts,
              (AgentIntervalProvider) a -> new AgentIntervalProvider.Interval(1000L, 5000L),
              (ShardingFilter) a -> true,
              agentProps,
              schedProps,
              metrics);

      Agent agent = TestFixtures.createMockAgent("rejected-agent", "test");
      ExecutionInstrumentation instr = TestFixtures.createNoOpInstrumentation();

      acq.registerAgent(
          agent,
          a -> {
            /* no-op */
          },
          instr);

      try (var j = pool.getResource()) {
        java.util.List<String> t = j.time();
        long now = Long.parseLong(t.get(0));
        j.zadd("waiting", now, "rejected-agent");
      }

      ExecutorService rejecting = mock(ExecutorService.class);
      doThrow(new RejectedExecutionException("full")).when(rejecting).submit(any(Runnable.class));

      Semaphore sem = new Semaphore(1);
      int initialPermits = sem.availablePermits();

      int acquired = acq.saturatePool(1L, sem, rejecting);
      assertThat(acquired).isGreaterThanOrEqualTo(0);

      // Verify permit released (critical for permit safety)
      assertThat(sem.availablePermits())
          .describedAs("Permit should be released after submission failure (permit safety)")
          .isEqualTo(initialPermits);

      // Verify submission failure metric incremented
      assertThat(
              registry
                  .counter(
                      registry
                          .createId("cats.priorityScheduler.acquire.submissionFailures")
                          .withTag("scheduler", "priority")
                          .withTag("reason", "rejected"))
                  .count())
          .describedAs("Submission failure metric should be incremented with reason='rejected'")
          .isGreaterThanOrEqualTo(1);

      // Verify agent requeued (agent should be back in waiting set)
      try (var j = pool.getResource()) {
        Double waitingScore = j.zscore("waiting", "rejected-agent");
        assertThat(waitingScore)
            .describedAs("Agent should be requeued to waiting set after submission failure")
            .isNotNull();
      }

      // Verify no orphaned state (agent should not be in working set)
      try (var j = pool.getResource()) {
        Double workingScore = j.zscore("working", "rejected-agent");
        assertThat(workingScore)
            .describedAs(
                "Agent should not be in working set after submission failure (no orphaned state)")
            .isNull();
      }

      // Verify acquisition metrics were recorded
      assertThat(
              registry
                  .counter(
                      registry
                          .createId("cats.priorityScheduler.acquire.attempts")
                          .withTag("scheduler", "priority"))
                  .count())
          .describedAs("Acquisition attempts metric should be incremented")
          .isGreaterThanOrEqualTo(1);
    } finally {
      pool.close();
    }
  }
}
