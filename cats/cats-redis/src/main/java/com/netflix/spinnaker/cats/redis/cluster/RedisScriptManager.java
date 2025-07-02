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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Manages Redis Lua scripts for the ClusteredSortAgentScheduler.
 *
 * <p>This service handles loading, caching, and executing all Lua scripts used for atomic Redis
 * operations. All scripts are loaded once during initialization and cached by SHA hash for
 * efficient execution via EVALSHA.
 *
 * <p><strong>Script Categories:</strong>
 *
 * <ul>
 *   <li><strong>Basic Operations:</strong> Add/remove agents from Redis sets
 *   <li><strong>State Transitions:</strong> Move agents between WAITING and WORKING sets
 *   <li><strong>Optimization Scripts:</strong> Batch operations for performance
 * </ul>
 */
@Component
public class RedisScriptManager {
  private static final Logger log = LoggerFactory.getLogger(RedisScriptManager.class);

  // Script name constants
  public static final String ADD_AGENT_SCRIPT = "addAgent";
  public static final String REMOVE_AGENT_SCRIPT = "removeAgent";
  public static final String SWAP_SET_SCRIPT = "swapSet";
  public static final String CONDITIONAL_SWAP_SET_SCRIPT = "conditionalSwapSet";
  public static final String VALID_SCORE_SCRIPT = "validScore";
  public static final String ORPHAN_REMOVE_SCRIPT = "orphanRemove";
  public static final String BATCH_ORPHAN_REMOVE_SCRIPT = "batchOrphanRemove";
  public static final String BATCH_ADD_AGENTS_SCRIPT = "batchAddAgents";
  public static final String BATCH_CLEANUP_AGENTS_SCRIPT = "batchCleanupAgents";

  private final JedisPool jedisPool;
  private final Map<String, String> scriptShas = new ConcurrentHashMap<>();
  private final AtomicBoolean initialized = new AtomicBoolean(false);

  public RedisScriptManager(JedisPool jedisPool) {
    this.jedisPool = jedisPool;
  }

  /**
   * Initialize all Lua scripts by loading them into Redis and caching their SHA hashes. This method
   * is thread-safe and will only load scripts once.
   */
  public void initializeScripts() {
    if (initialized.get()) {
      return;
    }

    synchronized (this) {
      if (initialized.get()) {
        return;
      }

      try (Jedis jedis = jedisPool.getResource()) {
        loadAllScripts(jedis);
        initialized.set(true);
        log.info("Loaded {} Redis Lua scripts for ClusteredSortAgentScheduler", scriptShas.size());
      } catch (Exception e) {
        log.error("Failed to initialize Redis scripts", e);
        throw new AgentSchedulingException("Failed to initialize Redis scripts", e);
      }
    }
  }

  /**
   * Get the SHA hash for a script name.
   *
   * @param scriptName The script name constant
   * @return The SHA hash for EVALSHA execution
   * @throws IllegalStateException if scripts are not initialized or script not found
   */
  public String getScriptSha(String scriptName) {
    if (!initialized.get()) {
      throw new IllegalStateException("Scripts not initialized. Call initializeScripts() first.");
    }

    String sha = scriptShas.get(scriptName);
    if (sha == null) {
      throw new IllegalArgumentException("Unknown script: " + scriptName);
    }
    return sha;
  }

  /**
   * Get the number of loaded Redis Lua scripts.
   *
   * @return Number of scripts currently loaded and cached
   */
  public int getScriptCount() {
    return scriptShas.size();
  }

  /**
   * Check if scripts are initialized.
   *
   * @return true if all scripts are loaded and ready for use
   */
  public boolean isInitialized() {
    return initialized.get();
  }

  private void loadAllScripts(Jedis jedis) {
    // --- BASIC OPERATIONS ---

    // Add agent to WAITING set if not in either set
    scriptShas.put(
        ADD_AGENT_SCRIPT,
        jedis.scriptLoad(
            "local exists = redis.call('zscore', KEYS[1], ARGV[1]) or redis.call('zscore', KEYS[2], ARGV[1])\n"
                + "if not exists then\n" // If not in either set
                + "  redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])\n" // Add to WAITING set
                + "  return 'added'\n" // Success
                + "else return nil end\n")); // Already exists in one of the sets

    // Remove agent from both WAITING and WORKING sets
    scriptShas.put(
        REMOVE_AGENT_SCRIPT,
        jedis.scriptLoad(
            "redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from WORKING_SET
                + "redis.call('zrem', KEYS[2], ARGV[1])\n" // Remove from WAITING_SET
                + "return 1\n")); // Always return success

    // --- AGENT STATE TRANSITION SCRIPTS ---

    // Move agent WAITING → WORKING unconditionally
    scriptShas.put(
        SWAP_SET_SCRIPT,
        jedis.scriptLoad(
            "redis.call('zrem', KEYS[2], ARGV[1])\n" // Remove from WAITING_SET
                + "redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n" // Add to WORKING_SET
                + "return ARGV[2]\n")); // Return the new score

    // Move agent WORKING → WAITING (only if score matches - ownership check)
    scriptShas.put(
        CONDITIONAL_SWAP_SET_SCRIPT,
        jedis.scriptLoad(
            "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                + "if score and tonumber(score) == tonumber(ARGV[2]) then\n" // Numeric comparison
                + "  redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from WORKING_SET
                + "  redis.call('zadd', KEYS[2], ARGV[3], ARGV[1])\n" // Add to WAITING_SET
                + "  return 'swapped'\n" // Success
                + "else return nil end\n")); // Failed - score mismatch or agent missing

    // Check if we still own the agent lock (score validation)
    scriptShas.put(
        VALID_SCORE_SCRIPT,
        jedis.scriptLoad(
            "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                + "if score and tonumber(score) == tonumber(ARGV[2]) then\n" // Numeric comparison
                + "  return score\n" // We still own it
                + "else return nil end\n")); // Ownership lost or agent not found

    // --- OPTIMIZATION SCRIPTS ---

    // Remove a single orphaned agent if score matches
    scriptShas.put(
        ORPHAN_REMOVE_SCRIPT,
        jedis.scriptLoad(
            "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                + "if score and tonumber(score) == tonumber(ARGV[2]) then\n" // Numeric comparison
                + "  redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from WORKZ
                + "  return 1\n" // Success
                + "else return 0 end\n")); // Failed - score mismatch or agent missing

    // Remove multiple orphaned agents in a single batch operation
    scriptShas.put(
        BATCH_ORPHAN_REMOVE_SCRIPT,
        jedis.scriptLoad(
            "local removed = {}\n" // Track removed agents for logging
                + "local count = 0\n" // Count of successful removals
                + "-- Agent scores are provided as pairs: [agent1, score1, agent2, score2, ...]\n"
                + "for i=2,#ARGV,2 do\n" // For each agent-score pair
                + "  local agent = ARGV[i-1]\n" // Agent name
                + "  local expectedScore = ARGV[i]\n" // Expected score
                + "  local actualScore = redis.call('zscore', KEYS[1], agent)\n"
                + "  if actualScore and tonumber(actualScore) == tonumber(expectedScore) then\n"
                + "    redis.call('zrem', KEYS[1], agent)\n" // Remove orphaned agent
                + "    table.insert(removed, agent)\n" // Track for logging
                + "    count = count + 1\n"
                + "  end\n"
                + "end\n"
                + "return {count, removed}\n")); // Return count and list of removed agents

    // Add multiple agents to WAITING set in a single operation
    scriptShas.put(
        BATCH_ADD_AGENTS_SCRIPT,
        jedis.scriptLoad(
            "local added = {}\n" // Track added agents for logging
                + "local count = 0\n" // Count of successful additions
                + "-- Agent scores are provided as pairs: [agent1, score1, agent2, score2, ...]\n"
                + "for i=2,#ARGV,2 do\n" // For each agent-score pair
                + "  local agent = ARGV[i-1]\n" // Agent name
                + "  local score = ARGV[i]\n" // Score
                + "  local exists = redis.call('zscore', KEYS[1], agent) or redis.call('zscore', KEYS[2], agent)\n"
                + "  if not exists then\n" // If not in either set
                + "    redis.call('zadd', KEYS[2], score, agent)\n" // Add to WAITING set
                + "    table.insert(added, agent)\n" // Track for logging
                + "    count = count + 1\n"
                + "  end\n"
                + "end\n"
                + "return {count, added}\n")); // Return count and list of added agents

    // Remove multiple zombie agents in a single batch operation
    scriptShas.put(
        BATCH_CLEANUP_AGENTS_SCRIPT,
        jedis.scriptLoad(
            "local cleaned = {}\n" // Track cleaned agents for logging
                + "local count = 0\n" // Count of successful cleanups
                + "-- Agent scores are provided as pairs: [agent1, score1, agent2, score2, ...]\n"
                + "for i=2,#ARGV,2 do\n" // For each agent-score pair
                + "  local agent = ARGV[i-1]\n" // Agent name
                + "  local expectedScore = ARGV[i]\n" // Expected score
                + "  local actualScore = redis.call('zscore', KEYS[1], agent)\n"
                + "  if actualScore and tonumber(actualScore) == tonumber(expectedScore) then\n"
                + "    redis.call('zrem', KEYS[1], agent)\n" // Remove zombie agent
                + "    table.insert(cleaned, agent)\n" // Track for logging
                + "    count = count + 1\n"
                + "  end\n"
                + "end\n"
                + "return {count, cleaned}\n")); // Return count and list of cleaned agents

    log.debug("Loaded Redis Lua scripts: {}", scriptShas.keySet());
  }
}
