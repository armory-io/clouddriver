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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Priority scheduler execution configuration properties for Redis priority scheduler.
 *
 * <p>This class caches scheduler-related configuration values to avoid dynamic config calls.
 * Configuration changes are applied through Spring Boot's configuration refresh mechanism.
 */
@Component
@ConfigurationProperties(prefix = "redis.scheduler")
public class PrioritySchedulerProperties {

  /**
   * How often the scheduler runs to check for ready agents (milliseconds). Lower values provide
   * faster response but increase CPU usage.
   */
  private long intervalMs = 1000L;

  /**
   * How often to refresh the Redis agent list (seconds). This helps recover from Redis failures and
   * ensures consistency.
   */
  private int refreshPeriodSeconds = 30;

  /** Zombie cleanup configuration for stuck agents. */
  private ZombieCleanupProperties zombieCleanup = new ZombieCleanupProperties();

  /** Orphan cleanup configuration for agents from crashed instances. */
  private OrphanCleanupProperties orphanCleanup = new OrphanCleanupProperties();

  /**
   * Enable batch operations for improved performance. When enabled, the scheduler will attempt to
   * acquire and process multiple agents in single Redis operations instead of processing them
   * individually.
   */
  private boolean batchOperationsEnabled = false;

  /**
   * Maximum number of agents to process in a single batch operation.
   *
   * <p>Larger batch sizes reduce Redis round-trips but increase memory usage and potential lock
   * contention.
   *
   * <p>This setting applies to:
   *
   * <ul>
   *   <li>Agent acquisition operations
   *   <li>Zombie cleanup operations
   *   <li>Orphan cleanup operations
   * </ul>
   *
   * <p>Default: 50 agents per batch
   */
  private int batchOperationsBatchSize = 50;

  /**
   * How long to cache Redis server time to reduce TIME command calls (milliseconds). Higher values
   * reduce Redis calls but may drift from server time.
   */
  private long timeCacheDurationMs = 10000L; // 10 seconds

  /** Thread pool configuration for agent execution. */
  private RedisThreadPoolProperties pool = new RedisThreadPoolProperties();

  // Getters and setters

  public long getIntervalMs() {
    return intervalMs;
  }

  public void setIntervalMs(long intervalMs) {
    this.intervalMs = intervalMs;
  }

  public int getRefreshPeriodSeconds() {
    return refreshPeriodSeconds;
  }

  public void setRefreshPeriodSeconds(int refreshPeriodSeconds) {
    this.refreshPeriodSeconds = refreshPeriodSeconds;
  }

  public ZombieCleanupProperties getZombieCleanup() {
    return zombieCleanup;
  }

  public void setZombieCleanup(ZombieCleanupProperties zombieCleanup) {
    this.zombieCleanup = zombieCleanup;
  }

  public OrphanCleanupProperties getOrphanCleanup() {
    return orphanCleanup;
  }

  public void setOrphanCleanup(OrphanCleanupProperties orphanCleanup) {
    this.orphanCleanup = orphanCleanup;
  }

  public boolean isBatchOperationsEnabled() {
    return batchOperationsEnabled;
  }

  public void setBatchOperationsEnabled(boolean enabled) {
    this.batchOperationsEnabled = enabled;
  }

  public int getBatchOperationsBatchSize() {
    return batchOperationsBatchSize;
  }

  public void setBatchOperationsBatchSize(int batchSize) {
    this.batchOperationsBatchSize = batchSize;
  }

  public int getAgentAcquisitionBatchSize() {
    return batchOperationsBatchSize;
  }

  public void setAgentAcquisitionBatchSize(int batchSize) {
    this.batchOperationsBatchSize = batchSize;
  }

  public long getTimeCacheDurationMs() {
    return timeCacheDurationMs;
  }

  public void setTimeCacheDurationMs(long timeCacheDurationMs) {
    this.timeCacheDurationMs = timeCacheDurationMs;
  }

  public RedisThreadPoolProperties getPool() {
    return pool;
  }

  public void setPool(RedisThreadPoolProperties pool) {
    this.pool = pool;
  }

  public int getThreadPoolCoreSize() {
    return pool.getCoreSize();
  }

  public int getThreadPoolMaxSize() {
    return pool.getMaxSize();
  }

  public long getThreadPoolKeepAliveSeconds() {
    return pool.getKeepAliveSeconds();
  }

  public boolean isZombieCleanupEnabled() {
    return zombieCleanup.isEnabled();
  }

  public long getZombieThresholdMs() {
    return zombieCleanup.getThresholdMs();
  }

  public long getZombieIntervalMs() {
    return zombieCleanup.getIntervalMs();
  }

  public int getZombieBatchSize() {
    return batchOperationsBatchSize;
  }

  public boolean hasExceptionalAgents() {
    return zombieCleanup.getExceptionalAgents() != null
        && !zombieCleanup.getExceptionalAgents().getPattern().isEmpty();
  }

  public String getExceptionalAgentsPattern() {
    return zombieCleanup.getExceptionalAgents().getPattern();
  }

  public long getExceptionalAgentsThresholdMs() {
    return zombieCleanup.getExceptionalAgents().getThresholdMs();
  }

  public boolean isOrphanCleanupEnabled() {
    return orphanCleanup.isEnabled();
  }

  public long getOrphanThresholdMs() {
    return orphanCleanup.getThresholdMs();
  }

  public long getOrphanIntervalMs() {
    return orphanCleanup.getIntervalMs();
  }

  public int getOrphanBatchSize() {
    return batchOperationsBatchSize;
  }

  public long getOrphanLeadershipTtlMs() {
    return orphanCleanup.getLeadershipTtlMs();
  }

  public boolean isOrphanForceAllPods() {
    return orphanCleanup.isForceAllPods();
  }
}

/**
 * Zombie cleanup configuration properties for stuck agents.
 *
 * <p>Configuration example: redis: scheduler: zombieCleanup: enabled: true # Default: zombie
 * detection enabled thresholdMs: 30000 # Default: 30 seconds (30 * 1000) intervalMs: 300000 #
 * Default: 5 minutes (5 * 60 * 1000) batchSize: 50 # Default: process 50 zombies per batch
 * exceptionalAgents: pattern: ".*BigQuery.*" # Example: Regex pattern for agent names thresholdMs:
 * 3600000 # Different threshold for matching agents (60 * 60 * 1000)
 */
class ZombieCleanupProperties {

  /** Whether zombie cleanup is enabled. Can be disabled for debugging or during maintenance. */
  private boolean enabled = true;

  /**
   * How long an agent can run beyond its completion deadline before being considered a zombie
   * (milliseconds). This is a buffer beyond the expected completion time to account for Redis
   * delays and clock skew. Agents past deadline + threshold are considered stuck or orphaned and
   * are forcibly terminated. This buffer provides operational safety for Redis delays and clock
   * skew. Default: 30 seconds.
   */
  private long thresholdMs = 30000L; // 30 seconds

  /** How often to check for and clean up zombie agents (milliseconds). Default: 5 minutes. */
  private long intervalMs = 300000L; // 5 minutes

  /** Configuration for exceptional agents that require different zombie thresholds. */
  private ExceptionalAgentsProperties exceptionalAgents = new ExceptionalAgentsProperties();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public long getThresholdMs() {
    return thresholdMs;
  }

  public void setThresholdMs(long thresholdMs) {
    this.thresholdMs = thresholdMs;
  }

  public long getIntervalMs() {
    return intervalMs;
  }

  public void setIntervalMs(long intervalMs) {
    this.intervalMs = intervalMs;
  }

  public ExceptionalAgentsProperties getExceptionalAgents() {
    if (exceptionalAgents == null) {
      exceptionalAgents = new ExceptionalAgentsProperties();
    }
    return exceptionalAgents;
  }

  public void setExceptionalAgents(ExceptionalAgentsProperties exceptionalAgents) {
    this.exceptionalAgents = exceptionalAgents;
  }
}

/** Configuration properties for exceptional agents that require different zombie thresholds. */
class ExceptionalAgentsProperties {

  /**
   * Regular expression pattern to match agent names that should use exceptional thresholds. Empty
   * string means no exceptional agents. Example patterns: - ".*BigQuery.*" - matches agents
   * containing "BigQuery" - "^(AWS|GCP).*" - matches agents starting with "AWS" or "GCP" -
   * ".*Provider$" - matches agents ending with "Provider"
   */
  private String pattern = "";

  /**
   * Zombie threshold for agents matching the pattern (milliseconds). Default: 60 minutes for
   * exceptional agents that may need longer processing time.
   */
  private long thresholdMs = 3600000L; // 60 minutes

  public String getPattern() {
    return pattern;
  }

  public void setPattern(String pattern) {
    this.pattern = pattern;
  }

  public long getThresholdMs() {
    return thresholdMs;
  }

  public void setThresholdMs(long thresholdMs) {
    this.thresholdMs = thresholdMs;
  }
}

/** Orphan cleanup configuration properties for agents from crashed instances. */
class OrphanCleanupProperties {

  /** Whether orphan cleanup is enabled. Can be disabled for debugging or during maintenance. */
  private boolean enabled = true;

  /**
   * Additional time buffer beyond completion deadlines (WORKZ) or execution times (WAITZ) before
   * considering an agent orphaned (milliseconds). WORKZ orphans are agents past completion deadline
   * + buffer. WAITZ orphans are agents with execution times older than current time - buffer. This
   * buffer accounts for network partitions and Redis latency.
   */
  private long thresholdMs = 600000L; // 10 minutes

  /** How often to check for and clean up orphaned agents (milliseconds). */
  private long intervalMs = 300000L; // 5 minutes

  /**
   * TTL for distributed cleanup leadership lock (milliseconds). Only one pod gets cleanup
   * leadership at a time.
   */
  private long leadershipTtlMs = 120000L; // 2 minutes

  /**
   * Force all pods to participate in orphan cleanup (not just leader). Disabled by default for
   * safety.
   */
  private boolean forceAllPods = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public long getThresholdMs() {
    return thresholdMs;
  }

  public void setThresholdMs(long thresholdMs) {
    this.thresholdMs = thresholdMs;
  }

  public long getIntervalMs() {
    return intervalMs;
  }

  public void setIntervalMs(long intervalMs) {
    this.intervalMs = intervalMs;
  }

  public long getLeadershipTtlMs() {
    return leadershipTtlMs;
  }

  public void setLeadershipTtlMs(long leadershipTtlMs) {
    this.leadershipTtlMs = leadershipTtlMs;
  }

  public boolean isForceAllPods() {
    return forceAllPods;
  }

  public void setForceAllPods(boolean forceAllPods) {
    this.forceAllPods = forceAllPods;
  }
}

/** Thread pool configuration properties for Redis scheduler. */
class RedisThreadPoolProperties {

  /**
   * Thread pool core size for agent execution. Defaults to 10, which works for most deployments.
   */
  private int coreSize = 10;

  /**
   * Thread pool maximum size for agent execution. Defaults to 50, increase for high-throughput
   * deployments.
   */
  private int maxSize = 50;

  /** Thread keep-alive time in seconds. */
  private long keepAliveSeconds = 60L;

  public int getCoreSize() {
    return coreSize;
  }

  public void setCoreSize(int coreSize) {
    this.coreSize = coreSize;
  }

  public int getMaxSize() {
    return maxSize;
  }

  public void setMaxSize(int maxSize) {
    this.maxSize = maxSize;
  }

  public long getKeepAliveSeconds() {
    return keepAliveSeconds;
  }

  public void setKeepAliveSeconds(long keepAliveSeconds) {
    this.keepAliveSeconds = keepAliveSeconds;
  }
}
