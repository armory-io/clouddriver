/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.clouddriver.cache;

import com.netflix.spinnaker.cats.agent.AgentScheduler;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.DefaultNodeIdentity;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.cats.redis.cluster.ClusteredAgentScheduler;
import com.netflix.spinnaker.cats.redis.cluster.ClusteredSortAgentScheduler;
import com.netflix.spinnaker.cats.redis.cluster.PriorityAgentProperties;
import com.netflix.spinnaker.cats.redis.cluster.PriorityAgentScheduler;
import com.netflix.spinnaker.cats.redis.cluster.PrioritySchedulerProperties;
import com.netflix.spinnaker.clouddriver.core.RedisConfigurationProperties;
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService;
import com.netflix.spinnaker.kork.jedis.RedisClientDelegate;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

@Configuration
@ConditionalOnProperty(value = "caching.write-enabled", matchIfMissing = true)
public class AgentSchedulerConfig {

  private static final Logger log = LoggerFactory.getLogger(AgentSchedulerConfig.class);

  /**
   * Creates the legacy "default" Redis agent scheduler. This bean is only created if
   * redis.scheduler.type is "default" or not specified.
   */
  @Bean
  @ConditionalOnProperty(
      value = "redis.scheduler.type",
      havingValue = "default",
      matchIfMissing = true)
  @ConditionalOnExpression("${redis.enabled:true} && ${redis.scheduler.enabled:true}")
  public AgentScheduler defaultRedisAgentScheduler(
      RedisConfigurationProperties redisConfigurationProperties,
      RedisClientDelegate redisClientDelegate,
      AgentIntervalProvider agentIntervalProvider,
      NodeStatusProvider nodeStatusProvider,
      DynamicConfigService dynamicConfigService,
      ShardingFilter shardingFilter) {
    log.info("Creating ClusteredAgentScheduler (default)");
    URI redisUri = URI.create(redisConfigurationProperties.getConnection());
    String redisHost = redisUri.getHost();
    int redisPort = redisUri.getPort() == -1 ? 6379 : redisUri.getPort();

    return new ClusteredAgentScheduler(
        redisClientDelegate,
        new DefaultNodeIdentity(redisHost, redisPort),
        agentIntervalProvider,
        nodeStatusProvider,
        redisConfigurationProperties.getAgent().getEnabledPattern(),
        redisConfigurationProperties.getAgent().getAgentLockAcquisitionIntervalSeconds(),
        dynamicConfigService,
        shardingFilter);
  }

  /**
   * Creates the legacy "sort" Redis agent scheduler. This bean is only created if
   * redis.scheduler.type is "sort".
   */
  @Bean
  @ConditionalOnProperty(value = "redis.scheduler.type", havingValue = "sort")
  @ConditionalOnExpression("${redis.enabled:true} && ${redis.scheduler.enabled:true}")
  public AgentScheduler sortRedisAgentScheduler(
      JedisPool jedisPool,
      NodeStatusProvider nodeStatusProvider,
      AgentIntervalProvider agentIntervalProvider,
      RedisConfigurationProperties redisConfigurationProperties) {
    log.info("Creating ClusteredSortAgentScheduler (sort)");

    int parallelism = redisConfigurationProperties.getScheduler().getParallelism();
    if (parallelism > 0) {
      log.info(
          "ClusteredSortAgentScheduler using parallelism: {} (max concurrent agents)", parallelism);
    } else if (parallelism == -1) {
      log.info("ClusteredSortAgentScheduler using unlimited parallelism");
    } else {
      log.warn(
          "Invalid parallelism value: {}. ClusteredSortAgentScheduler requires positive value or -1",
          parallelism);
    }

    if (!redisConfigurationProperties.getAgent().getDisabledAgents().isEmpty()) {
      log.warn(
          "redis.agent.disabledAgents ({} agents) is NOT supported by ClusteredSortAgentScheduler and will be ignored. "
              + "Consider migrating to priority scheduler for agent filtering support.",
          redisConfigurationProperties.getAgent().getDisabledAgents().size());
    }

    return new ClusteredSortAgentScheduler(
        jedisPool, nodeStatusProvider, agentIntervalProvider, parallelism);
  }

  /**
   * Creates the modern "priority" Redis agent scheduler. This bean is only created if
   * redis.scheduler.type is "priority". Uses proper Spring dependency injection for configuration
   * properties.
   */
  @Bean
  @ConditionalOnProperty(value = "redis.scheduler.type", havingValue = "priority")
  @ConditionalOnExpression("${redis.enabled:true} && ${redis.scheduler.enabled:true}")
  public AgentScheduler priorityRedisAgentScheduler(
      JedisPool jedisPool,
      NodeStatusProvider nodeStatusProvider,
      AgentIntervalProvider agentIntervalProvider,
      ShardingFilter shardingFilter,
      PriorityAgentProperties agentProperties,
      PrioritySchedulerProperties schedulerProperties,
      RedisConfigurationProperties redisConfigurationProperties) {
    log.info("Creating PriorityAgentScheduler (priority)");

    int parallelism = redisConfigurationProperties.getScheduler().getParallelism();
    if (parallelism != 0) {
      log.warn(
          "redis.scheduler.parallelism ({}) is completely ignored by PriorityAgentScheduler. "
              + "Use redis.agent.maxConcurrentAgents instead (current: {})",
          parallelism,
          agentProperties.getMaxConcurrentAgents());
    }
    if (!redisConfigurationProperties.getAgent().getDisabledAgents().isEmpty()) {
      log.warn(
          "redis.agent.disabledAgents ({} agents) is ignored by PriorityAgentScheduler. "
              + "Use redis.agent.disabledPattern instead (current: '{}')",
          redisConfigurationProperties.getAgent().getDisabledAgents().size(),
          agentProperties.getDisabledPattern());
    }

    log.info(
        "PriorityAgentScheduler configuration: maxConcurrentAgents={}, threadPoolMaxSize={}, "
            + "enabledPattern='{}', disabledPattern='{}', batchOperationsEnabled={}",
        agentProperties.getMaxConcurrentAgents(),
        schedulerProperties.getThreadPoolMaxSize(),
        agentProperties.getEnabledPattern(),
        agentProperties.getDisabledPattern(),
        schedulerProperties.isBatchOperationsEnabled());

    return new PriorityAgentScheduler(
        jedisPool,
        nodeStatusProvider,
        agentIntervalProvider,
        shardingFilter,
        agentProperties,
        schedulerProperties);
  }
}
