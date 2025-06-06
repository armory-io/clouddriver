/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

import static com.netflix.spinnaker.cats.agent.ExecutionInstrumentation.elapsedTimeMs;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.AgentScheduler;
import com.netflix.spinnaker.cats.agent.AgentSchedulerAware;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.cats.module.CatsModuleAware;
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Priority-based Redis agent scheduler that uses sorted sets for coordinated execution across
 * multiple clouddriver instances.
 *
 * <p>This scheduler provides enterprise-grade agent coordination with priority-based scheduling,
 * making it suitable for multi-instance deployments where execution order matters.
 *
 * <p>Key Features:
 *
 * <ul>
 *   <li>Priority scheduling using Redis sorted sets with timestamp scores
 *   <li>Atomic operations via Lua scripts for multi-instance coordination
 *   <li>ShardingFilter integration for distributed agent execution
 *   <li>DynamicConfigService support for runtime configuration changes
 *   <li>Comprehensive timeout handling and recovery mechanisms
 * </ul>
 *
 * <p>How it works:
 *
 * <ul>
 *   <li>Agents are stored in Redis sorted sets with timestamps as scores (lower scores = higher
 *       priority)
 *   <li>WAITING_SET contains agents ready for execution, sorted by next execution time
 *   <li>WORKING_SET contains agents currently being executed, sorted by timeout
 *   <li>Lua scripts ensure atomic transitions between sets across multiple instances
 * </ul>
 *
 * <p>Priority scheduling: Agents that missed their execution time get higher priority (lower
 * scores) than agents scheduled for later execution. This ensures critical agents don't get starved
 * during high load periods.
 *
 * <p>Atomicity: All state transitions use Lua scripts to prevent race conditions. Multiple
 * clouddriver instances can safely coordinate without double-executing agents or losing work.
 *
 * @see ClusteredAgentScheduler for the default Redis scheduler implementation
 * @see AgentScheduler for the base scheduler interface
 */
public class ClusteredSortAgentScheduler extends CatsModuleAware
    implements AgentScheduler<ClusteredSortAgentLock>, Runnable {
  private static enum Status {
    SUCCESS,
    FAILURE
  }

  private final JedisPool jedisPool;
  private final NodeStatusProvider nodeStatusProvider;
  private final AgentIntervalProvider intervalProvider;
  private final ExecutorService agentWorkPool;

  // Configuration constants - following ClusteredAgentScheduler pattern
  private final Pattern enabledAgentPattern;
  private final int redisRefreshPeriod;
  private final long schedulerIntervalMs;

  // Runtime state
  /**
   * NOW = 0: Used as offset in score() method to get current Redis timestamp. This is critical for
   * priority scheduling - agents with lower scores (earlier times) get higher priority. NOW means
   * "execute immediately", positive offsets mean "execute later".
   */
  private static final int NOW = 0;

  /**
   * Tracks scheduler execution cycles. Used to determine when to refresh Redis with all known
   * agents (every redisRefreshPeriod cycles). This prevents agent loss if Redis restarts or agents
   * get accidentally removed.
   */
  private int runCount = 0;

  private final Logger log;

  private Map<String, AgentWorker> agents;
  private Optional<Semaphore> runningAgents;

  // Agent state management: Agents can be in WAITING_SET (ready to run), WORKING_SET (currently
  // executing), or neither (not scheduled)
  @VisibleForTesting static final String WAITING_SET = "WAITZ";
  @VisibleForTesting static final String WORKING_SET = "WORKZ";
  private static final String ADD_AGENT_SCRIPT = "addAgentScript";
  private static final String VALID_SCORE_SCRIPT = "validScoreScript";
  private static final String SWAP_SET_SCRIPT = "swapSetScript";
  private static final String REMOVE_AGENT_SCRIPT = "removeAgentScript";
  private static final String CONDITIONAL_SWAP_SET_SCRIPT = "conditionalSwapSetScript";

  private ConcurrentHashMap<String, String> scriptShas;

  // Configuration constants to eliminate magic numbers
  private static final int DEFAULT_REDIS_REFRESH_PERIOD = 30;
  private static final long DEFAULT_SCHEDULER_INTERVAL_MS = 1000L;

  private final ShardingFilter shardingFilter;
  private final DynamicConfigService dynamicConfigService;

  /**
   * Create scheduler with default configuration suitable for most deployments. Follows the same
   * pattern as ClusteredAgentScheduler.
   *
   * @param jedisPool Redis connection pool for coordinated operations
   * @param nodeStatusProvider Provides node health status for scheduling decisions
   * @param intervalProvider Provides agent-specific execution intervals and timeouts
   * @param enabledAgentPattern Regex pattern for filtering which agents to schedule
   * @param parallelism Maximum concurrent agents (0 = unlimited)
   * @param shardingFilter Distributes agents across multiple clouddriver instances for HA
   *     deployments. Each instance only processes agents assigned to it, preventing duplicate work
   *     and enabling horizontal scaling across 40+ pods.
   * @param dynamicConfigService Enables runtime configuration changes without restarts. Critical
   *     for large deployments where different pods may need different limits based on available
   *     resources, load patterns, or operational requirements.
   */
  public ClusteredSortAgentScheduler(
      JedisPool jedisPool,
      NodeStatusProvider nodeStatusProvider,
      AgentIntervalProvider intervalProvider,
      String enabledAgentPattern,
      Integer parallelism,
      ShardingFilter shardingFilter,
      DynamicConfigService dynamicConfigService) {
    this(
        jedisPool,
        nodeStatusProvider,
        intervalProvider,
        enabledAgentPattern,
        parallelism,
        DEFAULT_REDIS_REFRESH_PERIOD,
        DEFAULT_SCHEDULER_INTERVAL_MS,
        shardingFilter,
        dynamicConfigService);
  }

  /**
   * Create scheduler with custom timing configuration. Follows the same pattern as
   * ClusteredAgentScheduler.
   *
   * @param jedisPool Redis connection pool for coordinated operations
   * @param nodeStatusProvider Provides node health status for scheduling decisions
   * @param intervalProvider Provides agent-specific execution intervals and timeouts
   * @param enabledAgentPattern Regex pattern for filtering which agents to schedule
   * @param parallelism Maximum concurrent agents (0 = unlimited)
   * @param redisRefreshPeriod How often to repopulate Redis with known agents (cycles)
   * @param schedulerIntervalMs How often the scheduler runs saturatePool() (milliseconds)
   * @param shardingFilter Distributes agents across multiple clouddriver instances for HA
   *     deployments. Prevents duplicate work and enables horizontal scaling. Essential for
   *     deployments with 40+ pods processing 28K+ agents.
   * @param dynamicConfigService Enables runtime configuration changes without restarts. Allows
   *     operational tuning of concurrent limits, timeouts, and other parameters based on real-time
   *     load and resource availability.
   */
  public ClusteredSortAgentScheduler(
      JedisPool jedisPool,
      NodeStatusProvider nodeStatusProvider,
      AgentIntervalProvider intervalProvider,
      String enabledAgentPattern,
      Integer parallelism,
      Integer redisRefreshPeriod,
      Long schedulerIntervalMs,
      ShardingFilter shardingFilter,
      DynamicConfigService dynamicConfigService) {

    this.jedisPool = jedisPool;
    this.nodeStatusProvider = nodeStatusProvider;
    this.agents = new ConcurrentHashMap<>();
    this.intervalProvider = intervalProvider;
    this.log = LoggerFactory.getLogger(getClass());

    // Apply configuration following ClusteredAgentScheduler pattern
    this.enabledAgentPattern = Pattern.compile(enabledAgentPattern);
    this.redisRefreshPeriod =
        redisRefreshPeriod != null ? redisRefreshPeriod : DEFAULT_REDIS_REFRESH_PERIOD;
    this.schedulerIntervalMs =
        schedulerIntervalMs != null ? schedulerIntervalMs : DEFAULT_SCHEDULER_INTERVAL_MS;

    if (parallelism > 0) {
      this.runningAgents = Optional.of(new Semaphore(parallelism));
    } else {
      this.runningAgents = Optional.empty();
    }

    scriptShas = new ConcurrentHashMap<>();
    storeScripts();

    // Use cached thread pool like ClusteredAgentScheduler
    this.agentWorkPool =
        Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                .setNameFormat(AgentWorker.class.getSimpleName() + "-%d")
                .build());

    this.shardingFilter = shardingFilter;
    this.dynamicConfigService = dynamicConfigService;

    // Start the scheduler thread
    Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat(ClusteredSortAgentScheduler.class.getSimpleName() + "-%d")
                .build())
        .scheduleAtFixedRate(this, 0, this.schedulerIntervalMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Initialize and store Lua scripts in Redis for atomic operations.
   *
   * <p>Redis Lua scripts execute atomically, preventing race conditions when multiple clouddriver
   * instances coordinate agent execution. Without atomic operations, agents could be
   * double-executed or lost during state transitions.
   *
   * <p>Scripts stored: - ADD_AGENT_SCRIPT: Add agent to WAITING (if not in either set) -
   * SWAP_SET_SCRIPT: Move agent WAITING → WORKING unconditionally - CONDITIONAL_SWAP_SET_SCRIPT:
   * Move agent WORKING → WAITING (if score matches) - VALID_SCORE_SCRIPT: Check if agent lock is
   * still valid - REMOVE_AGENT_SCRIPT: Remove agent from both sets
   *
   * <p>Each script returns SHA hash for efficient execution via EVALSHA.
   *
   * @throws AgentSchedulingException if script loading fails
   */
  private void storeScripts() {
    try (Jedis jedis = jedisPool.getResource()) {
      // SCRIPT 1: Unconditional agent state transition (WORKING → WAITING)
      // Used when releasing agents after execution completion
      scriptShas.put(
          SWAP_SET_SCRIPT,
          jedis.scriptLoad(
              "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                  + "if score ~= nil then\n" // If agent exists in source set
                  + "  redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from source
                  + "  redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])\n" // Add to destination
                  + "  return score\n" // Return original score as confirmation
                  + "else return nil end\n")); // Agent wasn't in source set

      // SCRIPT 2: Conditional agent state transition (WORKING → WAITING)
      // Used for safe agent release - only moves agent if we still own it
      scriptShas.put(
          CONDITIONAL_SWAP_SET_SCRIPT,
          jedis.scriptLoad(
              "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                  + "if score == ARGV[3] then\n" // If current score matches expected (we own it)
                  + "  redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from source
                  + "  redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])\n" // Add to destination
                  + "  return score\n" // Return original score as confirmation
                  + "else return nil end\n")); // Score mismatch - we don't own this agent

      // SCRIPT 3: Agent ownership validation
      scriptShas.put(
          VALID_SCORE_SCRIPT,
          jedis.scriptLoad(
              "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                  + "if score == ARGV[2] then\n" // If score matches our expectation
                  + "  return score\n" // We still own it
                  + "else return nil end\n")); // Ownership lost or agent not found

      // SCRIPT 4: Safe agent addition (WAITING_SET only if not in either set)
      scriptShas.put(
          ADD_AGENT_SCRIPT,
          jedis.scriptLoad(
              "if redis.call('zrank', KEYS[1], ARGV[1]) == nil then\n" // If NOT in WAITING_SET
                  + "  if redis.call('zrank', KEYS[2], ARGV[1]) == nil then\n" // AND NOT in
                  // WORKING_SET
                  + "    return redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n" // Add to
                  // WAITING_SET
                  + "  else return nil end\n" // Agent is currently executing
                  + "else return nil end\n")); // Agent already waiting

      // SCRIPT 5: Complete agent removal (cleanup)
      scriptShas.put(
          REMOVE_AGENT_SCRIPT,
          jedis.scriptLoad(
              "redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from WAITING_SET
                  + "redis.call('zrem', KEYS[2], ARGV[1])\n")); // Remove from WORKING_SET
    }
  }

  /**
   * Retrieve SHA hash for a Lua script, reloading if necessary.
   *
   * <p>Redis stores Lua scripts by SHA hash for efficient execution. This method: 1. Returns cached
   * SHA if available and script exists in Redis 2. Reloads all scripts if SHA is missing or script
   * was evicted 3. Throws exception if script loading fails
   *
   * <p>This handles Redis restarts and script eviction gracefully.
   *
   * @param scriptName Name of the script (e.g., ADD_AGENT_SCRIPT)
   * @param jedis Redis connection to check script existence
   * @return SHA hash for EVALSHA execution
   * @throws AgentSchedulingException if script cannot be loaded
   */
  private String getScriptSha(String scriptName, Jedis jedis) {
    String scriptSha = scriptShas.get(scriptName);
    if (scriptSha == null) {
      storeScripts();
      scriptSha = scriptShas.get(scriptName);
      if (scriptSha == null) {
        throw new AgentSchedulingException("Failed to load caching scripts.");
      }
    }

    if (!jedis.scriptExists(scriptSha)) {
      storeScripts();
      scriptSha = scriptShas.get(scriptName); // Get updated SHA after reload
      if (scriptSha == null) {
        throw new AgentSchedulingException("Failed to reload caching scripts.");
      }
    }

    return scriptSha;
  }

  /**
   * Schedule an agent for execution using Redis sorted sets for priority-based scheduling.
   *
   * <p>Key Differences from Other Schedulers: - DefaultAgentScheduler: Uses fixed intervals, no
   * coordination between instances - ClusteredAgentScheduler: Uses Redis locks, random execution
   * order - ClusteredSortAgentScheduler: Uses Redis sorted sets, priority-based execution order
   *
   * <p>How Priority Scheduling Works: Agents are stored in Redis sorted sets with timestamps as
   * scores WAITING_SET contains agents ready to run (sorted by next execution time) WORKING_SET
   * contains agents currently being executed Agents missed in previous runs get higher priority
   * (lower scores)
   *
   * <p>Agent Compatibility: This scheduler works with ALL agent types by following the proven
   * DefaultAgentScheduler pattern: - CachingAgent: Uses CacheExecution for cache operations -
   * RunnableAgent: Uses AgentExecution directly (maintenance agents) - Custom AgentExecution: Any
   * implementation works
   *
   * @param agent The agent to schedule (CachingAgent, RunnableAgent, etc.)
   * @param agentExecution The execution strategy (CacheExecution, AgentExecution, etc.)
   * @param executionInstrumentation Metrics and monitoring callbacks
   * @throws AgentSchedulingException if Redis operations fail
   */
  @Override
  public void schedule(
      Agent agent,
      AgentExecution agentExecution,
      ExecutionInstrumentation executionInstrumentation) {

    if (!enabledAgentPattern.matcher(agent.getAgentType().toLowerCase()).matches()) {
      log.debug(
          "Agent is not enabled (agent: {}, agentType: {}, pattern: {})",
          agent.getClass().getSimpleName(),
          agent.getAgentType(),
          enabledAgentPattern.pattern());
      return;
    }

    log.debug(
        "Scheduling agent: type={}, class={}",
        agent.getAgentType(),
        agent.getClass().getSimpleName());

    if (agent instanceof AgentSchedulerAware) {
      ((AgentSchedulerAware) agent).setAgentScheduler(this);
      log.debug("Agent {} is scheduler-aware, reference set", agent.getAgentType());
    }

    agents.put(
        agent.getAgentType(),
        new AgentWorker(agent, agentExecution, executionInstrumentation, this));

    log.debug(
        "Agent {} stored in local agents map, total agents: {}",
        agent.getAgentType(),
        agents.size());

    try (Jedis jedis = jedisPool.getResource()) {
      String currentScore = score(jedis, NOW);
      log.debug(
          "Adding agent {} to Redis WAITING_SET with score: {}",
          agent.getAgentType(),
          currentScore);

      Object result =
          jedis.evalsha(
              getScriptSha(ADD_AGENT_SCRIPT, jedis),
              2,
              WAITING_SET,
              WORKING_SET,
              agent.getAgentType(),
              currentScore);

      if (result != null) {
        log.debug("Agent {} successfully added to Redis WAITING_SET", agent.getAgentType());
      } else {
        log.debug(
            "Agent {} not added to Redis (already present in WAITING or WORKING set)",
            agent.getAgentType());
      }
    } catch (Exception e) {
      log.error("Failed to add agent {} to Redis WAITING_SET", agent.getAgentType(), e);
      throw new AgentSchedulingException("Redis operation failed during agent scheduling", e);
    }
  }

  /**
   * Attempt to acquire an exclusive lock for on-demand cache updates.
   *
   * <p>This scheduler supports atomic operations for on-demand cache updates, preventing race
   * conditions between scheduled and on-demand executions.
   *
   * @param agent The agent to lock for exclusive access
   * @return ClusteredSortAgentLock if successful, null if agent is already locked
   */
  @Override
  public ClusteredSortAgentLock tryLock(Agent agent) {
    ScoreTuple scores = acquireAgent(agent);
    if (scores != null) {
      return new ClusteredSortAgentLock(agent, scores.acquireScore, scores.releaseScore);
    } else {
      return null;
    }
  }

  /**
   * Release an exclusive lock acquired via tryLock().
   *
   * @param lock The lock to release
   * @return true if lock was successfully released, false if lock was invalid/expired
   */
  @Override
  public boolean tryRelease(ClusteredSortAgentLock lock) {
    return conditionalReleaseAgent(lock.getAgent(), lock.getAcquireScore(), lock.getReleaseScore())
        != null;
  }

  /**
   * Check if an exclusive lock is still valid.
   *
   * @param lock The lock to validate
   * @return true if lock is still held by this instance, false otherwise
   */
  @Override
  public boolean lockValid(ClusteredSortAgentLock lock) {
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis.evalsha(
              getScriptSha(VALID_SCORE_SCRIPT, jedis),
              1, // VALID_SCORE_SCRIPT only uses 1 key (WORKING_SET)
              WORKING_SET,
              lock.getAgent().getAgentType(),
              lock.getAcquireScore())
          != null;
    }
  }

  /**
   * Remove an agent from the scheduler completely.
   *
   * <p>This removes the agent from both Redis sets and the local agents map. The agent will no
   * longer be scheduled for execution.
   *
   * @param agent The agent to unschedule
   */
  @Override
  public void unschedule(Agent agent) {
    agents.remove(agent.getAgentType());
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.evalsha(
          getScriptSha(REMOVE_AGENT_SCRIPT, jedis),
          2,
          WAITING_SET,
          WORKING_SET,
          agent.getAgentType());
    }
  }

  /**
   * Indicates this scheduler supports atomic operations.
   *
   * <p>Atomic schedulers can coordinate on-demand cache updates with scheduled executions to
   * prevent race conditions and ensure data consistency.
   *
   * @return true - this scheduler supports atomic operations via Redis locks
   */
  @Override
  public boolean isAtomic() {
    return true;
  }

  /**
   * Main scheduler execution loop - called periodically by ScheduledExecutorService.
   *
   * <p>This method orchestrates the core scheduling logic: 1. Checks if this node is enabled for
   * scheduling 2. Calls saturatePool() to process ready agents 3. Handles any exceptions to prevent
   * scheduler death
   *
   * <p>Runs every schedulerIntervalMs (default: 1000ms).
   */
  @Override
  public void run() {
    if (!nodeStatusProvider.isNodeEnabled()) {
      return;
    }
    try {
      saturatePool();
    } catch (Throwable t) {
      log.error("Critical error in scheduler run cycle", t);
      // Don't rethrow - let scheduler continue and try again next cycle
    } finally {
      runCount++;
    }
  }

  /**
   * Generate Redis sorted set score based on current time + offset.
   *
   * <p>Lower scores = higher priority in Redis sorted sets. - NOW (0) = execute immediately
   * (highest priority) - Positive offset = execute later (lower priority)
   *
   * <p>This enables priority-based scheduling where agents with earlier execution times get
   * processed first.
   */
  @SuppressWarnings(
      "deprecation") // jedis.time() is deprecated but still the correct method for Redis TIME
  // coordination
  private static String score(Jedis jedis, long offset) {
    // Use Redis TIME command for server-side time coordination across multiple instances
    List<String> times = jedis.time();
    if (times == null || times.size() != 2) {
      throw new AgentSchedulingException("Error retrieving time from Redis");
    }
    int time = Integer.parseInt(times.get(0));
    return String.format("%d", time + offset);
  }

  /**
   * Calculate the next execution score for an agent based on its interval.
   *
   * <p>This determines when an agent should next be executed by adding its configured interval to
   * the current time. Agents with shorter intervals will have lower scores (higher priority) when
   * they become ready.
   *
   * @param agent The agent to calculate score for
   * @return Redis score representing next execution time
   */
  private String agentScore(Agent agent) {
    try (Jedis jedis = jedisPool.getResource()) {
      Double score = jedis.zscore(WORKING_SET, agent.getAgentType());
      if (score != null) {
        return score.toString();
      }

      score = jedis.zscore(WAITING_SET, agent.getAgentType());
      if (score != null) {
        return score.toString();
      }

      return null;
    }
  }

  /**
   * Core scheduling logic: Move agents from WAITING → WORKING and execute them.
   *
   * <p>This is the heart of the sort scheduler's priority-based execution. Called periodically by
   * the scheduler thread, this method: 1. Repopulates Redis with known agents (recovery from Redis
   * failures) 2. Cleans up stale agents in WORKING_SET (timeout handling) 3. Finds agents ready to
   * execute from WAITING_SET (priority order) 4. Atomically moves agents WAITING → WORKING
   * (prevents double execution) 5. Submits agents to thread pool for execution
   *
   * <p>Key Differences from Other Schedulers: - DefaultAgentScheduler: No coordination, each
   * instance runs independently - ClusteredAgentScheduler: Random agent selection, Redis locks -
   * ClusteredSortAgentScheduler: Priority-based selection using sorted sets
   *
   * <p>Priority Logic: Agents are sorted by score (timestamp). Lower scores = higher priority.
   * Agents that missed their execution time get priority over agents scheduled for later.
   *
   * <p>Concurrency Safety: All Redis operations use Lua scripts for atomicity. Multiple clouddriver
   * instances can safely coordinate without double-executing agents or losing work.
   */
  @VisibleForTesting
  void saturatePool() {
    log.debug("Starting saturatePool cycle {}, known agents: {}", runCount, agents.size());

    try (Jedis jedis = jedisPool.getResource()) {
      // PHASE 1: Agent Repopulation (Redis Recovery)
      // Occasionally repopulate Redis with all known agents in case Redis went down
      // or agents were lost. If agents already exist in Redis, this is a no-op.
      if (runCount % redisRefreshPeriod == 0) {
        log.debug("Redis refresh cycle - repopulating {} agents", agents.size());

        for (Map.Entry<String, AgentWorker> entry : agents.entrySet()) {
          String agentType = entry.getKey();
          AgentWorker worker = entry.getValue();

          // Apply sharding filter - only process agents assigned to this instance
          // ShardingFilter ensures distributed agent execution across multiple instances
          // Each instance only processes agents assigned to it, preventing duplicate work
          if (!shardingFilter.filter(worker.agent)) {
            log.debug(
                "Skipping agent {} - not assigned to this instance by sharding filter", agentType);
            continue;
          }

          String currentScore = score(jedis, NOW);
          log.debug("Adding agent {} to Redis WAITING_SET with score: {}", agentType, currentScore);

          Object result =
              jedis.evalsha(
                  getScriptSha(ADD_AGENT_SCRIPT, jedis),
                  2,
                  WAITING_SET,
                  WORKING_SET,
                  agentType,
                  currentScore);

          if (result != null) {
            log.debug("Repopulated agent {} in Redis with score {}", agentType, currentScore);
          }
        }
        log.debug("Redis refresh complete");
      }

      // PHASE 2: Stale Agent Cleanup (Timeout Handling)
      // Find agents that have been in WORKING_SET too long and release them.
      // This handles cases where agent execution threads died or hung.
      String timeoutScore = score(jedis, NOW); // Agents older than NOW are timed out
      Set<String> timedOutAgents = jedis.zrangeByScore(WORKING_SET, "-inf", timeoutScore);

      if (timedOutAgents != null && !timedOutAgents.isEmpty()) {
        log.warn(
            "Found {} timed-out agents in WORKING_SET: {}", timedOutAgents.size(), timedOutAgents);

        for (String agentType : timedOutAgents) {
          AgentWorker worker = agents.get(agentType);
          if (worker != null) {
            log.debug("Releasing timed-out agent: {}", agentType);
            releaseAgent(worker.agent);
          } else {
            log.warn("Timed-out agent {} not found in local agents map", agentType);
          }
        }
      }

      // PHASE 3: Ready Agent Discovery (Priority Selection)
      // Find agents ready to execute from WAITING_SET in priority order.
      // Lower scores = higher priority (agents that missed their time get priority)
      String currentScore = score(jedis, NOW);
      Set<String> readyAgentSet = jedis.zrangeByScore(WAITING_SET, "-inf", currentScore);
      List<String> readyAgents = new ArrayList<>(readyAgentSet);

      log.debug(
          "Found {} ready agents for execution (score <= {}): {}",
          readyAgents.size(),
          currentScore,
          readyAgents);

      // Apply dynamic configuration for max concurrent agents
      // DynamicConfigService allows runtime tuning without restarts - critical for large
      // deployments
      // where different pods may need different limits based on available resources or load
      // patterns
      Integer maxConcurrentAgents =
          dynamicConfigService.getConfig(Integer.class, "redis.agent.max-concurrent-agents", 1000);
      Integer currentlyRunning =
          runningAgents.map(s -> maxConcurrentAgents - s.availablePermits()).orElse(0);
      Integer availableSlots = maxConcurrentAgents - currentlyRunning;

      if (availableSlots <= 0) {
        log.debug(
            "Not acquiring more agents (maxConcurrentAgents: {}, currentlyRunning: {})",
            maxConcurrentAgents,
            currentlyRunning);
        return;
      }

      log.debug(
          "Available agent slots: {} (max: {}, running: {})",
          availableSlots,
          maxConcurrentAgents,
          currentlyRunning);

      // Pre-size collections for performance
      final int estimatedReadyAgents = Math.min(readyAgents.size(), availableSlots);
      Set<AgentWorker> workersToSubmit = new HashSet<>(estimatedReadyAgents);

      int threadsAcquired = 0;
      int agentsProcessed = 0;

      // PHASE 4: Agent Acquisition and Execution
      // Loop through ready agents in priority order, acquire them atomically,
      // and submit them to the thread pool for execution
      while (!readyAgents.isEmpty()
          && runningAgents.map(Semaphore::tryAcquire).orElse(true)
          && threadsAcquired < availableSlots) {
        threadsAcquired++;

        try {
          String agentType = readyAgents.remove(0); // Take highest priority agent
          agentsProcessed++;

          log.debug("Processing ready agent: {} (priority rank: {})", agentType, agentsProcessed);

          AgentWorker worker = agents.get(agentType);
          if (worker == null) {
            log.warn("Ready agent {} not found in local agents map, skipping", agentType);
            runningAgents.ifPresent(Semaphore::release);
            continue;
          }

          // Apply sharding filter - only process agents assigned to this instance
          // This is the second sharding check during execution phase to ensure that even if
          // an agent made it to the ready list, we double-check assignment before execution.
          // Critical for HA deployments where agent assignments may change dynamically.
          if (!shardingFilter.filter(worker.agent)) {
            log.debug(
                "Skipping agent {} - not assigned to this instance by sharding filter", agentType);
            runningAgents.ifPresent(Semaphore::release);
            continue;
          }

          // Atomically acquire the agent (WAITING → WORKING)
          // This prevents other clouddriver instances from executing the same agent
          ScoreTuple acquireResult = acquireAgent(worker.agent);
          if (acquireResult == null) {
            log.debug("Failed to acquire agent {} (already taken by another instance)", agentType);
            runningAgents.ifPresent(Semaphore::release);
            continue;
          }

          // Set the acquisition score for ownership verification during release
          worker.setScore(acquireResult.acquireScore);
          log.debug(
              "Successfully acquired agent {} with score {}",
              agentType,
              acquireResult.acquireScore);

          // Submit to thread pool for execution
          if (workersToSubmit.add(worker)) {
            agentWorkPool.submit(worker);
            log.debug("Submitted agent {} to execution thread pool", agentType);
          } else {
            log.warn("Agent {} already in submission set, releasing permit", agentType);
            runningAgents.ifPresent(Semaphore::release);
          }

        } catch (Throwable t) {
          log.error("Failed to process agent during saturatePool", t);
          runningAgents.ifPresent(Semaphore::release);
          // Continue processing other agents - don't let one failure stop the whole cycle
        }
      }

      log.debug(
          "SaturatePool cycle {} complete: processed={}, acquired={}, submitted={}",
          runCount,
          agentsProcessed,
          threadsAcquired,
          workersToSubmit.size());

    } catch (Exception e) {
      log.error("Critical error in saturatePool cycle {}", runCount, e);
      // Don't rethrow - let the scheduler continue and try again next cycle
    }
  }

  /**
   * Atomically acquire an agent for execution (WAITING → WORKING).
   *
   * <p>This method uses a Lua script to atomically move an agent from WAITING_SET to WORKING_SET,
   * preventing race conditions between multiple clouddriver instances.
   *
   * <p>The agent is assigned a timeout score based on its configured timeout interval. If the agent
   * execution doesn't complete within this time, it will be considered timed out and released by
   * the cleanup process.
   *
   * @param agent The agent to acquire for execution
   * @return ScoreTuple with acquire/release scores if successful, null if agent unavailable
   */
  private ScoreTuple acquireAgent(Agent agent) {
    try (Jedis jedis = jedisPool.getResource()) {
      String acquireScore = score(jedis, intervalProvider.getInterval(agent).getTimeout());
      Object releaseScore =
          jedis.evalsha(
              getScriptSha(SWAP_SET_SCRIPT, jedis),
              Arrays.asList(WAITING_SET, WORKING_SET),
              Arrays.asList(agent.getAgentType(), acquireScore));

      return releaseScore != null ? new ScoreTuple(acquireScore, releaseScore.toString()) : null;
    }
  }

  private ScoreTuple conditionalReleaseAgent(Agent agent, String acquireScore, Status status) {
    try (Jedis jedis = jedisPool.getResource()) {
      long newInterval =
          status == Status.SUCCESS
              ? intervalProvider.getInterval(agent).getInterval()
              : intervalProvider.getInterval(agent).getErrorInterval();
      String newAcquireScore = score(jedis, newInterval);
      Object releaseScore =
          jedis.evalsha(
              getScriptSha(CONDITIONAL_SWAP_SET_SCRIPT, jedis),
              Arrays.asList(WORKING_SET, WAITING_SET),
              Arrays.asList(agent.getAgentType(), newAcquireScore, acquireScore));

      return releaseScore != null ? new ScoreTuple(newAcquireScore, releaseScore.toString()) : null;
    }
  }

  private ScoreTuple conditionalReleaseAgent(
      Agent agent, String acquireScore, String newAcquireScore) {
    try (Jedis jedis = jedisPool.getResource()) {
      Object releaseScore =
          jedis
              .evalsha(
                  getScriptSha(CONDITIONAL_SWAP_SET_SCRIPT, jedis),
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  Arrays.asList(agent.getAgentType(), newAcquireScore, acquireScore))
              .toString();

      return releaseScore != null ? new ScoreTuple(newAcquireScore, releaseScore.toString()) : null;
    }
  }

  private ScoreTuple releaseAgent(Agent agent) {
    try (Jedis jedis = jedisPool.getResource()) {
      String acquireScore = score(jedis, intervalProvider.getInterval(agent).getInterval());
      Object releaseScore =
          jedis
              .evalsha(
                  getScriptSha(SWAP_SET_SCRIPT, jedis),
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  Arrays.asList(agent.getAgentType(), acquireScore))
              .toString();

      return releaseScore != null ? new ScoreTuple(acquireScore, releaseScore.toString()) : null;
    }
  }

  /**
   * Runnable wrapper for agent execution with proper cleanup and error handling.
   *
   * <p>Encapsulates the complete agent execution lifecycle: 1. Executes the agent via
   * AgentExecution.executeAgent() 2. Reports execution metrics via ExecutionInstrumentation 3.
   * Releases semaphore permits and Redis locks on completion 4. Handles both success and failure
   * scenarios gracefully
   */
  private static class AgentWorker implements Runnable {
    private final Agent agent;
    private final AgentExecution agentExecution;
    private final ExecutionInstrumentation executionInstrumentation;
    private final ClusteredSortAgentScheduler scheduler;
    private String acquireScore;

    AgentWorker(
        Agent agent,
        AgentExecution agentExecution,
        ExecutionInstrumentation executionInstrumentation,
        ClusteredSortAgentScheduler scheduler) {
      this.agent = agent;
      this.agentExecution = agentExecution;
      this.executionInstrumentation = executionInstrumentation;
      this.scheduler = scheduler;
    }

    public void setScore(String score) {
      acquireScore = score;
    }

    @Override
    public void run() {
      assert acquireScore != null;
      Status status = Status.FAILURE;
      long startTimeMs = System.currentTimeMillis();
      try {
        executionInstrumentation.executionStarted(agent);
        agentExecution.executeAgent(agent);
        executionInstrumentation.executionCompleted(agent, elapsedTimeMs(startTimeMs));
        status = Status.SUCCESS;
      } catch (Throwable cause) {
        executionInstrumentation.executionFailed(agent, cause, elapsedTimeMs(startTimeMs));
      } finally {
        scheduler.runningAgents.ifPresent(Semaphore::release);
        scheduler.conditionalReleaseAgent(agent, acquireScore, status);
      }
    }
  }

  /** Simple data holder for Redis acquisition and release scores. */
  private static class ScoreTuple {
    private final String acquireScore;
    private final String releaseScore;

    public ScoreTuple(String acquireScore, String releaseScore) {
      this.acquireScore = acquireScore;
      this.releaseScore = releaseScore;
    }
  }
}
