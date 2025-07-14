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
  private static final String WAITING_SET = "WAITZ";

  private final JedisPool jedisPool;
  private final RedisScriptManager scriptManager;
  private final ClusteredSortSchedulerProperties schedulerProperties;

  // Tracking for zombie cleanup
  private final AtomicLong zombiesCleanedUp = new AtomicLong(0);
  private volatile long lastZombieCleanup = 0;

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
      ClusteredSortSchedulerProperties schedulerProperties) {
    this.jedisPool = jedisPool;
    this.scriptManager = scriptManager;
    this.schedulerProperties = schedulerProperties;
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
   * instance. It works by checking the local activeAgents map for agents that have exceeded their
   * completion deadline (current_time + agent_timeout).
   *
   * @param activeAgents Map of active agents (agentType -> completionDeadline)
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
    int validAgentsScanned = 0;

    for (Map.Entry<String, String> entry : activeAgents.entrySet()) {
      String agentType = entry.getKey();
      String acquireScore = entry.getValue();

      try {
        // acquireScore is completion deadline (current_time + agent_timeout)
        // Convert acquire score from seconds to milliseconds for comparison
        long completionDeadlineMs = Long.parseLong(acquireScore) * 1000;
        validAgentsScanned++;

        // The agent is considered a zombie if current time exceeds completion deadline + zombie
        // threshold buffer
        if (currentTime > completionDeadlineMs + zombieThreshold) {
          zombieAgentTypes.add(agentType);
          long overdueMs = currentTime - completionDeadlineMs;
          log.warn(
              "Zombie agent detected: {} ({}ms overdue past completion deadline, {}ms buffer exceeded)",
              agentType,
              overdueMs,
              zombieThreshold);
        }
      } catch (NumberFormatException e) {
        log.warn("Invalid acquire score for agent {}: {}", agentType, acquireScore);
      }
    }

    // Log scanning summary
    if (zombieAgentTypes.isEmpty()) {
      log.debug("Zombie scan completed: {} agents analyzed, 0 zombies found", validAgentsScanned);
      return 0;
    }

    log.warn(
        "Zombie scan completed: {} agents analyzed, {} zombies found - cleaning up: {}",
        validAgentsScanned,
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
                cleanupZombieBatch(jedis, zombieBatch, activeAgents, activeAgentsFutures);
            zombieBatch.clear();
          }
        }

        // Process remaining zombies
        if (!zombieBatch.isEmpty()) {
          totalCleaned += cleanupZombieBatch(jedis, zombieBatch, activeAgents, activeAgentsFutures);
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

  /**
   * Clean up zombie batch with built-in fallback mechanism. Attempts batch cleanup first, falls
   * back to individual cleanup if batch fails.
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
      Map<String, Future<?>> activeAgentsFutures) {

    if (zombieAgentTypes.isEmpty()) {
      return 0;
    }

    // Try batch operation first if there are multiple agents
    if (zombieAgentTypes.size() > 1) {
      try {
        // Build arguments for batch cleanup: [agent1, score1, agent2, score2, ...]
        List<String> batchArgs = new ArrayList<>(zombieAgentTypes.size() * 2);

        for (String agentType : zombieAgentTypes) {
          String acquireScore = activeAgents.get(agentType);
          if (acquireScore != null) {
            batchArgs.add(agentType);
            batchArgs.add(acquireScore);
          }
        }

        if (!batchArgs.isEmpty()) {
          // Execute Lua script to batch cleanup zombie agents from Redis WORKING set
          Object result =
              jedis.evalsha(
                  scriptManager.getScriptSha(RedisScriptManager.BATCH_CLEANUP_AGENTS_SCRIPT),
                  java.util.Collections.singletonList(WORKING_SET), // Redis key (WORKZ)
                  batchArgs); // [agent1, score1, agent2, score2, ...]

          // Parse Lua script return value: [numCleaned, [cleanedAgent1, cleanedAgent2, ...]]
          int cleaned =
              parseBatchCleanupResult(
                  result, zombieAgentTypes.size(), activeAgents, activeAgentsFutures);
          if (cleaned > 0) {
            return cleaned;
          }
        }
      } catch (Exception e) {
        log.warn(
            "Batch zombie cleanup failed for {} agents, falling back to individual cleanup: {}",
            zombieAgentTypes.size(),
            e.getMessage());
      }
    }

    // Batch operation failed, disabled, or single agent - fall back to individual cleanup
    log.debug("Using individual cleanup for {} zombie agents", zombieAgentTypes.size());
    int totalCleaned = 0;
    for (String agentType : zombieAgentTypes) {
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

  /**
   * Parse the result from batch cleanup Lua script and update local state.
   *
   * @param result Result from batch cleanup Lua script
   * @param candidateCount Number of candidates processed
   * @param activeAgents Map of active agents (agentType -> completionDeadline)
   * @param activeAgentsFutures Map of agent futures for cancellation
   * @return Number of agents cleaned up
   */
  private int parseBatchCleanupResult(
      Object result,
      int candidateCount,
      Map<String, String> activeAgents,
      Map<String, Future<?>> activeAgentsFutures) {

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
              candidateCount);
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
