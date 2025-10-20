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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@DisplayName("Health summary cadence/content unit test")
class HealthSummaryUnitTest {

  private PriorityAgentScheduler newScheduler(JedisPool pool) {
    NodeStatusProvider nodeStatusProvider = mock(NodeStatusProvider.class);
    when(nodeStatusProvider.isNodeEnabled()).thenReturn(true);
    AgentIntervalProvider intervalProvider = mock(AgentIntervalProvider.class);
    when(intervalProvider.getInterval(org.mockito.ArgumentMatchers.any(Agent.class)))
        .thenReturn(new AgentIntervalProvider.Interval(1000L, 5000L));
    ShardingFilter shardingFilter = a -> true;

    PriorityAgentProperties agentProps = new PriorityAgentProperties();
    agentProps.setEnabledPattern(".*");
    agentProps.setDisabledPattern("");
    agentProps.setMaxConcurrentAgents(1);

    PrioritySchedulerProperties cfg = new PrioritySchedulerProperties();
    cfg.getKeys().setWaitingSet("waiting");
    cfg.getKeys().setWorkingSet("working");
    cfg.getKeys().setCleanupLeaderKey("cleanup-leader");

    return new PriorityAgentScheduler(
        pool,
        nodeStatusProvider,
        intervalProvider,
        shardingFilter,
        agentProps,
        cfg,
        new PrioritySchedulerMetrics(new DefaultRegistry()));
  }

  @Test
  @DisplayName("maybeLogHealthSummary logs once within 10m window and includes expected fields")
  void maybeLogHealthSummaryCadenceAndContent() {
    JedisPool pool = new JedisPool(new JedisPoolConfig(), "localhost");
    try {
      PriorityAgentScheduler scheduler = newScheduler(pool);

      Logger logger = (Logger) LoggerFactory.getLogger(PriorityAgentScheduler.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      logger.addAppender(appender);

      // First call: should log one health line
      scheduler.run();
      long healthLogs =
          appender.list.stream()
              .filter(
                  e ->
                      e.getLevel() == Level.INFO
                          && e.getFormattedMessage().contains("Scheduler health"))
              .count();
      assertThat(healthLogs).isGreaterThanOrEqualTo(1L);

      String msg =
          appender.list.stream()
              .filter(
                  e ->
                      e.getLevel() == Level.INFO
                          && e.getFormattedMessage().contains("Scheduler health"))
              .map(ILoggingEvent::getFormattedMessage)
              .findFirst()
              .orElse("");
      // Content assertions aligned with current summary format
      assertThat(msg).contains("Scheduler health | health=");
      assertThat(msg).contains("[backlog ready=");
      assertThat(msg).contains("oldest_overdue=");
      assertThat(msg).contains("capacityPerCycle=");
      assertThat(msg).contains("[permits ");
      assertThat(msg).contains("zombiesInFlight=");
      assertThat(msg).contains("[agents registered=");
      assertThat(msg).contains("scripts=");
      assertThat(msg).contains("queueDepth=");

      // Subsequent immediate call should not add another health log due to 10m cadence
      scheduler.run();
      long healthLogsAfter =
          appender.list.stream()
              .filter(
                  e ->
                      e.getLevel() == Level.INFO
                          && e.getFormattedMessage().contains("Scheduler health"))
              .count();
      assertThat(healthLogsAfter).isEqualTo(healthLogs);

      logger.detachAppender(appender);
    } finally {
      pool.close();
    }
  }
}
