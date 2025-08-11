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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Centralized configuration management for the PriorityAgentScheduler.
 *
 * <p>This service handles:
 *
 * <ul>
 *   <li>Thread pool configuration and creation
 *   <li>Semaphore setup for concurrency control
 *   <li>Pattern compilation for agent filtering
 *   <li>Configuration validation and defaults
 *   <li>Runtime configuration access
 * </ul>
 *
 * <p>All configuration is cached via Spring Boot @ConfigurationProperties, which allows consistent
 * access to configuration values during runtime.
 */
@Component
public class PrioritySchedulerConfiguration {
  private static final Logger log = LoggerFactory.getLogger(PrioritySchedulerConfiguration.class);

  private final PriorityAgentProperties agentProperties;
  private final PrioritySchedulerProperties schedulerProperties;

  // Runtime configuration
  private volatile Pattern enabledAgentPattern;
  private volatile Pattern disabledAgentPattern;
  private volatile ExecutorService agentWorkPool;
  private volatile ScheduledExecutorService schedulerExecutorService;
  private volatile Semaphore runningAgents;

  /**
   * Constructs a new PriorityConfiguration instance with the provided properties.
   *
   * @param agentProperties Configuration properties for agent management
   * @param schedulerProperties Configuration properties for scheduler behavior
   */
  public PrioritySchedulerConfiguration(
      PriorityAgentProperties agentProperties, PrioritySchedulerProperties schedulerProperties) {
    this.agentProperties = agentProperties;
    this.schedulerProperties = schedulerProperties;

    initializeConfiguration();
  }

  /** Initialize all configuration-dependent resources. */
  private void initializeConfiguration() {
    // Compile enabled agent pattern
    this.enabledAgentPattern = Pattern.compile(agentProperties.getEnabledPattern());
    log.info("Enabled agent pattern: {}", agentProperties.getEnabledPattern());

    // Compile disabled agent pattern if provided
    if (!agentProperties.getDisabledPattern().isEmpty()) {
      this.disabledAgentPattern = Pattern.compile(agentProperties.getDisabledPattern());
      log.info("Disabled agent pattern: {}", agentProperties.getDisabledPattern());
    } else {
      this.disabledAgentPattern = null;
      log.info("No disabled agent pattern configured");
    }

    // Create thread pool for agent execution
    createAgentWorkPool();

    // Create scheduler executor service
    createSchedulerExecutorService();

    // Create semaphore for concurrency control
    createConcurrencyControl();

    log.info("Scheduler configuration initialized successfully");
  }

  /**
   * Get the agent execution thread pool.
   *
   * @return ExecutorService for executing agents
   */
  public ExecutorService getAgentWorkPool() {
    return agentWorkPool;
  }

  /**
   * Get the scheduler executor service.
   *
   * @return ScheduledExecutorService for scheduler timing
   */
  public ScheduledExecutorService getSchedulerExecutorService() {
    return schedulerExecutorService;
  }

  /**
   * Get the concurrency control semaphore.
   *
   * @return Optional semaphore for instance-wide concurrency control
   */
  public Semaphore getRunningAgents() {
    return runningAgents;
  }

  /**
   * Get the compiled pattern for enabled agents.
   *
   * @return Pattern for filtering enabled agents
   */
  public Pattern getEnabledAgentPattern() {
    return enabledAgentPattern;
  }

  /**
   * Get the compiled pattern for disabled agents.
   *
   * @return Pattern for filtering disabled agents, or null if no pattern configured
   */
  public Pattern getDisabledAgentPattern() {
    return disabledAgentPattern;
  }

  /**
   * Get the scheduler interval in milliseconds.
   *
   * @return scheduler interval
   */
  public long getSchedulerIntervalMs() {
    return schedulerProperties.getIntervalMs();
  }

  /**
   * Get the Redis refresh period in seconds.
   *
   * @return refresh period
   */
  public int getRedisRefreshPeriod() {
    return schedulerProperties.getRefreshPeriodSeconds();
  }

  /**
   * Get the maximum concurrent agents.
   *
   * @return max concurrent agents
   */
  public int getMaxConcurrentAgents() {
    return agentProperties.getMaxConcurrentAgents();
  }

  /**
   * Check if zombie cleanup is enabled.
   *
   * @return true if zombie cleanup is enabled
   */
  public boolean isZombieCleanupEnabled() {
    return schedulerProperties.getZombieCleanup().isEnabled();
  }

  /**
   * Get the zombie threshold in milliseconds.
   *
   * @return zombie threshold
   */
  public long getZombieThresholdMs() {
    return schedulerProperties.getZombieCleanup().getThresholdMs();
  }

  /**
   * Get the zombie cleanup interval in milliseconds.
   *
   * @return zombie cleanup interval
   */
  public long getZombieIntervalMs() {
    return schedulerProperties.getZombieCleanup().getIntervalMs();
  }

  /**
   * Get the zombie cleanup batch size.
   *
   * @return batch size for zombie cleanup
   */
  public int getZombieCleanupBatchSize() {
    return schedulerProperties.getZombieBatchSize();
  }

  /**
   * Check if orphan cleanup is enabled for this pod.
   *
   * @return true if orphan cleanup is enabled
   */
  public boolean isOrphanCleanupEnabled() {
    return schedulerProperties.getOrphanCleanup().isEnabled();
  }

  /**
   * Get the orphan threshold in milliseconds.
   *
   * @return orphan threshold
   */
  public long getOrphanThresholdMs() {
    return schedulerProperties.getOrphanCleanup().getThresholdMs();
  }

  /**
   * Get the orphan cleanup interval in milliseconds.
   *
   * @return orphan cleanup interval
   */
  public long getOrphanIntervalMs() {
    return schedulerProperties.getOrphanCleanup().getIntervalMs();
  }

  /**
   * Get the orphan cleanup batch size.
   *
   * @return batch size for orphan cleanup
   */
  public int getOrphanCleanupBatchSize() {
    return schedulerProperties.getOrphanBatchSize();
  }

  /**
   * Get the orphan cleanup leadership TTL in milliseconds.
   *
   * @return leadership TTL
   */
  public long getOrphanCleanupLeadershipTtlMs() {
    return schedulerProperties.getOrphanCleanup().getLeadershipTtlMs();
  }

  /**
   * Check if all pods should be forced to participate in orphan cleanup.
   *
   * @return true if all pods should participate
   */
  public boolean isForceOrphanCleanupAllPods() {
    return schedulerProperties.getOrphanCleanup().isForceAllPods();
  }

  /**
   * Check if batch operations are enabled.
   *
   * @return true if batch operations are enabled
   */
  public boolean isBatchOperationsEnabled() {
    return schedulerProperties.isBatchOperationsEnabled();
  }

  /** Shutdown all managed resources. */
  public void shutdown() {
    log.info("Shutting down scheduler configuration resources");

    if (agentWorkPool != null) {
      agentWorkPool.shutdown();
      try {
        if (!agentWorkPool.awaitTermination(30, TimeUnit.SECONDS)) {
          agentWorkPool.shutdownNow();
        }
      } catch (InterruptedException e) {
        agentWorkPool.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    if (schedulerExecutorService != null) {
      schedulerExecutorService.shutdown();
      try {
        if (!schedulerExecutorService.awaitTermination(10, TimeUnit.SECONDS)) {
          schedulerExecutorService.shutdownNow();
        }
      } catch (InterruptedException e) {
        schedulerExecutorService.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    log.info("Scheduler configuration shutdown completed");
  }

  /** Creates the agent work pool with the specified configuration. */
  private void createAgentWorkPool() {
    // Create thread pool with explicit configuration (aligned with other schedulers)
    // Redis WAITING_SET provides queuing, so ThreadPool uses unbounded queue like other schedulers
    int corePoolSize = schedulerProperties.getThreadPoolCoreSize();
    int maximumPoolSize = schedulerProperties.getThreadPoolMaxSize();
    long keepAliveTime = schedulerProperties.getThreadPoolKeepAliveSeconds();

    log.info(
        "Creating agent work pool: core={}, max={}, keepAlive={}s",
        corePoolSize,
        maximumPoolSize,
        keepAliveTime);

    java.util.concurrent.BlockingQueue<Runnable> workQueue;
    if (schedulerProperties.getPool().isUseSynchronousQueue()) {
      // Direct handoff: no queuing. Strong backpressure via CallerRunsPolicy.
      workQueue = new java.util.concurrent.SynchronousQueue<>();
    } else {
      // Default parity with other schedulers: unbounded queue
      workQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    }

    this.agentWorkPool =
        new ThreadPoolExecutor(
            corePoolSize,
            maximumPoolSize,
            keepAliveTime,
            TimeUnit.SECONDS,
            workQueue,
            new ThreadFactoryBuilder().setNameFormat("PriorityAgentWorker-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()); // Backpressure to scheduler thread
  }

  // No derived capacity logic: default is unbounded queue for parity; optional direct handoff via
  // SynchronousQueue provides a single strong safety switch without extra knobs.

  /** Creates the scheduler executor service. */
  private void createSchedulerExecutorService() {
    this.schedulerExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("PriorityAgentScheduler-%d").build());

    log.info("Created scheduler executor service");
  }

  /**
   * Creates the concurrency control semaphore based on the configured maximum concurrent agents.
   */
  private void createConcurrencyControl() {
    int maxConcurrentAgents = agentProperties.getMaxConcurrentAgents();

    if (maxConcurrentAgents > 0) {
      this.runningAgents = new Semaphore(maxConcurrentAgents);
      log.info("Created concurrency semaphore with {} permits", maxConcurrentAgents);
    } else {
      this.runningAgents = null;
      log.info("Concurrency control disabled - running unlimited agents");
    }
  }
}
