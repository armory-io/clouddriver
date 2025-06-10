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
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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
@Component
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
  private volatile Pattern enabledAgentPattern;
  private final int redisRefreshPeriod;
  private final long schedulerIntervalMs;

  // Runtime state
  /**
   * Local agent registry: Maps agent type to worker instance. This maintains the canonical list of
   * agents this scheduler instance knows about. Updated when agents are scheduled/unscheduled via
   * schedule()/unschedule() methods.
   */
  private final ConcurrentHashMap<String, AgentWorker> agents = new ConcurrentHashMap<>();

  // Agent execution tracking
  private final Optional<Semaphore> runningAgents;
  private final ShardingFilter shardingFilter;
  private final DynamicConfigService dynamicConfigService;

  // Zombie agent cleanup - simplified tracking
  private static class ActiveAgent {
    final Future<?> future;
    final long startTime;
    final String acquireScore;

    ActiveAgent(Future<?> future, long startTime, String acquireScore) {
      this.future = future;
      this.startTime = startTime;
      this.acquireScore = acquireScore;
    }
  }

  @VisibleForTesting
  final ConcurrentHashMap<String, ActiveAgent> activeAgents = new ConcurrentHashMap<>();

  private volatile long lastZombieCleanup = System.currentTimeMillis();
  @VisibleForTesting final AtomicLong zombiesCleanedUp = new AtomicLong(0);
  private volatile long lastConfigRefresh = System.currentTimeMillis();

  private final Logger log = LoggerFactory.getLogger(ClusteredSortAgentScheduler.class);

  private ScheduledExecutorService schedulerExecutorService;
  private ScheduledFuture<?> schedulerFuture;

  private ConcurrentHashMap<String, String> scriptShas;

  private static final int NOW = 0;
  private int runCount = 0;

  @VisibleForTesting static final String WAITING_SET = "WAITZ";
  @VisibleForTesting static final String WORKING_SET = "WORKZ";
  private static final String ADD_AGENT_SCRIPT = "addAgentScript";
  private static final String VALID_SCORE_SCRIPT = "validScoreScript";
  private static final String SWAP_SET_SCRIPT = "swapSetScript";
  private static final String REMOVE_AGENT_SCRIPT = "removeAgentScript";
  private static final String CONDITIONAL_SWAP_SET_SCRIPT = "conditionalSwapSetScript";
  private static final String CONDITIONAL_REMOVE_SCRIPT = "conditionalRemoveScript";

  private static final int DEFAULT_REDIS_REFRESH_PERIOD = 30;
  private static final long DEFAULT_SCHEDULER_INTERVAL_MS = 1000L;

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
  @Autowired
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
  @Autowired
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
    this.intervalProvider = intervalProvider;

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

    this.agentWorkPool =
        Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                .setNameFormat(AgentWorker.class.getSimpleName() + "-%d")
                .build());

    this.shardingFilter = shardingFilter;
    this.dynamicConfigService = dynamicConfigService;

    this.schedulerExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat(ClusteredSortAgentScheduler.class.getSimpleName() + "-%d")
                .build());
    this.scriptShas = new ConcurrentHashMap<>();
    storeScripts();
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

      // SCRIPT 6: Conditional agent removal (zombie cleanup)
      scriptShas.put(
          CONDITIONAL_REMOVE_SCRIPT,
          jedis.scriptLoad(
              "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                  + "if score == ARGV[2] then\n" // If current score matches expected (we own it)
                  + "  redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from WORKING_SET
                  + "  return score\n" // Return original score as confirmation
                  + "else return nil end\n")); // Score mismatch - we don't own this agent
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
    cleanupZombieAgentsIfNeeded();
    refreshConfigurationIfNeeded();

    log.debug("Starting saturatePool cycle {}, known agents: {}", runCount, agents.size());

    try (Jedis jedis = jedisPool.getResource()) {
      // Check concurrent agent limits before processing
      int maxConcurrentAgents =
          dynamicConfigService.getConfig(Integer.class, "redis.agent.max-concurrent-agents", 1000);
      int currentlyRunning = activeAgents.size();

      if (currentlyRunning >= maxConcurrentAgents) {
        log.debug(
            "Skipping agent acquisition - at max concurrent limit ({} running, {} max)",
            currentlyRunning,
            maxConcurrentAgents);
        return;
      }

      // PHASE 1: Agent Repopulation (Redis Recovery)
      if (runCount % redisRefreshPeriod == 0) {
        repopulateRedisAgents(jedis);
      }

      // PHASE 2: Cleanup expired agents from WORKING_SET
      cleanupExpiredWorkingAgents(jedis);

      // PHASE 3: Find ready agents in priority order
      String currentScore = score(jedis, System.currentTimeMillis());
      Set<String> readyAgents =
          jedis.zrangeByScore(WAITING_SET, 0, Double.parseDouble(currentScore));

      log.debug(
          "Found {} agents ready for execution at score {}", readyAgents.size(), currentScore);

      if (readyAgents.isEmpty()) {
        log.debug("No agents ready for execution");
        return;
      }

      // PHASE 4: Agent Acquisition and Execution
      Set<AgentWorker> workersToSubmit = new HashSet<>();
      int threadsAcquired = 0;

      for (String agentType : readyAgents) {
        if (threadsAcquired >= maxConcurrentAgents) {
          break;
        }

        if (!runningAgents.map(Semaphore::tryAcquire).orElse(true)) {
          break;
        }

        threadsAcquired++;
        AgentWorker worker = agents.get(agentType);
        if (worker == null) {
          log.warn("Ready agent {} not found in local agents map, skipping", agentType);
          runningAgents.ifPresent(Semaphore::release);
          continue;
        }

        ScoreTuple acquireResult = acquireAgent(worker.agent);
        if (acquireResult == null) {
          log.debug("Unable to acquire agent {} (likely acquired by another instance)", agentType);
          runningAgents.ifPresent(Semaphore::release);
          continue;
        }

        worker.acquireScore = acquireResult.acquireScore;

        log.debug(
            "Successfully acquired agent {} with score {}", agentType, acquireResult.acquireScore);

        // Submit to thread pool for execution with tracking
        if (workersToSubmit.add(worker)) {
          Future<?> future = agentWorkPool.submit(worker);

          // Track agent execution for zombie detection
          activeAgents.put(
              agentType, new ActiveAgent(future, System.currentTimeMillis(), worker.acquireScore));

          log.debug("Submitted agent {} to execution thread pool with tracking", agentType);
        }
      }

      log.debug(
          "Scheduler cycle {} completed: {} agents processed, {} threads acquired, {} workers submitted",
          runCount,
          readyAgents.size(),
          threadsAcquired,
          workersToSubmit.size());

    } catch (Exception e) {
      log.error("Failed to saturate pool: {}", e.getMessage(), e);
    }
  }

  /** Repopulate Redis with known agents for recovery scenarios. */
  private void repopulateRedisAgents(Jedis jedis) {
    log.debug("Repopulating Redis with {} known agents", agents.size());
    for (Map.Entry<String, AgentWorker> entry : agents.entrySet()) {
      try {
        String agentType = entry.getKey();
        Agent agent = entry.getValue().agent;

        if (shardingFilter.filter(agent)) {
          scheduleAgentInRedis(jedis, agent);
        }
      } catch (Exception e) {
        log.warn("Failed to repopulate agent {}: {}", entry.getKey(), e.getMessage());
      }
    }
  }

  /** Clean up agents that have been in WORKING_SET too long. */
  private void cleanupExpiredWorkingAgents(Jedis jedis) {
    long currentTime = System.currentTimeMillis();
    Set<String> expiredAgents = jedis.zrangeByScore(WORKING_SET, 0, currentTime);

    if (!expiredAgents.isEmpty()) {
      log.info("Found {} expired agents in WORKING_SET, cleaning up", expiredAgents.size());
      for (String agentType : expiredAgents) {
        try {
          jedis.evalsha(
              getScriptSha(REMOVE_AGENT_SCRIPT, jedis),
              Arrays.asList(WORKING_SET),
              Arrays.asList(agentType));
          log.debug("Removed expired agent {} from WORKING_SET", agentType);
        } catch (Exception e) {
          log.warn("Failed to remove expired agent {}: {}", agentType, e.getMessage());
        }
      }
    }
  }

  /** Schedule an agent in Redis using atomic operations. */
  private void scheduleAgentInRedis(Jedis jedis, Agent agent) {
    String currentScore = score(jedis, System.currentTimeMillis());
    jedis.evalsha(
        getScriptSha(ADD_AGENT_SCRIPT, jedis),
        Arrays.asList(WAITING_SET, WORKING_SET),
        Arrays.asList(agent.getAgentType(), currentScore));
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
          jedis.evalsha(
              getScriptSha(CONDITIONAL_SWAP_SET_SCRIPT, jedis),
              Arrays.asList(WORKING_SET, WAITING_SET),
              Arrays.asList(agent.getAgentType(), newAcquireScore, acquireScore));

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

  /** Clean up zombie agents that have been running too long. */
  private void cleanupZombieAgentsIfNeeded() {
    long now = System.currentTimeMillis();
    long cleanupInterval =
        dynamicConfigService.getConfig(
            Long.class, "redis.agent.zombie-cleanup-interval-ms", 300000L); // 5 minutes

    if (now - lastZombieCleanup > cleanupInterval) {
      cleanupZombieAgents();
      lastZombieCleanup = now;
    }
  }

  private void cleanupZombieAgents() {
    long zombieThreshold =
        dynamicConfigService.getConfig(
            Long.class, "redis.agent.zombie-threshold-ms", 3600000L); // 1 hour
    long currentTime = System.currentTimeMillis();
    List<String> zombieAgents = new ArrayList<>();

    // Find zombie agents
    for (Map.Entry<String, ActiveAgent> entry : activeAgents.entrySet()) {
      String agentType = entry.getKey();
      ActiveAgent activeAgent = entry.getValue();

      if ((currentTime - activeAgent.startTime) > zombieThreshold) {
        zombieAgents.add(agentType);
      }
    }

    if (zombieAgents.isEmpty()) {
      return;
    }

    log.warn(
        "Found {} zombie agents, cleaning up: {}",
        zombieAgents.size(),
        zombieAgents.stream().limit(5).collect(Collectors.toList()));

    // Clean up zombies
    int cleaned = 0;
    for (String agentType : zombieAgents) {
      try {
        cleanupZombieAgent(agentType);
        cleaned++;
      } catch (Exception e) {
        log.error("Failed to cleanup zombie agent {}: {}", agentType, e.getMessage());
      }
    }

    zombiesCleanedUp.addAndGet(cleaned);
    log.warn("Zombie cleanup completed: {}/{} agents cleaned", cleaned, zombieAgents.size());
  }

  private void cleanupZombieAgent(String agentType) {
    ActiveAgent activeAgent = activeAgents.remove(agentType);
    if (activeAgent == null) {
      return;
    }

    // Cancel the thread
    try {
      if (activeAgent.future.cancel(true)) {
        log.info("Cancelled zombie agent execution: {}", agentType);
      }
    } catch (Exception e) {
      log.warn("Failed to cancel zombie agent {}: {}", agentType, e.getMessage());
    }

    // Remove from Redis
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.evalsha(
          getScriptSha(CONDITIONAL_REMOVE_SCRIPT, jedis),
          Arrays.asList(WORKING_SET),
          Arrays.asList(
              agentType, activeAgent.acquireScore, String.valueOf(System.currentTimeMillis())));
      log.debug("Removed zombie agent {} from Redis WORKING_SET", agentType);
    } catch (Exception e) {
      log.warn("Failed to remove zombie agent {} from Redis: {}", agentType, e.getMessage());
    }

    // Release semaphore
    runningAgents.ifPresent(Semaphore::release);
  }

  /** Refresh dynamic configuration periodically. */
  private void refreshConfigurationIfNeeded() {
    long now = System.currentTimeMillis();
    if (now - lastConfigRefresh > 30000) {
      refreshConfiguration();
      lastConfigRefresh = now;
    }
  }

  private void refreshConfiguration() {
    try {
      // Refresh enabled agent pattern
      String enabledPattern =
          dynamicConfigService.getConfig(String.class, "redis.agent.enabled-pattern", ".*");
      this.enabledAgentPattern = Pattern.compile(enabledPattern, Pattern.CASE_INSENSITIVE);

      log.debug("Refreshed agent configuration - enabled pattern: {}", enabledPattern);
    } catch (Exception e) {
      log.warn("Failed to refresh agent configuration: {}", e.getMessage());
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
  private class AgentWorker implements Runnable {
    private final Agent agent;
    private final AgentExecution agentExecution;
    private final ExecutionInstrumentation executionInstrumentation;
    private final ClusteredSortAgentScheduler scheduler;
    String acquireScore;

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

    @Override
    public void run() {
      assert acquireScore != null;
      Status status = Status.FAILURE;
      long startTimeMs = System.currentTimeMillis();
      String agentType = agent.getAgentType();

      try {
        log.debug("Starting execution of agent: {}", agentType);
        executionInstrumentation.executionStarted(agent);
        agentExecution.executeAgent(agent);
        executionInstrumentation.executionCompleted(agent, elapsedTimeMs(startTimeMs));
        status = Status.SUCCESS;
        log.debug(
            "Successfully completed execution of agent: {} in {}ms",
            agentType,
            elapsedTimeMs(startTimeMs));
      } catch (Throwable cause) {
        if (cause instanceof InterruptedException) {
          log.warn("Agent {} execution was interrupted (likely due to zombie cleanup)", agentType);
          Thread.currentThread().interrupt(); // Restore interrupt status
        } else {
          log.error(
              "Agent {} execution failed after {}ms", agentType, elapsedTimeMs(startTimeMs), cause);
        }
        executionInstrumentation.executionFailed(agent, cause, elapsedTimeMs(startTimeMs));
      } finally {
        // Clean up tracking data - this happens for both normal and zombie cleanup
        scheduler.activeAgents.remove(agentType);

        scheduler.runningAgents.ifPresent(Semaphore::release);
        scheduler.conditionalReleaseAgent(agent, acquireScore, status);

        log.debug("Agent {} execution cleanup completed", agentType);
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

  @PostConstruct
  public void startScheduler() {
    schedulerFuture =
        schedulerExecutorService.scheduleAtFixedRate(
            this, 0, schedulerIntervalMs, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  public void stopScheduler() {
    schedulerFuture.cancel(true);
  }
}
