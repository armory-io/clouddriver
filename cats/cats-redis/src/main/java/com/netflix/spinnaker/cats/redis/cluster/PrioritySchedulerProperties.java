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

import javax.annotation.PostConstruct;
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
   * How often the scheduler runs to check for ready agents (milliseconds). Controls the frequency
   * of the main scheduling loop. Config key: {@code redis.scheduler.interval-ms}
   */
  private long intervalMs = 1000L;

  /**
   * How often to refresh the Redis agent list (seconds). This helps recover from Redis failures and
   * ensures consistency. Config key: {@code redis.scheduler.refresh-period-seconds}
   */
  private int refreshPeriodSeconds = 30;

  /** Zombie cleanup configuration for stuck agents. */
  private ZombieCleanupProperties zombieCleanup = new ZombieCleanupProperties();

  /** Orphan cleanup configuration for agents from crashed instances. */
  private OrphanCleanupProperties orphanCleanup = new OrphanCleanupProperties();

  /**
   * Enable batch operations for Redis operations. When enabled, the scheduler will group agent
   * operations together in batches rather than processing them individually. This affects how
   * agents are acquired and scheduled.
   *
   * <p>All batchable workflows (acquisition, completion, zombie/orphan cleanup, repopulation)
   * respect this configuration. See {@link BatchOperations}.
   */
  private BatchOperations batchOperations = new BatchOperations();

  /**
   * How long to cache Redis server time to reduce TIME command calls (milliseconds). Higher values
   * reduce Redis calls but may drift from server time. Config key: {@code
   * redis.scheduler.time-cache-duration-ms}
   */
  private long timeCacheDurationMs = 10000L; // 10 seconds

  /** Thread pool configuration for agent execution. */
  private RedisThreadPoolProperties pool = new RedisThreadPoolProperties();

  /**
   * Failure-aware backoff configuration for agent failures.
   *
   * <p>This block controls how the scheduler delays subsequent executions after an agent run fails.
   * It enables class-based backoff (e.g., permanent forbidden, throttled, transient/server errors)
   * and optional jitter to avoid synchronized retries across pods.
   */
  private FailureBackoffProperties failureBackoff = new FailureBackoffProperties();

  /**
   * Optional jitter applied when initially registering new agents (in seconds). A positive value
   * spreads first execution across the window to reduce thundering herds. Default: 0 (disabled).
   * Config key: {@code redis.scheduler.initial-registration-jitter-seconds}
   */
  private int initialRegistrationJitterSeconds = 0;

  /**
   * Configurable Redis key names and namespacing for the scheduler's data structures.
   *
   * <p>Includes base names for the waiting/working sets and the cleanup leadership key, plus
   * optional prefix and hash-tag. When {@code hashTag} is set (non-empty), the final Redis keys
   * will include the value wrapped in braces to ensure all keys hash to the same slot on Redis
   * Cluster (e.g., {@code waiting{ps}}, {@code working{ps}}, {@code cleanup-leader{ps}}).
   */
  private Keys keys = new Keys();

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

  public BatchOperations getBatchOperations() {
    return batchOperations;
  }

  public void setBatchOperations(BatchOperations batchOperations) {
    this.batchOperations = batchOperations;
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

  /**
   * Returns the failure-aware backoff configuration. Never returns null.
   *
   * @return the current failure backoff configuration
   */
  public FailureBackoffProperties getFailureBackoff() {
    if (failureBackoff == null) {
      failureBackoff = new FailureBackoffProperties();
    }
    return failureBackoff;
  }

  /**
   * Sets the failure-aware backoff configuration.
   *
   * @param failureBackoff a non-null configuration object controlling failure backoff behavior
   */
  public void setFailureBackoff(FailureBackoffProperties failureBackoff) {
    this.failureBackoff = failureBackoff;
  }

  public int getInitialRegistrationJitterSeconds() {
    return initialRegistrationJitterSeconds;
  }

  public void setInitialRegistrationJitterSeconds(int initialRegistrationJitterSeconds) {
    this.initialRegistrationJitterSeconds = Math.max(0, initialRegistrationJitterSeconds);
  }

  /** Returns the configured Redis key naming and namespacing options. */
  public Keys getKeys() {
    return keys;
  }

  /** Sets the Redis key naming and namespacing options. */
  public void setKeys(Keys keys) {
    this.keys = keys != null ? keys : new Keys();
  }

  /**
   * Batch operations configuration block.
   *
   * <pre>
   * redis:
   *   scheduler:
   *     batch-operations:
   *       enabled: true
   *       batch-size: 50
   * </pre>
   */
  public static class BatchOperations {
    /** Enable batch operations globally (acquisition, cleanup, completion, repopulation). */
    private boolean enabled = false;

    /** Maximum number of items to process in a single batch. Default: 50. */
    private int batchSize = 50;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }
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

  public long getOrphanLeadershipTtlMs() {
    return orphanCleanup.getLeadershipTtlMs();
  }

  public boolean isOrphanForceAllPods() {
    return orphanCleanup.isForceAllPods();
  }

  @PostConstruct
  void validate() {
    validatePositive(intervalMs, "redis.scheduler.interval-ms");
    validatePositive(refreshPeriodSeconds, "redis.scheduler.refresh-period-seconds");
    validateNonNegative(
        batchOperations.getBatchSize(), "redis.scheduler.batch-operations.batch-size");

    // Pool bounds sanity
    if (pool.getCoreSize() <= 0) {
      throw new IllegalArgumentException("redis.scheduler.pool.core-size must be > 0");
    }
    if (pool.getMaxSize() < pool.getCoreSize()) {
      throw new IllegalArgumentException("redis.scheduler.pool.max-size must be >= core-size");
    }

    // Keys validation: non-empty base names
    if (keys == null) {
      keys = new Keys();
    }
    if (isBlank(keys.waitingSet)) {
      throw new IllegalArgumentException("redis.scheduler.keys.waiting-set must not be empty");
    }
    if (isBlank(keys.workingSet)) {
      throw new IllegalArgumentException("redis.scheduler.keys.working-set must not be empty");
    }
    if (isBlank(keys.cleanupLeaderKey)) {
      throw new IllegalArgumentException(
          "redis.scheduler.keys.cleanup-leader-key must not be empty");
    }
  }

  private static void validatePositive(long v, String name) {
    if (v <= 0) {
      throw new IllegalArgumentException(name + " must be > 0 (was " + v + ")");
    }
  }

  private static void validatePositive(int v, String name) {
    if (v <= 0) {
      throw new IllegalArgumentException(name + " must be > 0 (was " + v + ")");
    }
  }

  private static void validateNonNegative(int v, String name) {
    if (v < 0) {
      throw new IllegalArgumentException(name + " must be >= 0 (was " + v + ")");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  /**
   * Redis key naming configuration.
   *
   * <p>Defaults use lowercase, function-oriented names and preserve the historical leadership key
   * name for compatibility.
   */
  public static class Keys {
    /** Base name of the waiting/ready set. Default: "waiting". */
    private String waitingSet = "waiting";
    /** Base name of the working/leased set. Default: "working". */
    private String workingSet = "working";
    /** Leadership key used for orphan cleanup coordination. Default: "cleanup-leader". */
    private String cleanupLeaderKey = "cleanup-leader";
    /** Optional prefix added to all keys. Default: empty. */
    private String prefix = "";
    /**
     * Optional hash-tag value to force all keys into the same Redis Cluster slot. When non-empty,
     * the final keys will include the value wrapped in braces (e.g., "{ps}").
     */
    private String hashTag = "";

    public String getWaitingSet() {
      return waitingSet;
    }

    public void setWaitingSet(String waitingSet) {
      this.waitingSet = waitingSet;
    }

    public String getWorkingSet() {
      return workingSet;
    }

    public void setWorkingSet(String workingSet) {
      this.workingSet = workingSet;
    }

    public String getCleanupLeaderKey() {
      return cleanupLeaderKey;
    }

    public void setCleanupLeaderKey(String cleanupLeaderKey) {
      this.cleanupLeaderKey = cleanupLeaderKey;
    }

    public String getPrefix() {
      return prefix;
    }

    public void setPrefix(String prefix) {
      this.prefix = prefix != null ? prefix : "";
    }

    public String getHashTag() {
      return hashTag;
    }

    public void setHashTag(String hashTag) {
      this.hashTag = hashTag != null ? hashTag : "";
    }
  }
}

/**
 * Failure-aware backoff configuration properties.
 *
 * <p>Controls how the scheduler backs off agents after failures. Backoff is applied by scheduling
 * the agent into the waiting set with a future score equal to the computed delay.
 */
class FailureBackoffProperties {
  /** Master switch for failure-aware backoff. */
  private boolean enabled = false;

  /**
   * Jitter ratio applied to non-zero backoff delays. 0.1 means +/-10% randomization to avoid
   * synchronized retries.
   */
  private double jitterRatio = 0.1d;

  /** Number of immediate retries before applying errorInterval for transient/server errors. */
  private int maxImmediateRetries = 0;

  /** Fixed backoff for permanent forbidden errors (e.g., 403/AccessDenied). */
  private long permanentForbiddenBackoffMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(30);

  /** Throttled backoff policy parameters. */
  private ThrottledPolicy throttled = new ThrottledPolicy();

  /** @return true if failure-aware backoff is enabled; false to fallback to legacy behavior */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Enables or disables failure-aware backoff.
   *
   * @param enabled whether failure-aware backoff should be enabled
   */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /** @return jitter ratio applied to positive backoff delays */
  public double getJitterRatio() {
    return jitterRatio;
  }

  /**
   * Sets jitter ratio applied to positive backoff delays. For example, 0.1 means +/-10%.
   *
   * @param jitterRatio a value in [0.0, 1.0]
   */
  public void setJitterRatio(double jitterRatio) {
    this.jitterRatio = jitterRatio;
  }

  /**
   * @return number of immediate retries allowed before applying errorInterval for transient/server
   *     errors
   */
  public int getMaxImmediateRetries() {
    return maxImmediateRetries;
  }

  /**
   * Sets number of immediate retries before applying errorInterval for transient/server errors.
   *
   * @param maxImmediateRetries immediate retry count (>= 0)
   */
  public void setMaxImmediateRetries(int maxImmediateRetries) {
    this.maxImmediateRetries = maxImmediateRetries;
  }

  /** @return fixed backoff applied to permanent forbidden failures (e.g., 403/AccessDenied) */
  public long getPermanentForbiddenBackoffMs() {
    return permanentForbiddenBackoffMs;
  }

  /**
   * Sets fixed backoff applied to permanent forbidden failures (e.g., 403/AccessDenied).
   *
   * @param permanentForbiddenBackoffMs delay in milliseconds
   */
  public void setPermanentForbiddenBackoffMs(long permanentForbiddenBackoffMs) {
    this.permanentForbiddenBackoffMs = permanentForbiddenBackoffMs;
  }

  /** @return throttled backoff policy parameters */
  public ThrottledPolicy getThrottled() {
    if (throttled == null) {
      throttled = new ThrottledPolicy();
    }
    return throttled;
  }

  /**
   * Sets throttled backoff policy parameters.
   *
   * @param throttled policy (base, multiplier, cap)
   */
  public void setThrottled(ThrottledPolicy throttled) {
    this.throttled = throttled;
  }

  /** Parameters controlling exponential backoff for throttled failures. */
  static class ThrottledPolicy {
    /** Starting backoff for throttled errors. */
    private long baseMs = java.util.concurrent.TimeUnit.SECONDS.toMillis(30);
    /** Exponential multiplier for throttled errors. */
    private double multiplier = 2.0d;
    /** Upper cap for throttled exponential backoff. */
    private long capMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(10);

    /** @return starting backoff for throttled errors (milliseconds) */
    public long getBaseMs() {
      return baseMs;
    }

    /** Sets starting backoff for throttled errors (milliseconds). */
    public void setBaseMs(long baseMs) {
      this.baseMs = baseMs;
    }

    /** @return exponential multiplier for throttled errors */
    public double getMultiplier() {
      return multiplier;
    }

    /** Sets exponential multiplier for throttled errors. */
    public void setMultiplier(double multiplier) {
      this.multiplier = multiplier;
    }

    /** @return upper cap for throttled exponential backoff (milliseconds) */
    public long getCapMs() {
      return capMs;
    }

    /** Sets upper cap for throttled exponential backoff (milliseconds). */
    public void setCapMs(long capMs) {
      this.capMs = capMs;
    }
  }
}

/**
 * Zombie cleanup configuration properties for stuck agents.
 *
 * <p>Configuration example (kebab-case):
 *
 * <pre>
 * redis:
 *   scheduler:
 *     zombie-cleanup:
 *       enabled: true
 *       threshold-ms: 30000       # 30s buffer
 *       interval-ms: 300000       # 5m cadence
 *       batch-size: 50
 *       exceptional-agents:
 *         pattern: ".*BigQuery.*"
 *         threshold-ms: 3600000   # 60m for exceptional agents
 * </pre>
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
   * Additional time buffer beyond completion deadlines (working) or execution times (waiting)
   * before considering an agent orphaned (milliseconds). working orphans are agents past completion
   * deadline + buffer. waiting orphans are agents with execution times older than current time -
   * buffer. This buffer accounts for network partitions and Redis latency.
   */
  private long thresholdMs = 600000L; // 10 minutes

  /** How often to check for and clean up orphaned agents (milliseconds). */
  private long intervalMs = 300000L; // 5 minutes

  /**
   * TTL for distributed cleanup leadership lock (milliseconds). Only one pod gets cleanup
   * leadership at a time. Config key: {@code redis.scheduler.orphan-cleanup.leadership-ttl-ms}
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
   * Config key: {@code redis.scheduler.pool.core-size}
   */
  private int coreSize = 10;

  /**
   * Thread pool maximum size for agent execution. Defaults to 50, increase for high-throughput
   * deployments. Config key: {@code redis.scheduler.pool.max-size}
   */
  private int maxSize = 50;

  /**
   * Thread keep-alive time in seconds. Config key: {@code redis.scheduler.pool.keep-alive-seconds}
   */
  private long keepAliveSeconds = 60L;

  /**
   * When true, use a SynchronousQueue for direct handoff (no internal queue). This applies strong
   * backpressure to the scheduler once all workers are busy and up to max threads are in use. New
   * tasks will run in the caller via CallerRunsPolicy, eliminating memory build-up. Config key:
   * {@code redis.scheduler.pool.use-synchronous-queue}
   */
  private boolean useSynchronousQueue = false;

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

  public boolean isUseSynchronousQueue() {
    return useSynchronousQueue;
  }

  public void setUseSynchronousQueue(boolean useSynchronousQueue) {
    this.useSynchronousQueue = useSynchronousQueue;
  }
}
