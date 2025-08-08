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

import com.netflix.spinnaker.cats.agent.Agent;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.params.SetParams;

/**
 * Service responsible for detecting and cleaning up orphaned agents.
 *
 * <p>Definitions:
 *
 * <ul>
 *   <li><strong>WAITZ</strong> – Agents ready to run, scored by next execution time (epoch seconds)
 *   <li><strong>WORKZ</strong> – Agents running, scored by completion deadline (acquire_time +
 *       timeout)
 * </ul>
 *
 * <p>Correctness rules (Priority 0):
 *
 * <ul>
 *   <li>Do not purge valid WAITZ entries solely by age. Only remove WAITZ members that are
 *       positively identified as invalid (e.g., not registered/enabled locally). This preserves
 *       FIFO ordering under backlog and prevents queue cycling.
 *   <li>When cleaning WORKZ orphans, skip entries that are locally active on this pod. Local
 *       overruns are handled by the Zombie cleaner; orphan cleanup should not interfere with
 *       currently executing work.
 *   <li>For valid WORKZ orphans (owned by other pods and past deadline + buffer), move back to
 *       WAITZ using a conditional, score-checked move; for invalid ones, remove.
 * </ul>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code redis.scheduler.orphanThresholdMs} – Age threshold for orphaned agents
 *   <li>{@code redis.scheduler.orphanCleanupIntervalMs} – How often cleanup runs
 *   <li>{@code redis.scheduler.orphanCleanupEnabled} – Enable/disable cleanup per pod
 *   <li>{@code redis.scheduler.forceOrphanCleanupAllPods} – Force all pods to clean or use leader
 *       election
 *   <li>{@code redis.scheduler.orphanCleanupBatchSize} – Batch size for efficient cleanup (applies
 *       when safe)
 * </ul>
 */
@Component
public class OrphanCleanupService {
  private static final Logger log = LoggerFactory.getLogger(OrphanCleanupService.class);

  // Redis set names - must match AgentAcquisitionService constants
  private static final String WORKING_SET = "WORKZ";
  private static final String WAITING_SET = "WAITZ";
  private static final String CLEANUP_LEADER_KEY = "CLEANUP_LEADER";

  private final JedisPool jedisPool;
  private final RedisScriptManager scriptManager;
  private final PrioritySchedulerProperties schedulerProperties;
  private final AtomicLong orphansCleanedUp = new AtomicLong(0);

  // Reference to access agent state for orphan identification
  private AgentAcquisitionService acquisitionService;

  // Leadership tracking
  private volatile String currentLeadershipId = null;
  private volatile long lastOrphanCleanup = 0;

  public OrphanCleanupService(
      JedisPool jedisPool,
      RedisScriptManager scriptManager,
      PrioritySchedulerProperties schedulerProperties) {
    this.jedisPool = jedisPool;
    this.scriptManager = scriptManager;
    this.schedulerProperties = schedulerProperties;
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
    if (!schedulerProperties.getOrphanCleanup().isEnabled()) {
      return;
    }

    // Check if enough time has passed since last cleanup
    long currentTime = System.currentTimeMillis();
    long intervalMs = schedulerProperties.getOrphanCleanup().getIntervalMs();
    if (currentTime - lastOrphanCleanup < intervalMs) {
      log.debug(
          "Skipping orphan cleanup - interval not elapsed ({}ms remaining)",
          intervalMs - (currentTime - lastOrphanCleanup));
      return;
    }

    // Use leadership election to prevent multiple instances from running cleanup simultaneously
    boolean forceCleanup = schedulerProperties.getOrphanCleanup().isForceAllPods();
    if (!forceCleanup && !tryAcquireCleanupLeadership()) {
      log.debug("Skipping orphan cleanup - leadership held by another instance");
      return;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      int workzCleaned = cleanupOrphanedAgentsFromSet(jedis, WORKING_SET);
      int waitzCleaned = cleanupOrphanedAgentsFromSet(jedis, WAITING_SET);
      int totalCleaned = workzCleaned + waitzCleaned;

      if (totalCleaned > 0) {
        orphansCleanedUp.addAndGet(totalCleaned);
        log.info(
            "Orphan cleanup completed: {} agents cleaned ({} from WORKZ, {} from WAITZ)",
            totalCleaned,
            workzCleaned,
            waitzCleaned);
      }
      // Update the last cleanup timestamp
      lastOrphanCleanup = System.currentTimeMillis();
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
      int workzCleaned = cleanupOrphanedAgentsFromSet(jedis, WORKING_SET);
      int waitzCleaned = cleanupOrphanedAgentsFromSet(jedis, WAITING_SET);
      int totalCleaned = workzCleaned + waitzCleaned;

      if (totalCleaned > 0) {
        orphansCleanedUp.addAndGet(totalCleaned);
        log.info(
            "Forced orphan cleanup completed: {} agents cleaned ({} from WORKZ, {} from WAITZ)",
            totalCleaned,
            workzCleaned,
            waitzCleaned);
      }
      // Update the last cleanup timestamp
      lastOrphanCleanup = System.currentTimeMillis();
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
    return orphansCleanedUp.get();
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
  private int cleanupOrphanedAgentsFromSet(Jedis jedis, String setName) {
    long cutoffScore;
    long thresholdForLogging;

    if (WAITING_SET.equals(setName)) {
      // For WAITZ: consider orphaned if score < current_time - threshold
      long orphanThreshold = schedulerProperties.getOrphanCleanup().getThresholdMs();
      cutoffScore = (System.currentTimeMillis() - orphanThreshold) / 1000;
      thresholdForLogging = orphanThreshold;
    } else {
      // For WORKZ: agents have score = current_time + agent_timeout (completion deadline)
      // Consider orphaned if: current_time > score + orphan_threshold
      // Rearranging: score < current_time - orphan_threshold
      long orphanThreshold = schedulerProperties.getOrphanCleanup().getThresholdMs();
      cutoffScore = (System.currentTimeMillis() - orphanThreshold) / 1000;
      thresholdForLogging = orphanThreshold;
    }

    try {
      // Find all agents in set older than threshold
      Set<Tuple> potentialOrphans = jedis.zrangeByScoreWithScores(setName, 0, cutoffScore);

      if (potentialOrphans.isEmpty()) {
        log.debug("Orphan scan completed: {} set analyzed, 0 orphans found", setName);
        return 0;
      }

      log.warn(
          "Orphan scan completed: {} set analyzed, {} orphans found (older than {}ms) - cleaning up: {}",
          setName,
          potentialOrphans.size(),
          thresholdForLogging,
          potentialOrphans.stream().map(Tuple::getElement).limit(5).toArray());

      // Process orphans with batch operations and fallback
      List<Tuple> orphanList = new ArrayList<>(potentialOrphans);
      return processOrphanBatch(jedis, setName, orphanList);

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
  private int processOrphanBatch(Jedis jedis, String setName, List<Tuple> orphans) {
    if (orphans.isEmpty()) {
      return 0;
    }

    int batchSize = schedulerProperties.getOrphanBatchSize();
    boolean batchOperationsEnabled = schedulerProperties.isBatchOperationsEnabled();
    int totalCleaned = 0;

    if (WAITING_SET.equals(setName)) {
      // Priority 0: Never purge valid WAITZ by age. Batch-remove only invalid entries.
      if (batchOperationsEnabled) {
        List<String> invalidArgs = new ArrayList<>();
        for (Tuple orphan : orphans) {
          String agentName = orphan.getElement();
          if (!isAgentStillValid(agentName)) {
            invalidArgs.add(agentName);
            invalidArgs.add(String.valueOf((long) orphan.getScore()));
          }
        }
        if (!invalidArgs.isEmpty()) {
          try {
            Object result =
                jedis.evalsha(
                    scriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENTS_CONDITIONAL),
                    java.util.Collections.singletonList(WAITING_SET),
                    invalidArgs);
            if (result instanceof java.util.List) {
              java.util.List<?> list = (java.util.List<?>) result;
              if (!list.isEmpty()) {
                totalCleaned += ((Long) list.get(0)).intValue();
              }
            }
          } catch (Exception e) {
            log.warn(
                "Batch removal of invalid WAITZ agents failed, using individual path: {}",
                e.getMessage());
            totalCleaned += cleanupIndividualOrphans(jedis, setName, orphans);
          }
        }
      } else {
        totalCleaned += cleanupIndividualOrphans(jedis, setName, orphans);
      }
    } else {
      // WORKZ: Prefer individual path to allow validity checks and conditional moves, and to skip
      // locally active work.
      for (int i = 0; i < orphans.size(); i += batchSize) {
        int endIndex = Math.min(i + batchSize, orphans.size());
        List<Tuple> batch = orphans.subList(i, endIndex);
        totalCleaned += cleanupIndividualOrphans(jedis, setName, batch);
      }
    }

    return totalCleaned;
  }

  /**
   * Clean up a single batch of orphaned agents from the specified Redis set.
   *
   * <p>This method executes a Lua script to atomically remove orphaned agents that match both the
   * agent name and score (timestamp) criteria. The script ensures consistency by verifying that
   * agents haven't been updated by other instances since detection.
   *
   * @param jedis Redis connection
   * @param setName Redis set name (WORKZ or WAITZ)
   * @param batch List of orphaned agents with their scores
   * @return Number of agents actually cleaned up
   */
  private int cleanupSingleBatch(Jedis jedis, String setName, List<Tuple> batch) {
    try {
      // Transform agent tuples into flat argument list for Lua script
      // Format: [agent1, score1, agent2, score2, ...] for efficient script processing
      List<String> batchArgs = new ArrayList<>(batch.size() * 2);

      for (Tuple orphan : batch) {
        // Agent name (e.g., "aws-ec2-agent")
        batchArgs.add(orphan.getElement());
        // Agent's last activity timestamp as score (used for verification)
        batchArgs.add(
            String.valueOf(
                (long) orphan.getScore())); // Convert to long to match score() method format
      }

      // Execute atomic Lua script to remove orphaned agents from Redis set
      // Script verifies agent score hasn't changed (prevents race conditions)
      // and removes only agents that are still orphaned at the same timestamp
      Object result =
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENTS_CONDITIONAL),
              java.util.Collections.singletonList(setName), // Redis set key (WORKZ/WAITZ)
              batchArgs); // Flattened [name, score, name, score, ...] arguments

      // Parse Lua script response: [numRemoved, [removedAgent1, removedAgent2, ...]]
      // Script returns both count and list for verification and logging
      if (result instanceof List) {
        List<Object> resultList = (List<Object>) result;
        // Validate expected Lua return format: [count, agent_list]
        if (resultList.size() >= 2) {
          // First element: actual number of agents removed from Redis
          int cleaned = ((Long) resultList.get(0)).intValue();
          // Second element: list of agent names that were successfully removed
          List<String> removedAgents = (List<String>) resultList.get(1);

          if (cleaned > 0) {
            log.info("Cleaned {} orphaned agents from {}: {}", cleaned, setName, removedAgents);
          }

          return cleaned;
        } else {
          log.warn(
              "Unexpected Lua script result format from {}: expected [count, list], got: {}",
              setName,
              result);
        }
      } else {
        log.warn(
            "Unexpected Lua script result type from {}: expected List, got: {}",
            setName,
            result != null ? result.getClass().getSimpleName() : "null");
      }

      return 0;

    } catch (Exception e) {
      log.error("Error cleaning orphan batch from {}", setName, e);
      return 0;
    }
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
        log.debug("Acquired orphan cleanup leadership: {}", instanceId);
      } else {
        log.debug("Failed to acquire orphan cleanup leadership");
      }

      return acquired;

    } catch (Exception e) {
      log.warn("Failed to acquire cleanup leadership: {}", e.getMessage());
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
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.RELEASE_LEADERSHIP),
              java.util.Collections.singletonList(CLEANUP_LEADER_KEY),
              java.util.Collections.singletonList(currentLeadershipId));

      if ("1".equals(result.toString())) {
        log.debug("Released orphan cleanup leadership: {}", currentLeadershipId);
      } else {
        log.debug("Leadership was already released or expired: {}", currentLeadershipId);
      }

    } catch (Exception e) {
      log.warn("Failed to release cleanup leadership: {}", e.getMessage());
    } finally {
      currentLeadershipId = null;
    }
  }

  /**
   * Fallback method to clean up orphaned agents individually when batch operations fail or are
   * disabled. Implements dual-processing logic: - Valid agents (still configured) → Move to WAITZ
   * for rescheduling - Invalid agents (removed accounts) → Remove completely from Redis
   *
   * @param jedis Redis connection
   * @param setName Redis set name (WORKZ or WAITZ)
   * @param orphans List of orphaned agents to clean up
   * @return Number of agents successfully cleaned up
   */
  private int cleanupIndividualOrphans(Jedis jedis, String setName, List<Tuple> orphans) {
    int cleaned = 0;

    for (Tuple orphan : orphans) {
      try {
        String agentName = orphan.getElement();
        double score = orphan.getScore();
        String scoreInSet =
            String.valueOf((long) score); // Convert to long to match score() method format

        // Determine if this is a valid agent or an agent for a removed account
        boolean isStillValid = isAgentStillValid(agentName);

        // Shard-aware protection: For WAITZ entries, only this shard should consider removal.
        // If ownership cannot be determined or belongs to other shard, preserve.
        boolean belongsToThisShard;
        if (acquisitionService == null) {
          // Test environments (and legacy callers) may not wire acquisitionService. In that case,
          // treat entries as belonging to this shard so cleanup behavior matches previous default
          // (both sets cleaned when agents are considered invalid).
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
            log.debug("Skipping locally active WORKZ agent {} during orphan cleanup", agentName);
            continue;
          }

          if (isStillValid) {
            // For valid agents in WORKZ (truly orphaned due to crashes), move them to WAITZ for
            // immediate rescheduling
            String newScore = score(jedis, 0L); // Schedule for immediate execution
            Object result =
                jedis.evalsha(
                    scriptManager.getScriptSha(RedisScriptManager.MOVE_AGENTS_CONDITIONAL),
                    java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                    java.util.Arrays.asList(
                        agentName,
                        scoreInSet, // Expected score in WORKING set (ARGV[2])
                        newScore // New score in WAITING set (ARGV[3])
                        ));

            if (result != null && "swapped".equals(result)) {
              cleaned++;
              log.info(
                  "Successfully moved orphaned agent {} (original score: {}, new score: {}) from WORKZ to WAITZ set.",
                  agentName,
                  (long) score,
                  Long.valueOf(newScore));

              // Also clean up local state if needed
              removeActiveAgent(agentName);
            } else {
              log.debug(
                  "Failed to move orphaned agent {} (original score: {}) from WORKZ to WAITZ. It might have been removed or modified by another process.",
                  agentName,
                  (long) score);
            }
          } else {
            // For invalid agents or agents in WAITZ, completely remove them using individual script
            Object result =
                jedis.evalsha(
                    scriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENT),
                    java.util.Arrays.asList(
                        "WORKZ", "WAITZ"), // Both sets for unconditional removal
                    java.util.Collections.singletonList(agentName)); // Only agent name needed

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
                    "Successfully removed invalid orphaned agent {} (original score: {}) from {} set and local registry.",
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
          }
        } else if (WAITING_SET.equals(setName)) {
          // WAITZ: Only remove invalid entries for this shard; preserve others regardless of age
          if (!isStillValid && belongsToThisShard) {
            Object result =
                jedis.evalsha(
                    scriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENT),
                    java.util.Arrays.asList("WORKZ", "WAITZ"),
                    java.util.Collections.singletonList(agentName));
            boolean removed = result != null && ((Long) result).intValue() == 1;
            if (removed) {
              cleaned++;
              log.info(
                  "Removed invalid WAITZ agent {} (original score: {})", agentName, (long) score);
            }
          } else {
            log.debug("Preserving valid WAITZ agent {} (age-based purge disabled)", agentName);
          }
        }

      } catch (Exception e) {
        log.error(
            "Error during orphaned agent cleanup attempt for {} (original score: {}): {}",
            orphan.getElement(),
            (long) orphan.getScore(),
            e.getMessage(),
            e);
      }
    }

    if (cleaned > 0) {
      log.info("Individually cleaned {} orphaned agents from {}", cleaned, setName);
    }

    return cleaned;
  }

  /**
   * Check if an agent is still valid (still registered and enabled). This method determines whether
   * an orphaned agent should be moved back to WAITZ for rescheduling or completely removed from
   * Redis.
   *
   * @param agentType The agent type to validate
   * @return true if agent is still valid, false if it should be purged
   */
  private boolean isAgentStillValid(String agentType) {
    if (acquisitionService == null) {
      // In absence of local registry, treat entries as invalid (test environments). Production pods
      // always wire acquisitionService; tests should set it explicitly when needed.
      log.debug(
          "AgentAcquisitionService not available, treating agent {} as invalid (will be removed)",
          agentType);
      return false;
    }

    // Check if agent is still registered in the local registry
    // If it's registered, it's considered valid (enabled agents are registered)
    Agent registeredAgent = acquisitionService.getRegisteredAgent(agentType);
    if (registeredAgent == null) {
      log.debug("Agent {} is not registered locally, treating as invalid", agentType);
      return false;
    }

    log.debug("Agent {} is valid (registered locally)", agentType);
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
      // Use Redis TIME for distributed coordination
      List<String> time = jedis.time();
      long redisTimeSeconds = Long.parseLong(time.get(0));
      long redisTimeMicros = Long.parseLong(time.get(1));

      // Convert to milliseconds and add delay
      long targetTimeMs = (redisTimeSeconds * 1000) + (redisTimeMicros / 1000) + delayMs;

      // Convert back to seconds for Redis score
      return String.valueOf(targetTimeMs / 1000);
    } catch (Exception e) {
      log.warn("Failed to get Redis time, using system time: {}", e.getMessage());
      // Fallback to system time
      long targetTimeMs = System.currentTimeMillis() + delayMs;
      return String.valueOf(targetTimeMs / 1000);
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
