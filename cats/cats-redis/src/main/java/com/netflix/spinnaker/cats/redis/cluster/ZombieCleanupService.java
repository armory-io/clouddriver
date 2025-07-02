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
import java.util.Set;
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
   * Detect and clean up zombie agents from Redis and local state.
   *
   * <p>This method scans the Redis WORKING set for agents that have been running longer than the
   * configured zombie threshold and removes them. It also cleans up any local state associated with
   * these agents.
   *
   * @param activeAgents Map of active agents (agentType -> acquireScore)
   * @param activeAgentsFutures Map of agent futures for cancellation
   * @return Number of zombie agents cleaned up
   */
  public int cleanupZombieAgents(
      Map<String, String> activeAgents, Map<String, Future<?>> activeAgentsFutures) {
    long zombieThreshold = schedulerProperties.getZombieCleanup().getThresholdMs();
    long cutoffScore = System.currentTimeMillis() - zombieThreshold;

    log.debug(
        "Starting zombie agent cleanup with threshold {}ms (cutoff score: {})",
        zombieThreshold,
        cutoffScore);

    try (Jedis jedis = jedisPool.getResource()) {
      // Find all agents in WORKING set older than threshold
      Set<Tuple> zombieAgents = jedis.zrangeByScoreWithScores(WORKING_SET, 0, cutoffScore);

      if (zombieAgents.isEmpty()) {
        log.debug("No zombie agents found");
        return 0;
      }

      log.info(
          "Found {} potential zombie agents older than {}ms", zombieAgents.size(), zombieThreshold);

      // Clean up zombies in batches for efficiency
      int totalCleaned = 0;
      List<Tuple> zombieBatch = new ArrayList<>();
      // Use configurable batch size, capped at actual number of zombies to avoid empty batches
      int batchSize =
          Math.min(schedulerProperties.getZombieCleanup().getBatchSize(), zombieAgents.size());
      log.debug("Processing {} zombie agents in batches of {}", zombieAgents.size(), batchSize);

      for (Tuple zombie : zombieAgents) {
        zombieBatch.add(zombie);

        if (zombieBatch.size() >= batchSize) {
          totalCleaned += cleanupZombieBatch(jedis, zombieBatch, activeAgents, activeAgentsFutures);
          zombieBatch.clear();
        }
      }

      // Process remaining zombies
      if (!zombieBatch.isEmpty()) {
        totalCleaned += cleanupZombieBatch(jedis, zombieBatch, activeAgents, activeAgentsFutures);
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

            log.info("Cleaned up zombie agent: {}", agentType);
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
}
