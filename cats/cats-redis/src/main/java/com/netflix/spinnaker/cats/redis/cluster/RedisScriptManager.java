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
 * Manages Redis Lua scripts for the PriorityAgentScheduler.
 *
 * <p>This service handles loading, caching, and executing all Lua scripts used for atomic Redis
 * operations. All scripts are loaded once during initialization and cached by SHA hash for
 * efficient execution via EVALSHA.
 *
 * <p><strong>Script Categories:</strong>
 *
 * <ul>
 *   <li><strong>Individual Operations:</strong> Scripts with simple return values
 *   <li><strong>Batch Operations:</strong> Detailed tracking with complex return values
 *   <li><strong>State Transitions:</strong> Agent movement between WAITING and WORKING sets
 *   <li><strong>Advanced Operations:</strong> Conditional operations and ownership validation
 *   <li><strong>System Operations:</strong> Leadership management and scoring
 * </ul>
 */
@Component
public class RedisScriptManager {
  private static final Logger log = LoggerFactory.getLogger(RedisScriptManager.class);

  // Script name constants for Redis Lua operations

  // === BASIC OPERATIONS ===
  public static final String ADD_AGENT = "addAgent"; // Single agent addition
  public static final String REMOVE_AGENT = "removeAgent"; // Single agent removal

  // Batch operations (with detailed return values for tracking)
  public static final String ADD_AGENTS = "addAgents"; // Batch agent addition
  public static final String REMOVE_AGENTS =
      "removeAgents"; // Batch agent unconditional removal from both sets

  // === STATE TRANSITIONS ===
  public static final String MOVE_AGENT = "moveAgent"; // Single agent WAITING→WORKING movement

  public static final String MOVE_AGENTS =
      "moveAgents"; // Unconditional WAITING→WORKING for agent acquisition
  public static final String MOVE_AGENTS_CONDITIONAL =
      "moveAgentsConditional"; // Conditional WORKING→WAITING with ownership verification

  // === QUERIES ===
  public static final String SCORE_AGENTS = "scoreAgents"; // Batch score lookup for multiple agents
  public static final String VALIDATE_OWNERSHIP =
      "validateOwnership"; // Check agent ownership by score

  // === ADVANCED OPERATIONS ===
  public static final String ACQUIRE_AGENTS =
      "acquireAgents"; // Batch atomic WAITING→WORKING acquisition
  public static final String REMOVE_AGENTS_CONDITIONAL =
      "removeAgentsConditional"; // Conditional removal with score validation (orphan + zombie
  // cleanup)

  // === SYSTEM ===
  public static final String RELEASE_LEADERSHIP =
      "releaseLeadership"; // Distributed leadership release

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
        log.info("Loaded {} Redis Lua scripts for PriorityAgentScheduler", scriptShas.size());
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
    if (scriptName == null) {
      throw new IllegalArgumentException("Script name cannot be null");
    }

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
    // --- INDIVIDUAL OPERATIONS ---

    // ADD_AGENT: Add single agent to WAITING set with pipeline compatibility
    // ARGS: KEYS[1]=WORKZ, KEYS[2]=WAITZ, ARGV[1]=agentName, ARGV[2]=score
    // RETURNS: 1 if agent added successfully, 0 if agent already exists in either set
    // USAGE: Pipeline-friendly for bulk operations, individual scheduling
    scriptShas.put(
        ADD_AGENT,
        jedis.scriptLoad(
            "-- Check if agent exists in either WORKING or WAITING set\n"
                + "local exists = redis.call('zscore', KEYS[1], ARGV[1]) or redis.call('zscore', KEYS[2], ARGV[1])\n"
                + "if not exists then\n"
                + "  -- Agent is new, add to WAITING set with provided score\n"
                + "  redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])\n"
                + "  return 1  -- Success: agent added\n"
                + "else\n"
                + "  return 0  -- Already exists: no action taken\n"
                + "end\n"));

    // REMOVE_AGENT: Unconditionally remove agent from both WORKING and WAITING sets
    // ARGS: KEYS[1]=WORKZ, KEYS[2]=WAITZ, ARGV[1]=agentName
    // RETURNS: 1 (always successful - removes from both sets regardless of presence)
    // USAGE: Agent completion cleanup, zombie cleanup, pipeline-friendly removal
    scriptShas.put(
        REMOVE_AGENT,
        jedis.scriptLoad(
            "-- Remove agent from WORKING set (may not exist)\n"
                + "redis.call('zrem', KEYS[1], ARGV[1])\n"
                + "-- Remove agent from WAITING set (may not exist)\n"
                + "redis.call('zrem', KEYS[2], ARGV[1])\n"
                + "return 1  -- Always successful: Redis ZREM is idempotent\n"));

    // MOVE_AGENT: Atomically move single agent from WAITING → WORKING
    // ARGS: KEYS[1]=WORKZ, KEYS[2]=WAITZ, ARGV[1]=agentName, ARGV[2]=newScore
    // RETURNS: 1 if agent was moved successfully, 0 if agent was not in WAITING set
    // USAGE: Individual agent acquisition, pipeline-friendly conditional move
    scriptShas.put(
        MOVE_AGENT,
        jedis.scriptLoad(
            "-- Attempt to remove agent from WAITING set\n"
                + "local removed = redis.call('zrem', KEYS[2], ARGV[1])\n"
                + "if removed == 1 then\n"
                + "  -- Agent existed in WAITING, move to WORKING with new score\n"
                + "  redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n"
                + "  return 1  -- Success: agent moved WAITING → WORKING\n"
                + "else\n"
                + "  return 0  -- Failure: agent was not in WAITING set\n"
                + "end\n"));

    // --- BATCH OPERATIONS ---

    // Add single or multiple agents to WAITING set (consolidated from ADD_AGENT + BATCH_ADD_AGENTS)
    // Handles both single [agent, score] and batch [agent1, score1, agent2, score2, ...] operations
    scriptShas.put(
        ADD_AGENTS,
        jedis.scriptLoad(
            "local added = {}\n" // Track added agents for logging
                + "local count = 0\n" // Count of successful additions
                + "-- Agent scores are provided as pairs: [agent1, score1, agent2, score2, ...]\n"
                + "for i=1,#ARGV,2 do\n" // For each agent-score pair
                + "  local agent = ARGV[i]\n" // Agent name
                + "  local score = ARGV[i+1]\n" // Score
                + "  local exists = redis.call('zscore', KEYS[1], agent) or redis.call('zscore', KEYS[2], agent)\n"
                + "  if not exists then\n" // If not in either set
                + "    redis.call('zadd', KEYS[2], score, agent)\n" // Add to WAITING set
                + "    table.insert(added, agent)\n" // Track for logging
                + "    count = count + 1\n"
                + "  end\n"
                + "end\n"
                + "return {count, added}\n")); // Return count and list of added agents

    // Remove agent from both WAITING and WORKING sets (unconditional removal)
    scriptShas.put(
        REMOVE_AGENTS,
        jedis.scriptLoad(
            "redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from WORKING_SET
                + "redis.call('zrem', KEYS[2], ARGV[1])\n" // Remove from WAITING_SET
                + "return 1\n")); // Always return success

    // --- AGENT STATE TRANSITION SCRIPTS ---

    // MOVE_AGENTS: Unconditionally move agent WAITING → WORKING for acquisition
    // ARGS: KEYS[1]=WORKZ, KEYS[2]=WAITZ, ARGV[1]=agentName, ARGV[2]=newScore
    // RETURNS: newScore if successful, nil if agent not in WAITING set
    // USAGE: Agent acquisition (WAITING → WORKING transition)
    scriptShas.put(
        MOVE_AGENTS,
        jedis.scriptLoad(
            "-- Attempt to remove agent from WAITING set\n"
                + "local removed = redis.call('zrem', KEYS[2], ARGV[1])\n"
                + "if removed == 1 then\n"
                + "  -- Agent was in WAITING, move to WORKING with new score\n"
                + "  redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n"
                + "  return ARGV[2]  -- Return new score for success\n"
                + "else\n"
                + "  return nil  -- Agent was not in WAITING set\n"
                + "end\n"));

    // MOVE_AGENTS_CONDITIONAL: Conditionally move agent WORKING → WAITING with ownership
    // verification
    // ARGS: KEYS[1]=WORKZ, KEYS[2]=WAITZ, ARGV[1]=agentName, ARGV[2]=expectedScore,
    // ARGV[3]=newScore
    // RETURNS: 'swapped' if agent moved successfully, nil if ownership verification failed
    // USAGE: Graceful shutdown re-queuing, ensures only owning pod moves its agents
    scriptShas.put(
        MOVE_AGENTS_CONDITIONAL,
        jedis.scriptLoad(
            "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                + "if score and tonumber(score) == tonumber(ARGV[2]) then\n" // Numeric comparison
                + "  redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from WORKING_SET
                + "  redis.call('zadd', KEYS[2], ARGV[3], ARGV[1])\n" // Add to WAITING_SET
                + "  return 'swapped'\n" // Success
                + "else return nil end\n")); // Failed - score mismatch or agent missing

    // Check if we still own the agent lock (score validation)
    scriptShas.put(
        VALIDATE_OWNERSHIP,
        jedis.scriptLoad(
            "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                + "if score and tonumber(score) == tonumber(ARGV[2]) then\n" // Numeric comparison
                + "  return score\n" // We still own it
                + "else return nil end\n")); // Ownership lost or agent not found

    // --- ADVANCED CLEANUP SCRIPTS ---

    // Remove single or multiple agents with score validation (consolidated orphan + zombie cleanup)
    // Used for both orphan cleanup (cross-instance) and zombie cleanup (local instance)
    // Handles batch [agent1, score1, agent2, score2, ...] operations
    scriptShas.put(
        REMOVE_AGENTS_CONDITIONAL,
        jedis.scriptLoad(
            "local removed = {}\n" // Track removed agents for logging
                + "local count = 0\n" // Count of successful removals
                + "-- Agent scores are provided as pairs: [agent1, score1, agent2, score2, ...]\n"
                + "for i=1,#ARGV,2 do\n" // For each agent-score pair
                + "  local agent = ARGV[i]\n" // Agent name
                + "  local expectedScore = ARGV[i+1]\n" // Expected score
                + "  local actualScore = redis.call('zscore', KEYS[1], agent)\n"
                + "  if actualScore and tonumber(actualScore) == tonumber(expectedScore) then\n"
                + "    redis.call('zrem', KEYS[1], agent)\n" // Remove agent from specified set
                + "    table.insert(removed, agent)\n" // Track for logging
                + "    count = count + 1\n"
                + "  end\n"
                + "end\n"
                + "return {count, removed}\n")); // Return count and list of removed agents

    // Batch atomic agent acquisition: WAITING → WORKING in a single operation
    scriptShas.put(
        ACQUIRE_AGENTS,
        jedis.scriptLoad(
            "local acquired = {}\n" // Track acquired agents for logging
                + "local count = 0\n" // Count of successful acquisitions
                + "-- Agent scores are provided as pairs: [agent1, score1, agent2, score2, ...]\n"
                + "for i=1,#ARGV,2 do\n" // For each agent-score pair
                + "  local agent = ARGV[i]\n" // Agent name
                + "  local newScore = ARGV[i+1]\n" // New working score
                + "  local waitingScore = redis.call('zscore', KEYS[2], agent)\n" // Check if in
                // WAITING
                + "  if waitingScore then\n" // If agent is in WAITING set
                + "    redis.call('zrem', KEYS[2], agent)\n" // Remove from WAITING set
                + "    redis.call('zadd', KEYS[1], newScore, agent)\n" // Add to WORKING set
                + "    table.insert(acquired, agent)\n" // Track for logging
                + "    count = count + 1\n"
                + "  end\n"
                + "end\n"
                + "return {count, acquired}\n")); // Return count and list of acquired agents

    // --- QUERY SCRIPTS ---

    // Batch score lookup for multiple agents
    scriptShas.put(
        SCORE_AGENTS,
        jedis.scriptLoad(
            "-- Input validation: ensure at least one agent name provided\n"
                + "if #ARGV == 0 then\n"
                + "  return {}  -- Explicit empty input handling\n"
                + "end\n"
                + "local results = {}\n" // Results array
                + "for i=1,#ARGV do\n" // For each agent name
                + "  local agent = ARGV[i]\n" // Agent name
                + "  local workingScore = redis.call('zscore', KEYS[1], agent)\n" // Check WORKING
                // set
                + "  local waitingScore = redis.call('zscore', KEYS[2], agent)\n" // Check WAITING
                // set
                + "  table.insert(results, agent)\n" // Agent name
                + "  table.insert(results, workingScore or 'null')\n" // Working score or 'null'
                + "  table.insert(results, waitingScore or 'null')\n" // Waiting score or 'null'
                + "end\n"
                + "return results\n")); // Return [agent1, workScore1, waitScore1, agent2, ...]

    // --- LEADERSHIP MANAGEMENT ---

    // Release leadership only if we own it (atomic check-and-delete)
    scriptShas.put(
        RELEASE_LEADERSHIP,
        jedis.scriptLoad(
            "if redis.call('get', KEYS[1]) == ARGV[1] then\n"
                + "  return redis.call('del', KEYS[1])\n"
                + "else\n"
                + "  return 0\n"
                + "end\n"));

    log.debug("Loaded Redis Lua scripts: {}", scriptShas.keySet());
  }
}
