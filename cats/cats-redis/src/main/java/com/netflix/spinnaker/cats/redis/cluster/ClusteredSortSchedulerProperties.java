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
 * Scheduler execution configuration properties for Redis scheduler.
 *
 * <p>This class caches scheduler-related configuration values to avoid dynamic config calls.
 * Configuration changes are applied through Spring Boot's configuration refresh mechanism.
 */
@Component
@ConfigurationProperties(prefix = "redis.scheduler")
public class ClusteredSortSchedulerProperties {

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
   * Whether to enable batch operations for performance. Batch operations reduce Redis round trips
   * but are more complex.
   */
  private boolean batchOperationsEnabled = false;

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

  public void setBatchOperationsEnabled(boolean batchOperationsEnabled) {
    this.batchOperationsEnabled = batchOperationsEnabled;
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

  // Convenience methods for backward compatibility
  public int getThreadPoolCoreSize() {
    return pool.getCoreSize();
  }

  public int getThreadPoolMaxSize() {
    return pool.getMaxSize();
  }

  public long getThreadPoolKeepAliveSeconds() {
    return pool.getKeepAliveSeconds();
  }
}

/** Zombie cleanup configuration properties for stuck agents. */
class ZombieCleanupProperties {

  /** Whether zombie cleanup is enabled. */
  private boolean enabled = true;

  /**
   * Additional time buffer beyond agent completion deadline before considering an agent a zombie
   * (milliseconds). Zombies are agents that have exceeded their completion deadline + this buffer
   * and are forcibly terminated. This buffer provides operational safety for Redis delays and clock
   * skew.
   */
  private long thresholdMs = 30000L; // 30 seconds

  /** How often to check for and clean up zombie agents (milliseconds). */
  private long cleanupIntervalMs = 300000L; // 5 minutes

  /** Batch size for zombie cleanup. Defaults to 50, which handles typical bursts. */
  private int batchSize = 50;

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

  public long getCleanupIntervalMs() {
    return cleanupIntervalMs;
  }

  public void setCleanupIntervalMs(long cleanupIntervalMs) {
    this.cleanupIntervalMs = cleanupIntervalMs;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
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

  /** Batch size for orphan cleanup. Defaults to 50, which handles typical bursts. */
  private int batchSize = 50;

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

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
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
