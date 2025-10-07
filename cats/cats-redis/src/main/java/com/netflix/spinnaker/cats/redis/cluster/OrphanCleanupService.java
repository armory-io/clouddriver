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

import static com.netflix.spinnaker.cats.redis.cluster.support.CadenceGuard.*;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.redis.cluster.support.ScriptResults;
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
    long start = nowMs();
    if (!schedulerProperties.getOrphanCleanup().isEnabled()) {
      return;
    }

    // Guard against long-running loops: if previous pass is still considered running for too long
    // (e.g., due to a bug), skip starting another pass to avoid monopolizing cleanup leadership.
    long maxPassDurationMs =
        Math.max(1_000L, schedulerProperties.getOrphanCleanup().getRunBudgetMs());
    if (lastOrphanCleanup > 0 && maxPassDurationMs > 0) {
      long sinceLast = nowMs() - lastOrphanCleanup;
      // If we haven't updated lastOrphanCleanup for > runBudgetMs, assume the previous pass hung
      long threshold = maxPassDurationMs;
      if (sinceLast > threshold) {
        log.warn(
            "Skipping orphan cleanup: previous pass appears hung ({}ms since last update > budget {}ms). Releasing leadership defensively.",
            sinceLast,
            maxPassDurationMs);
        try {
          releaseCleanupLeadership();
        } catch (Exception ignore) {
        }
        // Bump the timestamp to avoid log spam; next cycle will attempt again
        lastOrphanCleanup = nowMs();
        return;
      }
    }

    // Check if enough time has passed since last cleanup
    long intervalMs = schedulerProperties.getOrphanCleanup().getIntervalMs();
    if (!isPeriodElapsed(lastOrphanCleanup, intervalMs)) {
      long remaining = intervalMs - (nowMs() - lastOrphanCleanup);
      log.debug("Skipping orphan cleanup - interval not elapsed ({}ms remaining)", remaining);
      return;
    }

    // Leadership: forceAllPods only skips election; it does NOT bypass shard gating anywhere.
    // Use leadership election to prevent multiple instances from running cleanup simultaneously
    boolean forceCleanup = schedulerProperties.getOrphanCleanup().isForceAllPods();
    if (!forceCleanup && !tryAcquireCleanupLeadership()) {
      log.debug("Skipping orphan cleanup - leadership held by another instance");
      return;
    }

    // Update timestamp immediately after acquiring leadership (or confirming forced run)
    lastOrphanCleanup = nowMs();

    try (Jedis jedis = jedisPool.getResource()) {
      final long budgetMs = schedulerProperties.getOrphanCleanup().getRunBudgetMs();

      int workingCleaned = cleanupOrphanedAgentsFromSet(jedis, WORKING_SET, start, budgetMs);
      if (overBudget(start, budgetMs)) {
        log.warn("Orphan cleanup budget exceeded after working set; skipping waiting set");
        return;
      }
      int waitingCleaned = cleanupOrphanedAgentsFromSet(jedis, WAITING_SET, start, budgetMs);
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
        metrics.recordCleanupTime("orphan", nowMs() - start);
        metrics.incrementCleanupCleaned("orphan", totalCleaned);
      }
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
      long start = nowMs();
      final long budgetMs = schedulerProperties.getOrphanCleanup().getRunBudgetMs();
      int workingCleaned = cleanupOrphanedAgentsFromSet(jedis, WORKING_SET, start, budgetMs);
      int waitingCleaned = cleanupOrphanedAgentsFromSet(jedis, WAITING_SET, start, budgetMs);
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
      lastOrphanCleanup = nowMs();
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
   * @param startEpochMs Epoch time when cleanup operation started (for budget checking)
   * @param budgetMs Maximum runtime budget in milliseconds (0 = disabled)
   * @return The number of orphaned agents cleaned up
   */
  private int cleanupOrphanedAgentsFromSet(
      Jedis jedis, String setName, long startEpochMs, long budgetMs) {
    long cutoffScore;
    long thresholdForLogging;

    if (WAITING_SET.equals(setName)) {
      // Waiting set: score = next execution time
      // Orphan detection: score < (current_time - threshold)
      // These are agents scheduled far in the past that never executed
      long orphanThreshold = schedulerProperties.getOrphanCleanup().getThresholdMs();
      cutoffScore = (nowMs() - orphanThreshold) / 1000;
      thresholdForLogging = orphanThreshold;
    } else {
      // Working set: score = completion deadline (acquire_time + timeout)
      // Orphan detection: current_time > (score + threshold)
      // Rearranged: score < (current_time - threshold)
      // These are agents that should have completed but their pod might have crashed
      long orphanThreshold = schedulerProperties.getOrphanCleanup().getThresholdMs();
      cutoffScore = (nowMs() - orphanThreshold) / 1000;
      thresholdForLogging = orphanThreshold;
    }

    try {
      int totalCleaned = 0;
      while (true) {
        if (Thread.currentThread().isInterrupted()) {
          log.warn("Stopping {} orphan scan due to interrupt", setName);
          break;
        }
        if (overBudget(startEpochMs, budgetMs)) {
          log.warn("Stopping {} orphan scan due to budget deadline", setName);
          break;
        }

        Set<Tuple> potentialOrphans = jedis.zrangeByScoreWithScores(setName, 0, cutoffScore);
        if (potentialOrphans.isEmpty()) {
          if (log.isDebugEnabled()) {
            log.debug("Orphan scan: {} set analyzed, 0 candidates found", setName);
          }
          break;
        }

        // Optional: remove numeric-only members in WAITING set (repair corruption)
        int numericRemoved = 0;
        if (WAITING_SET.equals(setName)
            && schedulerProperties.getOrphanCleanup().isRemoveNumericOnlyAgents()) {
          for (Tuple tuple : new java.util.ArrayList<>(potentialOrphans)) {
            String name = tuple.getElement();
            if (name != null && name.matches("^\\d{9,11}$")) {
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
            log.warn(
                "Removed {} numeric-only waiting members during orphan cleanup", numericRemoved);
          }
        }

        if (log.isWarnEnabled()) {
          log.warn(
              "Orphan scan: {} set analyzed, {} candidates (older than {}ms) - processing",
              setName,
              potentialOrphans.size(),
              thresholdForLogging);
        }

        List<Tuple> orphanList = new ArrayList<>(potentialOrphans);
        int cleanedThisPass =
            processOrphanBatch(jedis, setName, orphanList, startEpochMs, budgetMs);
        totalCleaned += cleanedThisPass;

        // Break when no progress was made to avoid infinite loops on valid-only candidates
        if (cleanedThisPass == 0) {
          log.warn(
              "Orphan scan: {} set made no progress this pass; exiting early before budget is exhausted",
              setName);
          break;
        }
      }

      return totalCleaned;

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
   * @param startEpochMs Epoch time when cleanup operation started (for budget checking)
   * @param budgetMs Maximum runtime budget in milliseconds (0 = disabled)
   * @return The number of orphaned agents cleaned up
   */
  private int processOrphanBatch(
      Jedis jedis, String setName, List<Tuple> orphans, long startEpochMs, long budgetMs) {
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
      // Critical: Never purge valid waiting by age. Batch-remove only invalid entries.
      if (batchOperationsEnabled) {
        List<String> invalidArgs = new ArrayList<>();
        // Also track which invalid agents we attempted to remove in this batch
        List<String> attemptedInvalid = new ArrayList<>();
        for (Tuple orphan : orphans) {
          if (Thread.currentThread().isInterrupted()) {
            log.warn("Aborting waiting-batch build due to interrupt");
            break;
          }
          if (overBudget(startEpochMs, budgetMs)) {
            log.warn("Aborting waiting-batch build due to budget deadline");
            break;
          }
          String agentName = orphan.getElement();
          if (!isAgentStillValid(agentName)) {
            // Shard-aware gating: Only remove invalid entries owned by this shard
            boolean belongsToThisShard;
            if (acquisitionService == null) {
              belongsToThisShard = true;
            } else {
              try {
                belongsToThisShard = acquisitionService.belongsToThisShard(agentName);
              } catch (Exception e) {
                belongsToThisShard = false; // fail-safe preserve
              }
            }

            if (belongsToThisShard) {
              invalidArgs.add(agentName);
              invalidArgs.add(String.valueOf((long) orphan.getScore()));
              attemptedInvalid.add(agentName);
            }
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
            ScriptResults.BatchRemovalResult parsed =
                ScriptResults.parseRemoveAgentsConditional(result);
            totalCleaned += parsed.getRemovedCount();
            // Per-item fallback for any attempted invalid entries not removed by batch (partial
            // success)
            if (parsed.getRemovedCount() < attemptedInvalid.size()) {
              java.util.Set<String> removedSet = new java.util.HashSet<>(parsed.getMembers());
              for (String agentName : attemptedInvalid) {
                if (Thread.currentThread().isInterrupted()) {
                  log.warn("Stopping per-item fallback due to interrupt");
                  break;
                }
                if (overBudget(startEpochMs, budgetMs)) {
                  log.warn("Stopping per-item fallback due to budget deadline");
                  break;
                }
                if (!removedSet.contains(agentName)) {
                  String scoreStr;
                  try {
                    Double s = jedis.zscore(WAITING_SET, agentName);
                    scoreStr = s != null ? String.valueOf(s.longValue()) : null;
                  } catch (Exception ignore) {
                    scoreStr = null;
                  }
                  try {
                    if (scoreStr != null) {
                      Object one =
                          scriptManager.evalshaWithSelfHeal(
                              jedis,
                              RedisScriptManager.REMOVE_AGENTS_CONDITIONAL,
                              java.util.Collections.singletonList(WAITING_SET),
                              java.util.Arrays.asList(agentName, scoreStr));
                      ScriptResults.BatchRemovalResult oneParsed =
                          ScriptResults.parseRemoveAgentsConditional(one);
                      totalCleaned += oneParsed.getRemovedCount();
                      if (oneParsed.getRemovedCount() == 0) {
                        Object fallback =
                            scriptManager.evalshaWithSelfHeal(
                                jedis,
                                RedisScriptManager.REMOVE_AGENT,
                                java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                                java.util.Collections.singletonList(agentName));
                        if (fallback != null && ((Long) fallback).intValue() == 1) {
                          totalCleaned += 1;
                        }
                      }
                    }
                  } catch (Exception ex) {
                    log.debug("Per-item fallback removal failed for {}: {}", agentName, ex);
                  }
                }
              }
            }
          } catch (Exception e) {
            log.warn("Batch removal of invalid waiting agents failed, using individual path", e);
            // Batch-first per-item: try conditional remove one-by-one, then fallback to
            // REMOVE_AGENT
            for (Tuple orphan : orphans) {
              if (Thread.currentThread().isInterrupted()) {
                log.warn("Stopping individual conditional removal due to interrupt");
                break;
              }
              if (overBudget(startEpochMs, budgetMs)) {
                log.warn("Stopping individual conditional removal due to budget deadline");
                break;
              }
              String agentName = orphan.getElement();
              String scoreStr = String.valueOf((long) orphan.getScore());
              try {
                Object one =
                    scriptManager.evalshaWithSelfHeal(
                        jedis,
                        RedisScriptManager.REMOVE_AGENTS_CONDITIONAL,
                        java.util.Collections.singletonList(WAITING_SET),
                        java.util.Arrays.asList(agentName, scoreStr));
                ScriptResults.BatchRemovalResult oneParsed =
                    ScriptResults.parseRemoveAgentsConditional(one);
                totalCleaned += oneParsed.getRemovedCount();
                if (oneParsed.getRemovedCount() == 0) {
                  Object fallback =
                      scriptManager.evalshaWithSelfHeal(
                          jedis,
                          RedisScriptManager.REMOVE_AGENT,
                          java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                          java.util.Collections.singletonList(agentName));
                  if (fallback != null && ((Long) fallback).intValue() == 1) {
                    totalCleaned += 1;
                  }
                }
              } catch (Exception ex) {
                log.debug("Individual conditional removal failed for {}: {}", agentName, ex);
              }
            }
          }
        }
      } else {
        totalCleaned += cleanupIndividualOrphans(jedis, setName, orphans, startEpochMs, budgetMs);
      }
    } else {
      // Critical: Prefer individual path to allow validity checks and conditional moves, and to
      // skip locally active work.
      for (int i = 0; i < orphans.size(); i += batchSize) {
        if (Thread.currentThread().isInterrupted()) {
          log.warn("Aborting working-batch processing due to interrupt");
          break;
        }
        if (overBudget(startEpochMs, budgetMs)) {
          log.warn("Aborting working-batch processing due to budget deadline");
          break;
        }
        int endIndex = Math.min(i + batchSize, orphans.size());
        List<Tuple> batch = orphans.subList(i, endIndex);
        totalCleaned += cleanupIndividualOrphans(jedis, setName, batch, startEpochMs, budgetMs);
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
   * @param startEpochMs Epoch time when cleanup operation started (for budget checking)
   * @param budgetMs Maximum runtime budget in milliseconds (0 = disabled)
   * @return Number of agents successfully cleaned up
   */
  private int cleanupIndividualOrphans(
      Jedis jedis, String setName, List<Tuple> orphans, long startEpochMs, long budgetMs) {
    int cleaned = 0;

    for (Tuple orphan : orphans) {
      if (Thread.currentThread().isInterrupted()) {
        log.warn("Stopping individual orphan cleanup early due to interrupt");
        break;
      }
      if (overBudget(startEpochMs, budgetMs)) {
        log.warn("Stopping individual orphan cleanup early due to budget deadline");
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
        // Fail-safe shard gating: false on unexpected errors to avoid cross-shard deletions;
        // when acquisitionService is not wired (tests), default to true for consistent behavior.
        boolean belongsToThisShard = safeBelongsToShard(agentName);

        if (WORKING_SET.equals(setName)) {
          // Skip locally active agents; zombie cleanup manages overruns
          // Defensive: avoid double map access that could race to null; read once and check.
          java.util.Map<String, String> activeMap =
              acquisitionService != null ? acquisitionService.getActiveAgentsMap() : null;
          boolean locallyActive = activeMap != null && activeMap.containsKey(agentName);
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
            } catch (Exception e) {
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
            // For invalid agents, removal is shard-aware
            if (belongsToThisShard) {
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
                      "Successfully removed invalid orphaned agent {} (original score: {}) from {} set.",
                      agentName,
                      (long) score,
                      setName);
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
          // waiting: Only remove invalid entries; always respect shard gating
          boolean removeCandidate = !isStillValid && belongsToThisShard;
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

  /**
   * Determine shard ownership for the given agent in a fail-safe way.
   *
   * <p>Behavior:
   *
   * <ul>
   *   <li>Uses {@code acquisitionService.belongsToThisShard(agentName)} when available.
   *   <li>Returns {@code false} on any unexpected error to preserve entries (avoid cross-shard
   *       delete).
   *   <li>Returns {@code true} when {@code acquisitionService} is not wired (e.g., in tests) to
   *       maintain consistent behavior without blocking cleanup flows.
   * </ul>
   *
   * @param agentName agent identifier used for shard ownership check
   * @return true if this shard should act on the agent, false otherwise
   */
  private boolean safeBelongsToShard(String agentName) {
    if (acquisitionService == null) {
      // In tests or when not wired, preserve entries by default in waiting; for working we gate
      // elsewhere.
      return true;
    }
    try {
      return acquisitionService.belongsToThisShard(agentName);
    } catch (Exception e) {
      return false;
    }
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
    java.util.function.LongSupplier supplier =
        acquisitionService != null ? () -> acquisitionService.nowMsWithOffset() : null;
    return com.netflix.spinnaker.cats.redis.cluster.support.RedisTimeUtils.scoreFromMsDelay(
        jedis, delayMs, supplier);
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
