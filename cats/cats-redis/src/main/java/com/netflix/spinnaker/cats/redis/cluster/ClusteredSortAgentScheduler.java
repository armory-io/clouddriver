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
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
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
import redis.clients.jedis.Response;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.params.SetParams;

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
 * <h2>Performance Tuning Guide</h2>
 *
 * <p><strong>Default Configuration (recommended for most deployments):</strong>
 *
 * <pre>
 * redis.agent.scheduler-interval-ms: 1000        # 1 sec pickup cycles
 * redis.agent.refresh-period-seconds: 30         # 30 sec Redis sync
 * redis.agent.zombie-threshold-ms: 1800000       # 30 min zombie detection
 * redis.agent.zombie-cleanup-interval-ms: 300000 # 5 min cleanup cycles
 * </pre>
 *
 * <p><strong>Suitable for:</strong> Standard deployments with moderate cache agent load, normal
 * deployment frequency, and standard Redis resources.
 *
 * <p><strong>Performance:</strong> Moderate agent pickup latency with efficient resource
 * utilization and deployment protection.
 *
 * <p><strong>High-Load Configuration (for enterprise-scale deployments):</strong>
 *
 * <pre>
 * redis.agent.scheduler-interval-ms: 500         # 0.5 sec pickup cycles - very fast
 * redis.agent.refresh-period-seconds: 15         # 15 sec Redis sync - frequent updates
 * redis.agent.zombie-threshold-ms: 2100000       # 35 min zombie detection - allows 30min + buffer
 * redis.agent.zombie-cleanup-interval-ms: 120000 # 2 min cleanup cycles - very frequent
 * </pre>
 *
 * <p><strong>Suitable for:</strong> Large-scale deployments with higher cache agent load, frequent
 * deployments, and dedicated Redis infrastructure.
 *
 * <p><strong>Performance:</strong> Low agent pickup latency and responsive cache processing.
 *
 * <p><strong>Key Parameter Guidelines:</strong>
 *
 * <ul>
 *   <li><strong>scheduler-interval-ms:</strong> How often the scheduler runs saturatePool() to pick
 *       up waiting agents. <em>Mechanics:</em> Lower values = faster agent pickup but more CPU and
 *       Redis load as scheduler polls more frequently. <em>Tradeoffs:</em> 500ms gives sub-second
 *       response but doubles Redis ops. 2000ms reduces load but agents wait longer. Range:
 *       500-2000ms. Never below 500ms to avoid resource exhaustion.
 *   <li><strong>refresh-period-seconds:</strong> How often agents are repopulated from Spring
 *       context into Redis sorted sets. <em>Mechanics:</em> When clouddriver starts or agents are
 *       added/removed, this controls how quickly they appear in the scheduler. <em>Tradeoffs:</em>
 *       Lower values = faster agent discovery but more Redis write operations during startup. Scale
 *       inversely with agent count: 1000+ agents need 10-15s, 100+ agents can use 30-60s.
 *   <li><strong>zombie-threshold-ms:</strong> Maximum time an agent can stay in WORKING_SET before
 *       being considered stuck. <em>Critical:</em> Must be longer than longest agent timeout
 *       (typically 30min for AWS). Setting too low will kill legitimate long-running agents.
 *       <em>Mechanics:</em> Compares (current_time - agent_score) vs threshold. If exceeded, agent
 *       is moved back to WAITING_SET. Monitor P95 agent execution times and set threshold 15-20%
 *       above that value, minimum 35 minutes.
 *   <li><strong>zombie-cleanup-interval-ms:</strong> How often the zombie detection process runs to
 *       find stuck agents. <em>Mechanics:</em> Scans WORKING_SET for agents exceeding
 *       zombie-threshold-ms and cancels their threads. Should be 5-10x more frequent than threshold
 *       to prevent accumulation. High-load environments benefit from 2-3 minute cycles.
 * </ul>
 *
 * <p><strong>Batch Operations (Performance Optimization):</strong>
 *
 * <p>For large-scale deployments with many agents, batch operations provide significant performance
 * improvements by reducing the number of Redis round-trips. All batch operations are controlled by
 * a single feature flag:
 *
 * <pre>
 * redis.agent.batch-operations-enabled: false      # All batch operations (disabled by default for safety)
 * </pre>
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
  private final List<String> disabledAgents;

  // Configuration constants - following ClusteredAgentScheduler pattern
  private volatile Pattern enabledAgentPattern;
  private volatile int redisRefreshPeriod;
  private volatile long schedulerIntervalMs;

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
  static class ActiveAgent {
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
  private volatile boolean shuttingDown = false;
  private volatile long lastOrphanCleanup = System.currentTimeMillis();
  @VisibleForTesting final AtomicLong orphansCleanedUp = new AtomicLong(0);

  @VisibleForTesting static final String WAITING_SET = "WAITZ";
  @VisibleForTesting static final String WORKING_SET = "WORKZ";
  private static final String CLEANUP_LEADER_KEY = "CLEANUP_LEADER";
  private String currentLeadershipId = null; // Stores the current leadership ID for this instance
  private static final String ADD_AGENT_SCRIPT = "addAgentScript";
  private static final String VALID_SCORE_SCRIPT = "validScoreScript";
  private static final String SWAP_SET_SCRIPT = "swapSetScript";
  private static final String REMOVE_AGENT_SCRIPT = "removeAgentScript";
  private static final String CONDITIONAL_SWAP_SET_SCRIPT = "conditionalSwapSetScript";
  private static final String CONDITIONAL_REMOVE_SCRIPT = "conditionalRemoveScript";
  private static final String BATCH_ORPHAN_REMOVE_SCRIPT = "batchOrphanRemoveScript";

  // Batch operation scripts for reduced per-agent overhead through batched operations
  private static final String BATCH_ADD_AGENTS_SCRIPT = "batchAddAgentsScript";
  private static final String BATCH_CLEANUP_AGENTS_SCRIPT = "batchCleanupAgentsScript";

  private static final String ORPHAN_REMOVE_SCRIPT = "orphanRemoveScript";

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
   * @param disabledAgents List of specific agent types to explicitly disable, regardless of the
   *     enabledAgentPattern. This allows for quickly disabling problematic agents without changing
   *     regex patterns.
   */
  @Autowired
  public ClusteredSortAgentScheduler(
      JedisPool jedisPool,
      NodeStatusProvider nodeStatusProvider,
      AgentIntervalProvider intervalProvider,
      String enabledAgentPattern,
      Integer parallelism,
      ShardingFilter shardingFilter,
      DynamicConfigService dynamicConfigService,
      List<String> disabledAgents) {
    this(
        jedisPool,
        nodeStatusProvider,
        intervalProvider,
        enabledAgentPattern,
        parallelism,
        dynamicConfigService.getConfig(
            Integer.class, "redis.agent.refresh-period-seconds", DEFAULT_REDIS_REFRESH_PERIOD),
        dynamicConfigService.getConfig(
            Long.class, "redis.agent.scheduler-interval-ms", DEFAULT_SCHEDULER_INTERVAL_MS),
        shardingFilter,
        dynamicConfigService,
        disabledAgents);
  }

  /**
   * Create scheduler with custom timing configuration. Follows the same pattern as
   * ClusteredAgentScheduler.
   *
   * <p><b>Note:</b> Runtime configuration via DynamicConfigService takes precedence over
   * constructor parameters. Constructor parameters serve as fallback defaults if dynamic
   * configuration is unavailable.
   *
   * @param jedisPool Redis connection pool for coordinated operations
   * @param nodeStatusProvider Provides node health status for scheduling decisions
   * @param intervalProvider Provides agent-specific execution intervals and timeouts
   * @param enabledAgentPattern Default regex pattern for filtering which agents to schedule
   *     (overridden by redis.agent.enabled-pattern)
   * @param parallelism Maximum concurrent agents (0 = unlimited)
   * @param redisRefreshPeriod Default refresh period in cycles (overridden by
   *     redis.agent.refresh-period-seconds)
   * @param schedulerIntervalMs Default scheduler interval in milliseconds (overridden by
   *     redis.agent.scheduler-interval-ms)
   * @param shardingFilter Distributes agents across multiple clouddriver instances for HA
   *     deployments. Prevents duplicate work and enables horizontal scaling. Essential for
   *     deployments with 40+ pods processing 28K+ agents.
   * @param dynamicConfigService Enables runtime configuration changes without restarts. Allows
   *     operational tuning of concurrent limits, timeouts, and other parameters based on real-time
   *     load and resource availability.
   *     <p><b>Other Dynamic Configuration Keys:</b>
   *     <ul>
   *       <li><code>redis.agent.enabled-pattern</code> - Regex pattern for filtering agents
   *       <li><code>redis.agent.refresh-period-seconds</code> - How often to repopulate Redis
   *           agents
   *       <li><code>redis.agent.scheduler-interval-ms</code> - How often scheduler runs
   *       <li><code>redis.agent.zombie-threshold-ms</code> - Zombie agent detection threshold
   *       <li><code>redis.agent.zombie-cleanup-interval-ms</code> - Zombie cleanup frequency
   *     </ul>
   *
   * @param disabledAgents List of specific agent types to explicitly disable, regardless of the
   *     enabledAgentPattern. Provides a way to quickly disable problematic agents without changing
   *     the global pattern.
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
      DynamicConfigService dynamicConfigService,
      List<String> disabledAgents) {

    this.jedisPool = jedisPool;
    this.nodeStatusProvider = nodeStatusProvider;
    this.intervalProvider = intervalProvider;

    // Apply configuration following ClusteredAgentScheduler pattern
    this.enabledAgentPattern =
        Pattern.compile(
            enabledAgentPattern != null ? enabledAgentPattern : ".*", Pattern.CASE_INSENSITIVE);

    this.disabledAgents =
        disabledAgents != null
            ? disabledAgents.stream().map(String::toLowerCase).collect(Collectors.toList())
            : Collections.emptyList();
    this.redisRefreshPeriod =
        redisRefreshPeriod != null ? redisRefreshPeriod : DEFAULT_REDIS_REFRESH_PERIOD;
    this.schedulerIntervalMs =
        schedulerIntervalMs != null ? schedulerIntervalMs : DEFAULT_SCHEDULER_INTERVAL_MS;

    if (parallelism > 0) {
      this.runningAgents = Optional.of(new Semaphore(parallelism));
    } else {
      this.runningAgents = Optional.empty();
    }

    // Create a bounded thread pool with proper rejection handling instead of unbounded cached pool

    // First determine maximum pool size based on config or available processors
    int maximumPoolSize =
        dynamicConfigService.getConfig(
            Integer.class,
            "redis.agent.thread-pool-size",
            Math.max(
                Runtime.getRuntime().availableProcessors() * 2,
                20)); // Scale with available processors

    // Use percentage of max size for core size (default 50%)
    int corePoolPercentage =
        dynamicConfigService.getConfig(
            Integer.class,
            "redis.agent.thread-pool-core-size-percentage",
            50); // Default to 50% of max size

    // Ensure percentage is valid (between 10% and 100%)
    corePoolPercentage = Math.min(Math.max(corePoolPercentage, 10), 100);

    // Calculate core pool size based on percentage, ensuring at least 1 thread
    int corePoolSize = Math.max(1, (maximumPoolSize * corePoolPercentage) / 100);

    // Configurable keep-alive time
    long keepAliveTime =
        dynamicConfigService.getConfig(
            Long.class, "redis.agent.thread-pool-keep-alive-seconds", 60L); // Default 60 seconds

    // Use bounded queue to prevent resource exhaustion
    int queueCapacity =
        dynamicConfigService.getConfig(
            Integer.class, "redis.agent.thread-pool-queue-size", 1000); // Default queue size

    this.agentWorkPool =
        new ThreadPoolExecutor(
            corePoolSize,
            maximumPoolSize,
            keepAliveTime,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            new ThreadFactoryBuilder()
                .setNameFormat(AgentWorker.class.getSimpleName() + "-%d")
                .build(),
            new RejectedExecutionHandler() {
              @Override
              public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                // CallerRunsPolicy - execute the task in the caller's thread as a fallback
                if (!executor.isShutdown()) {
                  r.run();
                }
              }
            }); // Throttle when queue is full

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
   * <p>Scripts are grouped by functional area:
   *
   * <p><strong>Agent Lifecycle Management:</strong>
   *
   * <ul>
   *   <li>{@code ADD_AGENT_SCRIPT}: Safely add agent to WAITING set (if not in either set)
   *   <li>{@code REMOVE_AGENT_SCRIPT}: Remove agent from both WAITING and WORKING sets
   * </ul>
   *
   * <p><strong>Agent State Transitions:</strong>
   *
   * <ul>
   *   <li>{@code SWAP_SET_SCRIPT}: Move agent WAITING → WORKING unconditionally
   *   <li>{@code CONDITIONAL_SWAP_SET_SCRIPT}: Move agent WORKING → WAITING (if score matches)
   *   <li>{@code VALID_SCORE_SCRIPT}: Check if agent lock is still valid
   * </ul>
   *
   * <p><strong>Optimization Scripts:</strong>
   *
   * <ul>
   *   <li>{@code ORPHAN_REMOVE_SCRIPT}: Remove a single orphaned agent
   *   <li>{@code BATCH_ORPHAN_REMOVE_SCRIPT}: Remove multiple orphaned agents in one operation
   *   <li>{@code BATCH_ADD_AGENTS_SCRIPT}: Add multiple agents in one operation
   *   <li>{@code BATCH_CLEANUP_AGENTS_SCRIPT}: Remove multiple zombie agents in one operation
   * </ul>
   *
   * <p>Each script is stored in Redis and returns a SHA hash for efficient execution via EVALSHA.
   *
   * @throws AgentSchedulingException if script loading fails
   */
  private void storeScripts() {
    try (Jedis jedis = jedisPool.getResource()) {
      // --- AGENT LIFECYCLE SCRIPTS ---

      // Add agent to WAITING set (only if not in either WAITING or WORKING set)
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
              "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                  + "if score ~= nil then\n" // If agent exists in source set
                  + "  redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from source
                  + "  redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])\n" // Add to destination
                  + "  return score\n" // Return original score as confirmation
                  + "else return nil end\n")); // Agent wasn't in source set

      // Move agent WORKING → WAITING conditionally (only if we still own it)
      scriptShas.put(
          CONDITIONAL_SWAP_SET_SCRIPT,
          jedis.scriptLoad(
              "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                  + "if score and tonumber(score) == tonumber(ARGV[3]) then\n" // Numeric comparison
                  + "  redis.call('zrem', KEYS[1], ARGV[1])\n" // Remove from source
                  + "  redis.call('zadd', KEYS[2], ARGV[2], ARGV[1])\n" // Add to destination
                  + "  return score\n" // Return original score as confirmation
                  + "else return nil end\n")); // Score mismatch - we don't own this agent

      // Validate agent ownership by checking score
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
                  + "  local agent = ARGV[i]\n" // Current agent name
                  + "  local expectedScore = ARGV[i+1]\n" // Expected score from our scan
                  + "  local currentScore = redis.call('zscore', KEYS[1], agent)\n" // Current Redis
                  // score
                  + "  if currentScore and tonumber(currentScore) == tonumber(expectedScore) then\n" // Exact match
                  + "    redis.call('zrem', KEYS[1], agent)\n" // Remove from WORKZ
                  + "    count = count + 1\n" // Increment success count
                  + "    table.insert(removed, agent)\n" // Add to removal list
                  + "  end\n"
                  + "end\n"
                  + "return {count, removed}\n")); // Return count and list for logging

      // Add multiple agents to WAITING set in a single operation
      scriptShas.put(
          BATCH_ADD_AGENTS_SCRIPT,
          jedis.scriptLoad(
              "local score = ARGV[1]\n" // Score for all agents (same for batch)
                  + "local addedCount = 0\n" // Track successful additions
                  + "for i=2,#ARGV do\n" // For each agent in batch
                  + "  local agent = ARGV[i]\n" // Current agent name
                  + "  local exists = redis.call('zscore', KEYS[1], agent) or redis.call('zscore', KEYS[2], agent)\n" // Check sets
                  + "  if not exists then\n" // If not in either set
                  + "    redis.call('zadd', KEYS[2], score, agent)\n" // Add to WAITING set
                  // (KEYS[2])
                  + "    addedCount = addedCount + 1\n" // Increment count
                  + "  end\n"
                  + "end\n"
                  + "return addedCount\n")); // Return count of successful additions

      // Remove multiple zombie agents from both sets in a single operation
      scriptShas.put(
          BATCH_CLEANUP_AGENTS_SCRIPT,
          jedis.scriptLoad(
              "local cleaned = 0\n" // Track number of agents removed
                  + "for i=1,#ARGV do\n" // For each agent in batch
                  + "  local agent = ARGV[i]\n" // Current agent name
                  + "  redis.call('zrem', KEYS[1], agent)\n" // Remove from WORKING
                  + "  redis.call('zrem', KEYS[2], agent)\n" // Remove from WAITING
                  + "  cleaned = cleaned + 1\n" // Increment count
                  + "end\n"
                  + "return cleaned\n")); // Return cleanup count

    } catch (Exception e) {
      throw new AgentSchedulingException("Failed to store Redis Lua scripts", e);
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
    String agentType = agent.getAgentType().toLowerCase();

    if (!enabledAgentPattern.matcher(agentType).matches() || disabledAgents.contains(agentType)) {
      log.debug(
          "Agent is not enabled (agent: {}, agentType: {}, pattern: {}) or is explicitly disabled",
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
    return conditionalReleaseAgent(
            lock.getAgent(), lock.getAcquireScore(), ClusteredSortAgentScheduler.Status.FAILURE)
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
   * <p>Lower scores = higher priority in Redis sorted sets: - NOW (0) = execute immediately
   * (highest priority) - Positive offset = execute later (lower priority)
   *
   * <p>This enables priority-based scheduling where agents with earlier execution times get
   * processed first.
   *
   * <p>Note: offsetMillis is expected to be in milliseconds (e.g., from
   * System.currentTimeMillis()), and will be converted to seconds before being added to the Redis
   * TIME (which is in seconds). This conversion is critical because Redis stores scores as seconds,
   * while Java timestamps are in milliseconds.
   */
  @SuppressWarnings(
      "deprecation") // jedis.time() is deprecated but still the correct method for Redis TIME
  // coordination
  private static final AtomicLong lastTimeCheck = new AtomicLong(0);

  private static final AtomicLong serverClientOffset = new AtomicLong(0);

  /**
   * Calculate a score for Redis sorted sets based on current time plus an offset. Uses a cached
   * time offset between Redis server and client to reduce Redis calls.
   *
   * @param jedis The Jedis connection
   * @param offsetMillis Offset to add to the current time in milliseconds
   * @return Score string for Redis sorted set
   */
  private String score(Jedis jedis, long offsetMillis) {
    long now = System.currentTimeMillis();
    long lastCheck = lastTimeCheck.get();

    // Get time cache duration from dynamic config (default 10 seconds)
    long timeCacheDurationMs =
        dynamicConfigService.getConfig(Long.class, "redis.agent.time-cache-duration-ms", 10000L);

    // Refresh the server-client offset if needed
    if (now - lastCheck > timeCacheDurationMs) {
      // Use Redis TIME command for server-side time coordination
      try {
        List<String> times = jedis.time();
        if (times != null && times.size() == 2) {
          // Redis TIME returns seconds and microseconds
          long serverTimeSeconds = Long.parseLong(times.get(0));
          long serverTimeMs = serverTimeSeconds * 1000;
          // Update the offset (server time - client time)
          serverClientOffset.set(serverTimeMs - now);
          lastTimeCheck.set(now);
        }
      } catch (Exception e) {
        // In case of Redis TIME command failure, we'll use client time
        // No need to throw exception, just log and continue
        log.warn("Failed to get Redis server time, using client time: {}", e.getMessage());
      }
    }

    // Get the current time accounting for server-client offset
    long adjustedTimeMs = now + serverClientOffset.get() + offsetMillis;
    long adjustedTimeSeconds = adjustedTimeMs / 1000;

    return String.format("%d", adjustedTimeSeconds);
  }

  /**
   * Determines if an agent is still valid according to the current configuration. This helps
   * distinguish between truly orphaned agents (from crashed pods) and agents that are no longer
   * needed due to account/configuration changes.
   *
   * @param agentType The agent type to validate
   * @return True if the agent should still be scheduled according to current config, false
   *     otherwise
   */
  private boolean isAgentStillValid(String agentType) {
    // First check if it's in our local agents map, which gets updated via schedule/unschedule calls
    if (!agents.containsKey(agentType)) {
      log.debug(
          "Agent {} is no longer in local agents map, likely removed via API/config change",
          agentType);
      return false;
    }

    // Additional validation logic could be added here, like checking the provider registry
    // or querying account information

    return true;
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
      // Use pipelined operations to reduce round-trips to Redis
      redis.clients.jedis.Pipeline pipeline = jedis.pipelined();

      // Queue both score lookups in a single pipeline
      Response<Double> workingScore = pipeline.zscore(WORKING_SET, agent.getAgentType());
      Response<Double> waitingScore = pipeline.zscore(WAITING_SET, agent.getAgentType());

      // Execute the pipeline
      pipeline.sync();

      // Check results in order of priority
      Double score = workingScore.get();
      if (score != null) {
        return score.toString();
      }

      score = waitingScore.get();
      if (score != null) {
        return score.toString();
      }

      return null;
    } catch (Exception e) {
      log.warn("Failed to get agent score for {}: {}", agent.getAgentType(), e.getMessage());
      return null;
    }
  }

  /**
   * Core scheduling logic: Move agents from WAITING → WORKING and execute them.
   *
   * <p>This is the heart of the sort scheduler's priority-based execution. Called periodically by
   * the scheduler thread, this method: 1. Periodically repopulates Redis with known agents (e.g.,
   * every 30 seconds, for recovery). 2. Finds agents ready to execute from WAITING_SET based on
   * their scores (priority order). 3. Atomically moves ready agents from WAITING_SET to WORKING_SET
   * (prevents double execution). 4. Submits acquired agents to a thread pool for execution.
   *
   * <p>Stale or "zombie" agent cleanup (agents stuck in WORKING_SET for too long) is handled by
   * {@code cleanupZombieAgentsIfNeeded()}, which is checked at the beginning of each {@code
   * saturatePool} cycle but performs its full cleanup logic on a configurable interval (e.g., every
   * 5 minutes).
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

      // PHASE 1: Agent Repopulation (Redis Recovery, periodic)
      if (runCount % redisRefreshPeriod == 0) {
        repopulateRedisAgents(jedis);
      }

      // PHASE 2: Find ready agents in priority order
      String currentScore = score(jedis, 0L);
      Set<String> readyAgents =
          jedis.zrangeByScore(WAITING_SET, 0, Double.parseDouble(currentScore));

      log.debug(
          "Found {} agents ready for execution at score {}", readyAgents.size(), currentScore);

      if (readyAgents.isEmpty()) {
        log.debug("No agents ready for execution");
        return;
      }

      // PHASE 3: Agent Acquisition and Execution
      Set<AgentWorker> workersToSubmit = new HashSet<>();
      int agentsAcquiredThisCycle = 0;

      // Calculate how many new agents this pod can try to acquire
      int availableSlotsForNewAgents = maxConcurrentAgents - currentlyRunning;

      if (availableSlotsForNewAgents <= 0) {
        log.debug(
            "No available slots to acquire new agents this cycle ({} running, {} max). Skipping acquisition phase.",
            currentlyRunning,
            maxConcurrentAgents);
      } else if (readyAgents.isEmpty()) {
        log.debug("No agents ready for execution in WAITING_SET. Skipping acquisition phase.");
      } else {
        int effectiveMaxToAcquire = Math.min(availableSlotsForNewAgents, readyAgents.size());
        log.debug(
            "Attempting to acquire agents ({} running, {} max capacity, {} ready in Redis, limited by {} available slots and {} ready agents)",
            currentlyRunning,
            maxConcurrentAgents,
            readyAgents.size(),
            availableSlotsForNewAgents,
            effectiveMaxToAcquire); // Min of available slots and ready agent count

        for (String agentType : readyAgents) {
          if (agentsAcquiredThisCycle >= availableSlotsForNewAgents) {
            log.debug(
                "Reached available slot limit for new agents this cycle ({} acquired out of {} target slots).",
                agentsAcquiredThisCycle,
                availableSlotsForNewAgents);
            break;
          }

          if (!runningAgents.map(Semaphore::tryAcquire).orElse(true)) {
            log.debug(
                "Instance concurrent agent limit reached (no permits from 'runningAgents' semaphore). Cannot acquire more agents this cycle.");
            break; // Stop trying if semaphore is full
          }

          // Semaphore permit acquired
          AgentWorker worker = agents.get(agentType);
          if (worker == null) {
            log.warn(
                "Ready agent {} not found in local agents map, releasing semaphore permit and skipping.",
                agentType);
            runningAgents.ifPresent(Semaphore::release);
            continue;
          }

          // Ensure we have a valid agent reference before attempting acquisition
          if (worker.agent == null) {
            log.warn(
                "Agent {} found in local map but has null agent reference, releasing semaphore permit and skipping.",
                agentType);
            runningAgents.ifPresent(Semaphore::release);
            continue;
          }

          ScoreTuple acquireResult = acquireAgent(worker.agent);
          if (acquireResult == null) {
            log.debug(
                "Unable to acquire agent {} from Redis (likely acquired by another instance), releasing semaphore permit and skipping.",
                agentType);
            runningAgents.ifPresent(Semaphore::release);
            continue;
          }

          // Successfully acquired from Redis and semaphore permit is held
          agentsAcquiredThisCycle++;
          worker.acquireScore = acquireResult.acquireScore;

          log.info(
              "Successfully acquired agent {} from Redis with score {} seconds. (Total acquired: {})",
              agentType,
              acquireResult.acquireScore,
              agentsAcquiredThisCycle);

          // Submit to thread pool for execution with tracking
          if (workersToSubmit.add(worker)) {
            Future<?> future = agentWorkPool.submit(worker);

            // Track agent execution for zombie detection
            activeAgents.put(
                agentType,
                new ActiveAgent(future, System.currentTimeMillis(), worker.acquireScore));

            log.debug("Submitted agent {} to execution thread pool with tracking", agentType);
          } else {
            // Should not happen if acquireAgent is unique per worker instance, but good to handle.
            log.warn(
                "Worker for agent {} was already in workersToSubmit set. Releasing semaphore permit.",
                agentType);
            runningAgents.ifPresent(Semaphore::release);
            agentsAcquiredThisCycle--; // Decrement as it was not actually submitted
          }
        }
      }

      log.debug(
          "Scheduler cycle {} completed: {} ready agents found in WAITING_SET, {} agents acquired this cycle, {} new workers submitted",
          runCount,
          readyAgents.size(),
          agentsAcquiredThisCycle,
          workersToSubmit.size());

    } catch (Exception e) {
      log.error("Failed to saturate pool: {}", e.getMessage(), e);
    }
  }

  /**
   * Clean up orphaned agents from the WAITZ set (waiting set). If an agent is in WAITZ but not in
   * our local agents map, it was likely removed from the configuration and should be removed from
   * Redis.
   *
   * @param jedis Redis connection to use
   * @return Number of orphaned agents removed from WAITZ
   */
  private int cleanupOrphanedAgentsFromWaitz(Jedis jedis) {
    // Note: This method doesn't actually use the orphan threshold for cleanup decisions,
    // it simply removes agents in WAITZ that aren't in the local registry.
    long cleanupStartTime = System.currentTimeMillis();
    int totalRemoved = 0;

    try {
      // Get current Redis time for timestamp comparisons
      long currentTimeSeconds = Long.parseLong(jedis.time().get(0));

      // Get agents from the WAITZ set with scores in the past (ready for execution)
      // These are the problematic ones that should have been executed already
      Set<Tuple> agentsWithScores =
          jedis.zrangeByScoreWithScores(WAITING_SET, "-inf", String.valueOf(currentTimeSeconds));

      if (agentsWithScores.isEmpty()) {
        log.debug("No agents with past timestamps found in WAITZ");
        return 0;
      }

      log.debug(
          "Checking {} agents with past timestamps in WAITZ set for orphaned entries",
          agentsWithScores.size());

      // Find agents that are in WAITZ but not in our local map
      List<String> orphanedAgents = new ArrayList<>();
      for (Tuple tuple : agentsWithScores) {
        String agentType = tuple.getElement();
        long score = Double.valueOf(tuple.getScore()).longValue();

        if (!agents.containsKey(agentType)) {
          // This agent is in WAITZ but not in our local registry - it's orphaned
          orphanedAgents.add(agentType);
          log.debug(
              "Agent {} with score {} is not in local registry and will be removed (pastDue: {}s)",
              agentType,
              score,
              currentTimeSeconds - score);
        }
      }

      if (orphanedAgents.isEmpty()) {
        return 0;
      }

      // Remove all orphaned agents from WAITZ
      log.info(
          "Found {} orphaned agents in WAITZ set that are no longer in configuration",
          orphanedAgents.size());

      for (String orphanedAgent : orphanedAgents) {
        try {
          long removed = jedis.zrem(WAITING_SET, orphanedAgent);
          if (removed > 0) {
            totalRemoved++;
            log.info("Successfully removed orphaned agent {} from WAITZ set", orphanedAgent);
          }
        } catch (Exception e) {
          log.warn(
              "Failed to remove orphaned agent {} from WAITZ set: {}",
              orphanedAgent,
              e.getMessage());
        }
      }

      log.info(
          "Removed {} orphaned agents from WAITZ set in {}ms",
          totalRemoved,
          System.currentTimeMillis() - cleanupStartTime);
    } catch (Exception e) {
      log.error("Error during WAITZ orphaned agent cleanup: {}", e.getMessage(), e);
    }

    return totalRemoved;
  }

  /** Repopulate Redis with known agents for recovery scenarios using batch operations. */
  private void repopulateRedisAgents(Jedis jedis) {
    if (agents.isEmpty()) {
      return;
    }

    log.debug("Repopulating Redis with {} known agents", agents.size());

    // Clean up any orphaned agents in WAITZ that are no longer in our configuration
    int orphansRemoved = cleanupOrphanedAgentsFromWaitz(jedis);

    // Check if batch operations are enabled (disabled by default for safety)
    boolean batchOperationsEnabled =
        dynamicConfigService.getConfig(
            Boolean.class, "redis.agent.batch-operations-enabled", false);

    if (!batchOperationsEnabled) {
      log.debug("Batch repopulation disabled, using individual operations");
      // Use individual operations (existing proven approach)
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
      return;
    }

    // Collect agents that pass sharding filter
    List<String> agentsToAdd = new ArrayList<>();
    // Use score(jedis, 0) for immediate execution or current time for normal scheduling
    // For new agents, schedule them for "now" by using 0L as the offset.
    String defaultScore = score(jedis, 0L); // Default score for new agents

    for (Map.Entry<String, AgentWorker> entry : agents.entrySet()) {
      try {
        String agentType = entry.getKey();
        Agent agent = entry.getValue().agent;

        if (shardingFilter.filter(agent)) {
          agentsToAdd.add(agentType);
        }
      } catch (Exception e) {
        log.warn(
            "Failed to evaluate agent {} for repopulation: {}", entry.getKey(), e.getMessage());
      }
    }

    if (agentsToAdd.isEmpty()) {
      log.debug("No agents to repopulate after sharding filter");
      return;
    }

    // Batch add agents using single Redis call
    try {
      List<String> scriptArgs = new ArrayList<>();
      scriptArgs.add(defaultScore); // First arg is the score
      scriptArgs.addAll(agentsToAdd); // Remaining args are agent names

      Object result =
          jedis.evalsha(
              getScriptSha(BATCH_ADD_AGENTS_SCRIPT, jedis),
              Arrays.asList(WAITING_SET, WORKING_SET),
              scriptArgs);

      int added = result instanceof Long ? ((Long) result).intValue() : 0;
      log.debug("Repopulation completed: {}/{} agents added to Redis", added, agentsToAdd.size());

    } catch (Exception e) {
      log.error(
          "Batch repopulation failed, falling back to individual operations: {}", e.getMessage());
      // Fallback to individual operations
      for (String agentType : agentsToAdd) {
        try {
          scheduleAgentInRedis(jedis, agents.get(agentType).agent);
        } catch (Exception fallbackError) {
          log.warn(
              "Failed to repopulate agent {} individually: {}",
              agentType,
              fallbackError.getMessage());
        }
      }
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
      // Always assign a proper timeout score when moving to WORKZ
      String acquireScore = score(jedis, intervalProvider.getInterval(agent).getTimeout());

      // Atomically move agent from WAITING_SET to WORKING_SET
      Object releaseScore =
          jedis.evalsha(
              getScriptSha(SWAP_SET_SCRIPT, jedis),
              Arrays.asList(WAITING_SET, WORKING_SET),
              Arrays.asList(agent.getAgentType(), acquireScore));

      return releaseScore != null ? new ScoreTuple(acquireScore, releaseScore.toString()) : null;
    } catch (Exception e) {
      log.error(
          "Failed to acquire agent {} from Redis due to exception: {}",
          agent.getAgentType(),
          e.getMessage(),
          e);
      // The error is already handled in saturatePool() where semaphore is released
      // This method only does Redis operations, not semaphore management
      return null;
    }
  }

  /**
   * Conditionally releases an agent, moving it from the {@code WORKING_SET} to the {@code
   * WAITING_SET} in Redis. This version is typically called by an {@link AgentWorker} upon
   * completion or error.
   *
   * <p>If the scheduler is shutting down ({@link #shuttingDown} is true), the agent is re-queued
   * with a zero interval (i.e., for immediate execution by another available scheduler instance).
   * Otherwise, the new interval is determined by the agent's execution {@link Status}.
   *
   * @param agent The agent to release.
   * @param acquireScoreInWorkZ The score the agent had when it was acquired and put into {@code
   *     WORKING_SET}. This is used by the conditional Lua script to ensure atomicity.
   * @param status The execution status of the agent.
   * @return A {@link ScoreTuple} containing the new score in {@code WAITING_SET} and the release
   *     score from Redis, or null if release failed.
   */
  private ScoreTuple conditionalReleaseAgent(
      Agent agent, String acquireScoreInWorkZ, Status status) {
    long newInterval;
    if (this.shuttingDown) {
      newInterval = 0; // Reschedule immediately (score will be current time)
      log.info(
          "Scheduler shutting down: Re-queuing agent {} immediately from conditionalReleaseAgent.",
          agent.getAgentType());
    } else {
      newInterval =
          status == Status.SUCCESS
              ? intervalProvider.getInterval(agent).getInterval()
              : intervalProvider.getInterval(agent).getErrorInterval();
    }

    String newScoreForWaitingSet;
    try (Jedis jedis = jedisPool.getResource()) {
      newScoreForWaitingSet = score(jedis, newInterval);
    }

    // Call the helper method that performs the actual Redis operation.
    // acquireScoreInWorkZ is the agent's score it had in WORKING_SET.
    // newScoreForWaitingSet is the score it will get in WAITING_SET.
    return doConditionalRedisRelease(agent, acquireScoreInWorkZ, newScoreForWaitingSet);
  }

  /**
   * Helper method to perform the conditional Redis operation to move an agent from {@code
   * WORKING_SET} to {@code WAITING_SET}. Uses {@code CONDITIONAL_SWAP_SET_SCRIPT} for atomicity.
   *
   * @param agent The agent to release.
   * @param currentScoreInWorkZ The score the agent is expected to have in {@code WORKING_SET}.
   * @param newScoreForWaitZ The new score the agent will receive in {@code WAITING_SET}.
   * @return A {@link ScoreTuple} if successful, null otherwise.
   */
  private ScoreTuple doConditionalRedisRelease(
      Agent agent, String currentScoreInWorkZ, String newScoreForWaitZ) {
    try (Jedis jedis = jedisPool.getResource()) {
      // The script CONDITIONAL_SWAP_SET_SCRIPT moves from KEYS[1] (WORKING_SET) to KEYS[2]
      // (WAITING_SET)
      // ARGV[1] = agentType
      // ARGV[2] = newScoreForWaitZ (the new score for the agent in WAITING_SET)
      // ARGV[3] = currentScoreInWorkZ (the score the agent must currently have in WORKING_SET to be
      // moved)
      Object releaseResult =
          jedis.evalsha(
              getScriptSha(CONDITIONAL_SWAP_SET_SCRIPT, jedis),
              Arrays.asList(WORKING_SET, WAITING_SET), // KEYS
              Arrays.asList(agent.getAgentType(), newScoreForWaitZ, currentScoreInWorkZ)); // ARGS

      if (releaseResult != null) {
        log.debug(
            "Agent {} moved from WORKING_SET (score: {}) to WAITING_SET (new score: {}).",
            agent.getAgentType(),
            currentScoreInWorkZ,
            newScoreForWaitZ);
        return new ScoreTuple(newScoreForWaitZ, releaseResult.toString());
      } else {
        log.warn(
            "Failed to conditionally move agent {} from WORKING_SET (expected score: {}) to WAITING_SET. Agent not found in WORKING_SET with that score, or script failed.",
            agent.getAgentType(),
            currentScoreInWorkZ);
        return null;
      }
    } catch (Exception e) {
      log.error(
          "Exception during conditional release of agent {} from WORKING_SET (expected score: {}): {}",
          agent.getAgentType(),
          currentScoreInWorkZ,
          e.getMessage(),
          e);
      return null;
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
   * Attempts to acquire leader status for the orphaned agent cleanup process. Uses Redis SET with
   * NX option and expiry to implement a distributed lock.
   *
   * @return true if leadership was acquired, false otherwise
   */
  private boolean tryAcquireCleanupLeadership() {
    Long leadershipTtlMsObj =
        dynamicConfigService.getConfig(
            Long.class,
            "redis.agent.orphan-cleanup-leadership-ttl-ms",
            120000L); // Default 2 minutes
    long leadershipTtlMs = leadershipTtlMsObj != null ? leadershipTtlMsObj : 120000L;

    try (Jedis jedis = jedisPool.getResource()) {
      // Create a unique instance ID to identify this instance as the leader
      String instanceId = InetAddress.getLocalHost().getHostName() + "::" + UUID.randomUUID();

      // Use Redis SET with NX and EX options for atomic lock acquisition with built-in expiry
      // This prevents race conditions where a pod could acquire the lock but crash before setting
      // expiry
      int leadershipTtlSeconds = (int) (leadershipTtlMs / 1000);
      String result =
          jedis.set(
              CLEANUP_LEADER_KEY, instanceId, SetParams.setParams().nx().ex(leadershipTtlSeconds));

      boolean acquired = "OK".equals(result);
      if (acquired) {
        // Store the leadership ID for later release
        currentLeadershipId = instanceId;
        log.debug(
            "Acquired orphaned agent cleanup leadership with ID {} for {} seconds - this pod will perform cleanup operations",
            instanceId,
            leadershipTtlSeconds);
      } else {
        log.debug("Another pod is the cleanup leader - skipping orphaned agent cleanup");
      }

      return acquired;
    } catch (Exception e) {
      log.warn(
          "Failed to acquire cleanup leadership, will assume not the leader: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Releases leadership for orphaned agent cleanup if this pod is the current leader. This prevents
   * the lock from being held longer than necessary.
   */
  private void releaseCleanupLeadership() {
    // If we don't have a stored leadership ID, nothing to release
    if (currentLeadershipId == null) {
      return;
    }

    try (Jedis jedis = jedisPool.getResource()) {
      // Only delete the key if we own it (atomic check-and-delete)
      String script =
          "if redis.call('get', KEYS[1]) == ARGV[1] then "
              + "return redis.call('del', KEYS[1]) "
              + "else return 0 end";

      // Execute the Lua script
      Object result =
          jedis.eval(
              script,
              Collections.singletonList(CLEANUP_LEADER_KEY),
              Collections.singletonList(currentLeadershipId));

      if (result != null && ((Long) result) > 0) {
        log.debug("Released orphaned agent cleanup leadership with ID {}", currentLeadershipId);
        // Clear the leadership ID after successful release
        currentLeadershipId = null;
      }
    } catch (Exception e) {
      log.warn("Failed to release cleanup leadership: {}", e.getMessage());
    }
  }

  /**
   * Periodically checks for and cleans up two distinct types of problematic agents:
   *
   * <p>1. "Zombie" agents: Agents running for too long on THIS instance, tracked in the {@code
   * activeAgents} map. These can occur due to rate limits, excessive processing time, or other
   * performance issues. Configuration: {@code redis.agent.zombie-threshold-ms} (default: 30
   * minutes), {@code redis.agent.zombie-cleanup-interval-ms} (default: 5 minutes). This threshold
   * is optimized to allow long-running but legitimate operations to complete without interruption,
   * while still catching actual zombie agents that may be stuck.
   *
   * <p>2. "Orphaned" agents: Agents abandoned in the Redis {@code WORKZ} set because their
   * executing instance crashed or terminated unexpectedly. Since no instance is tracking them
   * anymore, they remain "stuck" in the WORKZ set and prevent proper scheduling. Configuration:
   * {@code redis.agent.orphan-threshold-ms} (default: 10 minutes), {@code
   * redis.agent.orphan-cleanup-interval-ms} (default: 2 minutes). Thresholds are intentionally
   * aggressive to minimize stale data exposure, while staying under the on-demand agent circuit
   * breaker (12 minutes) to prevent cascading failures.
   *
   * <p>These are separate problems requiring different detection mechanisms and thresholds.
   * Orphaned agents use more aggressive thresholds as they have no recovery path other than
   * explicit cleanup.
   */
  void cleanupZombieAgentsIfNeeded() {
    // Standard zombie cleanup based on local activeAgents map
    long now = System.currentTimeMillis();
    long zombieCleanupIntervalMs =
        dynamicConfigService.getConfig(
            Long.class, "redis.agent.zombie-cleanup-interval-ms", 300000L); // Default 5 minutes

    if (now - lastZombieCleanup > zombieCleanupIntervalMs) {
      log.debug(
          "Checking for zombie agents based on local activeAgents map ({} items).",
          activeAgents.size());
      cleanupZombieAgents(); // This cleans based on local activeAgents
      lastZombieCleanup = now;
    }

    // Comprehensive orphaned agent cleanup - scan both WORKZ and WAITZ sets for abandoned agents
    // Default cleanup interval: 2 minutes (aggressive to minimize time stale data persists)
    long orphanCleanupIntervalMs =
        dynamicConfigService.getConfig(
            Long.class, "redis.agent.orphan-cleanup-interval-ms", 120000L); // Default 2 minutes

    // Check if this pod should participate in orphan cleanup
    Boolean orphanCleanupEnabledObj =
        dynamicConfigService.getConfig(
            Boolean.class, "redis.agent.orphan-cleanup-enabled", true); // Default enabled
    boolean orphanCleanupEnabled = orphanCleanupEnabledObj != null ? orphanCleanupEnabledObj : true;

    // Only proceed if cleanup is enabled for this pod
    if (orphanCleanupEnabled && now - lastOrphanCleanup > orphanCleanupIntervalMs) {
      // Get the orphan threshold for cleanup - this is the proper place to check this configuration
      // since we're deciding whether to run orphan cleanup here
      Long orphanThresholdMsObj =
          dynamicConfigService.getConfig(
              Long.class, "redis.agent.orphan-threshold-ms", 10 * 60 * 1000L); // Default 10 minutes
      // Only attempt cleanup if we become the leader or if force refresh is enabled
      Boolean forceRefreshObj =
          dynamicConfigService.getConfig(
              Boolean.class, "redis.agent.orphan-cleanup.force-all-pods", false);
      boolean forceRefresh = forceRefreshObj != null ? forceRefreshObj : false;

      if (forceRefresh || tryAcquireCleanupLeadership()) {
        try {
          // New comprehensive cleanup that handles both WORKZ and WAITZ sets
          cleanupOrphanedAgents();
        } finally {
          // Release leadership if we acquired it
          if (!forceRefresh) {
            releaseCleanupLeadership();
          }
        }
      }

      // Always update the last cleanup timestamp even if we didn't run the cleanup
      // This prevents pods from constantly trying to run cleanup if leadership is held by another
      // pod
      lastOrphanCleanup = now;
    }
  }

  /**
   * Cleans up orphaned agents directly from the Redis WORKZ set.
   *
   * <p>Orphaned agents are entries in the Redis WORKZ set that are no longer tracked by any active
   * Clouddriver instance, typically due to instance crashes, terminations, or network partitions.
   * Unlike zombie agents (which are tracked locally but running too long), orphaned agents have no
   * running instance associated with them at all.
   *
   * <p>This cleanup mechanism is critical for preventing agent starvation in distributed
   * environments, where without it orphaned agents would remain "stuck" in the WORKZ set
   * indefinitely.
   *
   * <p>The detection works by scanning the Redis WORKZ set for agent timeouts older than a
   * configurable threshold ({@code redis.agent.orphan-threshold-ms}), and uses an atomic Lua script
   * for conditional removal in batches to efficiently process large numbers of orphans in high-load
   * environments. If orphaned agents are also found in the local activeAgents map, they are cleaned
   * up as well.
   *
   * <p>Configuration:
   *
   * <ul>
   *   <li>{@code redis.agent.orphan-threshold-ms} - How old (in ms) a WORKZ agent must be to be
   *       considered orphaned (default: 10min). Balances cleanup speed vs. risk of false positives.
   *   <li>{@code redis.agent.orphan-cleanup-interval-ms} - How often this cleanup process runs
   *       (default: 2min). Frequent scanning minimizes the time stale data persists.
   *   <li>{@code redis.agent.orphan-cleanup-enabled} - Whether this pod participates in orphaned
   *       agent cleanup (default: true). Useful in complex deployments where some pods have
   *       different agent filtering than others, allowing only pods with the most complete agent
   *       configuration to perform cleanup.
   *   <li>{@code redis.agent.orphan-cleanup-batch-size} - Number of orphans to clean in a single
   *       Redis operation (default: 50). This optimizes Redis performance in high-load
   *       environments.
   * </ul>
   *
   * <p>For both sets, we apply safety thresholds to avoid race conditions during deployment. The
   * safety threshold for WAITZ is longer than for WORKZ since WAITZ entries are legitimate pending
   * work.
   */
  void cleanupOrphanedAgents() {
    // Start timestamp for timing the entire cleanup process
    long orphanCleanupStartTime = System.currentTimeMillis();
    log.info("Starting comprehensive orphaned agent cleanup process for both WORKZ and WAITZ sets");

    // Track total agents cleaned across both sets
    int totalAgentsCleaned = 0;

    try (Jedis jedis = jedisPool.getResource()) {
      // First, clean up orphaned agents from WORKZ (working set)
      int workzCleaned = cleanupOrphanedAgentsFromWorkz(jedis);
      totalAgentsCleaned += workzCleaned;

      // Next, clean up orphaned agents from WAITZ (waiting set)
      int waitzCleaned = cleanupOrphanedAgentsFromWaitz(jedis);
      totalAgentsCleaned += waitzCleaned;

      log.warn(
          "Comprehensive orphaned agent cleanup completed: {} agents cleaned from WORKZ, {} from WAITZ.",
          workzCleaned,
          waitzCleaned);
    } catch (Exception e) {
      log.error("Error during comprehensive orphaned agent cleanup: {}", e.getMessage(), e);
    }

    log.info(
        "Comprehensive orphaned agent cleanup process took {}ms, removed {} total agents.",
        System.currentTimeMillis() - orphanCleanupStartTime,
        totalAgentsCleaned);
  }

  /**
   * Legacy method specifically for cleaning up the WORKZ set. Now delegates to the specialized
   * helper method with proper resource handling.
   */
  void cleanupOrphanedAgentsFromRedis() {
    // Start timestamp for timing the entire cleanup process
    long orphanCleanupStartTime = System.currentTimeMillis();
    log.info("Starting orphaned agent cleanup process for WORKZ set only");

    try (Jedis jedis = jedisPool.getResource()) {
      int cleaned = cleanupOrphanedAgentsFromWorkz(jedis);

      if (cleaned > 0) {
        orphansCleanedUp.addAndGet(cleaned);
        log.warn(
            "Orphaned agent cleanup cycle completed. Removed {} agents from WORKZ set. Total orphans cleaned by this instance: {}.",
            cleaned,
            orphansCleanedUp.get());
      }

      log.info(
          "Orphaned agent cleanup process from Redis WORKZ set took {}ms.",
          System.currentTimeMillis() - orphanCleanupStartTime);
    } catch (Exception e) {
      log.error("Error during orphaned agent cleanup from WORKZ: {}", e.getMessage(), e);
    }
  }

  private int cleanupOrphanedAgentsFromWorkz(Jedis jedis) {
    // Get orphan threshold from configuration
    Long orphanThresholdMsObj =
        dynamicConfigService.getConfig(
            Long.class, "redis.agent.orphan-threshold-ms", 10 * 60 * 1000L); // Default 10 minutes
    long orphanThresholdMs = orphanThresholdMsObj != null ? orphanThresholdMsObj : 10 * 60 * 1000L;
    // Start timestamp for timing the WORKZ cleanup process
    long orphanCleanupStartTime = System.currentTimeMillis();
    log.info("Starting orphaned agent cleanup from Redis WORKZ set.");
    // The orphan threshold is used to determine which agents in WORKZ have been abandoned

    // Check if batch operations are enabled and configure batch size accordingly
    Boolean batchOperationsEnabledObj =
        dynamicConfigService.getConfig(
            Boolean.class, "redis.agent.batch-operations-enabled", false);
    boolean batchOperationsEnabled =
        batchOperationsEnabledObj != null ? batchOperationsEnabledObj : false;

    int maxOrphanBatchSize = 50; // Default to 50 agents per batch

    if (batchOperationsEnabled) {
      Integer maxOrphanBatchSizeObj =
          dynamicConfigService.getConfig(
              Integer.class, "redis.agent.orphan-cleanup-batch-size", 50);
      maxOrphanBatchSize = maxOrphanBatchSizeObj != null ? maxOrphanBatchSizeObj : 50;
    }

    // Calculate the score cutoff for querying Redis. Agents with scores (timeout timestamps)
    // less than this cutoff are considered for cleanup
    // NOTE: Redis stores scores as seconds, while Java uses milliseconds
    long actualOrphanTimeCutoffMs = System.currentTimeMillis() - orphanThresholdMs;
    // Convert cutoff to seconds for Redis compatibility
    long actualOrphanTimeCutoffSec = actualOrphanTimeCutoffMs / 1000;

    // First, retrieve all potential orphaned agents
    Set<Tuple> potentialOrphans;
    try {
      potentialOrphans =
          jedis.zrangeByScoreWithScores(
              WORKING_SET,
              "-inf", // from the beginning of time
              String.valueOf(
                  actualOrphanTimeCutoffSec) // up to the calculated cutoff score (in seconds)
              );
    } catch (Exception e) {
      log.error(
          "Failed to retrieve potential orphaned agents from Redis WORKZ set: {}",
          e.getMessage(),
          e);
      return 0;
    }

    if (potentialOrphans == null || potentialOrphans.isEmpty()) {
      log.info("No potential orphaned agents found in WORKZ set older than the orphan threshold.");
      return 0;
    }

    log.warn(
        "Found {} potential orphaned agents in WORKZ set with timeouts older than {}ms ago (cutoff: {} seconds). Attempting cleanup.",
        potentialOrphans.size(),
        orphanThresholdMs,
        actualOrphanTimeCutoffSec);

    // If batch operations are enabled, attempt to use batch processing
    int totalSuccessfullyCleaned = 0;
    if (batchOperationsEnabled) {
      log.debug(
          "Using batch processing for orphaned agent cleanup of {} agents",
          potentialOrphans.size());
      try {
        // Prepare for batch processing
        List<Tuple> orphansList = new ArrayList<>(potentialOrphans);
        int totalOrphans = orphansList.size();
        int processedSoFar = 0;

        // Process in batches of maxOrphanBatchSize
        while (processedSoFar < totalOrphans) {
          // Determine the end index for this batch (not exceeding the list size)
          int batchEndIndex = Math.min(processedSoFar + maxOrphanBatchSize, totalOrphans);
          List<Tuple> currentBatch = orphansList.subList(processedSoFar, batchEndIndex);

          // Process the current batch using our batch Lua script
          int cleanedInBatch =
              processBatchOfOrphans(currentBatch, actualOrphanTimeCutoffSec, maxOrphanBatchSize);
          totalSuccessfullyCleaned += cleanedInBatch;

          // Move to the next batch
          processedSoFar = batchEndIndex;

          // Log progress for large batches
          if (totalOrphans > 100) {
            log.info(
                "Orphaned agent cleanup progress: {}% ({}/{} processed, {} cleaned)",
                (int) ((processedSoFar * 100.0) / totalOrphans),
                processedSoFar,
                totalOrphans,
                totalSuccessfullyCleaned);
          }
        }

        log.info(
            "Successfully cleaned {} orphaned agents using batch processing",
            totalSuccessfullyCleaned);
      } catch (Exception e) {
        // If batch processing fails, log the error but don't attempt individual processing
        // This prevents infinite recursion and allows tests to verify the behavior
        log.error(
            "Batch orphaned agent cleanup failed with error, skipping cleanup: {}", e.getMessage());
        // Return early to avoid falling through to individual processing
        return totalSuccessfullyCleaned;
      }
    }

    // If batch processing is disabled or failed, fall back to individual processing
    if (!batchOperationsEnabled) {
      log.debug("Batch orphaned agent cleanup disabled, using individual operations");
      for (Tuple orphanTuple : potentialOrphans) {
        String agentType = orphanTuple.getElement();
        // Score from Redis is a double, convert to string for Lua script argument
        String scoreInWorkZ = String.valueOf(orphanTuple.getScore());

        try {
          // First determine if this is a valid agent or an agent for a removed account
          boolean isStillValid = isAgentStillValid(agentType);

          if (isStillValid) {
            // For valid agents (truly orphaned due to crashes), move them to WAITZ for rescheduling
            // Use a Lua script to atomically remove from WORKZ and add to WAITZ
            String newScore = score(jedis, 0L); // Schedule for immediate execution
            Object result =
                jedis.evalsha(
                    getScriptSha(CONDITIONAL_SWAP_SET_SCRIPT, jedis),
                    Arrays.asList(WORKING_SET, WAITING_SET),
                    Arrays.asList(
                        agentType,
                        newScore, // New score in WAITING set
                        scoreInWorkZ // Expected score in WORKING set
                        ));

            if (result != null) {
              totalSuccessfullyCleaned++;
              log.info(
                  "Successfully moved orphaned agent {} (original score: {}, new score: {}) from WORKZ to WAITZ set.",
                  agentType,
                  Double.valueOf(scoreInWorkZ).longValue(),
                  Double.valueOf(newScore).longValue());

              // Also clean up local state if needed
              ActiveAgent localCopy = activeAgents.remove(agentType);
              if (localCopy != null) {
                log.warn(
                    "Orphaned agent {} (moved to WAITZ) was also found in this instance's local activeAgents map. Cleaning up local state.",
                    agentType);
                if (localCopy.future != null) {
                  localCopy.future.cancel(true);
                }
                runningAgents.ifPresent(Semaphore::release);
              }
            } else {
              log.debug(
                  "Failed to move orphaned agent {} (original score: {}) from WORKZ to WAITZ. It might have been removed or modified by another process.",
                  agentType,
                  Double.valueOf(scoreInWorkZ).longValue());
            }
          } else {
            // For invalid agents (removed accounts), completely remove them
            Object result =
                jedis.evalsha(
                    getScriptSha(ORPHAN_REMOVE_SCRIPT, jedis),
                    Collections.singletonList(WORKING_SET), // KEYS[1] = WORKING_SET
                    Arrays.asList(
                        agentType,
                        scoreInWorkZ) // ARGV[1] = agentType, ARGV[2] = expectedScoreInWorkZ
                    );

            if (result != null && ((Long) result).intValue() == 1) {
              totalSuccessfullyCleaned++;
              log.info(
                  "Successfully removed invalid orphaned agent {} (original score: {}) from WORKZ set and local registry.",
                  agentType,
                  Double.valueOf(scoreInWorkZ).longValue());

              // Remove from local registry to prevent re-adding
              agents.remove(agentType);

              // Also clean up local state if needed
              ActiveAgent localCopy = activeAgents.remove(agentType);
              if (localCopy != null) {
                if (localCopy.future != null) {
                  localCopy.future.cancel(true);
                }
                runningAgents.ifPresent(Semaphore::release);
              }
            } else {
              log.debug(
                  "Failed to remove invalid orphaned agent {} (score: {}) from WORKZ set. It might have been removed by another process.",
                  agentType,
                  Double.valueOf(scoreInWorkZ).longValue());
            }
          }
        } catch (Exception e) {
          log.error(
              "Error during orphaned agent cleanup attempt for {} (original score: {}): {}",
              agentType,
              Double.valueOf(scoreInWorkZ).longValue(),
              e.getMessage(),
              e);
        }
      }
    }

    if (totalSuccessfullyCleaned > 0) {
      orphansCleanedUp.addAndGet(totalSuccessfullyCleaned);
      log.warn(
          "Orphaned agent cleanup cycle completed. Removed {} agents from WORKZ set. Total orphans cleaned by this instance: {}.",
          totalSuccessfullyCleaned,
          orphansCleanedUp.get());
    }
    log.info(
        "Orphaned agent cleanup process from Redis WORKZ set took {}ms.",
        System.currentTimeMillis() - orphanCleanupStartTime);

    return totalSuccessfullyCleaned;
  }

  /**
   * Process a batch of orphaned agents, handling valid vs. invalid agents differently. Valid agents
   * (truly orphaned due to crashes) are moved from WORKZ → WAITZ. Invalid agents (removed accounts)
   * are completely removed from Redis and local registry.
   *
   * @param orphanBatch List of orphaned agent tuples to process
   * @param cutoffScoreSec The cutoff score in seconds (agents with scores before this are
   *     considered orphaned)
   * @param maxBatchSize Maximum number of agents to process in this batch
   * @return Number of agents successfully cleaned
   */
  @SuppressWarnings("unchecked")
  private int processBatchOfOrphans(
      List<Tuple> orphanBatch, long cutoffScoreSec, int maxBatchSize) {
    if (orphanBatch.isEmpty()) {
      return 0;
    }

    int totalProcessed = 0;

    try (Jedis jedis = jedisPool.getResource()) {
      // Classify orphans as valid (still configured) or invalid (removed accounts)
      Map<Boolean, List<Tuple>> partitionedOrphans =
          orphanBatch.stream()
              .collect(Collectors.partitioningBy(orphan -> isAgentStillValid(orphan.getElement())));

      // Process valid orphans - move them to WAITZ for proper rescheduling
      List<Tuple> validOrphans = partitionedOrphans.get(true);
      int validProcessed = processValidOrphans(jedis, validOrphans);

      // Process invalid orphans - remove them completely
      List<Tuple> invalidOrphans = partitionedOrphans.get(false);
      int invalidProcessed = processInvalidOrphans(jedis, invalidOrphans, maxBatchSize);

      // Log the results
      if (validProcessed > 0) {
        log.info(
            "Batch cleanup: Moved {} valid orphaned agents from WORKZ to WAITZ for rescheduling",
            validProcessed);
      }

      if (invalidProcessed > 0) {
        log.info(
            "Batch cleanup: Completely removed {} invalid orphaned agents (removed accounts)",
            invalidProcessed);
      }

      totalProcessed = validProcessed + invalidProcessed;
    } catch (Exception e) {
      log.error("Error during batch orphaned agent cleanup: {}", e.getMessage(), e);
    }

    return totalProcessed;
  }

  /**
   * Process valid orphaned agents by moving them from WORKZ → WAITZ for proper rescheduling. These
   * are agents that correspond to valid accounts but were orphaned due to pod crashes.
   */
  private int processValidOrphans(Jedis jedis, List<Tuple> validOrphans) {
    int processed = 0;

    // Process each valid orphan individually to move from WORKZ → WAITZ
    for (Tuple orphan : validOrphans) {
      String agentType = orphan.getElement();
      String scoreInWorkZ = String.valueOf(orphan.getScore());
      String newScore = score(jedis, 0L); // Schedule for immediate execution

      try {
        // Use conditional swap to atomically move from WORKZ → WAITZ
        Object result =
            jedis.evalsha(
                getScriptSha(CONDITIONAL_SWAP_SET_SCRIPT, jedis),
                Arrays.asList(WORKING_SET, WAITING_SET),
                Arrays.asList(agentType, newScore, scoreInWorkZ));

        if (result != null) {
          processed++;
          log.debug(
              "Moved valid orphaned agent {} from WORKZ (score: {}) to WAITZ (new score: {})",
              agentType,
              Double.valueOf(scoreInWorkZ).longValue(),
              Double.valueOf(newScore).longValue());

          // Clean up local execution state
          ActiveAgent localCopy = activeAgents.remove(agentType);
          if (localCopy != null) {
            if (localCopy.future != null) {
              localCopy.future.cancel(true);
            }
            runningAgents.ifPresent(Semaphore::release);
          }
        }
      } catch (Exception e) {
        log.warn("Failed to move valid orphaned agent {}: {}", agentType, e.getMessage());
      }
    }

    return processed;
  }

  /**
   * Process invalid orphaned agents (from removed accounts) by removing them completely from Redis
   * and the local registry to prevent reprocessing.
   */
  private int processInvalidOrphans(Jedis jedis, List<Tuple> invalidOrphans, int maxBatchSize) {
    if (invalidOrphans.isEmpty()) {
      return 0;
    }

    int processed = 0;

    try {
      // Build arguments for batch removal: [maxBatchSize, agent1, score1, agent2, score2, ...]
      List<String> batchArgs = new ArrayList<>(1 + invalidOrphans.size() * 2);
      batchArgs.add(String.valueOf(maxBatchSize));

      for (Tuple orphan : invalidOrphans) {
        batchArgs.add(orphan.getElement());
        batchArgs.add(String.valueOf(orphan.getScore()));
      }

      // Execute the batch removal script
      Object result =
          jedis.evalsha(
              getScriptSha(BATCH_ORPHAN_REMOVE_SCRIPT, jedis),
              Collections.singletonList(WORKING_SET),
              batchArgs);

      // Process results
      if (result instanceof List) {
        List<Object> resultList = (List<Object>) result;
        if (resultList.size() >= 2) {
          processed = ((Long) resultList.get(0)).intValue();
          List<String> removedAgents = (List<String>) resultList.get(1);

          // Clean up local registry state for each removed agent
          for (String agentType : removedAgents) {
            // Remove from local agents registry to prevent re-addition
            agents.remove(agentType);

            // Clean up execution state
            ActiveAgent localCopy = activeAgents.remove(agentType);
            if (localCopy != null) {
              if (localCopy.future != null) {
                localCopy.future.cancel(true);
              }
              runningAgents.ifPresent(Semaphore::release);
            }

            log.debug(
                "Removed invalid orphaned agent {} from both Redis and local registry", agentType);
          }
        }
      }
    } catch (Exception e) {
      log.error("Batch removal of invalid orphaned agents failed: {}", e.getMessage());
    }

    return processed;
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
   * instance. It works by checking the local {@code activeAgents} map for agents that have been
   * running longer than a configurable threshold ({@code redis.agent.zombie-threshold-ms}).
   *
   * <p>Configuration:
   *
   * <ul>
   *   <li>{@code redis.agent.zombie-threshold-ms} - Time after which a running agent is considered
   *       a zombie (default: 30min). This balanced threshold allows time for rate-limited
   *       operations while catching truly stuck agents before they consume excessive resources.
   *   <li>{@code redis.agent.zombie-cleanup-interval-ms} - How often this cleanup process runs
   *       (default: 5min)
   * </ul>
   */
  private void cleanupZombieAgents() {
    long zombieThreshold =
        dynamicConfigService.getConfig(
            Long.class, "redis.agent.zombie-threshold-ms", 1800000L); // 30 minutes
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

    // Check if batch zombie cleanup is enabled (disabled by default for safety)
    boolean batchOperationsEnabled =
        dynamicConfigService.getConfig(
            Boolean.class, "redis.agent.batch-operations-enabled", false);

    if (!batchOperationsEnabled) {
      log.debug("Batch zombie cleanup disabled, using individual operations");
      // Use individual cleanup operations (existing proven approach)
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
      return;
    }

    // Cancel futures for zombie agents before Redis cleanup
    int futuresCanceled = 0;
    for (String agentType : zombieAgents) {
      ActiveAgent activeAgent = activeAgents.get(agentType);
      if (activeAgent != null && activeAgent.future != null) {
        try {
          if (activeAgent.future.cancel(true)) {
            futuresCanceled++;
          }
        } catch (Exception e) {
          log.warn("Failed to cancel future for zombie agent {}: {}", agentType, e.getMessage());
        }
      }
    }

    // Batch cleanup from Redis using single call
    int redisRemoved = 0;
    try (Jedis jedis = jedisPool.getResource()) {
      Object result =
          jedis.evalsha(
              getScriptSha(BATCH_CLEANUP_AGENTS_SCRIPT, jedis),
              Arrays.asList(WORKING_SET, WAITING_SET),
              zombieAgents);

      redisRemoved = result instanceof Long ? ((Long) result).intValue() : 0;

    } catch (Exception e) {
      log.error(
          "Batch zombie cleanup failed, falling back to individual operations: {}", e.getMessage());
      // Fallback to individual cleanup
      for (String agentType : zombieAgents) {
        try {
          cleanupZombieAgent(agentType);
        } catch (Exception fallbackError) {
          log.warn(
              "Failed to cleanup zombie agent {} individually: {}",
              agentType,
              fallbackError.getMessage());
        }
      }
    }

    // Remove from active agents tracking
    int trackingRemoved = 0;
    for (String agentType : zombieAgents) {
      if (activeAgents.remove(agentType) != null) {
        trackingRemoved++;
      }
    }

    zombiesCleanedUp.addAndGet(Math.max(redisRemoved, trackingRemoved));
    log.warn(
        "Zombie cleanup completed: {} futures canceled, {} removed from Redis, {} removed from tracking",
        futuresCanceled,
        redisRemoved,
        trackingRemoved);
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

      // Refresh Redis refresh period
      this.redisRefreshPeriod =
          dynamicConfigService.getConfig(
              Integer.class, "redis.agent.refresh-period-seconds", DEFAULT_REDIS_REFRESH_PERIOD);

      // Refresh scheduler interval
      this.schedulerIntervalMs =
          dynamicConfigService.getConfig(
              Long.class, "redis.agent.scheduler-interval-ms", DEFAULT_SCHEDULER_INTERVAL_MS);

      log.debug(
          "Refreshed agent configuration - enabled pattern: {}, refresh period: {}s, scheduler interval: {}ms",
          enabledPattern,
          redisRefreshPeriod,
          schedulerIntervalMs);
    } catch (Exception e) {
      log.warn("Failed to refresh agent configuration: {}", e.getMessage());
    }
  }

  /** Schedule an agent in Redis using atomic operations (fallback for batch failures). */
  private void scheduleAgentInRedis(Jedis jedis, Agent agent) {
    // When scheduling a new agent like this (typically from repopulateRedisAgents as a fallback),
    // the offset should be 0 to schedule it for "now", consistent with other initial scheduling
    // paths.
    // Passing System.currentTimeMillis() as offset was causing the score to be current_redis_time +
    // current_system_time.
    String currentScore = score(jedis, 0L);
    log.debug(
        "Scheduling agent {} in Redis with initial timestamp score: {}",
        agent.getAgentType(),
        currentScore);
    jedis.evalsha(
        getScriptSha(ADD_AGENT_SCRIPT, jedis),
        Arrays.asList(WAITING_SET, WORKING_SET),
        Arrays.asList(agent.getAgentType(), currentScore));
  }

  @PostConstruct
  public void startScheduler() {
    schedulerFuture =
        schedulerExecutorService.scheduleAtFixedRate(
            this, 0, schedulerIntervalMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Stops the scheduler and attempts a graceful shutdown of active agents and thread pools. This
   * method is annotated with {@link PreDestroy} to be called by the Spring container on context
   * destruction.
   *
   * <p>Shutdown sequence:
   *
   * <ol>
   *   <li>Sets the {@link #shuttingDown} flag to true.
   *   <li>Cancels the main scheduling future and shuts down the {@code schedulerExecutorService}.
   *   <li>Calls {@link #gracefullyReleaseActiveAgents()} to attempt to cancel and re-queue active
   *       agents.
   *   <li>Shuts down the {@code agentWorkPool}.
   * </ol>
   */
  @PreDestroy
  public void stopScheduler() {
    log.info("ClusteredSortAgentScheduler initiating shutdown...");
    this.shuttingDown = true; // Signal that shutdown is in progress

    // 1. Stop accepting new work / stop the main scheduling loop
    if (schedulerFuture != null) {
      if (!schedulerFuture.isDone()) {
        log.debug("Attempting to cancel main scheduler future.");
        schedulerFuture.cancel(true); // true to interrupt the running task (saturatePool)
      }
    }
    if (schedulerExecutorService != null) {
      log.debug("Shutting down scheduler executor service.");
      schedulerExecutorService.shutdown(); // Disable new tasks from being submitted
      try {
        if (!schedulerExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
          log.warn("Scheduler executor service did not terminate in 5s, forcing shutdown.");
          schedulerExecutorService.shutdownNow();
        }
      } catch (InterruptedException ie) {
        log.warn("Interrupted while waiting for scheduler executor service to terminate.");
        schedulerExecutorService.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    // 2. Attempt to gracefully release currently active agents
    gracefullyReleaseActiveAgents();

    // 3. Shutdown the agent worker pool
    if (agentWorkPool != null) {
      log.debug("Shutting down agent worker pool.");
      agentWorkPool.shutdown(); // Disable new tasks
      try {
        // Wait a finite time for existing tasks to complete or be cancelled
        if (!agentWorkPool.awaitTermination(30, TimeUnit.SECONDS)) { // Adjust timeout as needed
          log.warn("Agent worker pool did not terminate in 30s, forcing shutdown.");
          agentWorkPool.shutdownNow(); // Cancel currently executing tasks
        }
      } catch (InterruptedException ie) {
        log.warn("Interrupted while waiting for agent worker pool to terminate.");
        agentWorkPool.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    log.info("ClusteredSortAgentScheduler shutdown process completed.");
  }

  /**
   * Attempts to gracefully release agents currently active on this scheduler instance. This
   * involves cancelling their {@link Future}s, which should trigger their {@code finally} blocks in
   * {@link AgentWorker} to call {@link #conditionalReleaseAgent(Agent, String, Status)}. Since
   * {@link #shuttingDown} is true, {@code conditionalReleaseAgent} will re-queue them immediately.
   */
  private void gracefullyReleaseActiveAgents() {
    // Use a synchronized block to get a consistent view of the active agents map
    // and ensure thread safety during inspection and cancellation
    Map<String, ActiveAgent> agentsSnapshot;
    synchronized (activeAgents) {
      if (activeAgents.isEmpty()) {
        log.info("No active agents to release during shutdown.");
        return;
      }

      log.info(
          "Attempting to gracefully release {} active agents during shutdown...",
          activeAgents.size());
      // Create a deep copy of both keys and values to avoid any potential concurrent modification
      agentsSnapshot = new HashMap<>(activeAgents);
    }

    // Now work with the static snapshot, outside of synchronization to avoid blocking other threads
    for (Map.Entry<String, ActiveAgent> entry : agentsSnapshot.entrySet()) {
      String agentType = entry.getKey();
      ActiveAgent activeAgent = entry.getValue();

      if (activeAgent == null) {
        continue;
      }

      log.info("Graceful shutdown: Requesting cancellation for active agent {}", agentType);
      try {
        if (activeAgent.future != null && !activeAgent.future.isDone()) {
          activeAgent.future.cancel(true); // Interrupt the worker thread
          log.debug("Graceful shutdown: Cancelled future for agent {}", agentType);
        }
      } catch (Exception e) {
        log.warn(
            "Graceful shutdown: Error attempting to cancel future for agent {}: {}",
            agentType,
            e.getMessage(),
            e);
      }
    }

    // Get a safe size count for the waiting period
    int activeCountAfterCancel;
    synchronized (activeAgents) {
      activeCountAfterCancel = activeAgents.size();
    }

    if (activeCountAfterCancel > 0) {
      log.info(
          "Graceful shutdown: Waiting briefly for {} agents to complete their finally blocks.",
          activeCountAfterCancel);
      try {
        Thread.sleep(
            Math.min(activeCountAfterCancel * 100L, 5000L)); // Max 5 seconds, or 100ms per agent
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Graceful shutdown: Interrupted while waiting for agents to release.");
      }

      int remainingAgents;
      synchronized (activeAgents) {
        remainingAgents = activeAgents.size();
      }

      log.info(
          "Graceful shutdown: {} agents potentially still in active map after waiting period.",
          remainingAgents);
    } else {
      log.info(
          "Graceful shutdown: All active agents appear to have been processed or removed by their workers.");
    }
    log.info("Graceful release attempt for active agents completed.");
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
}
