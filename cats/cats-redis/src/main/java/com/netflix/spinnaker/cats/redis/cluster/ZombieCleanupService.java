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
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Tuple;

/**
 * Service responsible for detecting and cleaning up zombie agents.
 *
 * <p>Zombie agents are agents that have been running for longer than the configured threshold,
 * indicating they may be stuck or the process that was executing them has died. This service
 * identifies such agents and cleans them up to prevent resource leaks and allow them to be
 * rescheduled.
 *
 * <p>The cleanup process:
 *
 * <ul>
 *   <li>Scans the Redis WORKING set for agents older than zombieThresholdMs
 *   <li>Uses batch removal scripts for efficient cleanup of multiple zombies
 *   <li>Cancels any local Future references to zombie executions
 *   <li>Updates metrics and logs for monitoring
 * </ul>
 */
@Component
public class ZombieCleanupService {
  private static final Logger log = LoggerFactory.getLogger(ZombieCleanupService.class);

  private static final String WORKING_SET = "WORKZ";

  private final JedisPool jedisPool;
  private final RedisScriptManager scriptManager;
  private final ClusteredSortSchedulerProperties schedulerProperties;

  // Tracking for zombie cleanup
  private final AtomicLong zombiesCleanedUp = new AtomicLong(0);
  private volatile long lastZombieCleanup = 0;

  public ZombieCleanupService(
      JedisPool jedisPool,
      RedisScriptManager scriptManager,
      ClusteredSortSchedulerProperties schedulerProperties) {
    this.jedisPool = jedisPool;
    this.scriptManager = scriptManager;
    this.schedulerProperties = schedulerProperties;
  }

  /**
   * Clean up zombie agents if the cleanup interval has elapsed. This method is called periodically
   * by the main scheduler.
   */
  public void cleanupZombieAgentsIfNeeded(
      Map<String, String> activeAgents, Map<String, Future<?>> activeAgentsFutures) {
    long now = System.currentTimeMillis();
    long zombieCleanupInterval = schedulerProperties.getZombieCleanup().getCleanupIntervalMs();

    if (now - lastZombieCleanup >= zombieCleanupInterval) {
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
   * This can happen due to rate limiting, excessive processing time, or other performance issues.
   * Unlike orphaned agents (which have no running instance), zombies are still executing but have
   * exceeded their expected runtime.
   *
   * <p>This cleanup mechanism is important for preventing resource exhaustion on the local
   * instance. It works by checking the local activeAgents map for agents that have been running
   * longer than a configurable threshold.
   *
   * @param activeAgents Map of active agents (agentType -> acquireScore)
   * @param activeAgentsFutures Map of agent futures for cancellation
   * @return Number of zombie agents cleaned up
   */
  public int cleanupZombieAgents(
      Map<String, String> activeAgents, Map<String, Future<?>> activeAgentsFutures) {
    long zombieThreshold = schedulerProperties.getZombieCleanup().getThresholdMs();
    long currentTime = System.currentTimeMillis();
    List<String> zombieAgentTypes = new ArrayList<>();

    // Find zombie agents by checking local tracking for agents that have been running too long
    // This is the correct zombie detection logic - scan LOCAL activeAgents, not Redis
    log.debug(
        "Checking {} local active agents for zombies with threshold {}ms",
        activeAgents.size(),
        zombieThreshold);

    for (Map.Entry<String, String> entry : activeAgents.entrySet()) {
      String agentType = entry.getKey();
      String acquireScore = entry.getValue();

      try {
        // Convert acquire score (seconds) back to milliseconds to compare with current time
        long startTimeMs = Long.parseLong(acquireScore) * 1000;
        long runTime = currentTime - startTimeMs;

        log.debug(
            "Agent {}: acquireScore={}, startTime={}ms, runtime={}ms, threshold={}ms",
            agentType,
            acquireScore,
            startTimeMs,
            runTime,
            zombieThreshold);

        if (runTime > zombieThreshold) {
          zombieAgentTypes.add(agentType);
          log.debug(
              "Agent {} identified as zombie (runtime {} > threshold {})",
              agentType,
              runTime,
              zombieThreshold);
        }
      } catch (NumberFormatException e) {
        log.warn("Invalid acquire score for agent {}: {}", agentType, acquireScore);
      }
    }

    if (zombieAgentTypes.isEmpty()) {
      return 0;
    }

    log.warn(
        "Found {} zombie agents, cleaning up: {}",
        zombieAgentTypes.size(),
        zombieAgentTypes.stream().limit(5).collect(java.util.stream.Collectors.toList()));

    // Check if batch operations are enabled (disabled by default for safety)
    boolean batchOperationsEnabled = schedulerProperties.isBatchOperationsEnabled();
    int totalCleaned = 0;

    try (Jedis jedis = jedisPool.getResource()) {
      if (!batchOperationsEnabled) {
        log.debug(
            "Batch zombie cleanup disabled, using individual operations for {} agents",
            zombieAgentTypes.size());
        // Use individual cleanup operations (existing proven approach)
        for (String agentType : zombieAgentTypes) {
          try {
            if (cleanupIndividualZombieAgent(jedis, agentType, activeAgents, activeAgentsFutures)) {
              totalCleaned++;
            }
          } catch (Exception e) {
            log.error("Failed to cleanup zombie agent {}: {}", agentType, e.getMessage());
          }
        }
      } else {
        // Use batch cleanup with fallback to individual operations
        List<String> zombieBatch = new ArrayList<>();
        int batchSize =
            Math.min(
                schedulerProperties.getZombieCleanup().getBatchSize(), zombieAgentTypes.size());
        log.debug(
            "Processing {} zombie agents in batches of {} with fallback",
            zombieAgentTypes.size(),
            batchSize);

        for (String agentType : zombieAgentTypes) {
          zombieBatch.add(agentType);

          if (zombieBatch.size() >= batchSize) {
            totalCleaned +=
                cleanupZombieBatchWithFallback(
                    jedis, zombieBatch, activeAgents, activeAgentsFutures);
            zombieBatch.clear();
          }
        }

        // Process remaining zombies
        if (!zombieBatch.isEmpty()) {
          totalCleaned +=
              cleanupZombieBatchWithFallback(jedis, zombieBatch, activeAgents, activeAgentsFutures);
        }
      }

      zombiesCleanedUp.addAndGet(totalCleaned);
      log.debug("Zombie cleanup completed: {} agents cleaned up", totalCleaned);
      return totalCleaned;

    } catch (Exception e) {
      log.error("Error during zombie agent cleanup", e);
      return 0;
    }
  }

  /**
   * Get the total number of zombie agents cleaned up since startup.
   *
   * @return total zombies cleaned up
   */
  public long getZombiesCleanedUp() {
    return zombiesCleanedUp.get();
  }

  /**
   * Get the timestamp of the last zombie cleanup operation.
   *
   * @return last cleanup timestamp in milliseconds
   */
  public long getLastZombieCleanup() {
    return lastZombieCleanup;
  }

  private int cleanupZombieBatch(
      Jedis jedis,
      List<Tuple> zombieBatch,
      Map<String, String> activeAgents,
      Map<String, Future<?>> activeAgentsFutures) {

    if (zombieBatch.isEmpty()) {
      return 0;
    }

    try {
      // Build arguments for batch cleanup: [agent1, score1, agent2, score2, ...]
      List<String> batchArgs = new ArrayList<>(zombieBatch.size() * 2);

      for (Tuple zombie : zombieBatch) {
        batchArgs.add(zombie.getElement());
        batchArgs.add(String.valueOf(zombie.getScore()));
      }

      // Execute Lua script to batch cleanup zombie agents from Redis WORKING set
      // Script removes agents that match the provided agent names and scores
      Object result =
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.BATCH_CLEANUP_AGENTS_SCRIPT),
              java.util.Collections.singletonList(WORKING_SET), // Redis key (WORKZ)
              batchArgs); // [agent1, score1, agent2, score2, ...]

      // Parse Lua script return value: [numCleaned, [cleanedAgent1, cleanedAgent2, ...]]
      // Lua script returns a table with count + list of successfully cleaned agent names
      int cleaned = 0;
      if (result instanceof List) {
        List<Object> resultList = (List<Object>) result;
        // Lua script returns: [count, [agent_names]] format
        if (resultList.size() >= 2) {
          // First element: number of agents actually cleaned from Redis
          cleaned = ((Long) resultList.get(0)).intValue();
          // Second element: list of agent names that were successfully cleaned
          List<String> cleanedAgents = (List<String>) resultList.get(1);

          // Log batch-level summary at INFO for operational visibility
          if (cleaned > 0) {
            log.info(
                "Zombie cleanup batch processed: {} agents cleaned from {} candidates",
                cleaned,
                zombieBatch.size());
          }

          // Synchronize local Java state with Redis cleanup results
          // Only clean up local state for agents that were actually removed from Redis
          for (String agentType : cleanedAgents) {
            // Remove from in-memory active agent tracking map
            activeAgents.remove(agentType);

            // Cancel the Java Future to stop any running agent execution
            Future<?> future = activeAgentsFutures.remove(agentType);
            if (future != null && !future.isDone()) {
              // Interrupt the thread executing this agent (force cleanup)
              boolean cancelled = future.cancel(true);
              log.debug("Cancelled zombie agent {} future: {}", agentType, cancelled);
            }

            log.debug("Cleaned up zombie agent: {}", agentType);
          }
        } else {
          log.warn("Unexpected Lua script result format: expected [count, list], got: {}", result);
        }
      } else {
        log.warn(
            "Unexpected Lua script result type: expected List, got: {}",
            result != null ? result.getClass().getSimpleName() : "null");
      }

      return cleaned;

    } catch (Exception e) {
      log.error("Error cleaning up zombie batch", e);
      return 0;
    }
  }

  /**
   * Clean up zombie batch with fallback mechanism. Uses batch operations when enabled and
   * available, falls back to individual cleanup when disabled or failed.
   */
  private int cleanupZombieBatchWithFallback(
      Jedis jedis,
      List<Tuple> zombieBatch,
      Map<String, String> activeAgents,
      Map<String, Future<?>> activeAgentsFutures,
      boolean batchOperationsEnabled) {

    if (zombieBatch.isEmpty()) {
      return 0;
    }

    if (batchOperationsEnabled && zombieBatch.size() > 1) {
      // Try batch operation first
      int batchCleaned = cleanupZombieBatch(jedis, zombieBatch, activeAgents, activeAgentsFutures);
      if (batchCleaned > 0) {
        return batchCleaned;
      } else {
        // Batch operation failed, fall back to individual cleanup
        log.warn(
            "Batch zombie cleanup failed for {} agents, falling back to individual cleanup",
            zombieBatch.size());
        return cleanupIndividualZombies(jedis, zombieBatch, activeAgents, activeAgentsFutures);
      }
    } else {
      // Use individual cleanup (batch disabled or single agent)
      return cleanupIndividualZombies(jedis, zombieBatch, activeAgents, activeAgentsFutures);
    }
  }

  /**
   * Fallback method to clean up zombie agents individually when batch operations fail or are
   * disabled.
   */
  private int cleanupIndividualZombies(
      Jedis jedis,
      List<Tuple> zombies,
      Map<String, String> activeAgents,
      Map<String, Future<?>> activeAgentsFutures) {

    int cleaned = 0;

    for (Tuple zombie : zombies) {
      try {
        String agentType = zombie.getElement();
        double score = zombie.getScore();

        // Use individual REMOVE_AGENT_SCRIPT for each zombie
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENT_SCRIPT),
                java.util.Collections.singletonList(WORKING_SET), // Redis set key
                java.util.Arrays.asList(
                    agentType, String.valueOf(score)) // Agent name and score for verification
                );

        if ("removed".equals(result)) {
          cleaned++;

          // Clean up local state
          activeAgents.remove(agentType);
          Future<?> future = activeAgentsFutures.remove(agentType);
          if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true);
            log.debug("Cancelled individual zombie agent {} future: {}", agentType, cancelled);
          }

          log.debug("Individually cleaned zombie agent: {}", agentType);
        } else {
          log.debug(
              "Zombie agent {} was not cleaned (may have been updated): {}", agentType, result);
        }

      } catch (Exception e) {
        log.warn("Error cleaning individual zombie {}: {}", zombie.getElement(), e.getMessage());
      }
    }

    if (cleaned > 0) {
      log.info("Individually cleaned {} zombie agents", cleaned);
    }

    return cleaned;
  }

  /**
   * Clean up zombie batch with fallback mechanism. Tries batch operations first, falls back to
   * individual cleanup if batch fails.
   */
  private int cleanupZombieBatchWithFallback(
      Jedis jedis,
      List<String> zombieBatch,
      Map<String, String> activeAgents,
      Map<String, Future<?>> activeAgentsFutures) {

    if (zombieBatch.isEmpty()) {
      return 0;
    }

    try {
      // Try batch operation first - convert to original format expected by existing batch method
      List<Tuple> zombieTuples = new ArrayList<>();
      for (String agentType : zombieBatch) {
        String acquireScore = activeAgents.get(agentType);
        if (acquireScore != null) {
          // Create Tuple with agentType and score for batch cleanup
          zombieTuples.add(
              new redis.clients.jedis.Tuple(agentType, Double.parseDouble(acquireScore)));
        }
      }

      if (!zombieTuples.isEmpty()) {
        int batchCleaned =
            cleanupZombieBatch(jedis, zombieTuples, activeAgents, activeAgentsFutures);
        if (batchCleaned > 0) {
          return batchCleaned;
        }
      }
    } catch (Exception e) {
      log.error(
          "Batch zombie cleanup failed for {} agents, falling back to individual operations: {}",
          zombieBatch.size(),
          e.getMessage());
    }

    // Batch operation failed or returned 0, fall back to individual cleanup
    log.warn(
        "Batch zombie cleanup failed for {} agents, falling back to individual cleanup",
        zombieBatch.size());
    int totalCleaned = 0;
    for (String agentType : zombieBatch) {
      try {
        if (cleanupIndividualZombieAgent(jedis, agentType, activeAgents, activeAgentsFutures)) {
          totalCleaned++;
        }
      } catch (Exception e) {
        log.warn("Failed to cleanup individual zombie {}: {}", agentType, e.getMessage());
      }
    }
    return totalCleaned;
  }

  /** Clean up a single zombie agent individually. */
  private boolean cleanupIndividualZombieAgent(
      Jedis jedis,
      String agentType,
      Map<String, String> activeAgents,
      Map<String, Future<?>> activeAgentsFutures) {

    try {
      // Get acquire score from active agents
      String acquireScore = activeAgents.remove(agentType);
      Future<?> future = activeAgentsFutures.remove(agentType);

      if (acquireScore == null) {
        log.debug("Agent {} not found in active tracking, skipping cleanup", agentType);
        return false;
      }

      // Cancel the future if it exists
      if (future != null && !future.isDone()) {
        boolean cancelled = future.cancel(true);
        log.info("Cancelled zombie agent execution: {}", agentType);
      }

      // Remove from Redis using REMOVE_AGENT_SCRIPT (removes from both WORKZ and WAITZ)
      Object result =
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENT_SCRIPT),
              java.util.Arrays.asList(WORKING_SET, "WAITZ"), // KEYS[1] and KEYS[2]
              java.util.Collections.singletonList(agentType) // ARGV[1] - only agent name needed
              );

      boolean removed = result != null && ((Long) result).intValue() == 1;
      if (removed) {
        log.debug("Removed zombie agent {} from Redis WORKING_SET", agentType);
      } else {
        log.debug("Zombie agent {} was not cleaned (may have been updated): {}", agentType, result);
      }

      return removed;

    } catch (Exception e) {
      log.warn("Failed to remove zombie agent {} from Redis: {}", agentType, e.getMessage());
      return false;
    }
  }
}
