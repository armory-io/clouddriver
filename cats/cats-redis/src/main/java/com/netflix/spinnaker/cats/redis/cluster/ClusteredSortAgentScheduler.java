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
import com.netflix.spinnaker.cats.agent.AccountAware;
import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.AgentScheduler;
import com.netflix.spinnaker.cats.agent.AgentSchedulerAware;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.cats.module.CatsModuleAware;
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent;
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * <h2>Performance Tuning Guide</h2>
 *
 * <p><strong>Default Configuration (recommended for most deployments):</strong>
 *
 * <pre>
 * redis.agent.scheduler-interval-ms: 1000        # 1 sec pickup cycles
 * redis.agent.refresh-period-seconds: 30         # 30 sec Redis sync
 * redis.agent.zombie-threshold-ms: 1800000       # 30 min zombie detection
 * redis.agent.zombie-cleanup-interval-ms: 300000 # 5 min cleanup cycles
 * redis.agent.on-demand.boost-enabled: false     # Disabled by default - enable only if needed
 * redis.agent.on-demand.max-boosts-per-second: 10.0 # Standard rate limiting
 * </pre>
 *
 * <p><strong>Suitable for:</strong> 1-500 AWS accounts, normal deployment frequency, standard Redis
 * resources.
 *
 * <p><strong>Performance:</strong> ~1 second agent pickup, 600 boosts/minute capacity, deployment
 * circuit breaker protection.
 *
 * <p><strong>High-Load Configuration (for enterprise-scale deployments):</strong>
 *
 * <pre>
 * redis.agent.scheduler-interval-ms: 500         # 0.5 sec pickup cycles - very fast
 * redis.agent.refresh-period-seconds: 15         # 15 sec Redis sync - frequent updates
 * redis.agent.zombie-threshold-ms: 2100000       # 35 min zombie detection - allows 30min + buffer
 * redis.agent.zombie-cleanup-interval-ms: 120000 # 2 min cleanup cycles - very frequent
 * redis.agent.on-demand.boost-enabled: true      # Enable only for high-frequency deployment environments
 * redis.agent.on-demand.max-boosts-per-second: 30.0 # Moderate rate limiting for busy systems
 * </pre>
 *
 * <p><strong>Suitable for:</strong> 500+ AWS accounts, high deployment frequency, dedicated Redis
 * infrastructure.
 *
 * <p><strong>Performance:</strong> ~0.5 second agent pickup, 1800 boosts/minute capacity,
 * sub-10-second OnDemand processing.
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
 *   <li><strong>on-demand.boost-enabled:</strong> Whether to prioritize caching agents after
 *       OnDemand completion. <em>Mechanics:</em> When OnDemand agent completes on RW pod,
 *       immediately boost related caching agents to priority 0 for next pickup. <em>Impact:</em>
 *       Reduces OnDemand processing from 2-10 minutes to 10-20 seconds, critical for deployment
 *       circuit breakers. <em>Default:</em> Disabled - only enable if experiencing slow deployments
 *       or circuit breaker timeouts.
 *   <li><strong>max-boosts-per-second:</strong> Rate limiting to prevent Redis overload during
 *       deployment storms. <em>Mechanics:</em> Tracks last boost time per agent type, rejects boost
 *       requests exceeding this rate. <em>Sizing:</em> Scale with deployment frequency: 10-20 for
 *       normal environments, 30-50 for high-volume enterprises. Monitor boost success rates -
 *       increase if seeing "rate limited" warnings during busy periods.
 * </ul>
 *
 * <p><strong>Monitoring & Troubleshooting:</strong>
 *
 * <ul>
 *   <li><strong>Key Metrics:</strong> Agent execution times (P95), OnDemand boost success rate,
 *       Redis CPU/memory, queue depth
 *   <li><strong>Slow OnDemand:</strong> Enable boost feature, increase rate limit, verify
 *       account/region scoping in logs
 *   <li><strong>High Redis Load:</strong> Increase scheduler interval, reduce refresh frequency,
 *       check connection pooling
 *   <li><strong>Circuit Breaker Timeouts:</strong> Reduce scheduler interval, enable OnDemand
 *       boost, check pod network latency
 *   <li><strong>Premature Zombie Cleanup:</strong> If agents are being killed while legitimately
 *       running, increase zombie-threshold-ms
 * </ul>
 *
 * <p><strong>Batch Operations (Performance Optimization):</strong>
 *
 * <p>For enterprise-scale deployments (1000+ agents), batch operations provide significant
 * performance improvements by reducing Redis round-trips from O(n) to O(1). All batch operations
 * are controlled by a single feature flag:
 *
 * <pre>
 * redis.agent.batch-operations-enabled: false      # All batch operations (disabled by default for safety)
 * </pre>
 *
 * <p><strong>Performance Impact:</strong> Batch operations can improve performance by 10-1000x for
 * large agent counts:
 *
 * <ul>
 *   <li><strong>Agent Repopulation:</strong> 1000 agents: 1000 Redis calls → 1 Redis call (1000x
 *       improvement)
 *   <li><strong>Zombie Cleanup:</strong> 50 zombie agents: 50 Redis calls → 1 Redis call (50x
 *       improvement)
 *   <li><strong>OnDemand Boost:</strong> 10 related agents: 10 Redis calls → 1 Redis call (10x
 *       improvement)
 * </ul>
 *
 * <p><strong>Deployment Strategy:</strong> Enable batch operations gradually:
 *
 * <ol>
 *   <li>Enable in development environment first: {@code redis.agent.batch-operations-enabled: true}
 *   <li>Monitor Redis performance and error rates
 *   <li>Enable in staging with full load testing
 *   <li>Enable in production during low-traffic periods
 *   <li>All operations have automatic fallback to individual calls if batch operations fail
 * </ol>
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

  @VisibleForTesting static final String WAITING_SET = "WAITZ";
  @VisibleForTesting static final String WORKING_SET = "WORKZ";
  private static final String ADD_AGENT_SCRIPT = "addAgentScript";
  private static final String VALID_SCORE_SCRIPT = "validScoreScript";
  private static final String SWAP_SET_SCRIPT = "swapSetScript";
  private static final String REMOVE_AGENT_SCRIPT = "removeAgentScript";
  private static final String CONDITIONAL_SWAP_SET_SCRIPT = "conditionalSwapSetScript";
  private static final String CONDITIONAL_REMOVE_SCRIPT = "conditionalRemoveScript";
  private static final String BOOST_PRIORITY_SCRIPT = "boostPriorityScript";

  // Batch operation scripts for O(n) → O(1) performance optimization
  private static final String BATCH_ADD_AGENTS_SCRIPT = "batchAddAgentsScript";
  private static final String BATCH_CLEANUP_AGENTS_SCRIPT = "batchCleanupAgentsScript";
  private static final String BATCH_BOOST_PRIORITY_SCRIPT = "batchBoostPriorityScript";

  private static final int DEFAULT_REDIS_REFRESH_PERIOD = 30;
  private static final long DEFAULT_SCHEDULER_INTERVAL_MS = 1000L;

  // OnDemand boost rate limiting
  Map<String, Long> lastBoostTimes = new ConcurrentHashMap<>();

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
        dynamicConfigService.getConfig(
            Integer.class, "redis.agent.refresh-period-seconds", DEFAULT_REDIS_REFRESH_PERIOD),
        dynamicConfigService.getConfig(
            Long.class, "redis.agent.scheduler-interval-ms", DEFAULT_SCHEDULER_INTERVAL_MS),
        shardingFilter,
        dynamicConfigService);
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
   *     <p><b>OnDemand Priority Boost Configuration:</b>
   *     <ul>
   *       <li><code>redis.agent.on-demand.boost-enabled</code> (default: false) - Enable/disable
   *           OnDemand priority boosting feature
   *       <li><code>redis.agent.on-demand.max-boosts-per-second</code> (default: 10.0) - Rate
   *           limiting for priority boosts
   *     </ul>
   *     <p><b>Other Dynamic Configuration Keys:</b>
   *     <ul>
   *       <li><code>redis.agent.enabled-pattern</code> - Regex pattern for filtering agents
   *       <li><code>redis.agent.refresh-period-seconds</code> - How often to repopulate Redis
   *           agents
   *       <li><code>redis.agent.scheduler-interval-ms</code> - How often scheduler runs
   *       <li><code>redis.agent.zombie-threshold-ms</code> - Zombie agent detection threshold
   *       <li><code>redis.agent.zombie-cleanup-interval-ms</code> - Zombie cleanup frequency
   *     </ul>
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
    this.enabledAgentPattern =
        Pattern.compile(enabledAgentPattern != null ? enabledAgentPattern : ".*");
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
   * still valid - REMOVE_AGENT_SCRIPT: Remove agent from both sets - BOOST_PRIORITY_SCRIPT: Boost
   * priority of OnDemand-related agents
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
              "if redis.call('zscore', KEYS[2], ARGV[1]) then\n" // If agent is in WORKING_SET
                  // (KEYS[2])
                  + "  return 0\n" // Return 0 (indicate not added to WAITING_SET as it's working)
                  + "end\n"
                  + "redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n" // Add/Update in WAITING_SET
                  // (KEYS[1])
                  + "return 1\n")); // Return 1 (successfully added/updated in WAITING_SET)

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

      // SCRIPT 7: Priority boosting for OnDemand-related agents
      // SAFETY: Only boost if agent is in WAITING_SET (not being acquired)
      scriptShas.put(
          BOOST_PRIORITY_SCRIPT,
          jedis.scriptLoad(
              "local score = redis.call('zscore', KEYS[1], ARGV[1])\n"
                  + "if score ~= nil then\n"
                  + "  -- Only boost if agent is still in WAITING_SET (not being acquired)\n"
                  + "  local working_score = redis.call('zscore', KEYS[2], ARGV[1])\n"
                  + "  if working_score == nil then\n"
                  + "    -- Agent is in WAITING_SET and not in WORKING_SET, safe to boost\n"
                  + "    redis.call('zadd', KEYS[1], ARGV[2], ARGV[1])\n"
                  + "    return score\n"
                  + "  else\n"
                  + "    -- Agent is being executed, don't interfere\n"
                  + "    return nil\n"
                  + "  end\n"
                  + "else\n"
                  + "  -- Agent not in WAITING_SET, nothing to boost\n"
                  + "  return nil\n"
                  + "end"));

      // SCRIPT 8: Batch add agents to WAITING_SET
      scriptShas.put(
          BATCH_ADD_AGENTS_SCRIPT,
          jedis.scriptLoad(
              "-- Args: score, agent1, agent2, ...\n"
                  + "local score = ARGV[1]\n"
                  + "local processedCount = 0\n"
                  + "for i = 2, #ARGV do\n"
                  + "  local agent = ARGV[i]\n"
                  + "  if not redis.call('zscore', KEYS[2], agent) then\n" // If agent is NOT in
                  // WORKING_SET (KEYS[2])
                  + "    redis.call('zadd', KEYS[1], score, agent)\n" // Add/Update in WAITING_SET
                  // (KEYS[1])
                  + "    processedCount = processedCount + 1\n"
                  + "  end\n"
                  + "end\n"
                  + "return processedCount"));

      // SCRIPT 9: Batch cleanup agents from both sets
      scriptShas.put(
          BATCH_CLEANUP_AGENTS_SCRIPT,
          jedis.scriptLoad(
              "-- Args: agent1, agent2, ...\n"
                  + "local removed = 0\n"
                  + "for i = 1, #ARGV do\n"
                  + "  local agent = ARGV[i]\n"
                  + "  local working_removed = redis.call('zrem', KEYS[1], agent)\n"
                  + "  local waiting_removed = redis.call('zrem', KEYS[2], agent)\n"
                  + "  removed = removed + working_removed + waiting_removed\n"
                  + "end\n"
                  + "return removed"));

      // SCRIPT 10: Batch boost priority for OnDemand-related agents
      scriptShas.put(
          BATCH_BOOST_PRIORITY_SCRIPT,
          jedis.scriptLoad(
              "-- Args: new_score, agent1, agent2, ...\n"
                  + "local new_score = ARGV[1]\n"
                  + "local boosted = 0\n"
                  + "for i = 2, #ARGV do\n"
                  + "  local agent = ARGV[i]\n"
                  + "  local score = redis.call('zscore', KEYS[1], agent)\n"
                  + "  if score ~= nil then\n"
                  + "    -- Only boost if agent is in WAITING_SET and not in WORKING_SET\n"
                  + "    local working_score = redis.call('zscore', KEYS[2], agent)\n"
                  + "    if working_score == nil then\n"
                  + "      redis.call('zadd', KEYS[1], new_score, agent)\n"
                  + "      boosted = boosted + 1\n"
                  + "    end\n"
                  + "  end\n"
                  + "end\n"
                  + "return boosted"));
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
   *
   * <p>Note: offsetMillis is expected to be in milliseconds (e.g., from
   * System.currentTimeMillis()), and will be converted to seconds before being added to the Redis
   * TIME (which is in seconds).
   */
  @SuppressWarnings(
      "deprecation") // jedis.time() is deprecated but still the correct method for Redis TIME
  // coordination
  private static String score(Jedis jedis, long offsetMillis) {
    // Use Redis TIME command for server-side time coordination across multiple instances
    List<String> times = jedis.time();
    if (times == null || times.size() != 2) {
      throw new AgentSchedulingException("Error retrieving time from Redis");
    }
    long timeSeconds = Long.parseLong(times.get(0)); // Use Long.parseLong for robustness (Y2K38)

    // Convert offsetMillis from milliseconds to seconds for proper time unit compatibility
    long offsetSeconds = offsetMillis / 1000;

    return String.format("%d", timeSeconds + offsetSeconds);
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
        log.debug(
            "Attempting to acquire up to {} new agents ({} running, {} max, {} ready in WAITING_SET)",
            availableSlotsForNewAgents,
            currentlyRunning,
            maxConcurrentAgents,
            readyAgents.size());

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

          log.debug(
              "Successfully acquired agent {} from Redis with score {}. (Acquired {} of {} target this cycle)",
              agentType,
              acquireResult.acquireScore,
              agentsAcquiredThisCycle,
              availableSlotsForNewAgents);

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

  /** Repopulate Redis with known agents for recovery scenarios using batch operations. */
  private void repopulateRedisAgents(Jedis jedis) {
    log.debug("Repopulating Redis with {} known agents", agents.size());

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

  void cleanupZombieAgentsIfNeeded() {
    log.info(
        "Active agents map size: {}, checking if zombie cleanup is needed.", activeAgents.size());
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

  /**
   * Boost priority of specified agents to run immediately. Used when OnDemand cache refresh
   * completes to prioritize related caching agents.
   *
   * @param agentTypes Set of agent type names to boost
   * @return true if any agents were boosted, false otherwise
   */
  public boolean boostAgentPriority(Set<String> agentTypes) {
    if (agentTypes == null || agentTypes.isEmpty()) {
      return false;
    }

    log.debug("Attempting to boost priority for {} agents: {}", agentTypes.size(), agentTypes);

    // Check if batch operations are enabled (disabled by default for safety)
    boolean batchOperationsEnabled =
        dynamicConfigService.getConfig(
            Boolean.class, "redis.agent.batch-operations-enabled", false);

    if (!batchOperationsEnabled) {
      log.debug("Batch boost disabled, using individual operations");
      // Use individual boost operations (existing proven approach)
      boolean anyBoosted = false;
      try (Jedis jedis = jedisPool.getResource()) {
        String immediateScore = score(jedis, 0);

        for (String agentType : agentTypes) {
          if (shouldRateLimit(agentType)) {
            log.debug("Rate limiting priority boost for agent: {}", agentType);
            continue;
          }

          try {
            Object oldScore =
                jedis.evalsha(
                    getScriptSha(BOOST_PRIORITY_SCRIPT, jedis),
                    Arrays.asList(WAITING_SET, WORKING_SET),
                    Arrays.asList(agentType, immediateScore));

            if (oldScore != null) {
              log.info(
                  "Boosted priority for agent {} from score {} to {} (immediate execution)",
                  agentType,
                  oldScore,
                  immediateScore);
              updateBoostTimestamp(agentType);
              anyBoosted = true;
            } else {
              log.debug("Agent {} not found in WAITING_SET, cannot boost priority", agentType);
            }
          } catch (Exception e) {
            log.warn("Failed to boost priority for agent {}: {}", agentType, e.getMessage());
          }
        }
      } catch (Exception e) {
        log.error("Redis error during priority boost: {}", e.getMessage());
        return false;
      }

      log.debug("Priority boost completed. Boosted {} agents", anyBoosted);
      return anyBoosted;
    }

    // Filter agents that pass rate limiting
    List<String> agentsToBoost = new ArrayList<>();
    List<String> rateLimitedAgents = new ArrayList<>();

    for (String agentType : agentTypes) {
      if (shouldRateLimit(agentType)) {
        rateLimitedAgents.add(agentType);
      } else {
        agentsToBoost.add(agentType);
      }
    }

    if (!rateLimitedAgents.isEmpty()) {
      log.debug(
          "Rate limiting priority boost for {} agents: {}",
          rateLimitedAgents.size(),
          rateLimitedAgents);
    }

    if (agentsToBoost.isEmpty()) {
      log.debug("No agents to boost after rate limiting");
      return false;
    }

    // Batch boost using single Redis call
    boolean anyBoosted = false;
    try (Jedis jedis = jedisPool.getResource()) {
      String immediateScore = score(jedis, 0); // Current Redis time = run now

      List<String> scriptArgs = new ArrayList<>();
      scriptArgs.add(immediateScore); // First arg is the new score
      scriptArgs.addAll(agentsToBoost); // Remaining args are agent names

      Object result =
          jedis.evalsha(
              getScriptSha(BATCH_BOOST_PRIORITY_SCRIPT, jedis),
              Arrays.asList(WAITING_SET, WORKING_SET),
              scriptArgs);

      int boosted = result instanceof Long ? ((Long) result).intValue() : 0;
      anyBoosted = boosted > 0;

      if (anyBoosted) {
        log.info(
            "Batch priority boost completed: {}/{} agents boosted to immediate execution",
            boosted,
            agentsToBoost.size());

        // Update rate limiting timestamps for successfully boosted agents
        // (We update all since the batch operation doesn't tell us which specific ones succeeded)
        for (String agentType : agentsToBoost) {
          updateBoostTimestamp(agentType);
        }
      } else {
        log.debug("No agents were boosted - all agents may be executing or not in WAITING_SET");
      }

    } catch (Exception e) {
      log.error(
          "Batch priority boost failed, falling back to individual operations: {}", e.getMessage());
      // Fallback to individual boost operations
      try (Jedis jedis = jedisPool.getResource()) {
        String immediateScore = score(jedis, 0);

        for (String agentType : agentsToBoost) {
          try {
            Object oldScore =
                jedis.evalsha(
                    getScriptSha(BOOST_PRIORITY_SCRIPT, jedis),
                    Arrays.asList(WAITING_SET, WORKING_SET),
                    Arrays.asList(agentType, immediateScore));

            if (oldScore != null) {
              log.info(
                  "Boosted priority for agent {} from score {} to {} (immediate execution)",
                  agentType,
                  oldScore,
                  immediateScore);
              updateBoostTimestamp(agentType);
              anyBoosted = true;
            }
          } catch (Exception fallbackError) {
            log.warn(
                "Failed to boost priority for agent {} individually: {}",
                agentType,
                fallbackError.getMessage());
          }
        }
      } catch (Exception fallbackError) {
        log.error("Redis error during fallback priority boost: {}", fallbackError.getMessage());
        return false;
      }
    }

    log.debug("Priority boost completed. Any boosted: {}", anyBoosted);
    return anyBoosted;
  }

  /**
   * Find caching agents related to the OnDemand agent that should be boosted. Maps OnDemand agent
   * types to their corresponding caching agents.
   *
   * @param onDemandAgent The OnDemand agent that completed
   * @return Set of related caching agent type names
   */
  public Set<String> findRelatedCachingAgents(OnDemandAgent onDemandAgent) {
    if (onDemandAgent == null) {
      return Collections.emptySet();
    }

    String providerName = onDemandAgent.getProviderName();
    String onDemandType = onDemandAgent.getOnDemandAgentType();

    // Early return if onDemandType is null or empty
    if (onDemandType == null || onDemandType.isEmpty()) {
      log.debug("OnDemand agent type is null or empty for provider: {}", providerName);
      return Collections.emptySet();
    }

    log.debug(
        "Finding related caching agents for provider: {}, type: {}", providerName, onDemandType);

    Set<String> relatedAgents = new HashSet<>();

    // AWS Provider mappings
    if ("aws".equals(providerName)) {
      if (onDemandType.contains("ServerGroup")) {
        relatedAgents.add("AmazonServerGroupCachingAgent");
        relatedAgents.add("AmazonInstanceCachingAgent");
      }
      if (onDemandType.contains("LoadBalancer")) {
        relatedAgents.add("AmazonLoadBalancerCachingAgent");
      }
      if (onDemandType.contains("SecurityGroup")) {
        relatedAgents.add("AmazonSecurityGroupCachingAgent");
      }
      if (onDemandType.contains("TargetGroup")) {
        relatedAgents.add("AmazonTargetGroupCachingAgent");
      }
      if (onDemandType.contains("CloudFormation")) {
        relatedAgents.add("AmazonCloudFormationCachingAgent");
      }
      if (onDemandType.contains("Function")) {
        relatedAgents.add("LambdaCachingAgent");
      }
    }

    // Google Cloud Provider mappings
    if ("gce".equals(providerName)) {
      if (onDemandType.contains("ServerGroup")) {
        relatedAgents.add("GoogleServerGroupCachingAgent");
        relatedAgents.add("GoogleInstanceCachingAgent");
      }
      if (onDemandType.contains("LoadBalancer")) {
        relatedAgents.add("GoogleLoadBalancerCachingAgent");
        relatedAgents.add("GoogleHttpLoadBalancerCachingAgent");
        relatedAgents.add("GoogleInternalLoadBalancerCachingAgent");
        relatedAgents.add("GoogleInternalHttpLoadBalancerCachingAgent");
        relatedAgents.add("GoogleSslLoadBalancerCachingAgent");
        relatedAgents.add("GoogleTcpLoadBalancerCachingAgent");
      }
      if (onDemandType.contains("SecurityGroup")) {
        relatedAgents.add("GoogleSecurityGroupCachingAgent");
      }
    }

    // Yandex Provider mappings
    if ("yandex".equals(providerName)) {
      if (onDemandType.contains("ServerGroup")) {
        relatedAgents.add("YandexServerGroupCachingAgent");
      }
      if (onDemandType.contains("LoadBalancer")) {
        relatedAgents.add("YandexNetworkLoadBalancerCachingAgent");
      }
    }

    // Cloud Run Provider mappings
    if ("cloudrun".equals(providerName)) {
      if (onDemandType.contains("ServerGroup")) {
        relatedAgents.add("CloudrunServerGroupCachingAgent");
      }
    }

    // Cloud Foundry Provider mappings
    if ("cloudfoundry".equals(providerName)) {
      if (onDemandType.contains("ServerGroup")) {
        relatedAgents.add("CloudFoundryServerGroupCachingAgent");
      }
      if (onDemandType.contains("LoadBalancer")) {
        relatedAgents.add("CloudFoundryLoadBalancerCachingAgent");
      }
    }

    // ECS Provider mappings
    if ("ecs".equals(providerName)) {
      if (onDemandType.contains("ServerGroup")) {
        relatedAgents.add("EcsServerGroupCachingAgent");
        relatedAgents.add("EcsClusterCachingAgent");
      }
    }

    // AliCloud Provider mappings
    if ("alicloud".equals(providerName)) {
      if (onDemandType.contains("LoadBalancer")) {
        relatedAgents.add("AliCloudLoadBalancerCachingAgent");
      }
      if (onDemandType.contains("SecurityGroup")) {
        relatedAgents.add("AliCloudSecurityGroupCachingAgent");
      }
    }

    // Huawei Cloud Provider mappings
    if ("huaweicloud".equals(providerName)) {
      if (onDemandType.contains("SecurityGroup")) {
        relatedAgents.add("HuaweiCloudSecurityGroupCachingAgent");
      }
    }

    log.debug("Found {} related caching agents: {}", relatedAgents.size(), relatedAgents);
    return relatedAgents;
  }

  /**
   * Handle OnDemand completion and boost related caching agents. This is the main integration point
   * called after OnDemand agents complete.
   *
   * @param onDemandAgent The OnDemand agent that completed
   * @param result The result of the OnDemand execution
   */
  public void handleOnDemandCompletion(
      OnDemandAgent onDemandAgent, OnDemandAgent.OnDemandResult result) {
    log.debug("Handling OnDemand completion for agent: {}", onDemandAgent);

    // Check if OnDemand priority boosting is enabled (disabled by default)
    boolean onDemandBoostEnabled =
        dynamicConfigService.getConfig(Boolean.class, "redis.agent.on-demand.boost-enabled", false);

    if (!onDemandBoostEnabled) {
      log.debug(
          "OnDemand priority boosting is disabled. Skipping boost for agent: {}", onDemandAgent);
      return;
    }

    // Extract account/region information for targeted boosting
    String accountName = null;
    String region = null;

    // Try to get account name from AccountAware interface
    if (onDemandAgent instanceof AccountAware) {
      accountName = ((AccountAware) onDemandAgent).getAccountName();
    }

    // Try to get region from getRegion() method if it exists (some providers have this)
    try {
      Method getRegionMethod = onDemandAgent.getClass().getMethod("getRegion");
      Object regionResult = getRegionMethod.invoke(onDemandAgent);
      if (regionResult instanceof String) {
        region = (String) regionResult;
      }
    } catch (Exception e) {
      // getRegion() method doesn't exist or failed - this is expected for most agents
      log.trace(
          "No getRegion() method found on agent {}, will extract from agent type",
          onDemandAgent.getClass().getSimpleName());
    }

    // Extract account/region from agent type as fallback (always do this as agents store the
    // canonical format)
    if (onDemandAgent instanceof Agent) {
      String agentType = ((Agent) onDemandAgent).getAgentType();
      // Agent type format: "account/region/AgentClass"
      String[] parts = agentType.split("/");
      if (parts.length >= 3) {
        // Standard format: account/region/ClassName
        if (accountName == null) {
          accountName = parts[0];
        }
        if (region == null) {
          region = parts[1];
        }
      } else if (parts.length >= 2) {
        // Fallback format: account/region or region/ClassName
        if (region == null) {
          region = parts[1];
        }
        if (accountName == null) {
          accountName = parts[0];
        }
      }
    }

    log.debug("OnDemand agent account: {}, region: {}", accountName, region);

    Set<String> relatedAgentTypes = findRelatedCachingAgents(onDemandAgent);
    if (!relatedAgentTypes.isEmpty() && accountName != null && region != null) {
      // Build fully qualified agent identifiers with account/region
      Set<String> qualifiedAgentTypes = new HashSet<>();
      for (String agentType : relatedAgentTypes) {
        String qualifiedAgentType = String.format("%s/%s/%s", accountName, region, agentType);
        qualifiedAgentTypes.add(qualifiedAgentType);
      }

      boolean boosted = boostAgentPriority(qualifiedAgentTypes);
      log.info(
          "OnDemand completion for {} (account: {}, region: {}): boosted {} related caching agents (success: {})",
          onDemandAgent.getOnDemandAgentType(),
          accountName,
          region,
          qualifiedAgentTypes.size(),
          boosted);
    } else {
      log.warn(
          "Cannot boost related agents - missing account/region information or no related agents found. Account: {}, Region: {}, Related agents: {}",
          accountName,
          region,
          relatedAgentTypes.size());
    }
  }

  /**
   * Check if agent priority boost should be rate limited. Prevents boost storms that could impact
   * Redis performance.
   *
   * @param agentType Agent type to check
   * @return true if should be rate limited, false otherwise
   */
  private boolean shouldRateLimit(String agentType) {
    long currentTime = System.currentTimeMillis();
    Long lastBoostTime = lastBoostTimes.get(agentType);

    if (lastBoostTime == null) {
      return false; // First boost for this agent
    }

    long timeSinceLastBoost = currentTime - lastBoostTime;
    long minIntervalMs =
        (long)
            (1000.0
                / dynamicConfigService.getConfig(
                    Double.class, "redis.agent.on-demand.max-boosts-per-second", 10.0));

    return timeSinceLastBoost < minIntervalMs;
  }

  /**
   * Update the timestamp for the last priority boost of an agent. Used for rate limiting.
   *
   * @param agentType Agent type that was boosted
   */
  private void updateBoostTimestamp(String agentType) {
    lastBoostTimes.put(agentType, System.currentTimeMillis());
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

  @PreDestroy
  public void stopScheduler() {
    schedulerFuture.cancel(true);
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
