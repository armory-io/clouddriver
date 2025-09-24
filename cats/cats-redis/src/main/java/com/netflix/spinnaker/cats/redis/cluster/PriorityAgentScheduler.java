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

import static com.netflix.spinnaker.cats.redis.cluster.SchedulerUtils.*;

import com.netflix.spectator.api.DefaultRegistry;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Priority-based Redis agent scheduler using sorted sets for distributed coordination.
 *
 * <p>Uses two Redis sorted sets for scheduling agents across multiple instances:
 *
 * <ul>
 *   <li><strong>waiting set:</strong> Agents ready for execution, scored by next run time
 *   <li><strong>working set:</strong> Agents currently executing, scored by completion deadline
 * </ul>
 *
 * <p>Key features: atomic state transitions via Lua scripts, priority scheduling (lower scores =
 * higher priority), deadline-aware timeouts, zombie detection, orphan cleanup.
 *
 * <p>See external documentation for detailed configuration reference.
 */
@Component
@Slf4j
public class PriorityAgentScheduler extends CatsModuleAware
    implements AgentScheduler<AgentLock>, Runnable {

  // Core services
  private final RedisScriptManager scriptManager;
  private final PrioritySchedulerMetrics metrics;
  private final AgentAcquisitionService acquisitionService;
  private final ZombieCleanupService zombieService;
  private final OrphanCleanupService orphanService;
  private final PrioritySchedulerConfiguration config;

  // External dependencies
  private final NodeStatusProvider nodeStatusProvider;
  private final ShardingFilter shardingFilter;

  // Runtime state
  private final AtomicLong runCount = new AtomicLong(0);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong lastHealthLogEpochMs = new AtomicLong(0);

  // Non-blocking executors and guards
  private final java.util.concurrent.ExecutorService zombieCleanupExecutor;
  private final java.util.concurrent.ExecutorService orphanCleanupExecutor;
  private final java.util.concurrent.ExecutorService reconcileExecutor;
  private final AtomicBoolean zombieCleanupRunning = new AtomicBoolean(false);
  private final AtomicBoolean orphanCleanupRunning = new AtomicBoolean(false);
  private final AtomicBoolean reconcileRunning = new AtomicBoolean(false);

  // Track all agents provided via schedule(), regardless of current sharding gating
  private final java.util.concurrent.ConcurrentMap<String, KnownAgent> knownAgents =
      new java.util.concurrent.ConcurrentHashMap<>();

  // Reconciliation cadence control
  private final AtomicLong lastReconcileEpochMs = new AtomicLong(0);
  // Local submission gate for orphan cleanup to avoid per-second submits when not due/leader
  private final AtomicLong lastOrphanSubmitEpochMs = new AtomicLong(0);
  // Local submission gate for zombie cleanup to avoid per-second submits
  private final AtomicLong lastZombieSubmitEpochMs = new AtomicLong(0);

  // Watchdog streak counters (single-threaded scheduler loop)
  private int watchdogLeakStreak = 0;
  private int watchdogSkewStreak = 0;
  private int watchdogZeroProgressStreak = 0;
  private int watchdogRedisStallStreak = 0;

  /**
   * Creates a PriorityAgentScheduler with required dependencies.
   *
   * @param jedisPool Redis connection pool
   * @param nodeStatusProvider Node enablement state provider
   * @param shardingFilter Shard ownership filter
   * @param agentProperties Agent configuration
   * @param schedulerProperties Scheduler configuration
   * @param metrics Metrics registry
   */
  public PriorityAgentScheduler(
      JedisPool jedisPool,
      NodeStatusProvider nodeStatusProvider,
      AgentIntervalProvider intervalProvider,
      ShardingFilter shardingFilter,
      PriorityAgentProperties agentProperties,
      PrioritySchedulerProperties schedulerProperties,
      PrioritySchedulerMetrics metrics) {

    // Initialize services with defensive null checking
    this.metrics =
        SchedulerUtils.getOrDefault(metrics, new PrioritySchedulerMetrics(new DefaultRegistry()));
    this.scriptManager = new RedisScriptManager(jedisPool, this.metrics);
    this.config = new PrioritySchedulerConfiguration(agentProperties, schedulerProperties);
    this.acquisitionService =
        new AgentAcquisitionService(
            jedisPool,
            scriptManager,
            intervalProvider,
            shardingFilter,
            agentProperties,
            schedulerProperties,
            this.metrics);
    this.zombieService =
        new ZombieCleanupService(jedisPool, scriptManager, schedulerProperties, metrics);
    this.orphanService =
        new OrphanCleanupService(jedisPool, scriptManager, schedulerProperties, metrics);

    // Set up service references for advanced cleanup processing
    this.orphanService.setAcquisitionService(this.acquisitionService);
    // Provide acquisition service and fairness handler without reflection
    this.zombieService.setAcquisitionService(this.acquisitionService);
    this.zombieService.setFairnessHandler(this.acquisitionService);

    // Store external dependencies
    this.nodeStatusProvider = nodeStatusProvider;
    this.shardingFilter = shardingFilter;

    // Dedicated on-demand single-thread executors so the scheduler loop never blocks.
    // Threads are created only when needed and time out when idle for cleaner metrics.
    // For zombie cleanup, prefer to keep the worker thread parked when budget=0 by using the
    // zombie-cleanup interval as keep-alive. With a positive budget, keep-alive equals the budget
    // so the worker retires shortly after work finishes.
    this.zombieCleanupExecutor =
        ExecutorUtils.newOnDemandSingleThreadExecutor(
            "PriorityAgentCleanup-Zombie-#",
            config.getZombieRunBudgetMs() > 0
                ? config.getZombieRunBudgetMs()
                : config.getZombieIntervalMs());
    // For orphan cleanup, when runBudgetMs=0 we intentionally keep the worker thread around in
    // TIMED_WAITING between passes by using the cleanup interval as the keep-alive. This avoids
    // thread churn and makes APM attribution clearer. When a positive budget is configured, use it
    // as the keep-alive so the worker retires shortly after work finishes.
    this.orphanCleanupExecutor =
        ExecutorUtils.newOnDemandSingleThreadExecutor(
            "PriorityAgentCleanup-Orphan-#",
            config.getOrphanRunBudgetMs() > 0
                ? config.getOrphanRunBudgetMs()
                : config.getOrphanIntervalMs());
    // For reconcile, fall back to the Redis refresh cadence when no budget is set, to keep the
    // worker thread parked between reconciliation passes.
    this.reconcileExecutor =
        ExecutorUtils.newOnDemandSingleThreadExecutor(
            "PriorityAgentReconcile-#",
            config.getReconcileRunBudgetMs() > 0
                ? config.getReconcileRunBudgetMs()
                : Math.max(1_000L, (long) config.getRedisRefreshPeriod() * 1_000L));

    // Register shared gauges once
    try {
      this.metrics.registerGauges(
          jedisPool,
          () -> (double) acquisitionService.getRegisteredAgentCount(),
          () -> (double) acquisitionService.getActiveAgentCount(),
          () -> (double) acquisitionService.getReadyCountSnapshot(),
          () -> (double) acquisitionService.getOldestOverdueSeconds(),
          () -> acquisitionService.isDegraded() ? 1 : 0,
          () -> (double) acquisitionService.getCapacityPerCycleSnapshot(),
          () -> {
            if (config.getAgentWorkPool() instanceof java.util.concurrent.ThreadPoolExecutor) {
              return (double)
                  ((java.util.concurrent.ThreadPoolExecutor) config.getAgentWorkPool())
                      .getQueue()
                      .size();
            }
            return -1d;
          },
          () ->
              config.getRunningAgents() != null ? config.getRunningAgents().availablePermits() : -1,
          () -> (double) acquisitionService.getCompletionQueueSize(),
          () -> (double) acquisitionService.getServerClientOffsetMs(),
          () -> {
            double cap = acquisitionService.getCapacityPerCycleSnapshot();
            double ready = acquisitionService.getReadyCountSnapshot();
            return cap > 0 ? (ready / cap) : 0;
          },
          () -> (double) acquisitionService.getZombiesInFlight());
    } catch (Exception e) {
      log.debug("Failed to register scheduler gauges", e);
    }

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
   * Main scheduler execution loop - called periodically by ScheduledExecutorService. Orchestrates
   * agent scheduling, cleanup operations, and health monitoring.
   */
  @Override
  public void run() {
    if (!nodeStatusProvider.isNodeEnabled()) {
      return;
    }

    try {
      long start = currentTimeMillis();
      long currentRun = runCount.incrementAndGet();
      log.debug("Starting scheduler run cycle {}", currentRun);

      // Reconcile agent registrations with current sharding/enablement state (offloaded)
      long refreshPeriodMs = Math.max(1, config.getRedisRefreshPeriod()) * 1000L;
      boolean reconcileDue =
          CadenceGuard.isPeriodElapsed(lastReconcileEpochMs.get(), refreshPeriodMs);
      if (reconcileDue && reconcileRunning.compareAndSet(false, true)) {
        if (log.isDebugEnabled()) {
          log.debug("Begin reconcileKnownAgents offload for run {}", currentRun);
        }
        reconcileExecutor.submit(
            () -> {
              try {
                reconcileKnownAgentsIfNeeded(currentRun);
              } catch (Throwable t) {
                log.warn("Reconcile known agents failed", t);
                try {
                  metrics.incrementRunFailure(t.getClass().getSimpleName());
                } catch (Exception me) {
                  log.debug("Failed to record reconcile failure metric", me);
                }
              } finally {
                reconcileRunning.set(false);
                if (log.isDebugEnabled()) {
                  log.debug("End reconcileKnownAgents offload for run {}", currentRun);
                }
              }
            });
      } else if (log.isDebugEnabled()) {
        if (!reconcileDue) {
          log.debug("Skipping reconcile submission: refresh period not elapsed");
        } else {
          log.debug("Skipping reconcileKnownAgents: previous run still in progress");
        }
      }

      // Check if Redis repopulation is due. If we repopulate, skip acquisition
      // this cycle to avoid race conditions during initial agent registration
      boolean repopulatedThisCycle = acquisitionService.repopulateIfDueNow();

      // Acquire ready agents and submit them for execution first to guarantee forward progress
      // Skip if we just repopulated to let Redis stabilize
      int agentsAcquired = 0;
      if (!repopulatedThisCycle) {
        agentsAcquired =
            acquisitionService.saturatePool(
                currentRun, config.getRunningAgents(), config.getAgentWorkPool());
      } else {
        log.debug(
            "Skipping acquisition on repopulation cycle {} to prevent first-run races", currentRun);
      }

      // Watchdog: detect possible permit starvation and related stalls via ratio-based heuristics
      try {
        java.util.concurrent.Semaphore sem = config.getRunningAgents();
        int permits = sem != null ? sem.availablePermits() : -1;
        int poolActive = 0;
        if (config.getAgentWorkPool() instanceof java.util.concurrent.ThreadPoolExecutor) {
          poolActive =
              ((java.util.concurrent.ThreadPoolExecutor) config.getAgentWorkPool())
                  .getActiveCount();
        }
        int maxConcurrent =
            acquisitionService != null
                ? acquisitionService.getAgentProperties().getMaxConcurrentAgents()
                : 0;
        int activeCount = acquisitionService.getActiveAgentCount();
        long ready = acquisitionService.getReadyCountSnapshot();
        int zif = acquisitionService.getZombiesInFlight();
        int effectiveCapacity = Math.max(1, Math.max(0, maxConcurrent - (activeCount + zif)));
        double permitsFreePct =
            maxConcurrent > 0
                ? Math.max(0d, Math.min(1d, (double) permits / (double) maxConcurrent))
                : 0d;
        double activePct =
            maxConcurrent > 0
                ? Math.max(0d, Math.min(1d, (double) activeCount / (double) maxConcurrent))
                : 0d;
        double acquiredFillPct =
            effectiveCapacity > 0
                ? Math.max(0d, Math.min(1d, (double) agentsAcquired / (double) effectiveCapacity))
                : 0d;

        boolean redisStall = false;
        try {
          String redisState =
              String.valueOf(acquisitionService.getCircuitBreakerStatus().get("redis"));
          redisStall = (redisState != null && !"CLOSED".equalsIgnoreCase(redisState));
        } catch (Throwable t) {
          log.debug("Watchdog: unable to read redis breaker state; assuming CLOSED", t);
        }

        // Consecutive-tick streaks to avoid flapping
        // Leak suspect: almost no free permits AND pool idle AND ready backlog
        if (maxConcurrent > 0 && permitsFreePct < 0.01 && poolActive == 0 && ready > 0) {
          watchdogLeakStreak++;
        } else {
          watchdogLeakStreak = 0;
        }
        if (watchdogLeakStreak >= 3) {
          log.warn(
              "Watchdog: PERMIT_LEAK_SUSPECT permitsFreePct={} activePct={} ready={} poolActive={} maxConcurrent={}",
              String.format("%.2f", permitsFreePct),
              String.format("%.2f", activePct),
              ready,
              poolActive,
              maxConcurrent);
          watchdogLeakStreak = 0;
        }

        // Capacity skew (zIF): lots of free permits but we barely acquired
        if (ready > 0 && permitsFreePct > 0.90 && acquiredFillPct < 0.10) {
          watchdogSkewStreak++;
        } else {
          watchdogSkewStreak = 0;
        }
        if (watchdogSkewStreak >= 3) {
          log.warn(
              "Watchdog: CAPACITY_SKEW_ZIF permitsFreePct={} acquiredFillPct={} ready={} zIF={} effectiveCapacity={}",
              String.format("%.2f", permitsFreePct),
              String.format("%.2f", acquiredFillPct),
              ready,
              zif,
              effectiveCapacity);
          watchdogSkewStreak = 0;
        }

        // Zero progress (no acquisitions while ready) and not a redis stall
        if (ready > 0 && agentsAcquired == 0 && !redisStall) {
          watchdogZeroProgressStreak++;
        } else {
          watchdogZeroProgressStreak = 0;
        }
        if (watchdogZeroProgressStreak >= 3) {
          log.warn(
              "Watchdog: ZERO_PROGRESS ready={} acquiredThisTick={} redisStall={} permits={} active={} zIF={}",
              ready,
              agentsAcquired,
              redisStall,
              permits,
              activeCount,
              zif);
          watchdogZeroProgressStreak = 0;
        }

        // Redis stall streak (breaker open)
        if (redisStall) {
          watchdogRedisStallStreak++;
        } else {
          watchdogRedisStallStreak = 0;
        }
        if (watchdogRedisStallStreak >= 3) {
          log.warn("Watchdog: REDIS_STALL breaker open; skipping acquisitions until recovery");
          watchdogRedisStallStreak = 0;
        }
      } catch (Exception e) {
        log.debug("Watchdog check failed; continuing", e);
      }

      // Offload zombie cleanup (non-blocking) — pre-gated by cadence to avoid per-second submits
      try {
        long zombieIntervalMs = config.getZombieIntervalMs();
        long lastZombieSubmit = lastZombieSubmitEpochMs.get();
        boolean zombieDueToSubmit =
            CadenceGuard.isPeriodElapsed(lastZombieSubmit, zombieIntervalMs);
        if (zombieDueToSubmit && zombieCleanupRunning.compareAndSet(false, true)) {
          lastZombieSubmitEpochMs.set(currentTimeMillis());
          zombieCleanupExecutor.submit(
              () -> {
                try {
                  java.util.Map<String, String> activeAgentsSnapshot =
                      new java.util.HashMap<>(acquisitionService.getActiveAgentsMap());
                  java.util.Map<String, java.util.concurrent.Future<?>> futuresSnapshot =
                      new java.util.HashMap<>(acquisitionService.getActiveAgentsFutures());
                  long startTs = currentTimeMillis();
                  long budgetMs = config.getZombieRunBudgetMs();
                  zombieService.cleanupZombieAgentsIfNeeded(activeAgentsSnapshot, futuresSnapshot);
                  if (budgetMs > 0 && currentTimeMillis() - startTs > budgetMs) {
                    log.warn(
                        "Zombie cleanup exceeded budget {}ms; subsequent work will be deferred",
                        budgetMs);
                    // Cooperative hard stop: interrupt to signal budget breach
                    Thread.currentThread().interrupt();
                  }
                } catch (Throwable t) {
                  if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                  log.warn("Zombie cleanup failed", t);
                  try {
                    metrics.incrementRunFailure(t.getClass().getSimpleName());
                  } catch (Exception me) {
                    log.debug("Failed to record zombie cleanup failure metric", me);
                  }
                } finally {
                  zombieCleanupRunning.set(false);
                }
              });
        } else if (log.isDebugEnabled()) {
          if (!zombieDueToSubmit) {
            log.debug("Skipping zombie cleanup submission: interval not elapsed");
          } else {
            log.debug("Skipping zombie cleanup: previous run still in progress");
          }
        }
      } catch (Throwable t) {
        log.warn("Failed to schedule zombie cleanup", t);
      }

      // Offload orphan cleanup (non-blocking) — pre-gated by cadence to avoid per-second submits
      try {
        long intervalMs = config.getOrphanIntervalMs();
        long lastSubmit = lastOrphanSubmitEpochMs.get();
        boolean dueToSubmit = CadenceGuard.isPeriodElapsed(lastSubmit, intervalMs);
        if (dueToSubmit && orphanCleanupRunning.compareAndSet(false, true)) {
          lastOrphanSubmitEpochMs.set(currentTimeMillis());
          orphanCleanupExecutor.submit(
              () -> {
                try {
                  long startTs = currentTimeMillis();
                  long budgetMs = config.getOrphanRunBudgetMs();
                  orphanService.cleanupOrphanedAgentsIfNeeded();
                  if (budgetMs > 0 && currentTimeMillis() - startTs > budgetMs) {
                    log.warn(
                        "Orphan cleanup exceeded budget {}ms; subsequent work will be deferred",
                        budgetMs);
                    Thread.currentThread().interrupt();
                    return;
                  }
                } catch (Throwable t) {
                  if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                  log.warn("Orphan cleanup failed", t);
                  try {
                    metrics.incrementRunFailure(t.getClass().getSimpleName());
                  } catch (Exception me) {
                    log.debug("Failed to record orphan cleanup failure metric", me);
                  }
                } finally {
                  orphanCleanupRunning.set(false);
                }
              });
        } else if (log.isDebugEnabled()) {
          if (!dueToSubmit) {
            log.debug("Skipping orphan cleanup submission: interval not elapsed");
          } else {
            log.debug("Skipping orphan cleanup: previous run still in progress");
          }
        }
      } catch (Throwable t) {
        log.warn("Failed to schedule orphan cleanup", t);
      }

      if (log.isDebugEnabled() && agentsAcquired > 0) {
        log.debug(
            "Scheduler run cycle {} completed: {} agents acquired", currentRun, agentsAcquired);
      }

      // Log health summary every 10 minutes (time-based, not cycle-based)
      maybeLogHealthSummary();

      metrics.recordRunCycle(true, currentTimeMillis() - start);

    } catch (Throwable t) {
      log.error("Critical error in scheduler run cycle {}", runCount.get(), t);
      metrics.incrementRunFailure(t.getClass().getSimpleName());
      metrics.recordRunCycle(false, 0);
      // Continue scheduler operation despite errors - resilient to failures
    }
  }

  /**
   * Emit a periodic health summary at most once every 10 minutes, regardless of scheduler interval.
   *
   * <p>Includes: registered/active counts, scripts loaded, zombies/orphans cleaned, health state
   * (HEALTHY/DEGRADED + reason), oldest overdue (seconds), executor queue depth, and available
   * semaphore permits when enabled.
   */
  private void maybeLogHealthSummary() {
    long now = currentTimeMillis();
    long last = lastHealthLogEpochMs.get();
    if (!CadenceGuard.isPeriodElapsed(last, 10 * 60 * 1000L)) {
      return;
    }
    if (!lastHealthLogEpochMs.compareAndSet(last, now)) {
      return; // another thread logged
    }

    SchedulerStats stats = getStats();
    int queueDepth = -1;
    if (config.getAgentWorkPool() instanceof java.util.concurrent.ThreadPoolExecutor) {
      queueDepth =
          ((java.util.concurrent.ThreadPoolExecutor) config.getAgentWorkPool()).getQueue().size();
    }
    java.util.concurrent.Semaphore sem = config.getRunningAgents();
    int availablePermits = sem != null ? sem.availablePermits() : -1;
    int maxConcurrent = acquisitionService.getAgentProperties().getMaxConcurrentAgents();
    int activeCount = acquisitionService.getActiveAgentCount();
    int zombiesInFlight = acquisitionService.getZombiesInFlight();

    // Permit reconciliation: warn and mark degraded if heldPermits > active + zombiesInFlight
    boolean permitMismatch = false;
    if (sem != null && maxConcurrent > 0) {
      int heldPermits = Math.max(0, maxConcurrent - availablePermits);
      int accounted = activeCount + zombiesInFlight;
      if (heldPermits > accounted) {
        permitMismatch = true;
        log.warn(
            "Permit reconciliation warning: heldPermits={} > accounted={} (active={} + zombiesInFlight={}); maxConcurrent={} availablePermits={}. This suggests a stuck permit or lingering worker.",
            heldPermits,
            accounted,
            activeCount,
            zombiesInFlight,
            maxConcurrent,
            availablePermits);
      }
    }

    log.info(
        "Scheduler health [registered={}, active={}, futures={}, scripts={}] [zombies_cleaned={}, orphans_cleaned={}] running={} health={}{} oldest_overdue={}s queueDepth={} permitsAvailable={} zombiesInFlight={}",
        stats.getRegisteredAgents(),
        stats.getActiveAgents(),
        acquisitionService.getFuturesMapSize(),
        scriptManager.getScriptCount(),
        stats.getZombiesCleanedUp(),
        stats.getOrphansCleanedUp(),
        stats.isRunning(),
        (stats.isDegraded() || permitMismatch) ? "DEGRADED" : "HEALTHY",
        (stats.isDegraded() || permitMismatch)
            ? (" reason="
                + (permitMismatch
                    ? "permit_mismatch: heldPermits > active + zombiesInFlight; "
                    : "")
                + stats.getDegradedReason())
            : "",
        stats.getOldestOverdueSeconds(),
        queueDepth,
        availablePermits,
        zombiesInFlight);
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
      log.warn("Failed to lock agent {}", agent.getAgentType(), e);
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
      log.warn("Failed to release agent lock {}", lock.getAgent().getAgentType(), e);
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
      log.warn("Failed to validate agent lock {}", lock.getAgent().getAgentType(), e);
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
      long schedAwait = config.getOrphanExecutorShutdownAwaitMs();
      long schedForceAwait = config.getOrphanExecutorShutdownForceAwaitMs();
      if (!config
          .getSchedulerExecutorService()
          .awaitTermination(schedAwait, TimeUnit.MILLISECONDS)) {
        log.warn("Scheduler executor did not terminate gracefully, forcing shutdown");
        config.getSchedulerExecutorService().shutdownNow();
        // Wait for forced termination
        if (!config
            .getSchedulerExecutorService()
            .awaitTermination(schedForceAwait, TimeUnit.MILLISECONDS)) {
          log.error("Scheduler executor failed to terminate even after forced shutdown");
        }
      }

      // Stop zombie cleanup executor
      zombieCleanupExecutor.shutdown();
      long zombieAwait = config.getZombieExecutorShutdownAwaitMs();
      long zombieForceAwait = config.getZombieExecutorShutdownForceAwaitMs();
      if (!zombieCleanupExecutor.awaitTermination(zombieAwait, TimeUnit.MILLISECONDS)) {
        log.warn("Zombie cleanup executor did not terminate gracefully, forcing shutdown");
        zombieCleanupExecutor.shutdownNow();
        if (!zombieCleanupExecutor.awaitTermination(zombieForceAwait, TimeUnit.MILLISECONDS)) {
          log.error("Zombie cleanup executor failed to terminate after forced shutdown");
        }
      }

      // Stop orphan cleanup executor
      orphanCleanupExecutor.shutdown();
      long orphanAwait = config.getOrphanExecutorShutdownAwaitMs();
      long orphanForceAwait = config.getOrphanExecutorShutdownForceAwaitMs();
      if (!orphanCleanupExecutor.awaitTermination(orphanAwait, TimeUnit.MILLISECONDS)) {
        log.warn("Orphan cleanup executor did not terminate gracefully, forcing shutdown");
        orphanCleanupExecutor.shutdownNow();
        if (!orphanCleanupExecutor.awaitTermination(orphanForceAwait, TimeUnit.MILLISECONDS)) {
          log.error("Orphan cleanup executor failed to terminate after forced shutdown");
        }
      }

      // Stop reconcile executor (best-effort)
      reconcileExecutor.shutdown();
      long reconcileAwait = config.getReconcileExecutorShutdownAwaitMs();
      long reconcileForceAwait = config.getReconcileExecutorShutdownForceAwaitMs();
      if (!reconcileExecutor.awaitTermination(reconcileAwait, TimeUnit.MILLISECONDS)) {
        reconcileExecutor.shutdownNow();
        reconcileExecutor.awaitTermination(reconcileForceAwait, TimeUnit.MILLISECONDS);
      }

      // Step 3: Shutdown all services
      config.shutdown();

      log.info("PriorityAgentScheduler shutdown completed successfully");
    } catch (Exception e) {
      log.error("Error during scheduler shutdown", e);
    }
  }

  /**
   * Gracefully re-queues agents owned by this instance during shutdown.
   *
   * <p>Process: 1) Interrupt running futures and release permits 2) Conditionally move owned
   * working entries back to waiting if still owned (score match) 3) Perform a best-effort wait and
   * log outcomes
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
            SchedulerUtils.safeRelease(config.getRunningAgents(), 1);
            if (config.getRunningAgents() != null) {
              log.debug("Released semaphore permit for interrupted agent {}", agentType);
            }
          }
        } catch (Exception e) {
          log.debug("Failed to interrupt agent {}", agentType, e);
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
            // Conditionally re-queue - only if agent still in working with expected score
            acquisitionService.forceRequeueAgentForShutdown(agent, expectedScore);
            released++;
            log.debug(
                "Attempted re-queue of active agent {} for post-restart execution", agentType);
          }
        } catch (Exception e) {
          log.warn("Failed to gracefully release active agent {} during shutdown", agentType, e);
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
   * Periodically reconciles known agents with current sharding/enablement, registering newly-owned
   * agents and unregistering no-longer-owned ones without a restart.
   *
   * @param currentRun current scheduler cycle number
   */
  private void reconcileKnownAgentsIfNeeded(long currentRun) {
    try {
      long refreshPeriodSeconds = config.getRedisRefreshPeriod();
      long refreshPeriodMs = Math.max(1, refreshPeriodSeconds) * 1000L;
      long now = currentTimeMillis();
      long last = lastReconcileEpochMs.get();
      if (!CadenceGuard.isPeriodElapsed(last, refreshPeriodMs)) {
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

      // Lightweight local state validation: ensure activeAgents keys belong to registered agents
      try {
        java.util.Map<String, String> active = acquisitionService.getActiveAgentsMap();
        for (java.util.Map.Entry<String, String> e : active.entrySet()) {
          String agentType = e.getKey();
          String scoreStr = e.getValue();
          boolean numeric = scoreStr != null && scoreStr.matches("^\\d+$");
          if (acquisitionService.getRegisteredAgent(agentType) == null || !numeric) {
            // Inconsistent local tracking; clean it up to avoid leaks
            acquisitionService.removeActiveAgent(agentType);
            if (metrics != null) {
              metrics.incrementStateInconsistentActive();
            }
          }
        }
      } catch (Exception ignore) {
      }

      // Note: numeric-only waiting member detection and any repair is handled by
      // OrphanCleanupService on cadence. No reconcile-time sampling is performed here.
    } catch (Exception e) {
      log.warn("Failed to reconcile known agents with current shard/config", e);
    }
  }

  /** Lightweight holder for a scheduled agent and its execution/instrumentation handles. */
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

  /** Test hook that forces an immediate reconciliation without waiting for cadence. */
  void reconcileKnownAgentsNow() {
    lastReconcileEpochMs.set(0);
    reconcileKnownAgentsIfNeeded(runCount.get());
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

  /** Starts the periodic scheduler execution at the configured interval. */
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

  /**
   * Determines whether an agent is eligible for scheduling on this node.
   *
   * <p>Checks shard ownership, enabled pattern, and disabled pattern.
   */
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
   * <p>Includes a config-free health state derived from waiting queue lag relative to the minimum
   * enabled-agent interval on this pod. Queue lag is emitted in seconds even when HEALTHY to aid
   * sizing and performance diagnostics.
   */
  @lombok.Getter
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
