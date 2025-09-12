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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Manages Redis Lua scripts for atomic scheduler operations.
 *
 * <p>Loads and caches Lua scripts for efficient execution via EVALSHA. Scripts provide atomic
 * operations for agent state transitions between waiting and working sets.
 *
 * <p>Key operations:
 *
 * <ul>
 *   <li>Agent movement between sets (waiting ↔ working)
 *   <li>Batch operations for performance
 *   <li>Conditional operations with ownership validation
 *   <li>Leadership management for distributed coordination
 * </ul>
 *
 * <p>Self-heals on NOSCRIPT errors by transparently reloading scripts.
 */
@Component
@Slf4j
public class RedisScriptManager {

  // Script name constants for Redis Lua operations

  // === BASIC OPERATIONS ===
  public static final String ADD_AGENT = "addAgent"; // Single agent addition
  public static final String ADD_AGENTS = "addAgents"; // Batch agent addition
  public static final String REMOVE_AGENT = "removeAgent"; // Single agent removal
  public static final String REMOVE_AGENTS =
      "removeAgents"; // Batch agent unconditional removal from both sets

  // === STATE TRANSITIONS ===
  public static final String MOVE_AGENT = "moveAgent"; // Single agent waiting→working movement
  public static final String MOVE_AGENTS =
      "moveAgents"; // Unconditional waiting→working for agent acquisition
  public static final String MOVE_AGENTS_CONDITIONAL =
      "moveAgentsConditional"; // Conditional working→waiting with ownership verification

  // === QUERIES ===
  public static final String SCORE_AGENTS = "scoreAgents"; // Batch score lookup for multiple agents
  public static final String VALIDATE_OWNERSHIP =
      "validateOwnership"; // Check agent ownership by score

  // === ADVANCED OPERATIONS ===
  public static final String ACQUIRE_AGENTS =
      "acquireAgents"; // Batch atomic waiting→working acquisition
  public static final String REMOVE_AGENTS_CONDITIONAL =
      "removeAgentsConditional"; // Conditional removal with score validation (orphan + zombie
  // cleanup)

  // === SYSTEM ===
  public static final String RELEASE_LEADERSHIP =
      "releaseLeadership"; // Distributed leadership release

  private final JedisPool jedisPool;
  private final PrioritySchedulerMetrics metrics;
  private final Map<String, String> scriptShas = new ConcurrentHashMap<>();
  private final AtomicBoolean initialized = new AtomicBoolean(false);

  // Single source of truth for Lua bodies used by both scriptLoad and EVAL fallback
  private final Map<String, String> scriptBodies = new ConcurrentHashMap<>();

  public RedisScriptManager(JedisPool jedisPool, PrioritySchedulerMetrics metrics) {
    this.jedisPool = jedisPool;
    this.metrics = metrics;
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
   * Execute a script via EVALSHA with automatic self-heal on NOSCRIPT.
   *
   * <p>Scripts provide atomic operations for agent state transitions. If Redis has evicted scripts
   * (failover, SCRIPT FLUSH), transparently reloads and retries to avoid disruption.
   */
  public Object evalshaWithSelfHeal(
      Jedis jedis, String scriptName, java.util.List<String> keys, java.util.List<String> args) {
    long start = System.nanoTime();
    try {
      // Fast path: execute cached script SHA
      Object result = jedis.evalsha(getScriptSha(scriptName), keys, args);
      metrics.recordScriptEval(
          scriptName, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      return result;
    } catch (redis.clients.jedis.exceptions.JedisDataException e) {
      String msg = e.getMessage();
      if (msg != null && msg.contains("NOSCRIPT")) {
        try {
          // Script evicted from Redis - reload all scripts
          metrics.incrementScriptsReload();
          loadAllScripts(jedis);
          // Retry with reloaded SHA
          long retryStart = System.nanoTime();
          Object result = jedis.evalsha(getScriptSha(scriptName), keys, args);
          metrics.recordScriptEval(
              scriptName, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - retryStart));
          return result;
        } catch (Exception retry) {
          // Fallback: execute script body directly via EVAL
          String body = getScriptBody(scriptName);
          if (body != null) {
            long evalStart = System.nanoTime();
            Object result = jedis.eval(body, keys, args);
            metrics.recordScriptEval(
                scriptName, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - evalStart));
            return result;
          }
        }
      }
      metrics.incrementScriptError(scriptName, e.getClass().getSimpleName());
      throw e;
    } catch (ClassCastException cce) {
      // Defensive: result type mismatch (e.g., Redis/Jedis returns a different shape)
      // Attempt one-time reload of scripts and retry this call
      try {
        metrics.incrementScriptResultTypeError(scriptName);
      } catch (Exception ignoreMetric) {
      }

      try {
        loadAllScripts(jedis);
        metrics.incrementScriptsReload();
        long retryStart = System.nanoTime();
        Object result = jedis.evalsha(getScriptSha(scriptName), keys, args);
        metrics.recordScriptEval(
            scriptName, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - retryStart));
        return result;
      } catch (Exception retry) {
        // Fall back to EVAL body
        String body = getScriptBody(scriptName);
        if (body != null) {
          long evalStart = System.nanoTime();
          Object result = jedis.eval(body, keys, args);
          metrics.recordScriptEval(
              scriptName, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - evalStart));
          return result;
        }
        throw cce; // rethrow original CCE if we have no body
      }
    }
  }

  // Returns the script body for a given name from the single-source map.
  private String getScriptBody(String scriptName) {
    return scriptBodies.get(scriptName);
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
    // Build bodies once to avoid duplication and drift. These scripts codify the agent lifecycle
    // invariants used by the Priority scheduler. Inline comments explain expected semantics.
    Map<String, String> bodies = new LinkedHashMap<>();

    // --- INDIVIDUAL OPERATIONS ---

    // ADD_AGENT: Add single agent to the waiting set with pipeline compatibility.
    // Invariants:
    // - An agent may be in at most one of waiting or working at any time.
    // - If present in either, re-adding is a no-op (idempotent enqueue).
    // ARGS: KEYS[1]=working, KEYS[2]=waiting, ARGV[1]=agentName, ARGV[2]=score
    // RETURNS: 1 if agent added successfully, 0 if agent already exists in either set
    // USAGE: Pipeline-friendly for bulk operations, individual scheduling
    bodies.put(
        ADD_AGENT,
        "-- Check if agent exists in either working or waiting set\n"
            + "local exists = redis.call('zscore', KEYS[1], ARGV[1]) or redis.call('zscore', KEYS[2], ARGV[1])\n"
            + "if not exists then\n"
            + "  -- Agent is new, add to the waiting set with provided score\n"
            + "  redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])\n"
            + "  return 1  -- Success: agent added\n"
            + "else\n"
            + "  return 0  -- Already exists: no action taken\n"
            + "end\n");

    // REMOVE_AGENT: Unconditionally remove agent from both working and waiting sets.
    // Invariants:
    // - Removal is idempotent; used by completion, zombie cleanup, and defensive cleanup.
    // ARGS: KEYS[1]=working, KEYS[2]=waiting, ARGV[1]=agentName
    // RETURNS: 1 (always successful - removes from both sets regardless of presence)
    // USAGE: Agent completion cleanup, zombie cleanup, pipeline-friendly removal
    bodies.put(
        REMOVE_AGENT,
        "-- Remove agent from working set (may not exist)\n"
            + "redis.call('zrem', KEYS[1], ARGV[1])\n"
            + "-- Remove agent from waiting set (may not exist)\n"
            + "redis.call('zrem', KEYS[2], ARGV[1])\n"
            + "return 1  -- Always successful: Redis ZREM is idempotent\n");

    // MOVE_AGENT: Atomically move single agent from the waiting set to the working set.
    // Invariants:
    // - Transition is atomic: agent is removed from waiting and added to working with new score.
    // - Returns 1 on success, 0 if agent was not waiting (no-op, safe under races).
    // ARGS: KEYS[1]=working, KEYS[2]=waiting, ARGV[1]=agentName, ARGV[2]=newScore
    // RETURNS: 1 if agent was moved successfully, 0 if agent was not in the waiting set
    // USAGE: Individual agent acquisition, pipeline-friendly conditional move
    bodies.put(
        MOVE_AGENT,
        "-- Attempt to remove agent from the waiting set\n"
            + "local removed = redis.call('zrem', KEYS[2], ARGV[1])\n"
            + "if removed == 1 then\n"
            + "  -- Agent existed in waiting, move to working with new score\n"
            + "  redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n"
            + "  return 1  -- Success: agent moved waiting → working\n"
            + "else\n"
            + "  return 0  -- Failure: agent was not in the waiting set\n"
            + "end\n");

    // --- BATCH OPERATIONS ---

    // ADD_AGENTS: Add single or multiple agents to the waiting set (consolidated from ADD_AGENT +
    // BATCH_ADD_AGENTS).
    // Invariants:
    // - Same as ADD_AGENT but batched; returns {count, [added...]} for observability.
    // Handles both single [agent, score] and batch [agent1, score1, agent2, score2, ...] operations
    // Track added agents and the count of successful additions.
    bodies.put(
        ADD_AGENTS,
        "local added = {}\n"
            + "local count = 0\n"
            + "-- Agent scores are provided as pairs: [agent1, score1, agent2, score2, ...]\n"
            + "for i=1,#ARGV,2 do\n"
            + "  local agent = ARGV[i]\n"
            + "  local score = ARGV[i+1]\n"
            + "  -- Guards: score must be numeric and agent must not be numeric\n"
            + "  if tonumber(score) ~= nil and tonumber(agent) == nil then\n"
            + "    local exists = redis.call('zscore', KEYS[1], agent) or redis.call('zscore', KEYS[2], agent)\n"
            + "    if not exists then\n"
            + "      redis.call('zadd', KEYS[2], score, agent)\n"
            + "      table.insert(added, agent)\n"
            + "      count = count + 1\n"
            + "    end\n"
            + "  end\n"
            + "end\n"
            + "return {count, added}\n");

    // REMOVE_AGENTS: Unconditional removal (single-arg variant kept for compatibility).
    bodies.put(
        REMOVE_AGENTS,
        "redis.call('zrem', KEYS[1], ARGV[1])\n"
            + "redis.call('zrem', KEYS[2], ARGV[1])\n"
            + "return 1\n");

    // --- AGENT STATE TRANSITION SCRIPTS ---

    // MOVE_AGENTS: Unconditionally move agent waiting → working for acquisition (single agent).
    // Invariants:
    // - Returns newScore if moved; nil if not waiting. Used by acquisition flows.
    // ARGS: KEYS[1]=working, KEYS[2]=waiting, ARGV[1]=agentName, ARGV[2]=newScore
    // RETURNS: newScore if successful, nil if agent not in the waiting set
    // USAGE: Agent acquisition (WAITING → WORKING transition)
    bodies.put(
        MOVE_AGENTS,
        "-- Attempt to remove agent from the waiting set\n"
            + "local removed = redis.call('zrem', KEYS[2], ARGV[1])\n"
            + "if removed == 1 then\n"
            + "  -- Agent was in waiting, move to working with new score\n"
            + "  redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n"
            + "  return ARGV[2]\n"
            + "else\n"
            + "  return nil\n"
            + "end\n");

    // MOVE_AGENTS_CONDITIONAL: Conditionally move working → waiting with ownership verification.
    // Invariants:
    // - Score encodes lock ownership. Only the owning scorer may requeue.
    // - Used by graceful shutdown and local zombie/orphan fixes.
    // ARGS: KEYS[1]=working, KEYS[2]=waiting, ARGV[1]=agentName, ARGV[2]=expectedScore,
    // ARGV[3]=newScore
    // RETURNS: 'swapped' if agent moved successfully, nil if ownership verification failed
    // USAGE: Graceful shutdown re-queuing, ensures only owning pod moves its agents
    bodies.put(
        MOVE_AGENTS_CONDITIONAL,
        "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
            + "if score and tonumber(score) == tonumber(ARGV[2]) then\n"
            + "  redis.call('zrem', KEYS[1], ARGV[1])\n"
            + "  redis.call('zadd', KEYS[2], ARGV[3], ARGV[1])\n"
            + "  return 'swapped'\n"
            + "else return nil end\n");

    // VALIDATE_OWNERSHIP: Check if we still own the agent lock (score validation).
    bodies.put(
        VALIDATE_OWNERSHIP,
        "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
            + "if score and tonumber(score) == tonumber(ARGV[2]) then\n"
            + "  return score\n"
            + "else return nil end\n");

    // --- ADVANCED CLEANUP SCRIPTS ---

    // REMOVE_AGENTS_CONDITIONAL: Remove single or multiple agents with score validation.
    // Invariants:
    // - Used for orphan cleanup (cross-instance) and zombie cleanup (local instance).
    // - Only removes entries whose scores match expectations, preventing races.
    // - Handles batch [agent1, score1, agent2, score2, ...] operations.
    // Track removed agents and the count of successful removals.
    bodies.put(
        REMOVE_AGENTS_CONDITIONAL,
        "local removed = {}\n"
            + "local count = 0\n"
            + "-- Agent scores are provided as pairs: [agent1, score1, agent2, score2, ...]\n"
            + "for i=1,#ARGV,2 do\n"
            + "  local agent = ARGV[i]\n"
            + "  local expectedScore = ARGV[i+1]\n"
            + "  local actualScore = redis.call('zscore', KEYS[1], agent)\n"
            + "  if actualScore and tonumber(actualScore) == tonumber(expectedScore) then\n"
            + "    redis.call('zrem', KEYS[1], agent)\n"
            + "    table.insert(removed, agent)\n"
            + "    count = count + 1\n"
            + "  end\n"
            + "end\n"
            + "return {count, removed}\n");

    // ACQUIRE_AGENTS: Batch atomic acquisition waiting → working.
    // Invariants:
    // - For each candidate, if it is still in waiting, atomically move and track acquired list.
    // - Combined with ready-scan limit, keeps acquisition O(N) per cycle.
    bodies.put(
        ACQUIRE_AGENTS,
        "local acquired = {}\n"
            + "local count = 0\n"
            + "-- Agent scores are provided as pairs: [agent1, score1, agent2, score2, ...]\n"
            + "for i=1,#ARGV,2 do\n"
            + "  local agent = ARGV[i]\n"
            + "  local newScore = ARGV[i+1]\n"
            + "  if tonumber(newScore) ~= nil then\n"
            + "    local waitingScore = redis.call('zscore', KEYS[2], agent)\n"
            + "    if waitingScore then\n"
            + "      redis.call('zrem', KEYS[2], agent)\n"
            + "      redis.call('zadd', KEYS[1], newScore, agent)\n"
            + "      table.insert(acquired, agent)\n"
            + "      count = count + 1\n"
            + "    end\n"
            + "  end\n"
            + "end\n"
            + "return {count, acquired}\n");

    // --- QUERY SCRIPTS ---

    // SCORE_AGENTS: Batch score lookup for multiple agents.
    // Invariants:
    // - Returns [agent, workScore|'null', waitScore|'null', ...] for diagnostics/observability.
    bodies.put(
        SCORE_AGENTS,
        "-- Input validation: ensure at least one agent name provided\n"
            + "if #ARGV == 0 then\n"
            + "  return {}\n"
            + "end\n"
            + "local results = {}\n"
            + "for i=1,#ARGV do\n"
            + "  local agent = ARGV[i]\n"
            + "  local workingScore = redis.call('zscore', KEYS[1], agent)\n"
            + "  local waitingScore = redis.call('zscore', KEYS[2], agent)\n"
            + "  table.insert(results, agent)\n"
            + "  table.insert(results, workingScore or 'null')\n"
            + "  table.insert(results, waitingScore or 'null')\n"
            + "end\n"
            + "return results\n");

    // --- LEADERSHIP MANAGEMENT ---

    // RELEASE_LEADERSHIP: Release leadership only if we own it (atomic check-and-delete).
    // Invariants:
    // - Prevents another node from releasing a lock it does not own.
    bodies.put(
        RELEASE_LEADERSHIP,
        "if redis.call('get', KEYS[1]) == ARGV[1] then\n"
            + "  return redis.call('del', KEYS[1])\n"
            + "else\n"
            + "  return 0\n"
            + "end\n");

    // Persist and load
    scriptBodies.clear();
    scriptBodies.putAll(bodies);
    scriptShas.clear();
    for (Map.Entry<String, String> e : bodies.entrySet()) {
      scriptShas.put(e.getKey(), jedis.scriptLoad(e.getValue()));
    }

    log.debug("Loaded Redis Lua scripts: {}", scriptShas.keySet());
  }
}
