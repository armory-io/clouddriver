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
import java.util.concurrent.TimeUnit;
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
 * <p>This scheduler provides distributed agent coordination with priority-based scheduling, zombie
 * detection, orphan cleanup, and atomic operations via Lua scripts.
 *
 * <p>Core Architecture:
 *
 * <ul>
 *   <li><strong>WAITING_SET (WAITZ):</strong> Agents ready for execution, scored by next run time
 *   <li><strong>WORKING_SET (WORKZ):</strong> Agents currently executing, scored by start time
 *   <li><strong>Atomic Operations:</strong> Lua scripts ensure race-free state transitions
 *   <li><strong>Priority Scheduling:</strong> Lower scores = higher priority execution
 * </ul>
 *
 * <h2>Configuration Properties & Performance Tuning</h2>
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
 *   <li><strong>enabledPattern:</strong> Regex for agent inclusion. Performance impact: O(k) regex
 *       evaluation per agent where k=pattern complexity. Use simple patterns for better
 *       performance.
 *   <li><strong>disabledPattern:</strong> Regex for agent exclusion (takes precedence). Same
 *       performance characteristics as enabledPattern. Empty string disables pattern matching.
 *   <li><strong>maxConcurrentAgents:</strong> Instance-wide semaphore limit. Prevents resource
 *       exhaustion. Increase for powerful instances (500-1000+), decrease for constrained
 *       environments (50-100). Affects memory usage and Redis connection pool pressure.
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
 *   <li><strong>intervalMs:</strong> Main scheduling loop frequency. Lower values = faster agent
 *       pickup but higher CPU/Redis load. Minimum recommended: 500ms. Optimal range: 1000-2000ms.
 *       High-load deployments can use 500ms with adequate resources.
 *   <li><strong>refreshPeriodSeconds:</strong> How often agents are synchronized from Spring
 *       context to Redis. Lower values = faster discovery of new agents but more Redis write
 *       operations. Scale with agent count: 1000+ agents use 15-20s, <100 agents can use 60s.
 *   <li><strong>batchOperationsEnabled:</strong> Groups multiple Redis operations for efficiency.
 *       Significant performance improvement for 200+ agents. Increases complexity but reduces
 *       network round-trips by 60-80%.
 *   <li><strong>timeCacheDurationMs:</strong> Caches Redis TIME command results to reduce calls.
 *       Higher values reduce Redis load but may cause timestamp drift. Range: 5000-30000ms.
 * </ul>
 *
 * <p><strong>Zombie Cleanup Configuration (redis.scheduler.zombieCleanup.*):</strong>
 *
 * <pre>
 * redis:
 *   scheduler:
 *     zombieCleanup:
 *       enabled: true                  # Default: zombie detection enabled
 *       thresholdMs: 1800000           # Default: 30 minutes (30 * 60 * 1000)
 *       cleanupIntervalMs: 300000      # Default: 5 minutes (5 * 60 * 1000)
 *       batchSize: 50                  # Default: process 50 zombies per batch
 * </pre>
 *
 * <ul>
 *   <li><strong>enabled:</strong> Master switch for zombie detection. Disable only for debugging.
 *   <li><strong>thresholdMs:</strong> Maximum agent execution time before considered stuck.
 *       <em>Critical:</em> Must exceed longest legitimate agent runtime (typically AWS=30min). Set
 *       to P99 agent execution time + 20% buffer. Too low kills valid agents, too high allows
 *       resource leaks.
 *   <li><strong>cleanupIntervalMs:</strong> Zombie scan frequency. Should be 6-12x more frequent
 *       than threshold to prevent accumulation. High-load environments benefit from 2-3 minute
 *       intervals.
 *   <li><strong>batchSize:</strong> Zombies processed per cleanup cycle. Higher values = fewer
 *       Redis round-trips but larger memory usage. Optimal: 25-100 based on typical zombie count.
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
 *   <li><strong>enabled:</strong> Controls cleanup of agents from crashed instances. Essential for
 *       preventing Redis memory bloat in multi-instance deployments.
 *   <li><strong>thresholdMs:</strong> Age threshold for orphan detection. WORKING set uses this
 *       value, WAITING set uses 2x this value. Balance between quick cleanup and startup grace
 *       period. Decrease for faster cleanup, increase for slower instance starts.
 *   <li><strong>intervalMs:</strong> Cleanup frequency. More frequent = cleaner Redis but higher
 *       overhead. Less frequent = potential memory bloat but lower load. Optimal: 5-10 minutes.
 *   <li><strong>batchSize:</strong> Orphans processed per cycle. Scale with typical orphan count
 *       after instance failures.
 *   <li><strong>leadershipTtlMs:</strong> Duration of cleanup leadership lock. Prevents multiple
 *       instances from cleaning simultaneously. Should be 2-3x intervalMs.
 *   <li><strong>forceAllPods:</strong> If true, all instances clean (no leadership). Use only for
 *       small deployments or troubleshooting.
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
 *   <li><strong>coreSize:</strong> Always-active threads for agent execution. Scale with
 *       steady-state agent load. Too low = queuing delays, too high = wasted resources.
 *   <li><strong>maxSize:</strong> Maximum threads under peak load. Should handle burst capacity.
 *       Monitor thread pool metrics to optimize. Typical ratio: maxSize = 3-5x coreSize.
 *   <li><strong>keepAliveSeconds:</strong> Idle thread timeout. Higher values = less thread churn
 *       but more memory usage. Lower values = more responsive to load changes.
 * </ul>
 */
@Component
public class ClusteredSortAgentScheduler extends CatsModuleAware
    implements AgentScheduler<ClusteredSortAgentLock>, Runnable {

  private static final Logger log = LoggerFactory.getLogger(ClusteredSortAgentScheduler.class);

  // Core services
  private final RedisScriptManager scriptManager;
  private final AgentAcquisitionService acquisitionService;
  private final ZombieCleanupService zombieService;
  private final OrphanCleanupService orphanService;
  private final SchedulerConfiguration config;

  // External dependencies
  private final NodeStatusProvider nodeStatusProvider;
  private final AgentIntervalProvider intervalProvider;
  private final ShardingFilter shardingFilter;

  // Runtime state
  private final AtomicLong runCount = new AtomicLong(0);
  private volatile boolean running = false;

  public ClusteredSortAgentScheduler(
      JedisPool jedisPool,
      NodeStatusProvider nodeStatusProvider,
      AgentIntervalProvider intervalProvider,
      ShardingFilter shardingFilter,
      ClusteredSortAgentProperties agentProperties,
      ClusteredSortSchedulerProperties schedulerProperties) {

    // Initialize services
    this.scriptManager = new RedisScriptManager(jedisPool);
    this.config = new SchedulerConfiguration(agentProperties, schedulerProperties);
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

    // Store external dependencies
    this.nodeStatusProvider = nodeStatusProvider;
    this.intervalProvider = intervalProvider;
    this.shardingFilter = shardingFilter;

    log.info("ClusteredSortAgentScheduler initialized successfully with extracted services");
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
      running = true;

      log.info("ClusteredSortAgentScheduler started successfully");
    } catch (Exception e) {
      log.error("Failed to initialize ClusteredSortAgentScheduler", e);
      throw new RuntimeException("Scheduler initialization failed", e);
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
   */
  @Override
  public void run() {
    if (!nodeStatusProvider.isNodeEnabled()) {
      return;
    }

    try {
      long currentRun = runCount.incrementAndGet();
      log.debug("Starting scheduler run cycle {}", currentRun);

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

    if (!isAgentEnabled(agent)) {
      log.debug("Agent {} not enabled, skipping registration", agent.getAgentType());
      return;
    }

    // Set up agent scheduler awareness
    if (agent instanceof AgentSchedulerAware) {
      ((AgentSchedulerAware) agent).setAgentScheduler(this);
    }

    // Register with acquisition service
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
  }

  /** Shutdown the scheduler and clean up resources. */
  @PreDestroy
  public void shutdown() {
    if (!running) {
      return;
    }

    log.info("Shutting down ClusteredSortAgentScheduler");
    running = false;

    try {
      // Stop the scheduler
      config.getSchedulerExecutorService().shutdown();
      if (!config.getSchedulerExecutorService().awaitTermination(30, TimeUnit.SECONDS)) {
        config.getSchedulerExecutorService().shutdownNow();
      }

      // Shutdown all services
      config.shutdown();

      log.info("ClusteredSortAgentScheduler shutdown completed");
    } catch (Exception e) {
      log.error("Error during scheduler shutdown", e);
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
        running);
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

  /** Statistics holder for scheduler metrics. */
  public static class SchedulerStats {
    private final long runCount;
    private final int registeredAgents;
    private final int activeAgents;
    private final long zombiesCleanedUp;
    private final long orphansCleanedUp;
    private final boolean running;

    public SchedulerStats(
        long runCount,
        int registeredAgents,
        int activeAgents,
        long zombiesCleanedUp,
        long orphansCleanedUp,
        boolean running) {
      this.runCount = runCount;
      this.registeredAgents = registeredAgents;
      this.activeAgents = activeAgents;
      this.zombiesCleanedUp = zombiesCleanedUp;
      this.orphansCleanedUp = orphansCleanedUp;
      this.running = running;
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

    @Override
    public String toString() {
      return String.format(
          "SchedulerStats{runCount=%d, registered=%d, active=%d, zombies=%d, orphans=%d, running=%s}",
          runCount, registeredAgents, activeAgents, zombiesCleanedUp, orphansCleanedUp, running);
    }
  }
}
