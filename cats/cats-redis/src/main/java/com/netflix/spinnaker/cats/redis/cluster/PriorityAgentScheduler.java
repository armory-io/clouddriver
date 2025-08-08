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

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.AgentScheduler;
import com.netflix.spinnaker.cats.agent.AgentSchedulerAware;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.NodeStatusProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import com.netflix.spinnaker.cats.module.CatsModuleAware;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Priority-based Redis agent scheduler using sorted sets for coordinated execution across multiple
 * clouddriver instances.
 *
 * <p>This scheduler provides distributed agent coordination with deadline-aware scheduling, zombie
 * detection, orphan cleanup, and atomic operations via Lua scripts.
 *
 * <p>Core Architecture:
 *
 * <ul>
 *   <li><strong>WAITING_SET (WAITZ):</strong> Agents ready for execution, scored by next run time
 *   <li><strong>WORKING_SET (WORKZ):</strong> Agents currently executing, scored by completion
 *       deadline (current_time + agent_timeout)
 *   <li><strong>Atomic Operations:</strong> Lua scripts ensure race-free state transitions
 *   <li><strong>Priority Scheduling:</strong> Lower scores = higher priority execution
 *   <li><strong>Agent-Specific Timeouts:</strong> Each agent type gets appropriate timeout handling
 * </ul>
 *
 * <h2>Priority 0 invariants and health</h2>
 *
 * <ul>
 *   <li>Re-scheduling: On success, re-schedule for the next cadence (prefer original acquire-based
 *       cadence; else now + interval). On failure, immediate retry.
 *   <li>WAITZ preservation: Do not purge valid WAITZ entries by age; preserve FIFO under backlog.
 *   <li>WORKZ orphans: Skip locally active entries; move valid stale entries back to WAITZ using
 *       conditional move; remove invalid ones.
 *   <li>Health logging: Every 10 minutes, emit HEALTHY/DEGRADED and queue lag in seconds (0 if
 *       none). DEGRADED when oldest_overdue_seconds > minimum enabled-agent interval on this pod.
 *       WARNs are rate-limited to avoid flooding.
 * </ul>
 *
 * <h2>Configuration Properties</h2>
 *
 * <p><strong>Agent Configuration (redis.agent.*):</strong>
 *
 * <pre>
 * redis:
 *   agent:
 *     enabledPattern: ".*"              # Default: all agents enabled
 *     disabledPattern: ""               # Default: no pattern-based disabling
 *     maxConcurrentAgents: 100          # Default: max 100 simultaneous agents
 * </pre>
 *
 * <ul>
 *   <li><strong>enabledPattern:</strong> Regex for agent inclusion. Controls which agents are
 *       enabled for this scheduler instance.
 *   <li><strong>disabledPattern:</strong> Regex for agent exclusion (takes precedence over
 *       enabledPattern). Empty string disables pattern matching.
 *   <li><strong>maxConcurrentAgents:</strong> Instance-wide semaphore limit for concurrent agent
 *       executions. Controls resource utilization across the system.
 * </ul>
 *
 * <p><strong>Scheduler Configuration (redis.scheduler.*):</strong>
 *
 * <pre>
 * redis:
 *   scheduler:
 *     intervalMs: 1000                  # Default: 1 second pickup cycles
 *     refreshPeriodSeconds: 30          # Default: 30 second agent refresh
 *     batchOperationsEnabled: false     # Default: disabled for safety
 *     timeCacheDurationMs: 10000        # Default: 10 second time cache
 * </pre>
 *
 * <ul>
 *   <li><strong>intervalMs:</strong> Main scheduling loop frequency. Controls how often the
 *       scheduler checks for and acquires ready agents.
 *   <li><strong>refreshPeriodSeconds:</strong> How often agents are synchronized from Spring
 *       context to Redis. Controls the frequency of agent registration updates.
 *   <li><strong>batchOperationsEnabled:</strong> Groups multiple Redis operations into batches.
 *       Controls whether operations like agent acquisition use batch mode.
 *   <li><strong>timeCacheDurationMs:</strong> Duration to cache Redis TIME command results.
 *       Controls how frequently the scheduler refreshes its time synchronization.
 * </ul>
 *
 * <p><strong>Zombie Cleanup Configuration (redis.scheduler.zombieCleanup.*):</strong>
 *
 * <pre>
 * redis:
 *   scheduler:
 *     zombieCleanup:
 *       enabled: true                  # Default: zombie detection enabled
 *       thresholdMs: 30000             # Default: 30 seconds (30 * 1000)
 *       intervalMs: 300000             # Default: 5 minutes (5 * 60 * 1000)
 *       batchSize: 50                  # Default: process 50 zombies per batch
 *       exceptionalAgents:
 *         pattern: ".*BigQuery.*"      # Example: Regex pattern for agent names
 *         thresholdMs: 3600000         # Different threshold for matching agents (60 * 60 * 1000)
 * </pre>
 *
 * <ul>
 *   <li><strong>enabled:</strong> Master switch for zombie detection. Disable only for debugging.
 *   <li><strong>thresholdMs:</strong> Additional time buffer beyond agent completion deadline
 *       before considering an agent zombie. Defines how long to wait after an agent exceeds its
 *       timeout before marking it as a zombie.
 *   <li><strong>intervalMs:</strong> Zombie scan frequency. Controls how often the system checks
 *       for and cleans up zombie agents.
 *   <li><strong>batchSize:</strong> Number of zombies processed per cleanup cycle. Controls how
 *       many zombie agents can be cleaned up in a single operation.
 *   <li><strong>exceptionalAgents:</strong> Configuration for exceptional agents that require
 *       different zombie thresholds.
 *   <li><strong>pattern:</strong> Regex pattern for agent names.
 *   <li><strong>thresholdMs:</strong> Different time buffer beyond agent completion deadline before
 *       considering an agent zombie.
 * </ul>
 *
 * <p><strong>Orphan Cleanup Configuration (redis.scheduler.orphanCleanup.*):</strong>
 *
 * <pre>
 * redis:
 *   scheduler:
 *     orphanCleanup:
 *       enabled: true                  # Default: orphan cleanup enabled
 *       thresholdMs: 600000            # Default: 10 minutes (10 * 60 * 1000)
 *       intervalMs: 300000             # Default: 5 minutes (5 * 60 * 1000)
 *       batchSize: 50                  # Default: process 50 orphans per batch
 *       leadershipTtlMs: 120000        # Default: 2 minutes (2 * 60 * 1000)
 *       forceAllPods: false            # Default: leader-only cleanup
 * </pre>
 *
 * <ul>
 *   <li><strong>enabled:</strong> Controls cleanup of agents from crashed instances. Manages the
 *       removal of agents left behind by pods that no longer exist.
 *   <li><strong>thresholdMs:</strong> Time buffer for orphan detection. Defines how long to wait
 *       before considering an agent as orphaned. Different logic applies for agents in the WORKZ vs
 *       WAITZ sets: - WORKZ orphans: agents past completion deadline + buffer. - WAITZ orphans:
 *       agents with execution times older than current time - buffer.
 *   <li><strong>intervalMs:</strong> Orphan cleanup frequency. Controls how often the system checks
 *       for and removes orphaned agents.
 *   <li><strong>batchSize:</strong> Number of orphans processed per cleanup cycle. Controls how
 *       many orphaned agents can be cleaned up in a single operation.
 *   <li><strong>leadershipTtlMs:</strong> Duration of cleanup leadership lock. Prevents multiple
 *       instances from cleaning simultaneously.
 *   <li><strong>forceAllPods:</strong> If true, all instances perform cleanup without leadership
 *       coordination.
 * </ul>
 *
 * <p><strong>Thread Pool Configuration (redis.scheduler.pool.*):</strong>
 *
 * <pre>
 * redis:
 *   scheduler:
 *     pool:
 *       coreSize: 10                   # Default: 10 core threads
 *       maxSize: 50                    # Default: 50 max threads
 *       keepAliveSeconds: 60           # Default: 60 second keep-alive
 * </pre>
 *
 * <ul>
 *   <li><strong>coreSize:</strong> Base number of threads for agent execution. Controls the number
 *       of always-available threads in the pool.
 *   <li><strong>maxSize:</strong> Maximum number of threads the pool can grow to. Controls the
 *       upper limit of concurrent agent executions.
 *   <li><strong>keepAliveSeconds:</strong> Idle thread timeout. Controls how long non-core threads
 *       remain in the pool when idle.
 * </ul>
 */
@Component
public class PriorityAgentScheduler extends CatsModuleAware
    implements AgentScheduler<AgentLock>, Runnable {

  private static final Logger log = LoggerFactory.getLogger(PriorityAgentScheduler.class);

  // Core services
  private final RedisScriptManager scriptManager;
  private final AgentAcquisitionService acquisitionService;
  private final ZombieCleanupService zombieService;
  private final OrphanCleanupService orphanService;
  private final PrioritySchedulerConfiguration config;

  // External dependencies
  private final NodeStatusProvider nodeStatusProvider;
  private final AgentIntervalProvider intervalProvider;
  private final ShardingFilter shardingFilter;

  // Runtime state
  private final AtomicLong runCount = new AtomicLong(0);
  private final AtomicBoolean running = new AtomicBoolean(false);

  // Track all agents provided via schedule(), regardless of current sharding gating
  private final java.util.concurrent.ConcurrentMap<String, KnownAgent> knownAgents =
      new java.util.concurrent.ConcurrentHashMap<>();

  // Reconciliation cadence control
  private final AtomicLong lastReconcileEpochMs = new AtomicLong(0);

  public PriorityAgentScheduler(
      JedisPool jedisPool,
      NodeStatusProvider nodeStatusProvider,
      AgentIntervalProvider intervalProvider,
      ShardingFilter shardingFilter,
      PriorityAgentProperties agentProperties,
      PrioritySchedulerProperties schedulerProperties) {

    // Initialize services
    this.scriptManager = new RedisScriptManager(jedisPool);
    this.config = new PrioritySchedulerConfiguration(agentProperties, schedulerProperties);
    this.acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties);
    this.zombieService = new ZombieCleanupService(jedisPool, scriptManager, schedulerProperties);
    this.orphanService = new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties);

    // Set up service references for advanced cleanup processing
    this.orphanService.setAcquisitionService(this.acquisitionService);

    // Store external dependencies
    this.nodeStatusProvider = nodeStatusProvider;
    this.intervalProvider = intervalProvider;
    this.shardingFilter = shardingFilter;

    log.info("PriorityAgentScheduler initialized successfully");
  }

  /** Initialize the scheduler and start the periodic execution. */
  @PostConstruct
  public void initialize() {
    try {
      // Initialize Redis scripts
      scriptManager.initializeScripts();
      log.info("Redis scripts initialized: {} scripts loaded", scriptManager.getScriptCount());

      // Start the scheduler
      startScheduler();
      running.set(true);

      log.info("PriorityAgentScheduler started successfully");
    } catch (Exception e) {
      log.error("Failed to initialize PriorityAgentScheduler", e);
      throw new AgentSchedulingException("Scheduler initialization failed", e);
    }
  }

  /**
   * Main scheduler execution loop - called periodically by ScheduledExecutorService.
   *
   * <p>This method orchestrates the core scheduling logic:
   *
   * <ol>
   *   <li>Checks if this node is enabled for scheduling
   *   <li>Performs cleanup operations (zombies and orphans)
   *   <li>Acquires ready agents and submits them for execution
   * </ol>
   *
   * <p>Key Scheduling Decisions:
   *
   * <ul>
   *   <li>Agents are acquired from WAITZ set based on their next execution time
   *   <li>When acquired, agents are moved to WORKZ set with a completion deadline (current_time +
   *       agent_timeout)
   *   <li>Agent timeouts are agent-specific
   *   <li>Completion deadlines are used for zombie detection and orphan cleanup
   * </ul>
   */
  @Override
  public void run() {
    if (!nodeStatusProvider.isNodeEnabled()) {
      return;
    }

    try {
      long currentRun = runCount.incrementAndGet();
      log.debug("Starting scheduler run cycle {}", currentRun);

      // PHASE 0: Dynamic configuration refresh (periodic)
      refreshConfigurationIfNeeded(currentRun);

      // PHASE 0.5: Reconcile known agents with current sharding/enablement (periodic)
      reconcileKnownAgentsIfNeeded(currentRun);

      // PHASE 1: Cleanup operations
      zombieService.cleanupZombieAgentsIfNeeded(
          acquisitionService.getActiveAgentsMap(), acquisitionService.getActiveAgentsFutures());

      orphanService.cleanupOrphanedAgentsIfNeeded();

      // PHASE 2: Agent acquisition and execution
      int agentsAcquired =
          acquisitionService.saturatePool(
              currentRun, config.getRunningAgents(), config.getAgentWorkPool());

      if (agentsAcquired > 0) {
        log.debug(
            "Scheduler run cycle {} completed: {} agents acquired", currentRun, agentsAcquired);
      }

      // Log periodic operational health summary (every 10 minutes)
      if (currentRun % 600 == 0) {
        SchedulerStats stats = getStats();
        log.info(
            "Scheduler health [registered={}, active={}, futures={}, scripts={}] [zombies_cleaned={}, orphans_cleaned={}] running={} health={}{} oldest_overdue={}s",
            stats.getRegisteredAgents(),
            stats.getActiveAgents(),
            acquisitionService.getFuturesMapSize(),
            scriptManager.getScriptCount(),
            stats.getZombiesCleanedUp(),
            stats.getOrphansCleanedUp(),
            stats.isRunning(),
            stats.isDegraded() ? "DEGRADED" : "HEALTHY",
            stats.isDegraded() ? (" reason=" + stats.getDegradedReason()) : "",
            stats.getOldestOverdueSeconds());
      }

    } catch (Throwable t) {
      log.error("Critical error in scheduler run cycle {}", runCount.get(), t);
      // Don't rethrow - let scheduler continue and try again next cycle
    }
  }

  /**
   * Register an agent for scheduling.
   *
   * @param agent The agent to register
   * @param agentExecution Agent execution callback
   * @param executionInstrumentation Metrics instrumentation
   */
  @Override
  public void schedule(
      Agent agent,
      AgentExecution agentExecution,
      ExecutionInstrumentation executionInstrumentation) {
    // Always track the agent so that we can re-balance on shard changes
    knownAgents.put(
        agent.getAgentType(), new KnownAgent(agent, agentExecution, executionInstrumentation));

    if (!isAgentEnabled(agent)) {
      log.debug("Agent {} not enabled, skipping registration", agent.getAgentType());
      return;
    }

    // Set up agent scheduler awareness
    if (agent instanceof AgentSchedulerAware) {
      ((AgentSchedulerAware) agent).setAgentScheduler(this);
    }

    // Register with acquisition service (it will log registration details)
    acquisitionService.registerAgent(agent, agentExecution, executionInstrumentation);

    log.debug("Registered agent {} for scheduling", agent.getAgentType());
  }

  /**
   * Unregister an agent from scheduling.
   *
   * @param agent The agent to unregister
   */
  @Override
  public void unschedule(Agent agent) {
    acquisitionService.unregisterAgent(agent);
    log.debug("Unregistered agent {} from scheduling", agent.getAgentType());
    knownAgents.remove(agent.getAgentType());
  }

  /**
   * Try to manually lock an agent for execution.
   *
   * @param agent The agent to lock
   * @return AgentLock if successful, null if agent is already locked or unavailable
   */
  public AgentLock tryLock(Agent agent) {
    try {
      // Use acquisition service to try to acquire the agent
      return acquisitionService.tryLockAgent(agent);
    } catch (Exception e) {
      log.warn("Failed to lock agent {}: {}", agent.getAgentType(), e.getMessage());
      return null;
    }
  }

  /**
   * Try to release a manually acquired agent lock.
   *
   * @param lock The lock to release
   * @return true if successfully released, false otherwise
   */
  public boolean tryRelease(AgentLock lock) {
    try {
      return acquisitionService.tryReleaseAgent(lock);
    } catch (Exception e) {
      log.warn(
          "Failed to release agent lock {}: {}", lock.getAgent().getAgentType(), e.getMessage());
      return false;
    }
  }

  /**
   * Check if an agent lock is still valid.
   *
   * @param lock The lock to validate
   * @return true if lock is still valid, false otherwise
   */
  public boolean lockValid(AgentLock lock) {
    try {
      return acquisitionService.isLockValid(lock);
    } catch (Exception e) {
      log.warn(
          "Failed to validate agent lock {}: {}", lock.getAgent().getAgentType(), e.getMessage());
      return false;
    }
  }

  /**
   * Check if the scheduler is atomic (always returns true for Redis-based scheduler).
   *
   * @return true
   */
  public boolean isAtomic() {
    return true;
  }

  /** Shutdown the scheduler and clean up resources with graceful agent re-queuing. */
  @PreDestroy
  public void shutdown() {
    log.info("Shutting down PriorityAgentScheduler");

    // Signal shutdown to prevent new work
    running.set(false);

    // Signal shutdown to all services for proper coordination
    acquisitionService.setShuttingDown(true);

    try {
      // Step 1: Gracefully release active agents back to waiting queue
      gracefullyReleaseActiveAgents();

      // Step 2: Stop the scheduler executor
      config.getSchedulerExecutorService().shutdown();
      if (!config.getSchedulerExecutorService().awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("Scheduler executor did not terminate gracefully, forcing shutdown");
        config.getSchedulerExecutorService().shutdownNow();

        // Wait for forced termination
        if (!config.getSchedulerExecutorService().awaitTermination(10, TimeUnit.SECONDS)) {
          log.error("Scheduler executor failed to terminate even after forced shutdown");
        }
      }

      // Step 3: Shutdown all services
      config.shutdown();

      log.info("PriorityAgentScheduler shutdown completed successfully");
    } catch (Exception e) {
      log.error("Error during scheduler shutdown", e);
    }
  }

  /**
   * Gracefully releases all active agents back to the waiting queue during shutdown. This prevents
   * agent loss during deployments and restarts. Only re-queues agents this instance was actively
   * working on to prevent conflicts in multi-instance non-sharded environments.
   */
  private void gracefullyReleaseActiveAgents() {
    // Get count of agents this instance is actively working on
    int activeCount = acquisitionService.getActiveAgentCount();
    if (activeCount == 0) {
      log.info("No active agents to release during shutdown");
      return;
    }

    log.info("Gracefully releasing {} active agents during shutdown", activeCount);

    try {
      // Set graceful shutdown flag to prevent race condition with normal agent completion
      acquisitionService.setGracefulShutdown(true);

      // PHASE 1: Interrupt any running futures
      Map<String, Future<?>> activeAgentsFutures =
          acquisitionService.getActiveAgentsFuturesSnapshot();
      int interrupted = 0;

      for (Map.Entry<String, Future<?>> entry : activeAgentsFutures.entrySet()) {
        String agentType = entry.getKey();
        Future<?> future = entry.getValue();

        try {
          if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true);
            if (cancelled) {
              interrupted++;
              log.debug("Interrupted agent {} during shutdown", agentType);
            }

            // Release semaphore permit for interrupted agent
            if (config.getRunningAgents() != null) {
              config.getRunningAgents().release();
              log.debug("Released semaphore permit for interrupted agent {}", agentType);
            }
          }
        } catch (Exception e) {
          log.debug("Failed to interrupt agent {}: {}", agentType, e.getMessage());
        }
      }

      // Brief wait for interrupted agents to complete their cleanup
      if (interrupted > 0) {
        Thread.sleep(100);
      }

      // PHASE 2: Re-queue ONLY agents this instance was actively working on
      // This prevents duplicate re-queuing in non-sharded multi-instance environments
      Map<String, String> activeAgentsSnapshot = acquisitionService.getActiveAgentsMap();
      int released = 0;

      for (Map.Entry<String, String> entry : activeAgentsSnapshot.entrySet()) {
        String agentType = entry.getKey();
        String expectedScore = entry.getValue(); // Score when we acquired the agent

        try {
          Agent agent = acquisitionService.getAgentByType(agentType);
          if (agent != null) {
            // Conditionally re-queue - only if agent still in WORKZ with expected score
            acquisitionService.forceRequeueAgentForShutdown(agent, expectedScore);
            released++;
            log.debug(
                "Attempted re-queue of active agent {} for post-restart execution", agentType);
          }
        } catch (Exception e) {
          log.warn(
              "Failed to gracefully release active agent {} during shutdown: {}",
              agentType,
              e.getMessage());
        }
      }

      log.info(
          "Graceful shutdown: {} agents interrupted, {} active agents re-queued for restart",
          interrupted,
          released);

    } catch (Exception e) {
      log.error("Error during graceful agent release", e);
    }
  }

  /**
   * Refresh configuration if needed based on the configured refresh interval. This allows dynamic
   * configuration updates without restarts.
   *
   * @param currentRun The current run cycle number
   */
  private void refreshConfigurationIfNeeded(long currentRun) {
    // Check if we should refresh configuration (every 30 seconds by default)
    // With @ConfigurationProperties, most config is cached, but we maintain
    // the refresh framework for dynamic config support
    long schedulerIntervalMs = config.getSchedulerIntervalMs();

    // Calculate refresh cycles (refresh every 30 seconds)
    long refreshPeriodMs = 30000L; // 30 seconds
    long cyclesPerRefresh = refreshPeriodMs / schedulerIntervalMs;

    if (cyclesPerRefresh > 0 && currentRun % cyclesPerRefresh == 0) {
      refreshConfiguration();
    }
  }

  // Periodically re-apply sharding/enablement to known agents to achieve re-balancing when
  // podCount/podIndex or configuration changes.
  private void reconcileKnownAgentsIfNeeded(long currentRun) {
    try {
      long intervalMs = config.getSchedulerIntervalMs();
      long refreshPeriodSeconds = config.getRedisRefreshPeriod();
      long refreshPeriodMs = Math.max(1, refreshPeriodSeconds) * 1000L;
      long now = System.currentTimeMillis();
      long last = lastReconcileEpochMs.get();
      if (now - last < refreshPeriodMs) {
        return;
      }
      lastReconcileEpochMs.set(now);

      for (KnownAgent ka : knownAgents.values()) {
        Agent agent = ka.agent;
        boolean enabledNow = isAgentEnabled(agent);
        Agent registered = acquisitionService.getRegisteredAgent(agent.getAgentType());

        if (enabledNow && registered == null) {
          log.debug("Reconcile: registering newly-owned agent {}", agent.getAgentType());
          acquisitionService.registerAgent(agent, ka.execution, ka.instrumentation);
        } else if (!enabledNow && registered != null) {
          log.debug("Reconcile: unregistering no-longer-owned agent {}", agent.getAgentType());
          acquisitionService.unregisterAgent(agent);
        }
      }
    } catch (Throwable t) {
      log.warn("Failed to reconcile known agents with current shard/config: {}", t.getMessage());
    }
  }

  // Helper holder for known agent metadata
  private static final class KnownAgent {
    final Agent agent;
    final AgentExecution execution;
    final ExecutionInstrumentation instrumentation;

    KnownAgent(Agent agent, AgentExecution execution, ExecutionInstrumentation instrumentation) {
      this.agent = agent;
      this.execution = execution;
      this.instrumentation = instrumentation;
    }
  }

  // Test hook to force an immediate reconciliation without waiting for the cadence.
  void reconcileKnownAgentsNow() {
    lastReconcileEpochMs.set(0);
    reconcileKnownAgentsIfNeeded(runCount.get());
  }

  /**
   * Refresh runtime configuration from properties. Note: With @ConfigurationProperties, most config
   * is already cached, but this method can be used for dynamic config integration.
   */
  private void refreshConfiguration() {
    try {
      // Currently using @ConfigurationProperties which are already cached
      // This method is a placeholder for dynamic configuration support

      log.debug("Configuration refresh completed - using cached @ConfigurationProperties");

      // Possible enhancement: Add support for runtime configuration updates
      // that don't require application restart

    } catch (Exception e) {
      log.warn("Failed to refresh configuration: {}", e.getMessage());
    }
  }

  /**
   * Get scheduler statistics.
   *
   * @return SchedulerStats with current metrics
   */
  public SchedulerStats getStats() {
    return new SchedulerStats(
        runCount.get(),
        acquisitionService.getRegisteredAgentCount(),
        acquisitionService.getActiveAgentCount(),
        zombieService.getZombiesCleanedUp(),
        orphanService.getOrphansCleanedUp(),
        running.get(),
        acquisitionService.isDegraded(),
        acquisitionService.getDegradedReason(),
        acquisitionService.getOldestOverdueSeconds());
  }

  private void startScheduler() {
    long intervalMs = config.getSchedulerIntervalMs();

    config
        .getSchedulerExecutorService()
        .scheduleAtFixedRate(
            this,
            intervalMs, // Initial delay
            intervalMs, // Period
            TimeUnit.MILLISECONDS);

    log.info("Scheduler started with interval {}ms", intervalMs);
  }

  private boolean isAgentEnabled(Agent agent) {
    String agentType = agent.getAgentType();

    // Check sharding filter
    if (!shardingFilter.filter(agent)) {
      return false;
    }

    // Check enabled pattern
    if (!config.getEnabledAgentPattern().matcher(agentType).matches()) {
      return false;
    }

    // Check if agent is disabled (pattern or list)
    if (isAgentDisabled(agentType)) {
      return false;
    }

    return true;
  }

  /**
   * Check if an agent is disabled using regex pattern matching.
   *
   * @param agentType the agent type to check
   * @return true if the agent matches the disabled pattern
   */
  private boolean isAgentDisabled(String agentType) {
    return config.getDisabledAgentPattern() != null
        && config.getDisabledAgentPattern().matcher(agentType).matches();
  }

  /**
   * Statistics holder for scheduler metrics.
   *
   * <p>Includes a config-free health state derived from WAITZ queue lag relative to the minimum
   * enabled-agent interval on this pod. Queue lag is emitted in seconds even when HEALTHY to aid
   * sizing and performance diagnostics.
   */
  public static class SchedulerStats {
    private final long runCount;
    private final int registeredAgents;
    private final int activeAgents;
    private final long zombiesCleanedUp;
    private final long orphansCleanedUp;
    private final boolean running;
    private final boolean degraded;
    private final String degradedReason;
    private final long oldestOverdueSeconds;

    public SchedulerStats(
        long runCount,
        int registeredAgents,
        int activeAgents,
        long zombiesCleanedUp,
        long orphansCleanedUp,
        boolean running,
        boolean degraded,
        String degradedReason,
        long oldestOverdueSeconds) {
      this.runCount = runCount;
      this.registeredAgents = registeredAgents;
      this.activeAgents = activeAgents;
      this.zombiesCleanedUp = zombiesCleanedUp;
      this.orphansCleanedUp = orphansCleanedUp;
      this.running = running;
      this.degraded = degraded;
      this.degradedReason = degradedReason == null ? "" : degradedReason;
      this.oldestOverdueSeconds = oldestOverdueSeconds;
    }

    public long getRunCount() {
      return runCount;
    }

    public int getRegisteredAgents() {
      return registeredAgents;
    }

    public int getActiveAgents() {
      return activeAgents;
    }

    public long getZombiesCleanedUp() {
      return zombiesCleanedUp;
    }

    public long getOrphansCleanedUp() {
      return orphansCleanedUp;
    }

    public boolean isRunning() {
      return running;
    }

    public boolean isDegraded() {
      return degraded;
    }

    public String getDegradedReason() {
      return degradedReason;
    }

    public long getOldestOverdueSeconds() {
      return oldestOverdueSeconds;
    }

    @Override
    public String toString() {
      return String.format(
          "SchedulerStats{runCount=%d, registered=%d, active=%d, zombies=%d, orphans=%d, running=%s, health=%s, oldest_overdue=%ds%s}",
          runCount,
          registeredAgents,
          activeAgents,
          zombiesCleanedUp,
          orphansCleanedUp,
          running,
          degraded ? "DEGRADED" : "HEALTHY",
          oldestOverdueSeconds,
          degraded && degradedReason != null && !degradedReason.isEmpty()
              ? ", reason=" + degradedReason
              : "");
    }
  }
}
