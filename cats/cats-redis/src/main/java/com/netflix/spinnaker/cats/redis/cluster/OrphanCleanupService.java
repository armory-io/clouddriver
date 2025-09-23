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

import com.netflix.spinnaker.cats.agent.Agent;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.params.SetParams;

/**
 * Service that detects and cleans up orphaned agents from crashed instances.
 *
 * <p>Orphans are agents left in Redis after their owning pod crashes. Unlike zombies (locally stuck
 * agents), orphans have no running instance. The service uses leader election to coordinate cleanup
 * across pods, preventing duplicate work.
 *
 * <p>Key rules:
 *
 * <ul>
 *   <li>Preserve valid waiting agents - only remove positively invalid ones
 *   <li>Skip locally active agents - zombie cleaner handles those
 *   <li>Move valid working orphans back to waiting, remove invalid ones
 * </ul>
 */
@Component
@Slf4j
public class OrphanCleanupService {

  // Redis key names derived from configuration
  private final String WORKING_SET;
  private final String WAITING_SET;
  private final String CLEANUP_LEADER_KEY;

  private final JedisPool jedisPool;
  private final RedisScriptManager scriptManager;
  private final PrioritySchedulerProperties schedulerProperties;
  private final PrioritySchedulerMetrics metrics;
  private final LongAdder orphansCleanedUp = new LongAdder();

  // Reference to access agent state for orphan identification
  private AgentAcquisitionService acquisitionService;

  // Leadership tracking
  private volatile String currentLeadershipId = null;
  private volatile long lastOrphanCleanup = 0;

  public OrphanCleanupService(
      JedisPool jedisPool,
      RedisScriptManager scriptManager,
      PrioritySchedulerProperties schedulerProperties,
      PrioritySchedulerMetrics metrics) {
    this.jedisPool = jedisPool;
    this.scriptManager = scriptManager;
    this.schedulerProperties = schedulerProperties;
    this.metrics = metrics;

    PrioritySchedulerProperties.Keys keysCfg = schedulerProperties.getKeys();
    String hash = keysCfg.getHashTag();
    String brace = (hash != null && !hash.isEmpty()) ? ("{" + hash + "}") : "";
    String prefix = keysCfg.getPrefix() != null ? keysCfg.getPrefix() : "";
    this.WAITING_SET = prefix + keysCfg.getWaitingSet() + brace;
    this.WORKING_SET = prefix + keysCfg.getWorkingSet() + brace;
    this.CLEANUP_LEADER_KEY = prefix + keysCfg.getCleanupLeaderKey() + brace;
  }

  /**
   * Set reference to AgentAcquisitionService for advanced orphan processing. This provides access
   * to agent registry and active agent cleanup.
   */
  public void setAcquisitionService(AgentAcquisitionService acquisitionService) {
    this.acquisitionService = acquisitionService;
  }

  /** Cleanup orphaned agents if needed, with configurable intervals and leadership coordination. */
  public void cleanupOrphanedAgentsIfNeeded() {
    long start = currentTimeMillis();
    if (!schedulerProperties.getOrphanCleanup().isEnabled()) {
      return;
    }

    // Guard against long-running loops: if previous pass is still considered running for too long
    // (e.g., due to a bug), skip starting another pass to avoid monopolizing cleanup leadership.
    long maxPassDurationMs =
        Math.max(1_000L, schedulerProperties.getOrphanCleanup().getRunBudgetMs());
    if (lastOrphanCleanup > 0 && maxPassDurationMs > 0) {
      long sinceLast = currentTimeMillis() - lastOrphanCleanup;
      // If we haven't updated lastOrphanCleanup for > 10x budget, assume the previous pass hung
      if (sinceLast > (10L * maxPassDurationMs)) {
        log.warn(
            "Skipping orphan cleanup: previous pass appears hung ({}ms since last update > budget {}ms). Releasing leadership defensively.",
            sinceLast,
            maxPassDurationMs);
        try {
          releaseCleanupLeadership();
        } catch (Exception ignore) {
        }
        // Bump the timestamp to avoid log spam; next cycle will attempt again
        lastOrphanCleanup = currentTimeMillis();
        return;
      }
    }

    // Check if enough time has passed since last cleanup
    long intervalMs = schedulerProperties.getOrphanCleanup().getIntervalMs();
    if (!isPeriodElapsed(lastOrphanCleanup, intervalMs)) {
      long remaining = intervalMs - (currentTimeMillis() - lastOrphanCleanup);
      log.debug("Skipping orphan cleanup - interval not elapsed ({}ms remaining)", remaining);
      return;
    }

    // Use leadership election to prevent multiple instances from running cleanup simultaneously
    boolean forceCleanup = schedulerProperties.getOrphanCleanup().isForceAllPods();
    if (!forceCleanup && !tryAcquireCleanupLeadership()) {
      log.debug("Skipping orphan cleanup - leadership held by another instance");
      return;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      int workingCleaned = cleanupOrphanedAgentsFromSet(jedis, WORKING_SET, start);
      // If we already exceeded the budget on working, do not attempt waiting
      if (overBudget(start)) {
        log.warn("Orphan cleanup budget exceeded after working set; skipping waiting set");
        lastOrphanCleanup = currentTimeMillis();
        return;
      }
      int waitingCleaned = cleanupOrphanedAgentsFromSet(jedis, WAITING_SET, start);
      int totalCleaned = workingCleaned + waitingCleaned;

      if (totalCleaned > 0) {
        orphansCleanedUp.add(totalCleaned);
        log.info(
            "Orphan cleanup completed: {} agents cleaned ({} from working, {} from waiting)",
            totalCleaned,
            workingCleaned,
            waitingCleaned);
      }
      if (metrics != null) {
        metrics.recordCleanupTime("orphan", currentTimeMillis() - start);
        metrics.incrementCleanupCleaned("orphan", totalCleaned);
      }
      // Update the last cleanup timestamp
      lastOrphanCleanup = currentTimeMillis();
    } catch (Exception e) {
      log.error("Failed to cleanup orphaned agents", e);
    } finally {
      // Release leadership if we acquired it (but not if forced cleanup)
      if (!forceCleanup) {
        releaseCleanupLeadership();
      }
    }
  }

  /**
   * Force cleanup of orphaned agents immediately, bypassing interval checks. This method is
   * primarily intended for testing purposes.
   *
   * @return Total number of orphaned agents cleaned up
   */
  public int forceCleanupOrphanedAgents() {
    if (!schedulerProperties.getOrphanCleanup().isEnabled()) {
      return 0;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      long start = currentTimeMillis();
      int workingCleaned = cleanupOrphanedAgentsFromSet(jedis, WORKING_SET, start);
      int waitingCleaned = cleanupOrphanedAgentsFromSet(jedis, WAITING_SET, start);
      int totalCleaned = workingCleaned + waitingCleaned;

      if (totalCleaned > 0) {
        orphansCleanedUp.add(totalCleaned);
        log.info(
            "Forced orphan cleanup completed: {} agents cleaned ({} from working, {} from waiting)",
            totalCleaned,
            workingCleaned,
            waitingCleaned);
      }
      // Update the last cleanup timestamp
      lastOrphanCleanup = currentTimeMillis();
      return totalCleaned;
    } catch (Exception e) {
      log.error("Failed to force cleanup orphaned agents", e);
      return 0;
    }
  }

  /**
   * Get the total number of orphaned agents cleaned up since startup.
   *
   * @return total orphans cleaned up
   */
  public long getOrphansCleanedUp() {
    return orphansCleanedUp.sum();
  }

  /**
   * Get the timestamp of the last orphan cleanup operation.
   *
   * @return last cleanup timestamp in milliseconds
   */
  public long getLastOrphanCleanup() {
    return lastOrphanCleanup;
  }

  /**
   * Clean up orphaned agents from the specified Redis set with built-in batch processing and
   * fallback mechanism.
   *
   * @param jedis The Jedis connection to the Redis server
   * @param setName The name of the Redis set to clean up
   * @return The number of orphaned agents cleaned up
   */
  private int cleanupOrphanedAgentsFromSet(Jedis jedis, String setName, long startTs) {
    long cutoffScore;
    long thresholdForLogging;

    if (WAITING_SET.equals(setName)) {
      // Waiting set: score = next execution time
      // Orphan detection: score < (current_time - threshold)
      // These are agents scheduled far in the past that never executed
      long orphanThreshold = schedulerProperties.getOrphanCleanup().getThresholdMs();
      cutoffScore = (currentTimeMillis() - orphanThreshold) / 1000;
      thresholdForLogging = orphanThreshold;
    } else {
      // Working set: score = completion deadline (acquire_time + timeout)
      // Orphan detection: current_time > (score + threshold)
      // Rearranged: score < (current_time - threshold)
      // These are agents that should have completed but their pod might have crashed
      long orphanThreshold = schedulerProperties.getOrphanCleanup().getThresholdMs();
      cutoffScore = (currentTimeMillis() - orphanThreshold) / 1000;
      thresholdForLogging = orphanThreshold;
    }

    try {
      if (overBudget(startTs) || Thread.currentThread().isInterrupted()) {
        log.warn("Skipping {} orphan scan due to budget/interrupt", setName);
        return 0;
      }
      // Find all agents in set older than threshold
      Set<Tuple> potentialOrphans = jedis.zrangeByScoreWithScores(setName, 0, cutoffScore);

      if (potentialOrphans.isEmpty()) {
        log.debug("Orphan scan completed: {} set analyzed, 0 orphans found", setName);
        return 0;
      }

      // Optional: remove numeric-only members in WAITING set (repair corruption)
      int numericRemoved = 0;
      if (WAITING_SET.equals(setName)
          && schedulerProperties.getOrphanCleanup().isRemoveNumericWaiting()) {
        for (Tuple tuple : new java.util.ArrayList<>(potentialOrphans)) {
          String name = tuple.getElement();
          if (name != null && name.matches("^\\d+$")) {
            try {
              Object res =
                  scriptManager.evalshaWithSelfHeal(
                      jedis,
                      RedisScriptManager.REMOVE_AGENT,
                      java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                      java.util.Collections.singletonList(name));
              boolean removed = res != null && ((Long) res).intValue() == 1;
              if (removed) {
                numericRemoved++;
                if (metrics != null) {
                  metrics.incrementInvalidMember("waiting_numeric_removed");
                }
              }
            } catch (Exception ignore) {
            }
          }
        }
        if (numericRemoved > 0) {
          log.warn("Removed {} numeric-only waiting members during orphan cleanup", numericRemoved);
        }
      }

      log.warn(
          "Orphan scan completed: {} set analyzed, {} orphans found (older than {}ms) - cleaning up: {}",
          setName,
          potentialOrphans.size(),
          thresholdForLogging,
          potentialOrphans.stream().map(Tuple::getElement).limit(5).toArray());

      // Process orphans with batch operations and fallback
      List<Tuple> orphanList = new ArrayList<>(potentialOrphans);
      return processOrphanBatch(jedis, setName, orphanList, startTs);

    } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
      log.warn("Redis connection error while scanning {} for orphans", setName, e);
      return 0;
    } catch (Exception e) {
      log.error("Error cleaning orphaned agents from {} set", setName, e);
      return 0;
    }
  }

  /**
   * Process orphaned agents with batch operations and built-in fallback to individual cleanup.
   *
   * @param jedis The Jedis connection to the Redis server
   * @param setName The name of the Redis set to clean up
   * @param orphans List of orphaned agents to process
   * @return The number of orphaned agents cleaned up
   */
  private int processOrphanBatch(Jedis jedis, String setName, List<Tuple> orphans, long startTs) {
    if (orphans.isEmpty()) {
      return 0;
    }

    int batchSize = schedulerProperties.getBatchOperations().getBatchSize();
    if (batchSize <= 0) {
      // Simple, non-magic fallback: process up to the current number of candidates.
      batchSize = Math.max(1, orphans.size());
    }
    boolean batchOperationsEnabled = schedulerProperties.getBatchOperations().isEnabled();
    int totalCleaned = 0;

    if (WAITING_SET.equals(setName)) {
      // CRITICAL: Never purge valid waiting by age. Batch-remove only invalid entries.
      if (batchOperationsEnabled) {
        List<String> invalidArgs = new ArrayList<>();
        for (Tuple orphan : orphans) {
          if (overBudget(startTs) || Thread.currentThread().isInterrupted()) {
            log.warn("Aborting waiting-batch build due to budget/interrupt");
            break;
          }
          String agentName = orphan.getElement();
          if (!isAgentStillValid(agentName)) {
            invalidArgs.add(agentName);
            invalidArgs.add(String.valueOf((long) orphan.getScore()));
          }
        }
        if (!invalidArgs.isEmpty()) {
          try {
            Object result =
                scriptManager.evalshaWithSelfHeal(
                    jedis,
                    RedisScriptManager.REMOVE_AGENTS_CONDITIONAL,
                    java.util.Collections.singletonList(WAITING_SET),
                    invalidArgs);
            if (result instanceof java.util.List) {
              java.util.List<?> list = (java.util.List<?>) result;
              if (!list.isEmpty()) {
                totalCleaned += ((Long) list.get(0)).intValue();
              }
            }
          } catch (Exception e) {
            log.warn("Batch removal of invalid waiting agents failed, using individual path", e);
            totalCleaned += cleanupIndividualOrphans(jedis, setName, orphans, startTs);
          }
        }
      } else {
        totalCleaned += cleanupIndividualOrphans(jedis, setName, orphans, startTs);
      }
    } else {
      // CRITICAL: Prefer individual path to allow validity checks and conditional moves, and to
      // skip locally active work.
      for (int i = 0; i < orphans.size(); i += batchSize) {
        if (overBudget(startTs) || Thread.currentThread().isInterrupted()) {
          log.warn("Aborting working-batch processing due to budget/interrupt");
          break;
        }
        int endIndex = Math.min(i + batchSize, orphans.size());
        List<Tuple> batch = orphans.subList(i, endIndex);
        totalCleaned += cleanupIndividualOrphans(jedis, setName, batch, startTs);
      }
    }

    return totalCleaned;
  }

  /**
   * Attempts to acquire leadership for orphan cleanup using Redis distributed lock.
   *
   * @return true if leadership was acquired, false otherwise
   */
  private boolean tryAcquireCleanupLeadership() {
    long leadershipTtlMs = schedulerProperties.getOrphanCleanup().getLeadershipTtlMs();
    int leadershipTtlSeconds = (int) (leadershipTtlMs / 1000);

    try (Jedis jedis = jedisPool.getResource()) {
      // Create a unique instance ID to identify this instance as the leader
      String instanceId = InetAddress.getLocalHost().getHostName() + "::" + UUID.randomUUID();

      // Use Redis SET with NX and EX options for atomic lock acquisition with built-in expiry
      String result =
          jedis.set(
              CLEANUP_LEADER_KEY, instanceId, SetParams.setParams().nx().ex(leadershipTtlSeconds));

      boolean acquired = "OK".equals(result);
      if (acquired) {
        currentLeadershipId = instanceId;
        if (log.isDebugEnabled()) {
          log.debug("Acquired orphan cleanup leadership: {}", instanceId);
        }
      } else {
        log.debug("Failed to acquire orphan cleanup leadership");
      }

      return acquired;

    } catch (Exception e) {
      log.warn("Failed to acquire cleanup leadership", e);
      return false;
    }
  }

  /** Releases leadership for orphaned agent cleanup if this instance is the current leader. */
  private void releaseCleanupLeadership() {
    if (currentLeadershipId == null) {
      return;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      // Only delete the key if we own it (atomic check-and-delete)
      Object result =
          scriptManager.evalshaWithSelfHeal(
              jedis,
              RedisScriptManager.RELEASE_LEADERSHIP,
              java.util.Collections.singletonList(CLEANUP_LEADER_KEY),
              java.util.Collections.singletonList(currentLeadershipId));

      if ("1".equals(result.toString())) {
        if (log.isDebugEnabled()) {
          log.debug("Released orphan cleanup leadership: {}", currentLeadershipId);
        }
      } else {
        if (log.isDebugEnabled()) {
          log.debug("Leadership was already released or expired: {}", currentLeadershipId);
        }
      }

    } catch (Exception e) {
      log.warn("Failed to release cleanup leadership", e);
    } finally {
      currentLeadershipId = null;
    }
  }

  /**
   * Fallback method to clean up orphaned agents individually when batch operations fail or are
   * disabled. Implements dual-processing logic: - Valid agents (still configured) → Move to waiting
   * for rescheduling - Invalid agents (removed accounts) → Remove completely from Redis
   *
   * @param jedis Redis connection
   * @param setName Redis set name (working or waiting)
   * @param orphans List of orphaned agents to clean up
   * @return Number of agents successfully cleaned up
   */
  private int cleanupIndividualOrphans(
      Jedis jedis, String setName, List<Tuple> orphans, long startTs) {
    int cleaned = 0;

    for (Tuple orphan : orphans) {
      if (overBudget(startTs) || Thread.currentThread().isInterrupted()) {
        log.warn("Stopping individual orphan cleanup early due to budget/interrupt");
        break;
      }
      try {
        String agentName = orphan.getElement();
        double score = orphan.getScore();
        String scoreInSet =
            String.valueOf((long) score); // Convert to long to match score() method format

        // Determine if this is a valid agent or an agent for a removed account
        boolean isStillValid = isAgentStillValid(agentName);

        // Shard-aware protection: For waiting entries, only this shard should consider removal.
        // If ownership cannot be determined or belongs to other shard, preserve.
        boolean belongsToThisShard;
        if (acquisitionService == null) {
          // Test environments may not wire acquisitionService. In that case,
          // treat entries as belonging to this shard for consistent cleanup behavior.
          belongsToThisShard = true;
        } else {
          try {
            belongsToThisShard = acquisitionService.belongsToThisShard(agentName);
          } catch (Throwable t) {
            belongsToThisShard = false; // fail-safe preserve
          }
        }

        if (WORKING_SET.equals(setName)) {
          // Skip locally active agents; zombie cleanup manages overruns
          boolean locallyActive =
              acquisitionService != null
                  && acquisitionService.getActiveAgentsMap() != null
                  && acquisitionService.getActiveAgentsMap().containsKey(agentName);
          if (locallyActive) {
            log.debug("Skipping locally active working agent {} during orphan cleanup", agentName);
            continue;
          }

          if (isStillValid) {
            // For valid agents in working (truly orphaned due to crashes), move them to waiting
            // and preserve their original ready time to maintain queue fairness.
            // workingScore = acquire_time + timeout; originalReady = acquire_time
            String preservedScore = null;
            try {
              if (acquisitionService != null) {
                preservedScore =
                    acquisitionService.computeOriginalReadySecondsFromWorkingScore(
                        agentName, scoreInSet);
              }
            } catch (Throwable t) {
              preservedScore = null; // Fail-safe below
            }

            // Fallback to immediate eligibility (now) if preservation is not possible
            String newScore = preservedScore != null ? preservedScore : score(jedis, 0L);
            Object result =
                scriptManager.evalshaWithSelfHeal(
                    jedis,
                    RedisScriptManager.MOVE_AGENTS_CONDITIONAL,
                    java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                    java.util.Arrays.asList(agentName, scoreInSet, newScore));

            if (result != null && "swapped".equals(result)) {
              cleaned++;
              log.info(
                  "Successfully moved orphaned agent {} (original score: {}, new score: {}) from working to waiting set (preserveReady={}).",
                  agentName,
                  (long) score,
                  Long.valueOf(newScore),
                  preservedScore != null);

              // Also clean up local state if needed
              removeActiveAgent(agentName);
            } else {
              log.debug(
                  "Failed to move orphaned agent {} (original score: {}) from working to waiting. It might have been removed or modified by another process.",
                  agentName,
                  (long) score);
            }
          } else {
            // For invalid agents, removal is shard-aware unless explicitly forced for all pods
            boolean forceAllPods = schedulerProperties.getOrphanCleanup().isForceAllPods();

            if (forceAllPods || belongsToThisShard) {
              // Remove invalid agent using individual script
              Object result =
                  scriptManager.evalshaWithSelfHeal(
                      jedis,
                      RedisScriptManager.REMOVE_AGENT,
                      java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                      java.util.Collections.singletonList(agentName));

              // REMOVE_AGENT returns 1 for success
              boolean removed = result != null && ((Long) result).intValue() == 1;
              if (removed) {
                cleaned++;
                if (isStillValid) {
                  log.info(
                      "Successfully removed orphaned agent {} (original score: {}) from {} set.",
                      agentName,
                      (long) score,
                      setName);
                } else {
                  log.info(
                      "Successfully removed invalid orphaned agent {} (original score: {}) from {} set{}.",
                      agentName,
                      (long) score,
                      setName,
                      forceAllPods ? " (forceAllPods)" : "");
                }

                // Also clean up local state if needed
                removeActiveAgent(agentName);
              } else {
                log.debug(
                    "Failed to remove orphaned agent {} (score: {}) from {} set. It might have been removed by another process.",
                    agentName,
                    (long) score,
                    setName);
              }
            } else {
              log.debug(
                  "Preserving invalid working agent {} due to shard gating (belongsToThisShard=false)",
                  agentName);
            }
          }
        } else if (WAITING_SET.equals(setName)) {
          // waiting: Only remove invalid entries; shard gating may be skipped when forceAllPods
          boolean forceAllPods = schedulerProperties.getOrphanCleanup().isForceAllPods();
          // Avoid potentially blocking shard check when forceAllPods is enabled
          boolean removeCandidate = !isStillValid && (forceAllPods || belongsToThisShard);
          if (removeCandidate) {
            Object result =
                scriptManager.evalshaWithSelfHeal(
                    jedis,
                    RedisScriptManager.REMOVE_AGENT,
                    java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                    java.util.Collections.singletonList(agentName));
            boolean removed = result != null && ((Long) result).intValue() == 1;
            if (removed) {
              cleaned++;
              log.info(
                  "Removed invalid waiting agent {} (original score: {})", agentName, (long) score);
            }
          } else {
            log.debug("Preserving valid waiting agent {} (age-based purge disabled)", agentName);
          }
        }

      } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
        log.warn(
            "Redis connection error during orphan cleanup for {} (score {})",
            orphan.getElement(),
            (long) orphan.getScore(),
            e);
      } catch (Exception e) {
        log.error(
            "Error during orphaned agent cleanup attempt for {} (original score: {})",
            orphan.getElement(),
            (long) orphan.getScore(),
            e);
      }
    }

    if (cleaned > 0) {
      log.info("Individually cleaned {} orphaned agents from {}", cleaned, setName);
    }

    return cleaned;
  }

  // Cooperative time-budget guard. Prevents long orphan passes from monopolizing cleanup threads
  // when many entries are present. Network calls inside a single Redis operation still rely on
  // Jedis socket timeouts; this guard stops between operations.
  private boolean overBudget(long startTs) {
    long budgetMs = schedulerProperties.getOrphanCleanup().getRunBudgetMs();
    return budgetMs > 0 && (currentTimeMillis() - startTs) > budgetMs;
  }

  /**
   * Check if an agent is still valid (still registered and enabled). This method determines whether
   * an orphaned agent should be moved back to waiting for rescheduling or completely removed from
   * Redis.
   *
   * @param agentType The agent type to validate
   * @return true if agent is still valid, false if it should be purged
   */
  private boolean isAgentStillValid(String agentType) {
    if (acquisitionService == null) {
      // In absence of local registry, treat entries as invalid (test environments). Production pods
      // always wire acquisitionService; tests should set it explicitly when needed.
      if (log.isDebugEnabled()) {
        log.debug(
            "AgentAcquisitionService not available, treating agent {} as invalid (will be removed)",
            agentType);
      }
      return false;
    }

    // Check if agent is still registered in the local registry
    // If it's registered, it's considered valid (enabled agents are registered)
    Agent registeredAgent = acquisitionService.getRegisteredAgent(agentType);
    if (registeredAgent == null) {
      if (log.isDebugEnabled()) {
        log.debug("Agent {} is not registered locally, treating as invalid", agentType);
      }
      return false;
    }

    if (log.isDebugEnabled()) {
      log.debug("Agent {} is valid (registered locally)", agentType);
    }
    return true;
  }

  /**
   * Generate a Redis score (timestamp) for agent scheduling. Scores are in seconds since epoch to
   * match Redis sorted set format.
   *
   * @param jedis Redis connection
   * @param delayMs Delay in milliseconds before agent should run (0 = immediate)
   * @return Score as string
   */
  private String score(Jedis jedis, long delayMs) {
    try {
      // Prefer a unified time source: scheduler's view of "now" (Redis time + measured offset).
      // This keeps all components (acquisition, cleanup) consistent even across Redis failovers.
      long nowMsWithOffset = 0L;
      if (acquisitionService != null) {
        nowMsWithOffset = acquisitionService.nowMsWithOffset();
      }

      // Fast path: if we have a non-zero unified time, schedule using it.
      // Scores are stored in seconds, so convert ms→s after adding any delay.
      if (nowMsWithOffset > 0L) {
        return String.valueOf((nowMsWithOffset + delayMs) / 1000L);
      }

      // Fallback: query Redis TIME directly (returns [seconds, microseconds]).
      // Convert to ms, add delay, then down-convert to seconds for the ZSET score.
      List<String> time = jedis.time();
      long sec = Long.parseLong(time.get(0));
      long micros = Long.parseLong(time.get(1));
      long targetMs = (sec * 1000) + (micros / 1000) + delayMs;
      return String.valueOf(targetMs / 1000L);
    } catch (Exception ignore) {
      // Last-resort fallback: use local system clock. This is less ideal for coordination,
      // but preserves forward progress if Redis TIME or offset lookups are unavailable.
      long targetMs = currentTimeMillis() + delayMs;
      return String.valueOf(targetMs / 1000L);
    }
  }

  /**
   * Remove an active agent from local tracking and clean up its execution state. This includes
   * canceling futures and releasing semaphore permits.
   *
   * @param agentType The agent to remove
   */
  private void removeActiveAgent(String agentType) {
    if (acquisitionService != null) {
      acquisitionService.removeActiveAgent(agentType);
      log.debug("Removed active agent {} from local tracking", agentType);
    } else {
      log.debug("AgentAcquisitionService not available, cannot remove active agent {}", agentType);
    }
  }
}
