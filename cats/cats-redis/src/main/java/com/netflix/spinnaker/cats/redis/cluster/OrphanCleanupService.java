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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Tuple;

/**
 * Service responsible for detecting and cleaning up orphaned agents.
 *
 * <p>Orphaned agents are agents that remain in Redis sets but are no longer known to any
 * clouddriver instance. This can happen due to deployment changes, pod restarts, or configuration
 * updates. This service identifies such agents and removes them to prevent resource leaks and stale
 * state.
 *
 * <p>The cleanup process handles two Redis sets:
 *
 * <ul>
 *   <li><strong>WORKING set (WORKZ):</strong> Agents currently being executed
 *   <li><strong>WAITING set (WAITZ):</strong> Agents waiting to be executed
 * </ul>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code redis.scheduler.orphanThresholdMs} - Age threshold for orphaned agents
 *   <li>{@code redis.scheduler.orphanCleanupIntervalMs} - How often cleanup runs
 *   <li>{@code redis.scheduler.orphanCleanupEnabled} - Enable/disable cleanup per pod
 *   <li>{@code redis.scheduler.forceOrphanCleanupAllPods} - Force all pods to clean or use leader
 *       election
 *   <li>{@code redis.scheduler.orphanCleanupBatchSize} - Batch size for efficient cleanup
 * </ul>
 */
@Component
public class OrphanCleanupService {
  private static final Logger log = LoggerFactory.getLogger(OrphanCleanupService.class);

  private static final String WORKING_SET = "WORKZ";
  private static final String WAITING_SET = "WAITZ";

  private final JedisPool jedisPool;
  private final RedisScriptManager scriptManager;
  private final ClusteredSortSchedulerProperties schedulerProperties;

  // Tracking metrics
  private final AtomicLong orphansCleanedUp = new AtomicLong(0);
  private volatile long lastOrphanCleanup = 0;

  public OrphanCleanupService(
      JedisPool jedisPool,
      RedisScriptManager scriptManager,
      ClusteredSortSchedulerProperties schedulerProperties) {
    this.jedisPool = jedisPool;
    this.scriptManager = scriptManager;
    this.schedulerProperties = schedulerProperties;
  }

  /**
   * Clean up orphaned agents if the cleanup interval has elapsed and this pod should perform
   * cleanup. This method is called periodically by the main scheduler.
   */
  public void cleanupOrphanedAgentsIfNeeded() {
    if (!schedulerProperties.getOrphanCleanup().isEnabled()) {
      return;
    }

    long now = System.currentTimeMillis();
    long orphanCleanupInterval = schedulerProperties.getOrphanCleanup().getIntervalMs();

    if (now - lastOrphanCleanup >= orphanCleanupInterval) {
      int cleaned = cleanupOrphanedAgents();
      lastOrphanCleanup = now;

      if (cleaned > 0) {
        log.info("Orphan cleanup completed: {} agents cleaned up", cleaned);
      }
    }
  }

  /**
   * Comprehensive cleanup of orphaned agents from both WORKING and WAITING sets.
   *
   * <p>This method identifies agents that are no longer known to any clouddriver instance and
   * removes them from Redis. It handles both sets with appropriate safety thresholds.
   *
   * @return Total number of orphaned agents cleaned up
   */
  public int cleanupOrphanedAgents() {
    long orphanCleanupStartTime = System.currentTimeMillis();
    log.info(
        "Starting comprehensive orphaned agent cleanup process for both WORKING and WAITING sets");

    int totalAgentsCleaned = 0;

    try (Jedis jedis = jedisPool.getResource()) {
      // First, clean up orphaned agents from WORKING set
      int workingCleaned = cleanupOrphanedAgentsFromWorkingSet(jedis);
      totalAgentsCleaned += workingCleaned;

      // Next, clean up orphaned agents from WAITING set
      int waitingCleaned = cleanupOrphanedAgentsFromWaitingSet(jedis);
      totalAgentsCleaned += waitingCleaned;

      long elapsedMs = System.currentTimeMillis() - orphanCleanupStartTime;
      log.info(
          "Comprehensive orphaned agent cleanup completed in {}ms: {} agents cleaned from WORKING, {} from WAITING, {} total",
          elapsedMs,
          workingCleaned,
          waitingCleaned,
          totalAgentsCleaned);

      orphansCleanedUp.addAndGet(totalAgentsCleaned);
      return totalAgentsCleaned;

    } catch (Exception e) {
      long elapsedMs = System.currentTimeMillis() - orphanCleanupStartTime;
      log.error("Error during orphaned agent cleanup after {}ms", elapsedMs, e);
      return totalAgentsCleaned;
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

  private int cleanupOrphanedAgentsFromWorkingSet(Jedis jedis) {
    long orphanThreshold = schedulerProperties.getOrphanCleanup().getThresholdMs();
    long cutoffScore = System.currentTimeMillis() - orphanThreshold;

    log.debug(
        "Cleaning orphaned agents from WORKING set with threshold {}ms (cutoff: {})",
        orphanThreshold,
        cutoffScore);

    try {
      // Find all agents in WORKING set older than threshold
      Set<Tuple> potentialOrphans = jedis.zrangeByScoreWithScores(WORKING_SET, 0, cutoffScore);

      if (potentialOrphans.isEmpty()) {
        log.debug("No orphaned agents found in WORKING set");
        return 0;
      }

      log.info(
          "Found {} potential orphaned agents in WORKING set older than {}ms",
          potentialOrphans.size(),
          orphanThreshold);

      // Clean up orphans in batches
      return cleanupOrphanBatch(jedis, WORKING_SET, new ArrayList<>(potentialOrphans));

    } catch (Exception e) {
      log.error("Error cleaning orphaned agents from WORKING set", e);
      return 0;
    }
  }

  private int cleanupOrphanedAgentsFromWaitingSet(Jedis jedis) {
    // Use longer threshold for WAITING set as these are legitimate pending work
    long orphanThreshold = schedulerProperties.getOrphanCleanup().getThresholdMs() * 2;
    long cutoffScore = System.currentTimeMillis() - orphanThreshold;

    log.debug(
        "Cleaning orphaned agents from WAITING set with threshold {}ms (cutoff: {})",
        orphanThreshold,
        cutoffScore);

    try {
      // Find all agents in WAITING set older than threshold
      Set<Tuple> potentialOrphans = jedis.zrangeByScoreWithScores(WAITING_SET, 0, cutoffScore);

      if (potentialOrphans.isEmpty()) {
        log.debug("No orphaned agents found in WAITING set");
        return 0;
      }

      log.info(
          "Found {} potential orphaned agents in WAITING set older than {}ms",
          potentialOrphans.size(),
          orphanThreshold);

      // Clean up orphans in batches
      return cleanupOrphanBatch(jedis, WAITING_SET, new ArrayList<>(potentialOrphans));

    } catch (Exception e) {
      log.error("Error cleaning orphaned agents from WAITING set", e);
      return 0;
    }
  }

  private int cleanupOrphanBatch(Jedis jedis, String setName, List<Tuple> orphans) {
    if (orphans.isEmpty()) {
      return 0;
    }

    int batchSize = schedulerProperties.getOrphanCleanup().getBatchSize();
    int totalCleaned = 0;

    // Process orphans in batches
    for (int i = 0; i < orphans.size(); i += batchSize) {
      int endIndex = Math.min(i + batchSize, orphans.size());
      List<Tuple> batch = orphans.subList(i, endIndex);

      totalCleaned += cleanupSingleBatch(jedis, setName, batch);
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
        batchArgs.add(String.valueOf(orphan.getScore()));
      }

      // Execute atomic Lua script to remove orphaned agents from Redis set
      // Script verifies agent score hasn't changed (prevents race conditions)
      // and removes only agents that are still orphaned at the same timestamp
      Object result =
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.BATCH_ORPHAN_REMOVE_SCRIPT),
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
}
