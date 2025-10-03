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

import static com.netflix.spinnaker.cats.redis.cluster.SchedulerUtils.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Service that detects and cleans up zombie agents (stuck or timed-out agents).
 *
 * <p>Zombies are agents that exceed their completion deadline plus a configurable buffer. The
 * service scans locally active agents, cancels stuck executions, and removes them from Redis to
 * allow rescheduling.
 */
@Component
@Slf4j
public class ZombieCleanupService {

  private final String WORKING_SET;
  private final String WAITING_SET;

  private final JedisPool jedisPool;
  private final RedisScriptManager scriptManager;
  private final PrioritySchedulerProperties schedulerProperties;
  private final PrioritySchedulerMetrics metrics;

  // Tracking for zombie cleanup
  private final LongAdder zombiesCleanedUp = new LongAdder();
  private volatile long lastZombieCleanup = 0;

  // Compiled regex pattern for exceptional agents
  private volatile Pattern exceptionalAgentsPattern;

  /**
   * Constructs a new ZombieCleanupService instance with the provided properties.
   *
   * @param jedisPool Jedis connection pool for Redis operations
   * @param scriptManager Redis script manager for batch operations
   * @param schedulerProperties Configuration properties for scheduler behavior
   */
  public ZombieCleanupService(
      JedisPool jedisPool,
      RedisScriptManager scriptManager,
      PrioritySchedulerProperties schedulerProperties,
      PrioritySchedulerMetrics metrics) {
    this.jedisPool = jedisPool;
    this.scriptManager = scriptManager;
    this.schedulerProperties = schedulerProperties;
    this.metrics = metrics;
    compileExceptionalAgentsPattern();

    PrioritySchedulerProperties.Keys keysCfg = schedulerProperties.getKeys();
    String hash = keysCfg.getHashTag();
    String brace = (hash != null && !hash.isEmpty()) ? ("{" + hash + "}") : "";
    String prefix = keysCfg.getPrefix() != null ? keysCfg.getPrefix() : "";
    this.WAITING_SET = prefix + keysCfg.getWaitingSet() + brace;
    this.WORKING_SET = prefix + keysCfg.getWorkingSet() + brace;
  }

  // Optional fairness wiring to acquisition service.
  //
  // Meaning of optional:
  // - Not required for correctness: if this reference is not set, zombie cleanup still cancels
  //   running futures and removes entries from Redis; the scheduler continues to function.
  // - When set (wired by the scheduler), zombie cleanup also performs early permit release using an
  //   exactly-once handshake and increments a zombiesInFlight counter in the acquisition service.
  //   This avoids temporary under-filling when a cancelled thread lingers before exiting.
  // - When omitted, only the fairness step is skipped: a cancelled zombie may hold its semaphore
  //   permit until the worker thread exits, which can temporarily reduce effective concurrency on
  //   this pod. There is no oversubscription risk either way; the capacity guard remains intact.
  //
  // Tests or alternate constructors may omit the wiring for simplicity; the main scheduler wires
  // it in to enable the fairness behavior in production.
  private AgentAcquisitionService acquisitionService;
  private PermitFairnessHandler fairnessHandler;

  void setAcquisitionService(AgentAcquisitionService acquisitionService) {
    this.acquisitionService = acquisitionService;
    this.fairnessHandler = acquisitionService; // backward-compatible default
  }

  void setFairnessHandler(PermitFairnessHandler fairnessHandler) {
    this.fairnessHandler = fairnessHandler;
  }

  /**
   * Compiles the exceptional agents pattern for efficient matching. This method is called during
   * initialization and can be called again if configuration changes.
   */
  private void compileExceptionalAgentsPattern() {
    try {
      this.exceptionalAgentsPattern = schedulerProperties.getExceptionalAgentsPatternCompiled();
      if (this.exceptionalAgentsPattern != null) {
        log.info(
            "Compiled exceptional agents pattern: {}", this.exceptionalAgentsPattern.pattern());
      } else {
        log.debug("No exceptional agents pattern configured");
      }
    } catch (Exception e) {
      this.exceptionalAgentsPattern = null;
      log.error("Failed to obtain exceptional agents pattern", e);
    }
  }

  /**
   * Determines the appropriate zombie threshold for the given agent type.
   *
   * @param agentType The agent type to check
   * @return The zombie threshold in milliseconds (default or exceptional)
   */
  private long getZombieThresholdForAgent(String agentType) {
    // Check if agent matches exceptional pattern
    if (exceptionalAgentsPattern != null && exceptionalAgentsPattern.matcher(agentType).matches()) {
      long exceptionalThreshold =
          schedulerProperties.getZombieCleanup().getExceptionalAgents().getThresholdMs();
      log.debug(
          "Agent '{}' matches exceptional pattern, using threshold: {}ms",
          agentType,
          exceptionalThreshold);
      return exceptionalThreshold;
    }

    // Use default threshold
    return schedulerProperties.getZombieCleanup().getThresholdMs();
  }

  /**
   * Clean up zombie agents if the cleanup interval has elapsed. This method is called periodically
   * by the main scheduler.
   *
   * @param activeAgents Map of active agents (agentType -> completionDeadline)
   * @param activeAgentsFutures Map of agent futures for cancellation
   */
  public void cleanupZombieAgentsIfNeeded(
      Map<String, String> activeAgents, Map<String, Future<?>> activeAgentsFutures) {
    long now = currentTimeMillis();
    long zombieCleanupInterval = schedulerProperties.getZombieCleanup().getIntervalMs();

    if (CadenceGuard.isPeriodElapsed(lastZombieCleanup, zombieCleanupInterval)) {
      int cleaned = cleanupZombieAgents(activeAgents, activeAgentsFutures);
      lastZombieCleanup = now;

      if (cleaned > 0) {
        log.info("Zombie cleanup completed: {} agents cleaned up", cleaned);
      }
    }
  }

  /**
   * Cleans up zombie agents on the local instance based on execution duration.
   *
   * <p>Zombie agents are those that have been running for too long on this Clouddriver instance.
   * This can happen due to rate limiting, execution timeouts, or other interruptions in agent
   * processing. Unlike orphaned agents (which have no running instance), zombies are still
   * executing but have exceeded their expected runtime.
   *
   * <p>This cleanup prevents thread pool exhaustion: stuck agents hold executor threads
   * indefinitely, eventually saturating the pool and blocking all new work. The cleanup cancels
   * stuck futures and frees threads for other agents. Checks the local activeAgents map for agents
   * that have exceeded their completion deadline (current_time + agent_timeout), then performs
   * cleanup in Redis to remove the agents from both working and waiting sets.
   *
   * @param activeAgents Map of active agents (agentType -> completionDeadline)
   * @param activeAgentsFutures Map of agent futures for cancellation
   * @return Number of zombie agents cleaned up
   */
  public int cleanupZombieAgents(
      Map<String, String> activeAgents, Map<String, Future<?>> activeAgentsFutures) {
    long start = currentTimeMillis();
    final long budgetMs = schedulerProperties.getZombieCleanup().getRunBudgetMs();
    final long deadlineEpochMs = budgetMs > 0 ? (start + budgetMs) : Long.MAX_VALUE;
    long currentTime = currentTimeMillis();
    List<String> zombieAgentTypes = new ArrayList<>();

    int validAgentsScanned = 0;

    for (Map.Entry<String, String> entry : activeAgents.entrySet()) {
      if (Thread.currentThread().isInterrupted()) {
        log.warn("Stopping zombie scan early due to interrupt");
        break;
      }
      if (currentTimeMillis() > deadlineEpochMs) {
        log.warn("Stopping zombie scan early due to budget deadline");
        break;
      }
      currentTime = currentTimeMillis();
      String agentType = entry.getKey();
      String acquireScore = entry.getValue();

      try {
        // Score represents completion deadline in epoch seconds
        // Formula: acquire_time + agent_timeout = completion deadline
        long completionDeadlineMs = Long.parseLong(acquireScore) * 1000;
        validAgentsScanned++;

        // Different agents may have different thresholds (e.g., BigQuery agents need longer)
        long zombieThreshold = getZombieThresholdForAgent(agentType);

        // Zombie detection: current_time > (completion_deadline + buffer_threshold)
        // Buffer prevents false positives from temporary delays
        if (currentTime > completionDeadlineMs + zombieThreshold) {
          zombieAgentTypes.add(agentType);
          long overdueMs = currentTime - completionDeadlineMs;
          boolean isExceptional =
              exceptionalAgentsPattern != null
                  && exceptionalAgentsPattern.matcher(agentType).matches();
          log.warn(
              "Zombie agent detected: {} ({}ms overdue past completion deadline, {}ms {} threshold exceeded)",
              agentType,
              overdueMs,
              zombieThreshold,
              isExceptional ? "exceptional" : "default");
        }
      } catch (NumberFormatException e) {
        log.warn("Invalid acquire score for agent {}: {}", agentType, acquireScore, e);

        // Force cleanup invalid scores to prevent permanent stuck state
        // Defensive against external modifications
        zombieAgentTypes.add(agentType);
        log.error(
            "Force cleaning zombie agent {} with corrupted acquire score '{}' - likely external modification during acquisition",
            agentType,
            acquireScore);
      }
    }

    // Log scanning summary
    if (zombieAgentTypes.isEmpty()) {
      if (log.isDebugEnabled()) {
        log.debug("Zombie scan completed: {} agents analyzed, 0 zombies found", validAgentsScanned);
      }
      return 0;
    }

    log.warn(
        "Zombie scan completed: {} agents analyzed, {} zombies found - cleaning up: {}",
        validAgentsScanned,
        zombieAgentTypes.size(),
        zombieAgentTypes.stream().limit(5).collect(java.util.stream.Collectors.toList()));

    // Check if batch operations are enabled (disabled by default for safety)
    boolean batchOperationsEnabled = schedulerProperties.getBatchOperations().isEnabled();
    int totalCleaned = 0;

    try (Jedis jedis = jedisPool.getResource()) {
      if (!batchOperationsEnabled) {
        if (log.isDebugEnabled()) {
          log.debug(
              "Batch zombie cleanup disabled, using individual operations for {} agents",
              zombieAgentTypes.size());
        }

        for (String agentType : zombieAgentTypes) {
          if (Thread.currentThread().isInterrupted()) {
            log.warn("Stopping zombie individual cleanup due to interrupt");
            break;
          }
          if (currentTimeMillis() > deadlineEpochMs) {
            log.warn("Stopping zombie individual cleanup due to budget deadline");
            break;
          }
          try {
            if (cleanupIndividualZombieAgent(jedis, agentType, activeAgents, activeAgentsFutures)) {
              totalCleaned++;
            }
          } catch (Exception e) {
            log.error("Failed to cleanup zombie agent {}", agentType, e);
          }
        }
      } else {
        // Use batch cleanup with fallback to individual operations
        List<String> zombieBatch = new ArrayList<>();
        int configured = schedulerProperties.getBatchOperations().getBatchSize();
        if (configured <= 0) {
          // Simple, non-magic fallback: process up to the current number of candidates.
          configured = Math.max(1, zombieAgentTypes.size());
        }
        int batchSize = Math.min(configured, zombieAgentTypes.size());
        if (log.isDebugEnabled()) {
          log.debug(
              "Processing {} zombie agents in batches of {} with fallback",
              zombieAgentTypes.size(),
              batchSize);
        }

        for (String agentType : zombieAgentTypes) {
          if (Thread.currentThread().isInterrupted()) {
            log.warn("Stopping zombie batch preparation due to interrupt");
            break;
          }
          if (currentTimeMillis() > deadlineEpochMs) {
            log.warn("Stopping zombie batch preparation due to budget deadline");
            break;
          }
          zombieBatch.add(agentType);

          if (zombieBatch.size() >= batchSize) {
            if (Thread.currentThread().isInterrupted()) {
              log.warn("Skipping zombie batch execution due to interrupt");
              break;
            }
            if (currentTimeMillis() > deadlineEpochMs) {
              log.warn("Skipping zombie batch execution due to budget deadline");
              break;
            }
            totalCleaned +=
                cleanupZombieBatch(
                    jedis, zombieBatch, activeAgents, activeAgentsFutures, deadlineEpochMs);
            zombieBatch.clear();
          }
        }

        // Process remaining zombies
        if (!zombieBatch.isEmpty()) {
          if (!Thread.currentThread().isInterrupted() && currentTimeMillis() <= deadlineEpochMs) {
            totalCleaned +=
                cleanupZombieBatch(
                    jedis, zombieBatch, activeAgents, activeAgentsFutures, deadlineEpochMs);
          }
        }
      }

      zombiesCleanedUp.add(totalCleaned);
      if (metrics != null) {
        metrics.recordCleanupTime("zombie", currentTimeMillis() - start);
        metrics.incrementCleanupCleaned("zombie", totalCleaned);
      }
      if (log.isDebugEnabled()) {
        log.debug("Zombie cleanup completed: {} agents cleaned up", totalCleaned);
      }
      return totalCleaned;

    } catch (Exception e) {
      log.error("Error during zombie agent cleanup", e);
      if (metrics != null) {
        metrics.recordCleanupTime("zombie", currentTimeMillis() - start);
      }
      return 0;
    }
  }

  /**
   * Get the total number of zombie agents cleaned up since startup.
   *
   * @return total zombies cleaned up
   */
  public long getZombiesCleanedUp() {
    return zombiesCleanedUp.sum();
  }

  /**
   * Get the timestamp of the last zombie cleanup operation.
   *
   * @return last cleanup timestamp in milliseconds
   */
  public long getLastZombieCleanup() {
    return lastZombieCleanup;
  }

  /**
   * Refreshes the exceptional agents pattern configuration. This can be called when configuration
   * is updated at runtime.
   */
  public void refreshExceptionalAgentsPattern() {
    compileExceptionalAgentsPattern();
  }

  /**
   * Clean up zombie batch with optional batch operation and fallback mechanism. If batch operations
   * are enabled in configuration, attempts batch cleanup first. If batch operations are disabled or
   * if the batch operation fails, falls back to individual cleanup for each zombie agent.
   *
   * @param jedis Jedis connection for Redis operations
   * @param zombieAgentTypes List of zombie agent types
   * @param activeAgents Map of active agents (agentType -> completionDeadline)
   * @param activeAgentsFutures Map of agent futures for cancellation
   * @return Number of agents cleaned up
   */
  private int cleanupZombieBatch(
      Jedis jedis,
      List<String> zombieAgentTypes,
      Map<String, String> activeAgents,
      Map<String, Future<?>> activeAgentsFutures,
      long deadlineEpochMs) {

    if (zombieAgentTypes.isEmpty()) {
      return 0;
    }

    // Try batch operation first (even for a single agent)
    if (!zombieAgentTypes.isEmpty()) {
      try {
        // Build arguments for batch cleanup: [agent1, score1, agent2, score2, ...]
        List<String> batchArgs = new ArrayList<>(zombieAgentTypes.size() * 2);
        // Track the specific agents we actually attempted in the batch (acquireScore present)
        List<String> attemptedCandidates = new ArrayList<>();
        // Snapshot of input candidates to compute leftover set for per-item fallback
        List<String> inputCandidates = new ArrayList<>(zombieAgentTypes);

        for (String agentType : zombieAgentTypes) {
          if (Thread.currentThread().isInterrupted()) {
            log.warn("Stopping zombie batch build due to interrupt");
            break;
          }
          if (currentTimeMillis() > deadlineEpochMs) {
            log.warn("Stopping zombie batch build due to budget deadline");
            break;
          }
          String acquireScore = activeAgents.get(agentType);
          if (acquireScore != null) {
            batchArgs.add(agentType);
            batchArgs.add(acquireScore);
            attemptedCandidates.add(agentType);
          }
        }

        if (!batchArgs.isEmpty()) {
          // Execute Lua script to batch cleanup zombie agents from Redis working set
          Object result =
              scriptManager.evalshaWithSelfHeal(
                  jedis,
                  RedisScriptManager.REMOVE_AGENTS_CONDITIONAL,
                  java.util.Collections.singletonList(WORKING_SET), // Redis key (working)
                  batchArgs); // [agent1, score1, agent2, score2, ...]

          // Parse Lua script return value and synchronize local state
          ScriptResults.BatchRemovalResult parsed =
              ScriptResults.parseRemoveAgentsConditional(result);
          int cleanedByBatch = parsed.getRemovedCount();
          if (cleanedByBatch > 0) {
            log.info(
                "Zombie cleanup batch processed: {} agents cleaned from {} candidates",
                cleanedByBatch,
                zombieAgentTypes.size());
            for (String agentType : parsed.getMembers()) {
              Future<?> future = activeAgentsFutures.remove(agentType);
              if (future != null && !future.isDone()) {
                boolean cancelled = future.cancel(true);
                if (log.isDebugEnabled()) {
                  log.debug("Cancelled zombie agent {} future: {}", agentType, cancelled);
                }
              }
              if (acquisitionService != null) {
                try {
                  acquisitionService.removeActiveAgent(agentType);
                } catch (Exception e) {
                  log.debug(
                      "Failed to remove active agent via service; falling back to map removal", e);
                  activeAgents.remove(agentType);
                }
              } else {
                activeAgents.remove(agentType);
              }
              if (fairnessHandler != null) {
                try {
                  fairnessHandler.tryEarlyPermitReleaseAndMaybeIncrementZif(agentType);
                } catch (Exception e) {
                  log.debug("Fairness handshake during zombie cleanup failed; continuing", e);
                }
              }
              if (log.isDebugEnabled()) {
                log.debug("Cleaned up zombie agent: {}", agentType);
              }
            }
            // Do not return early; fall through to per-item cleanup for any remaining original
            // candidates
            // that were not removed by the batch operation. We compute leftovers from the full
            // input set.
            java.util.Set<String> removedSet = new java.util.HashSet<>(parsed.getMembers());
            List<String> remainingForFallback = new ArrayList<>();
            for (String a : inputCandidates) {
              if (!removedSet.contains(a)) {
                remainingForFallback.add(a);
              }
            }
            // Perform per-item fallback over remaining candidates and add to cleanedByBatch
            if (log.isDebugEnabled()) {
              log.debug(
                  "Using individual cleanup for {} remaining zombie agents after batch",
                  remainingForFallback.size());
            }
            int fallbackCleaned = 0;
            for (String agentType : remainingForFallback) {
              if (Thread.currentThread().isInterrupted()) {
                log.warn("Stopping zombie individual fallback due to interrupt");
                break;
              }
              if (currentTimeMillis() > deadlineEpochMs) {
                log.warn("Stopping zombie individual fallback due to budget deadline");
                break;
              }
              try {
                if (cleanupIndividualZombieAgent(
                    jedis, agentType, activeAgents, activeAgentsFutures)) {
                  fallbackCleaned++;
                }
              } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
                log.warn("Redis connection error while cleaning zombie {}", agentType, e);
              } catch (Exception e) {
                log.warn("Failed to cleanup individual zombie {}", agentType, e);
              }
            }
            return cleanedByBatch + fallbackCleaned;
          }
        }
      } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
        log.warn(
            "Redis connection error during batch zombie cleanup for {} agents",
            zombieAgentTypes.size(),
            e);
      } catch (Exception e) {
        log.warn(
            "Batch zombie cleanup failed for {} agents, falling back to individual cleanup",
            zombieAgentTypes.size(),
            e);
      }
    }

    // Batch operation failed, disabled, or single agent - fall back to individual cleanup
    if (log.isDebugEnabled()) {
      log.debug("Using individual cleanup for {} zombie agents", zombieAgentTypes.size());
    }
    int totalCleaned = 0;
    for (String agentType : zombieAgentTypes) {
      if (Thread.currentThread().isInterrupted()) {
        log.warn("Stopping zombie individual fallback due to interrupt");
        break;
      }
      if (currentTimeMillis() > deadlineEpochMs) {
        log.warn("Stopping zombie individual fallback due to budget deadline");
        break;
      }
      try {
        if (cleanupIndividualZombieAgent(jedis, agentType, activeAgents, activeAgentsFutures)) {
          totalCleaned++;
        }
      } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
        log.warn("Redis connection error while cleaning zombie {}", agentType, e);
      } catch (Exception e) {
        log.warn("Failed to cleanup individual zombie {}", agentType, e);
      }
    }
    return totalCleaned;
  }

  /**
   * Clean up a single zombie agent individually.
   *
   * @param jedis Jedis connection for Redis operations
   * @param agentType Type of the zombie agent
   * @param activeAgents Map of active agents (agentType -> completionDeadline)
   * @param activeAgentsFutures Map of agent futures for cancellation
   * @return true if the agent was successfully cleaned up
   */
  private boolean cleanupIndividualZombieAgent(
      Jedis jedis,
      String agentType,
      Map<String, String> activeAgents,
      Map<String, Future<?>> activeAgentsFutures) {

    try {
      // Snapshot current future and then delegate active tracking removal to acquisition service
      Future<?> future = activeAgentsFutures.remove(agentType);
      String acquireScore = activeAgents.get(agentType);

      if (acquireScore == null) {
        log.debug("Agent {} not found in active tracking, skipping cleanup", agentType);
        return false;
      }

      // Cancel the future if it exists
      if (future != null && !future.isDone()) {
        future.cancel(true);
        log.info("Cancelled zombie agent execution: {}", agentType);
      }

      // Remove from WORKING only by default to preserve any legitimate waiting entry that
      // should allow immediate reacquisition. This matches batch behavior and avoids delaying
      // the next run. If a duplicate exists in WAITING (corruption), it will be handled by
      // orphan cleanup's optional repair or a targeted check below.
      Object result =
          scriptManager.evalshaWithSelfHeal(
              jedis,
              RedisScriptManager.REMOVE_AGENTS_CONDITIONAL,
              java.util.Collections.singletonList(WORKING_SET),
              java.util.Arrays.asList(agentType, acquireScore));

      boolean removed = false;
      try {
        if (result instanceof java.util.List) {
          java.util.List<?> list = (java.util.List<?>) result;
          if (!list.isEmpty() && list.get(0) instanceof Number) {
            removed = ((Number) list.get(0)).intValue() > 0;
          }
        }
      } catch (Exception parseEx) {
        log.debug(
            "Failed to parse REMOVE_AGENTS_CONDITIONAL result for {}: {}",
            agentType,
            result,
            parseEx);
        removed = false;
      }

      if (!removed) {
        try {
          // Preserve waiting entries: remove only from WORKING as a conservative fallback
          Long zrem = jedis.zrem(WORKING_SET, agentType);
          removed = zrem != null && zrem.longValue() > 0L;
          if (removed) {
            log.debug("Fallback ZREM removed {} from working set (preserved waiting)", agentType);
          }
        } catch (Exception fbEx) {
          log.warn("Fallback working-set removal failed for {}", agentType, fbEx);
        }
      }

      // ALWAYS clean local state and perform fairness, regardless of Redis outcome.
      // This prevents permit leaks and stuck 'running' counts when Redis removal races or fails.
      if (acquisitionService != null) {
        acquisitionService.removeActiveAgent(agentType);
      } else {
        activeAgents.remove(agentType);
      }

      if (fairnessHandler != null) {
        try {
          fairnessHandler.tryEarlyPermitReleaseAndMaybeIncrementZif(agentType);
        } catch (Exception e) {
          log.debug(
              "Failed early-permit release during individual zombie cleanup for {}", agentType, e);
        }
      }

      if (removed) {
        log.debug("Removed zombie agent {} from Redis working set", agentType);
        // Optional: targeted repair. If a duplicate exists in WAITING, remove it now.
        try {
          Double waitScore = jedis.zscore(WAITING_SET, agentType);
          if (waitScore != null) {
            Object remRes =
                scriptManager.evalshaWithSelfHeal(
                    jedis,
                    RedisScriptManager.REMOVE_AGENT,
                    java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                    java.util.Collections.singletonList(agentType));
            if (remRes != null && ((Long) remRes).intValue() == 1) {
              log.warn(
                  "Removed duplicate waiting entry for zombie agent {} during cleanup", agentType);
            }
          }
        } catch (Exception ignore) {
          // Best-effort duplicate repair; ignore failures
        }
      } else {
        log.debug(
            "Zombie agent {} not found in Redis working set during cleanup (result={})",
            agentType,
            result);
      }

      // Count as cleaned once we've definitively stopped local execution and freed capacity.
      return true;

    } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
      log.warn("Redis connection error removing zombie {} from Redis", agentType, e);
      return false;
    } catch (Exception e) {
      log.warn("Failed to remove zombie agent {} from Redis", agentType, e);
      return false;
    }
  }
}
