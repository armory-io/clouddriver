/*
 * Copyright 2025 Armory, Inc.
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
 * Scheduler execution configuration properties for Redis scheduler. Follows SQL scheduler pattern
 * with redis.scheduler.* prefix.
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

  /**
   * How long an agent can run before being considered a zombie (milliseconds). Zombies are forcibly
   * terminated to prevent resource leaks.
   */
  private long zombieThresholdMs = 1800000L; // 30 minutes

  /** How often to check for and clean up zombie agents (milliseconds). */
  private long zombieCleanupIntervalMs = 300000L; // 5 minutes

  /**
   * How long an agent can be stuck in WORKING state before cleanup (milliseconds). This handles
   * orphaned agents from crashed instances.
   */
  private long orphanThresholdMs = 600000L; // 10 minutes

  /** How often to check for and clean up orphaned agents (milliseconds). */
  private long orphanCleanupIntervalMs = 300000L; // 5 minutes

  /**
   * Whether to enable orphaned agent cleanup. Can be disabled for debugging or during maintenance.
   */
  private boolean orphanCleanupEnabled = true;

  /**
   * Whether to enable batch operations for performance. Batch operations reduce Redis round trips
   * but are more complex.
   */
  private boolean batchOperationsEnabled = false;

  /**
   * Force all pods to participate in orphan cleanup (not just leader). Disabled by default for
   * safety.
   */
  private boolean forceOrphanCleanupAllPods = false;

  /**
   * How long to cache Redis server time to reduce TIME command calls (milliseconds). Higher values
   * reduce Redis calls but may drift from server time.
   */
  private long timeCacheDurationMs = 10000L; // 10 seconds

  /**
   * TTL for distributed cleanup leadership lock (milliseconds). Only one pod gets cleanup
   * leadership at a time.
   */
  private long orphanCleanupLeadershipTtlMs = 120000; // 2 minutes

  /** Batch size for orphan cleanup. Defaults to 50, which handles typical bursts. */
  private int orphanCleanupBatchSize = 50;

  /**
   * Thread pool configuration for agent execution. Similar to SQL scheduler's connectionPools
   * pattern.
   */
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

  public long getZombieThresholdMs() {
    return zombieThresholdMs;
  }

  public void setZombieThresholdMs(long zombieThresholdMs) {
    this.zombieThresholdMs = zombieThresholdMs;
  }

  public long getZombieCleanupIntervalMs() {
    return zombieCleanupIntervalMs;
  }

  public void setZombieCleanupIntervalMs(long zombieCleanupIntervalMs) {
    this.zombieCleanupIntervalMs = zombieCleanupIntervalMs;
  }

  public long getOrphanThresholdMs() {
    return orphanThresholdMs;
  }

  public void setOrphanThresholdMs(long orphanThresholdMs) {
    this.orphanThresholdMs = orphanThresholdMs;
  }

  public long getOrphanCleanupIntervalMs() {
    return orphanCleanupIntervalMs;
  }

  public void setOrphanCleanupIntervalMs(long orphanCleanupIntervalMs) {
    this.orphanCleanupIntervalMs = orphanCleanupIntervalMs;
  }

  public boolean isOrphanCleanupEnabled() {
    return orphanCleanupEnabled;
  }

  public void setOrphanCleanupEnabled(boolean orphanCleanupEnabled) {
    this.orphanCleanupEnabled = orphanCleanupEnabled;
  }

  public boolean isBatchOperationsEnabled() {
    return batchOperationsEnabled;
  }

  public void setBatchOperationsEnabled(boolean batchOperationsEnabled) {
    this.batchOperationsEnabled = batchOperationsEnabled;
  }

  public boolean isForceOrphanCleanupAllPods() {
    return forceOrphanCleanupAllPods;
  }

  public void setForceOrphanCleanupAllPods(boolean forceOrphanCleanupAllPods) {
    this.forceOrphanCleanupAllPods = forceOrphanCleanupAllPods;
  }

  public long getTimeCacheDurationMs() {
    return timeCacheDurationMs;
  }

  public void setTimeCacheDurationMs(long timeCacheDurationMs) {
    this.timeCacheDurationMs = timeCacheDurationMs;
  }

  public long getOrphanCleanupLeadershipTtlMs() {
    return orphanCleanupLeadershipTtlMs;
  }

  public void setOrphanCleanupLeadershipTtlMs(long orphanCleanupLeadershipTtlMs) {
    this.orphanCleanupLeadershipTtlMs = orphanCleanupLeadershipTtlMs;
  }

  public int getOrphanCleanupBatchSize() {
    return orphanCleanupBatchSize;
  }

  public void setOrphanCleanupBatchSize(int orphanCleanupBatchSize) {
    this.orphanCleanupBatchSize = orphanCleanupBatchSize;
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

/**
 * Thread pool configuration properties for Redis scheduler. Similar to SQL connectionPools pattern.
 */
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
