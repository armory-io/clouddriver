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

import static com.netflix.spinnaker.cats.agent.ExecutionInstrumentation.elapsedTimeMs;
import static com.netflix.spinnaker.cats.redis.cluster.SchedulerUtils.*;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Tuple;

/**
 * Service that acquires agents from Redis and executes them using atomic operations.
 *
 * <p>Manages agent lifecycle: acquisition from waiting set, execution in working set, completion
 * handling, and periodic repopulation for recovery.
 */
@Component
@Slf4j
public class AgentAcquisitionService {

  // Redis key names (injected via properties)
  private final String WAITING_SET;
  private final String WORKING_SET;

  private final JedisPool jedisPool;
  private final RedisScriptManager scriptManager;
  private final AgentIntervalProvider intervalProvider;
  private final ShardingFilter shardingFilter;
  private final PriorityAgentProperties agentProperties;
  private final PrioritySchedulerProperties schedulerProperties;
  private final PrioritySchedulerMetrics metrics;

  // Agent tracking
  private final Map<String, AgentWorker> agents = new ConcurrentHashMap<>();
  private final Map<String, String> activeAgents = new ConcurrentHashMap<>();
  private final Map<String, java.util.concurrent.Future<?>> activeAgentsFutures =
      new ConcurrentHashMap<>();

  // Tracks zombies that were cancelled and had their permits pre-released but whose threads
  // have not yet exited. This prevents the scheduler from oversubscribing when we release early.
  private final AtomicInteger zombiesInFlight = new AtomicInteger(0);

  // Redis TIME synchronization for multi-instance coordination
  private static final AtomicLong lastTimeCheck = new AtomicLong(0);
  private static final AtomicLong serverClientOffset = new AtomicLong(0);

  // Advanced statistics tracking
  private final AtomicLong agentMapSize = new AtomicLong(0);
  private final AtomicLong activeAgentMapSize = new AtomicLong(0);
  private final AtomicLong agentsAcquired = new AtomicLong(0);
  private final AtomicLong agentsExecuted = new AtomicLong(0);
  private final AtomicLong agentsFailed = new AtomicLong(0);

  // Exactly-once permit release handshake for zombie cancellation fairness
  private final ConcurrentHashMap<String, RunState> runStates = new ConcurrentHashMap<>();
  private volatile Semaphore runningAgentsRef; // Provided by scheduler when calling saturatePool

  private static final class RunState {
    final java.util.concurrent.atomic.AtomicBoolean permitHeld =
        new java.util.concurrent.atomic.AtomicBoolean(true);
  }

  // Backlog/health snapshots and rate-limiting
  private final AtomicLong lastBacklogWarnEpochMs = new AtomicLong(0);
  private final AtomicLong lastStallWarnEpochMs = new AtomicLong(0);
  private final AtomicLong lastDiagEpochMs = new AtomicLong(0);
  private final AtomicLong lastOldestOverdueSeconds = new AtomicLong(0);
  private final AtomicLong lastReadyCount = new AtomicLong(0);
  private final AtomicLong lastCapacityPerCycle = new AtomicLong(0);
  private final AtomicBoolean lastDegraded = new AtomicBoolean(false);
  private final java.util.concurrent.atomic.AtomicReference<String> lastDegradedReason =
      new java.util.concurrent.atomic.AtomicReference<>("");

  // Cached minimal enabled-agent interval in seconds for diagnostics (0 disables checks)
  private final AtomicLong cachedMinEnabledIntervalSec = new AtomicLong(0L);

  /**
   * Health evaluation notes:
   *
   * <ul>
   *   <li>Queue lag is computed based on the scores of agents in the waiting set; working-set
   *       overruns (zombies) are handled by the zombie cleanup.
   *   <li>Degradation is config-free: oldest_overdue_seconds > min enabled-agent interval on this
   *       pod.
   *   <li>WARNs are rate-limited to once per 10 minutes to avoid flooding.
   * </ul>
   */

  // Reusable collections to reduce GC pressure in high-load scenarios.
  // ThreadLocal is safe here because saturatePool() runs in single-threaded scheduler executor.
  // This avoids creating new HashSet instances on every scheduler cycle.
  private static final ThreadLocal<Set<AgentWorker>> REUSABLE_WORKERS_SET =
      ThreadLocal.withInitial(HashSet::new);

  // Runtime configuration
  private volatile Pattern enabledAgentPattern;
  private volatile Pattern disabledAgentPattern;
  private volatile int redisRefreshPeriod;

  // Shutdown coordination
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  private final AtomicBoolean gracefulShutdown = new AtomicBoolean(false);

  // Circuit breakers for protecting against cascading failures
  private final PrioritySchedulerCircuitBreaker acquisitionCircuitBreaker;
  private final PrioritySchedulerCircuitBreaker redisCircuitBreaker;

  // Queue agent completions for batch processing
  private final ConcurrentLinkedQueue<AgentCompletion> completionQueue =
      new ConcurrentLinkedQueue<>();

  // Time-based repopulation cadence control (epoch millis of last repopulation)
  private final java.util.concurrent.atomic.AtomicLong lastRepopulateEpochMs =
      new java.util.concurrent.atomic.AtomicLong(0L);

  /**
   * Local, per-pod failure streaks used for exponential backoff without extra Redis keys.
   *
   * <p>Streaks reset on success and increment on failure. This map is intentionally ephemeral and
   * will reset on pod restarts or resharding events.
   */
  private final java.util.concurrent.ConcurrentHashMap<String, Integer> failureStreaks =
      new java.util.concurrent.ConcurrentHashMap<>();

  // Failure classification for error-aware scheduling decisions.
  private enum FailureClass {
    PERMANENT_FORBIDDEN,
    THROTTLED,
    TRANSIENT,
    SERVER_ERROR,
    UNKNOWN
  }

  /** Represents an agent completion waiting to be processed in the next scheduler cycle. */
  private static class AgentCompletion {
    final Agent agent;
    final String acquireScore;
    final boolean success;
    final long timestamp;
    final FailureClass failureClass; // null when success
    final String throwableClassName; // optional; may be null

    AgentCompletion(Agent agent, String acquireScore, boolean success) {
      this.agent = agent;
      this.acquireScore = acquireScore;
      this.success = success;
      this.timestamp = currentTimeMillis();
      this.failureClass = null;
      this.throwableClassName = null;
    }

    AgentCompletion(
        Agent agent,
        String acquireScore,
        boolean success,
        FailureClass failureClass,
        String throwableClassName) {
      this.agent = agent;
      this.acquireScore = acquireScore;
      this.success = success;
      this.timestamp = currentTimeMillis();
      this.failureClass = failureClass;
      this.throwableClassName = throwableClassName;
    }
  }

  public AgentAcquisitionService(
      JedisPool jedisPool,
      RedisScriptManager scriptManager,
      AgentIntervalProvider intervalProvider,
      ShardingFilter shardingFilter,
      PriorityAgentProperties agentProperties,
      PrioritySchedulerProperties schedulerProperties,
      PrioritySchedulerMetrics metrics) {
    this.jedisPool = jedisPool;
    this.scriptManager = scriptManager;
    this.intervalProvider = intervalProvider;
    this.shardingFilter = shardingFilter;
    this.agentProperties = agentProperties;
    this.schedulerProperties = schedulerProperties;
    this.metrics = metrics;

    // Initialize circuit breakers with configuration settings
    PrioritySchedulerProperties.CircuitBreaker cbConfig = schedulerProperties.getCircuitBreaker();
    if (cbConfig != null && cbConfig.isEnabled()) {
      this.acquisitionCircuitBreaker =
          new PrioritySchedulerCircuitBreaker(
              "acquisition",
              cbConfig.getFailureThreshold(),
              cbConfig.getFailureWindowMs(),
              cbConfig.getCooldownMs(),
              cbConfig.getHalfOpenDurationMs(),
              metrics);

      // Redis circuit breaker is more sensitive (lower threshold, shorter window)
      this.redisCircuitBreaker =
          new PrioritySchedulerCircuitBreaker(
              "redis",
              Math.max(3, cbConfig.getFailureThreshold() - 2), // Slightly lower threshold
              Math.min(5000, cbConfig.getFailureWindowMs()), // Shorter window
              (long) (cbConfig.getCooldownMs() * 0.7), // Shorter cooldown
              (long) (cbConfig.getHalfOpenDurationMs() * 0.6), // Shorter half-open
              metrics);
    } else {
      // Create disabled circuit breakers that always allow requests
      this.acquisitionCircuitBreaker =
          new PrioritySchedulerCircuitBreaker(
              "acquisition",
              Integer.MAX_VALUE, // Never trip
              Long.MAX_VALUE,
              0,
              0,
              metrics);
      this.redisCircuitBreaker =
          new PrioritySchedulerCircuitBreaker(
              "redis",
              Integer.MAX_VALUE, // Never trip
              Long.MAX_VALUE,
              0,
              0,
              metrics);
    }

    // Resolve configured key names at construction time
    PrioritySchedulerProperties.Keys keysCfg = schedulerProperties.getKeys();
    String hash = keysCfg.getHashTag();
    String brace = (hash != null && !hash.isEmpty()) ? ("{" + hash + "}") : "";
    String prefix = keysCfg.getPrefix() != null ? keysCfg.getPrefix() : "";
    this.WAITING_SET = prefix + keysCfg.getWaitingSet() + brace;
    this.WORKING_SET = prefix + keysCfg.getWorkingSet() + brace;

    // Initialize runtime configuration
    this.enabledAgentPattern = Pattern.compile(agentProperties.getEnabledPattern());

    // Compile disabled agent pattern if provided
    if (!agentProperties.getDisabledPattern().isEmpty()) {
      this.disabledAgentPattern = Pattern.compile(agentProperties.getDisabledPattern());
    } else {
      this.disabledAgentPattern = null;
    }

    this.redisRefreshPeriod = schedulerProperties.getRefreshPeriodSeconds();
  }

  /**
   * Core scheduling logic: Find ready agents, acquire them, and submit for execution.
   *
   * <p>Semantics (chunked acquisition): - Compute available slots = max(0, maxConcurrentAgents -
   * currentlyRunning) unless unbounded - Fill up to available slots in chunks of size
   * redis.scheduler.batch-operations.batch-size within the same scheduler tick (multiple scans if
   * needed) - If batch-operations.batch-size <= 0, treat as "no per-chunk cap" and use remaining
   * slots as the chunk size (still uses batch acquisition if enabled) - Never spin: if a chunk
   * acquires 0, stop and submit what was acquired
   *
   * @param runCount Current run cycle number for periodic refresh
   * @param runningAgents Optional semaphore for instance-wide concurrency control
   * @param agentWorkPool Thread pool for executing agents
   * @return Number of agents successfully acquired and submitted for execution
   */
  public int saturatePool(long runCount, Semaphore runningAgents, ExecutorService agentWorkPool) {
    // Store reference for fairness bookkeeping (zombie in-flight compensation)
    this.runningAgentsRef = runningAgents;
    log.debug("Starting agent acquisition cycle {}, known agents: {}", runCount, agents.size());
    if (metrics != null) {
      metrics.incrementAcquireAttempts();
    }

    // Check circuit breaker before attempting acquisition
    if (!acquisitionCircuitBreaker.allowRequest()) {
      log.warn(
          "Acquisition circuit breaker is OPEN - skipping agent acquisition cycle {}", runCount);
      if (metrics != null) {
        metrics.recordCircuitBreakerBlocked("acquisition");
      }
      return 0;
    }

    long acquireStartMs = System.currentTimeMillis();

    try (Jedis jedis = jedisPool.getResource()) {
      // Prune completed futures (best-effort) to keep tracking map small
      try {
        for (Map.Entry<String, Future<?>> entry : new ArrayList<>(activeAgentsFutures.entrySet())) {
          Future<?> f = entry.getValue();
          if (f != null && f.isDone()) {
            activeAgentsFutures.remove(entry.getKey(), f);
          }
        }
      } catch (Exception ignore) {
        // Best-effort only
      }
      // Check concurrent agent limits before processing
      int maxConcurrentAgents = agentProperties.getMaxConcurrentAgents();
      int currentlyRunning = (int) activeAgentMapSize.get();
      boolean unbounded = maxConcurrentAgents <= 0;

      if (!unbounded && currentlyRunning >= maxConcurrentAgents) {
        log.debug(
            "Skipping agent acquisition - at max concurrent limit ({} running, {} max)",
            currentlyRunning,
            maxConcurrentAgents);
        return 0;
      }

      // PHASE 1: Process queued agent completions
      processQueuedCompletions(jedis);

      // PHASE 2: Agent Repopulation (Redis Recovery, periodic)
      long nowMsForRepop = System.currentTimeMillis();
      long refreshPeriodMs = Math.max(1L, schedulerProperties.getRefreshPeriodSeconds()) * 1000L;
      long last = lastRepopulateEpochMs.get();
      boolean dueByTime = (last != 0L) && ((nowMsForRepop - last) >= refreshPeriodMs);
      boolean dueByCycle = (redisRefreshPeriod > 0) && (runCount % redisRefreshPeriod == 0);
      if (dueByCycle) {
        lastRepopulateEpochMs.set(nowMsForRepop);
        repopulateRedisAgents(jedis);
      } else if (dueByTime) {
        lastRepopulateEpochMs.set(nowMsForRepop);
        repopulateRedisAgents(jedis);
      } else if (last == 0L) {
        // Initialize the window without performing repopulation on first call
        lastRepopulateEpochMs.compareAndSet(0L, nowMsForRepop);
      }

      // PHASE 3: Determine current readiness state for diagnostics (gated by cadence/need)
      String currentScore = score(jedis, 0L);
      // Gate diagnostics: only compute when debug is enabled, when warn cadence is due,
      // or when a periodic diagnostic cadence elapses. Period derives from scheduler interval.
      long schedulerIntervalMs = schedulerProperties.getIntervalMs();
      final long DIAG_PERIOD_MS = Math.max(3L * Math.max(1L, schedulerIntervalMs), 10_000L);
      boolean emitDiag =
          log.isDebugEnabled()
              || isPeriodElapsed(lastBacklogWarnEpochMs, 600_000L)
              || isPeriodElapsed(lastStallWarnEpochMs, 300_000L)
              || isPeriodElapsed(lastDiagEpochMs, DIAG_PERIOD_MS);

      long readyCountForDiagnostics = -1L;
      if (emitDiag) {
        try {
          readyCountForDiagnostics = jedis.zcount(WAITING_SET, "-inf", currentScore);
        } catch (Exception ignore) {
          readyCountForDiagnostics = -1L; // unknown on failure
        }

        if (readyCountForDiagnostics == 0L) {
          // Detect acquisition stall: waiting set has backlog but none are ready (e.g.,
          // future-scored)
          try {
            long waitingBacklog = jedis.zcard(WAITING_SET);

            if (waitingBacklog > 0) {
              long nowSec;
              try {
                nowSec = Long.parseLong(currentScore);
              } catch (NumberFormatException nfe) {
                nowSec = System.currentTimeMillis() / 1000L;
              }

              final int window =
                  Math.max(
                      8, Math.min(64, schedulerProperties.getBatchOperations().getBatchSize()));
              Long earliestLocalWaitingScore = null;
              try {
                Set<Tuple> earliest =
                    jedis.zrangeWithScores(WAITING_SET, 0, Math.max(0, window - 1));
                for (Tuple t : earliest) {
                  String agentType = t.getElement();
                  AgentWorker local = agents.get(agentType);
                  if (local != null && isAgentEnabled(local.getAgent())) {
                    earliestLocalWaitingScore = (long) t.getScore();
                    break;
                  }
                }
              } catch (Exception ignore) {
                // Best-effort; keep null on failure
              }

              long minIntervalSec = cachedMinEnabledIntervalSec.get();
              if (earliestLocalWaitingScore != null
                  && minIntervalSec > 0L
                  && (earliestLocalWaitingScore - nowSec) > minIntervalSec
                  && shouldWarnNow(lastStallWarnEpochMs, 300_000)) {
                long nextReadyInSec = Math.max(0L, earliestLocalWaitingScore - nowSec);
                log.warn(
                    "Acquisition stall detected: ready=0, waiting_backlog={}, next_local_ready_in={}s > min_interval={}s, pool_active={}, pool_waiters={}",
                    waitingBacklog,
                    nextReadyInSec,
                    minIntervalSec,
                    jedisPool.getNumActive(),
                    jedisPool.getNumWaiters());
                if (metrics != null) {
                  metrics.incrementStallDetected();
                }
              }
            }
          } catch (Exception ignore) {
            // Diagnostics only
          }
          if (log.isDebugEnabled()) {
            log.debug("No agents ready for execution");
          }
          // Early return consistent with original behavior when we know none are ready
          return 0;
        }
      }

      // Compute queue lag and health before acquisition; reuse Redis results when possible
      long nowSec;
      try {
        nowSec = Long.parseLong(currentScore);
      } catch (NumberFormatException nfe) {
        nowSec = System.currentTimeMillis() / 1000L;
      }

      long readyCount = emitDiag ? Math.max(0L, readyCountForDiagnostics) : -1L;
      long oldestOverdueSec = 0L;
      if (emitDiag) {
        try {
          // To avoid false positives on DEGRADED, consider only agents known and enabled locally.
          // Fetch a small window of the oldest ready entries and pick the first matching local
          // agent.
          final int window =
              Math.max(8, Math.min(64, schedulerProperties.getBatchOperations().getBatchSize()));
          Set<Tuple> oldestWindow =
              jedis.zrangeByScoreWithScores(WAITING_SET, "-inf", currentScore, 0, window);
          for (Tuple t : oldestWindow) {
            String agentType = t.getElement();
            AgentWorker local = agents.get(agentType);
            if (local != null && isAgentEnabled(local.getAgent())) {
              long oldestScore = (long) t.getScore();
              oldestOverdueSec = Math.max(0L, nowSec - oldestScore);
              break;
            }
          }
        } catch (Exception ignore) {
          // Best-effort; keep defaults on failure
        }
      }

      // PHASE 4: Agent acquisition setup
      // Reusing thread-local collection to avoid memory allocations
      Set<AgentWorker> workersToSubmit = REUSABLE_WORKERS_SET.get();
      workersToSubmit.clear(); // Clear any previous contents
      int agentsAcquiredThisCycle = 0;

      // Prevent same-tick reacquisition attempts for the same agent
      java.util.Set<String> attemptedThisCycle = new java.util.HashSet<>();

      // Calculate how many new agents this pod can try to acquire
      int effectiveRunning = currentlyRunning + zombiesInFlight.get();
      int availableSlotsForNewAgents =
          unbounded ? Integer.MAX_VALUE : Math.max(0, maxConcurrentAgents - effectiveRunning);

      if (!unbounded && availableSlotsForNewAgents <= 0) {
        if (log.isDebugEnabled()) {
          log.debug(
              "No available slots to acquire new agents this cycle ({} running, {} max). Skipping acquisition phase.",
              currentlyRunning,
              maxConcurrentAgents);
        }
        return 0;
      }

      long readyLimit = (readyCount >= 0) ? readyCount : Long.MAX_VALUE;
      int effectiveMaxToAcquire =
          unbounded
              ? (int) Math.min(Integer.MAX_VALUE, readyLimit)
              : (int) Math.min(availableSlotsForNewAgents, Math.max(0L, readyLimit));

      // Evaluate health/degradation and rate-limited WARNing.
      // Avoid false positives by excluding known-orphan/zombie cases: the decision is based purely
      // on queue lag in the waiting set (via agent scores) and local agent cadences. Working-set
      // overruns are handled by the zombie cleanup and are not considered here.
      long minIntervalSec = cachedMinEnabledIntervalSec.get();

      boolean degraded = oldestOverdueSec > minIntervalSec && minIntervalSec > 0L;
      int capacityPerCycle = availableSlotsForNewAgents;

      if (degraded && shouldWarnNow(lastBacklogWarnEpochMs, 600_000)) {
        log.warn(
            "PriorityScheduler degraded: oldest_overdue={}s > min_interval={}s; ready={} capacityPerCycle={} running={} maxConcurrent={}",
            oldestOverdueSec,
            minIntervalSec,
            readyCount,
            capacityPerCycle,
            currentlyRunning,
            agentProperties.getMaxConcurrentAgents());
      }

      // Store initial degradation state for later update after slot filling check
      boolean initialDegraded = degraded;
      String initialDegradedReason =
          degraded
              ? String.format(
                  "oldest_overdue=%ss > min_interval=%ss; ready=%d capacityPerCycle=%d",
                  oldestOverdueSec, minIntervalSec, Math.max(0L, readyCount), capacityPerCycle)
              : "";

      // Persist initial snapshots (will be updated after slot filling check if needed)
      if (emitDiag) {
        lastOldestOverdueSeconds.set(oldestOverdueSec);
        lastReadyCount.set(Math.max(0L, readyCount));
        lastCapacityPerCycle.set(capacityPerCycle);
        // Degradation status will be finalized after slot filling check
      }
      int queueDepthDebug = -1;
      if (agentWorkPool instanceof java.util.concurrent.ThreadPoolExecutor) {
        queueDepthDebug =
            ((java.util.concurrent.ThreadPoolExecutor) agentWorkPool).getQueue().size();
      }
      log.debug(
          "Attempting to acquire agents ({} running, {} max capacity, {} ready in Redis, up to {} slots this cycle, queueDepth={})",
          effectiveRunning,
          maxConcurrentAgents,
          readyCount,
          availableSlotsForNewAgents,
          queueDepthDebug);

      // PHASE 5: Acquire up to available slots in chunks of batch-size
      int remainingToAcquire = availableSlotsForNewAgents;
      int chunkOffset = 0; // Track offset for pagination through ready agents

      // Calculate max chunk attempts based on actual need and filtering expectations
      int configuredBatchSize = schedulerProperties.getBatchOperations().getBatchSize();
      if (configuredBatchSize <= 0) {
        configuredBatchSize =
            availableSlotsForNewAgents; // Use all slots if batch size not configured
      }

      // Base calculation: how many chunks we need to fill available slots
      int baseAttempts =
          (availableSlotsForNewAgents + configuredBatchSize - 1)
              / configuredBatchSize; // ceiling division

      // Apply multiplier for filtering scenarios
      double multiplier = schedulerProperties.getBatchOperations().getChunkAttemptMultiplier();
      int maxChunkAttempts;

      if (multiplier <= 0) {
        // No multiplier configured - just use base attempts (no extra attempts for filtering)
        maxChunkAttempts = Math.max(1, baseAttempts);
        log.debug("Using base chunk attempts (no multiplier): {}", maxChunkAttempts);
      } else {
        // Apply multiplier to handle filtering
        maxChunkAttempts = Math.max(1, (int) Math.ceil(baseAttempts * multiplier));

        // Cap at a reasonable limit to prevent runaway in edge cases
        maxChunkAttempts = Math.min(maxChunkAttempts, 100);

        log.debug(
            "Calculated chunk attempts: {} (slots: {} / batch: {} = {} base × {} multiplier)",
            maxChunkAttempts,
            availableSlotsForNewAgents,
            configuredBatchSize,
            baseAttempts,
            multiplier);
      }

      int chunkAttempts = 0;

      while (remainingToAcquire > 0 && chunkAttempts < maxChunkAttempts) {
        chunkAttempts++;

        // Use configured batch size when positive; otherwise treat as unlimited for this chunk
        int configuredBatch = schedulerProperties.getBatchOperations().getBatchSize();
        int perChunkLimit = configuredBatch > 0 ? configuredBatch : remainingToAcquire;
        int chunkSize = Math.min(remainingToAcquire, perChunkLimit);
        if (chunkSize <= 0) {
          break;
        }

        // Refresh server time for fairness across chunks
        currentScore = score(jedis, 0L);

        // Use offset to skip already-tried agents when continuing after filtered chunks
        Set<String> readyChunk =
            jedis.zrangeByScore(WAITING_SET, "-inf", currentScore, chunkOffset, chunkSize);

        if (readyChunk == null || readyChunk.isEmpty()) {
          break; // Nothing else ready right now
        }

        int acquiredThisChunk = 0;
        if (schedulerProperties.getBatchOperations().isEnabled() && readyChunk.size() > 1) {
          try {
            acquiredThisChunk =
                saturatePoolBatch(
                    jedis,
                    readyChunk,
                    chunkSize,
                    runningAgents,
                    workersToSubmit,
                    attemptedThisCycle);
          } catch (Exception e) {
            log.warn(
                "Batch acquisition failed for chunk, falling back to individual: {}",
                e.getMessage());
            workersToSubmit.clear();
            acquiredThisChunk =
                saturatePoolIndividual(
                    jedis,
                    readyChunk,
                    chunkSize,
                    runningAgents,
                    workersToSubmit,
                    attemptedThisCycle);
          }
        } else {
          acquiredThisChunk =
              saturatePoolIndividual(
                  jedis, readyChunk, chunkSize, runningAgents, workersToSubmit, attemptedThisCycle);
        }

        if (acquiredThisChunk <= 0) {
          // No agents acquired from this chunk - could be due to:
          // 1. All agents in chunk were acquired by other pods (normal contention)
          // 2. All agents were filtered out (sharding/disabled)
          // 3. Semaphore exhausted
          // Continue to next chunk to avoid starvation of agents deeper in queue
          log.debug(
              "No agents acquired from chunk (size: {}) at offset {}, checking for more ready agents",
              readyChunk.size(),
              chunkOffset);

          // Only break if we had nothing to attempt (empty chunk means no more ready)
          if (readyChunk.isEmpty()) {
            break;
          }
          // Move offset forward to skip agents we've already tried
          chunkOffset += readyChunk.size();
          // Continue to next chunk to prevent starvation
          continue;
        }

        agentsAcquiredThisCycle += acquiredThisChunk;
        remainingToAcquire -= acquiredThisChunk;
        // Reset offset to 0 after successful acquisition since acquired agents are removed from
        // waiting set
        // Only move offset forward if no agents were acquired (filtering scenario)
        chunkOffset = 0;
      }

      // Check for slot filling performance issues
      boolean slotFillingIssue = false;
      String slotFillingReason = null;

      if (chunkAttempts >= maxChunkAttempts && remainingToAcquire > 0) {
        // Check if we have significant unfilled slots with evidence of filtering
        double unfilledRatio = (double) remainingToAcquire / availableSlotsForNewAgents;
        int scannedButNotAcquired = chunkOffset - agentsAcquiredThisCycle;

        if (unfilledRatio > 0.2 && scannedButNotAcquired > 0) {
          // Significant performance degradation detected
          slotFillingIssue = true;
          double actualFilterRate = (double) scannedButNotAcquired / chunkOffset;
          slotFillingReason =
              String.format(
                  "slot_filling_degraded: %d%% unfilled after scanning %d agents (filter_rate=%.1f%%, multiplier=%s)",
                  (int) (unfilledRatio * 100),
                  chunkOffset,
                  actualFilterRate * 100,
                  multiplier > 0 ? String.valueOf(multiplier) : "0");
        } else if (log.isDebugEnabled()) {
          // Still log at debug for troubleshooting
          log.debug(
              "Reached max chunk attempts ({}), {} slots unfilled, scanned {}, acquired {}",
              maxChunkAttempts,
              remainingToAcquire,
              chunkOffset,
              agentsAcquiredThisCycle);
        }
      }

      // Update final degradation status combining both issues
      if (emitDiag) {
        boolean finalDegraded = initialDegraded || slotFillingIssue;
        String finalDegradedReason;

        if (initialDegraded && slotFillingIssue) {
          // Both issues present
          finalDegradedReason = initialDegradedReason + "; " + slotFillingReason;
        } else if (initialDegraded) {
          // Only backlog issue
          finalDegradedReason = initialDegradedReason;
        } else if (slotFillingIssue) {
          // Only slot filling issue
          finalDegradedReason = slotFillingReason;
        } else {
          // No issues
          finalDegradedReason = "";
        }

        lastDegraded.set(finalDegraded);
        lastDegradedReason.set(finalDegradedReason);
        lastDiagEpochMs.set(System.currentTimeMillis());
      }

      // PHASE 6: Submit all acquired agents for execution
      // Submit each agent individually to handle rejections properly
      for (AgentWorker worker : workersToSubmit) {
        // CRITICAL: Set semaphore before execution so it can be released when done
        worker.setRunningAgents(runningAgents);
        // Initialize run-state for exactly-once permit release
        runStates.put(worker.getAgent().getAgentType(), new RunState());

        // Submit with proper rejection handling
        java.util.concurrent.Future<?> future =
            submitAgentWithRejectionHandling(worker, agentWorkPool, runningAgents);

        if (future != null) {
          activeAgentsFutures.put(worker.getAgent().getAgentType(), future);
          log.debug("Submitted agent {} for execution", worker.getAgent().getAgentType());
        }
      }

      log.debug(
          "Completed agent acquisition cycle: {} agents acquired and submitted for execution",
          agentsAcquiredThisCycle);

      if (metrics != null) {
        metrics.incrementAcquired(agentsAcquiredThisCycle);
        metrics.recordAcquireTime("auto", System.currentTimeMillis() - acquireStartMs);
      }

      // Record successful acquisition to circuit breaker
      acquisitionCircuitBreaker.recordSuccess();
      redisCircuitBreaker.recordSuccess();

      return agentsAcquiredThisCycle;

    } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
      log.warn("Redis connection error during agent acquisition: {}", e.getMessage());

      // Record Redis failure to circuit breakers
      redisCircuitBreaker.recordFailure(e);
      acquisitionCircuitBreaker.recordFailure(e);

      if (metrics != null) {
        metrics.recordAcquireTime("auto", System.currentTimeMillis() - acquireStartMs);
      }
      return 0;
    } catch (Exception e) {
      log.error("Error during agent acquisition cycle", e);

      // Record general failure to acquisition circuit breaker
      acquisitionCircuitBreaker.recordFailure(e);

      if (metrics != null) {
        metrics.recordAcquireTime("auto", System.currentTimeMillis() - acquireStartMs);
      }
      return 0;
    }
  }

  /**
   * Public helper to perform only the periodic Redis repopulation when due, without attempting any
   * acquisition. Intended for schedulers that want to separate repopulation from acquisition within
   * a single run cycle.
   *
   * @param runCount current scheduler cycle number
   */
  public void repopulateIfDue(long runCount) {
    // Check Redis circuit breaker before attempting repopulation
    if (!redisCircuitBreaker.allowRequest()) {
      log.debug("Redis circuit breaker is OPEN - skipping repopulation for cycle {}", runCount);
      if (metrics != null) {
        metrics.recordCircuitBreakerBlocked("redis");
      }
      return;
    }

    long start = System.currentTimeMillis();
    try (Jedis jedis = jedisPool.getResource()) {
      long nowMsForRepop = start;
      long refreshPeriodMs = Math.max(1L, schedulerProperties.getRefreshPeriodSeconds()) * 1000L;
      long last = lastRepopulateEpochMs.get();
      if (nowMsForRepop - last >= refreshPeriodMs
          && lastRepopulateEpochMs.compareAndSet(last, nowMsForRepop)) {
        repopulateRedisAgents(jedis);
        if (metrics != null) {
          metrics.recordRepopulateTime(System.currentTimeMillis() - start);
        }
        redisCircuitBreaker.recordSuccess();
      }
    } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
      log.warn("Redis connection error during repopulation: {}", e.getMessage());
      redisCircuitBreaker.recordFailure(e);
      if (metrics != null) {
        metrics.incrementRepopulateError("redis_connection");
        metrics.recordRepopulateTime(System.currentTimeMillis() - start);
      }
    } catch (Exception e) {
      log.warn("Repopulation attempt failed: {}", e.getMessage());
      if (metrics != null) {
        metrics.incrementRepopulateError(e.getClass().getSimpleName());
        metrics.recordRepopulateTime(System.currentTimeMillis() - start);
      }
    }
  }

  /**
   * Perform time-based repopulation when due and return true if it was executed. This is intended
   * for schedulers to decide whether to skip acquisition on the same tick.
   */
  public boolean repopulateIfDueNow() {
    long now = SchedulerUtils.currentTimeMillis();
    long refreshPeriodMs = Math.max(1L, schedulerProperties.getRefreshPeriodSeconds()) * 1000L;
    long last = lastRepopulateEpochMs.get();
    if (last == 0L) {
      // Do not initialize here; allow caller (scheduler) to trigger repopulation
      // on first run when required by tests/config.
      return false;
    }
    if (!SchedulerUtils.isPeriodElapsed(last, refreshPeriodMs)) {
      return false;
    }
    if (!lastRepopulateEpochMs.compareAndSet(last, now)) {
      return false;
    }

    long start = now;
    try (Jedis jedis = jedisPool.getResource()) {
      repopulateRedisAgents(jedis);
      if (metrics != null) {
        metrics.recordRepopulateTime(System.currentTimeMillis() - start);
      }
      return true;
    } catch (Exception e) {
      log.warn("Repopulation attempt failed: {}", e.getMessage());
      if (metrics != null) {
        metrics.incrementRepopulateError(e.getClass().getSimpleName());
      }
      return false;
    }
  }

  private static boolean shouldWarnNow(AtomicLong lastEpochMs, long minPeriodMs) {
    long now = System.currentTimeMillis();
    long last = lastEpochMs.get();
    if (now - last >= minPeriodMs) {
      lastEpochMs.set(now);
      return true;
    }
    return false;
  }

  private static boolean isPeriodElapsed(AtomicLong lastEpochMs, long periodMs) {
    long now = System.currentTimeMillis();
    long last = lastEpochMs.get();
    return now - last >= periodMs;
  }

  /**
   * Acquires agents in batches to control memory consumption and lock contention in large
   * deployments. Uses {@code agentAcquisitionBatchSize} to limit the number of agents processed in
   * each Redis operation.
   *
   * @param jedis Redis connection
   * @param readyAgents Set of agent types ready for execution
   * @param maxToAcquire Maximum number of agents to acquire (concurrency limit)
   * @param runningAgents Semaphore for concurrency control
   * @param workersToSubmit Collection to add successfully acquired workers
   * @return Number of agents successfully acquired
   */
  private int saturatePoolBatch(
      Jedis jedis,
      Set<String> readyAgents,
      int maxToAcquire,
      Semaphore runningAgents,
      Set<AgentWorker> workersToSubmit,
      java.util.Set<String> attemptedThisCycle) {

    // Calculate batch size to prevent memory/Redis overload
    int configuredBatchSize = schedulerProperties.getBatchOperations().getBatchSize();
    int effectiveBatchSize =
        configuredBatchSize > 0 ? Math.min(maxToAcquire, configuredBatchSize) : maxToAcquire;

    log.debug(
        "Using batch agent acquisition: {} ready agents, max: {}, batch size: {}",
        readyAgents.size(),
        maxToAcquire,
        effectiveBatchSize);

    int candidateCount = 0;
    List<String> candidateAgents = new ArrayList<>();
    List<AgentWorker> candidateWorkers = new ArrayList<>();

    // Snapshot agents to prevent concurrent modification during batch processing
    // Critical for avoiding race conditions with dynamic account updates
    Map<String, AgentWorker> agentsSnapshot = new ConcurrentHashMap<>(agents);

    // PHASE 1: Build candidate list and acquire semaphore permits
    // Note: We respect BOTH the concurrency limit (maxToAcquire) AND batch size limit
    for (String agentType : readyAgents) {
      if (attemptedThisCycle != null && attemptedThisCycle.contains(agentType)) {
        continue;
      }
      if (candidateCount >= effectiveBatchSize) {
        log.debug(
            "Reached batch size limit: {} agents prepared for acquisition", effectiveBatchSize);
        break;
      }

      if (runningAgents != null && !runningAgents.tryAcquire()) {
        log.debug("Semaphore limit reached at {} agents", candidateCount);
        break;
      }

      AgentWorker worker = agentsSnapshot.get(agentType);
      if (worker == null) {
        log.warn(
            "Agent {} not found in local registry, skipping (may have been dynamically removed)",
            agentType);
        if (runningAgents != null) {
          runningAgents.release();
        }
        continue;
      }

      // Sharding/enablement gating at acquisition time (dynamic-safe)
      if (!isAgentEnabled(worker.getAgent())) {
        log.debug(
            "Skipping candidate agent {} due to sharding/enablement filter during acquisition",
            agentType);
        if (runningAgents != null) {
          runningAgents.release();
        }
        continue;
      }

      candidateAgents.add(agentType);
      candidateWorkers.add(worker);
      candidateCount++; // Track candidates prepared
      if (attemptedThisCycle != null) {
        attemptedThisCycle.add(agentType);
      }
    }

    if (candidateAgents.isEmpty()) {
      log.debug("No valid candidate agents for batch acquisition");
      return 0;
    }

    // PHASE 2: Batch acquire agents using Redis Lua script
    try {
      // Prepare Redis Lua script arguments: [agent1, score1, agent2, score2, ...]
      // The Lua script expects alternating agent names and scores
      List<String> agentScorePairs = new ArrayList<>();

      for (int i = 0; i < candidateAgents.size(); i++) {
        String agentType = candidateAgents.get(i);
        AgentWorker worker = candidateWorkers.get(i);

        // Generate completion deadline for this agent (current time + timeout)
        long agentTimeout = intervalProvider.getInterval(worker.getAgent()).getTimeout();
        String acquireScore = score(jedis, agentTimeout);

        // Add to script arguments: agent name, then its score
        agentScorePairs.add(agentType); // Even index: agent name
        agentScorePairs.add(acquireScore); // Odd index: agent score
      }

      // Execute batch acquisition Lua script
      Object result =
          scriptManager.evalshaWithSelfHeal(
              jedis,
              RedisScriptManager.ACQUIRE_AGENTS,
              Arrays.asList(WORKING_SET, WAITING_SET),
              agentScorePairs);

      // PHASE 3: Process batch acquisition results
      if (result instanceof List) {
        List<Object> resultList = (List<Object>) result;
        long successCount = (Long) resultList.get(0);
        List<String> acquiredAgentTypes = (List<String>) resultList.get(1);

        log.debug(
            "Batch acquisition completed: {} successes out of {} attempts",
            successCount,
            candidateAgents.size());

        // Process each candidate agent to see if it was successfully acquired
        for (int i = 0; i < candidateAgents.size(); i++) {
          String agentType = candidateAgents.get(i);

          if (acquiredAgentTypes.contains(agentType)) {
            // SUCCESS: This pod acquired the agent - set up for execution
            AgentWorker worker = candidateWorkers.get(i);

            // Extract the agent's score from agentScorePairs array
            // Array structure: [agent1, score1, agent2, score2, ...]
            // For agent at index i: score is at position (i * 2 + 1)

            // CRITICAL: Validate index and score format to prevent corruption
            // from dynamic account updates during batch acquisition
            String acquireScore = null;
            int scoreIndex = i * 2 + 1;

            if (scoreIndex < agentScorePairs.size()) {
              String scoreCandidate = agentScorePairs.get(scoreIndex);
              // Validate that the score is a numeric string (timestamp in seconds)
              if (scoreCandidate != null && scoreCandidate.matches("^\\d+$")) {
                acquireScore = scoreCandidate;
              } else {
                log.error(
                    "Invalid acquire score detected for agent {} at index {}: '{}' - likely corruption from dynamic account update",
                    agentType,
                    scoreIndex,
                    scoreCandidate);
              }
            } else {
              log.error(
                  "Score index {} out of bounds for agent {} (agentScorePairs.size={}). Dynamic account modification likely occurred during batch acquisition.",
                  scoreIndex,
                  agentType,
                  agentScorePairs.size());
            }

            // Only proceed if we have a valid score
            if (acquireScore != null) {
              worker.acquireScore = acquireScore;
              workersToSubmit.add(worker);
              activeAgents.put(agentType, acquireScore);
              activeAgentMapSize.incrementAndGet();
              agentsAcquired.incrementAndGet();

              log.debug("Batch acquired agent {} with score {}", agentType, acquireScore);
            } else {
              // Could not get valid score - release permit and skip this agent
              if (runningAgents != null) {
                runningAgents.release();
              }
              log.warn(
                  "Skipping agent {} due to invalid/missing acquire score - will retry on next cycle",
                  agentType);
              if (metrics != null) {
                metrics.incrementAcquireValidationFailure("batch_score_corruption");
              }
            }
          } else {
            // FAILURE: Agent lost to another pod in race condition
            // Release the semaphore permit we pre-acquired
            if (runningAgents != null) {
              runningAgents.release();
            }
            log.debug("Agent {} was acquired by another pod", agentType);
          }
        }

        log.info(
            "Batch acquisition completed: {}/{} agents acquired",
            successCount,
            candidateAgents.size());
        return (int) successCount; // Return actual successful acquisitions from Redis
      }

      log.warn("Unexpected batch acquisition result: {}", result);
      // Release all semaphore permits on batch failure
      if (runningAgents != null) {
        for (int i = 0; i < candidateAgents.size(); i++) {
          runningAgents.release();
        }
      }
      return 0;

    } catch (Exception e) {
      log.error(
          "Batch agent acquisition failed, falling back to individual mode: {}", e.getMessage());
      // Release all semaphore permits on batch failure
      if (runningAgents != null) {
        for (int i = 0; i < candidateAgents.size(); i++) {
          runningAgents.release();
        }
      }

      // Fallback to individual acquisition
      return saturatePoolIndividual(
          jedis,
          new HashSet<>(candidateAgents),
          maxToAcquire,
          runningAgents,
          workersToSubmit,
          attemptedThisCycle);
    }
  }

  /**
   * This is the original individual acquisition logic, kept as fallback when batch operations are
   * disabled or fail.
   *
   * @param jedis Redis connection
   * @param readyAgents Set of agent types ready for execution
   * @param maxToAcquire Maximum number of agents to acquire
   * @param runningAgents Semaphore for concurrency control
   * @param workersToSubmit Collection to add successfully acquired workers
   * @return Number of agents successfully acquired
   */
  private int saturatePoolIndividual(
      Jedis jedis,
      Set<String> readyAgents,
      int maxToAcquire,
      Semaphore runningAgents,
      Set<AgentWorker> workersToSubmit,
      java.util.Set<String> attemptedThisCycle) {

    log.debug(
        "Using individual agent acquisition for {} ready agents (max: {})",
        readyAgents.size(),
        maxToAcquire);

    int agentsAcquiredThisCycle = 0;

    for (String agentType : readyAgents) {
      if (attemptedThisCycle != null && attemptedThisCycle.contains(agentType)) {
        continue;
      }
      if (agentsAcquiredThisCycle >= maxToAcquire) {
        log.debug(
            "Reached available slot limit for new agents this cycle ({} acquired out of {} target slots).",
            agentsAcquiredThisCycle,
            maxToAcquire);
        break;
      }

      if (runningAgents != null && !runningAgents.tryAcquire()) {
        log.debug(
            "Instance concurrent agent limit reached (no permits from 'runningAgents' semaphore). Cannot acquire more agents this cycle.");
        break; // Stop trying if semaphore is full
      }

      // Semaphore permit acquired
      // Note: Individual acquisition is less prone to race conditions since it processes one agent
      // at a time
      // The dynamic account plugin race primarily affects batch acquisition where indices can be
      // corrupted
      AgentWorker worker = agents.get(agentType);
      if (worker == null) {
        log.warn(
            "Ready agent {} not found in local agents map, releasing semaphore permit and skipping.",
            agentType);
        if (runningAgents != null) {
          runningAgents.release();
        }
        continue;
      }

      // Sharding/enablement gating at acquisition time (dynamic-safe)
      if (!isAgentEnabled(worker.getAgent())) {
        log.debug(
            "Skipping ready agent {} due to sharding/enablement filter during acquisition",
            agentType);
        if (runningAgents != null) {
          runningAgents.release();
        }
        continue;
      }

      // Try to acquire this agent from Redis
      String agentAcquireScore = tryAcquireAgent(jedis, worker.getAgent());
      if (agentAcquireScore != null) {
        // Successfully acquired agent, prepare for execution
        worker.acquireScore = agentAcquireScore;
        workersToSubmit.add(worker);
        agentsAcquiredThisCycle++;

        // Track active agent
        activeAgents.put(agentType, agentAcquireScore);
        activeAgentMapSize.incrementAndGet();
        agentsAcquired.incrementAndGet(); // Track acquisition statistics

        log.debug("Acquired agent {} with score {}", agentType, agentAcquireScore);
        if (attemptedThisCycle != null) {
          attemptedThisCycle.add(agentType);
        }
      } else {
        // Failed to acquire (another instance got it first)
        if (runningAgents != null) {
          runningAgents.release();
        }
        log.debug("Agent {} was acquired by another instance, releasing permit", agentType);
        if (attemptedThisCycle != null) {
          attemptedThisCycle.add(agentType);
        }
      }
    }

    return agentsAcquiredThisCycle;
  }

  /**
   * Determine if the provided agent type belongs to this shard according to the configured {@link
   * ShardingFilter}. When the agent is not registered locally, a lightweight stub is used to
   * evaluate sharding based on agentType alone (providerName defaults to "unknown").
   *
   * <p>Returns false if the shard ownership cannot be determined to avoid cross-shard deletions in
   * cleanup flows.
   */
  public boolean belongsToThisShard(String agentType) {
    try {
      AgentWorker worker = agents.get(agentType);
      Agent agent = worker != null ? worker.getAgent() : new AgentTypeOnlyStub(agentType);
      return shardingFilter.filter(agent);
    } catch (Exception e) {
      log.debug("Unable to determine shard ownership for {}: {}", agentType, e.getMessage());
      return false;
    }
  }

  /** Minimal Agent implementation for sharding checks when only agentType is available. */
  private static final class AgentTypeOnlyStub implements com.netflix.spinnaker.cats.agent.Agent {
    private final String agentType;

    AgentTypeOnlyStub(String agentType) {
      this.agentType = agentType;
    }

    @Override
    public String getAgentType() {
      return agentType;
    }

    @Override
    public String getProviderName() {
      return "unknown";
    }

    @Override
    public com.netflix.spinnaker.cats.agent.AgentExecution getAgentExecution(
        com.netflix.spinnaker.cats.provider.ProviderRegistry providerRegistry) {
      throw new UnsupportedOperationException("Not supported in shard ownership checks");
    }
  }

  /**
   * Register an agent for scheduling.
   *
   * @param agent The agent to register
   * @param agentExecution The execution wrapper for the agent
   * @param executionInstrumentation Instrumentation for tracking agent execution
   */
  public void registerAgent(
      Agent agent,
      AgentExecution agentExecution,
      ExecutionInstrumentation executionInstrumentation) {
    if (!isAgentEnabled(agent)) {
      log.debug(
          "Agent is not enabled (agent: {}, agentType: {}, pattern: {})",
          agent.getClass().getSimpleName(),
          agent.getAgentType(),
          enabledAgentPattern.pattern());
      return;
    }

    AgentWorker worker = new AgentWorker(agent, agentExecution, executionInstrumentation, this);
    agents.put(agent.getAgentType(), worker);
    agentMapSize.set(agents.size()); // Update statistics

    // Update cached minimum interval based on this agent if enabled
    try {
      if (isAgentEnabled(agent)) {
        long intervalSec = Math.max(0L, intervalProvider.getInterval(agent).getInterval() / 1000L);
        if (intervalSec > 0L) {
          cachedMinEnabledIntervalSec.getAndUpdate(
              prev -> (prev == 0L) ? intervalSec : Math.min(prev, intervalSec));
        }
      }
    } catch (Exception ignore) {
      // Best-effort only
    }

    // Log enhanced registration info with operational details
    AgentIntervalProvider.Interval interval = intervalProvider.getInterval(agent);
    String initialScore = agentScore(agent);

    log.debug(
        "Registered agent {} (interval {}s/timeout {}s) for scheduling [score {}/total agents {}]",
        agent.getAgentType(),
        interval.getInterval() / 1000,
        interval.getTimeout() / 1000,
        initialScore,
        agents.size());

    // Persist the agent into Redis immediately only if scripts are initialized. When scripts are
    // not yet initialized (e.g., during early scheduler bootstrap), defer to repopulation so that
    // initial-registration jitter (when enabled) can be applied and to preserve historical tests.
    if (scriptManager.isInitialized()) {
      try {
        scheduleAgentInRedis(agent, 0L);
      } catch (Exception e) {
        log.warn(
            "Failed to write initial Redis entry for agent {} – will rely on repopulation: {}",
            agent.getAgentType(),
            e.getMessage());
      }
    } else {
      log.debug(
          "Deferring initial Redis write for agent {} until repopulation (scripts not initialized)",
          agent.getAgentType());
    }
  }

  /**
   * Unregisters an agent from the scheduler. This removes the agent from local tracking and
   * prevents it from being scheduled for execution.
   *
   * @param agent The agent to unregister
   */
  public void unregisterAgent(Agent agent) {
    String agentType = agent.getAgentType();
    agents.remove(agentType);

    // Remove any local failure backoff state for this agent (important for dynamically removed
    // agents)
    failureStreaks.remove(agentType);

    // Clean up active tracking
    if (activeAgents.remove(agentType) != null) {
      activeAgentMapSize.decrementAndGet();
    }

    log.debug("Unregistered agent {} from scheduling", agentType);

    // Recompute cached minimum interval conservatively when an agent is removed
    try {
      long minSec =
          agents.values().stream()
              .map(AgentWorker::getAgent)
              .filter(this::isAgentEnabled)
              .mapToLong(a -> intervalProvider.getInterval(a).getInterval() / 1000L)
              .filter(v -> v > 0L)
              .min()
              .orElse(0L);
      cachedMinEnabledIntervalSec.set(minSec);
    } catch (Exception ignore) {
      // Best-effort only
    }
  }

  /**
   * Removes an agent from active tracking. Called when an agent execution completes or is
   * interrupted.
   *
   * @param agentType The type identifier of the agent to remove
   */
  public void removeActiveAgent(String agentType) {
    // CRITICAL: Capture removed value to ensure atomic consistency between map and counter
    String removedScore = activeAgents.remove(agentType);
    if (removedScore != null) {
      // Only decrement counter if we actually removed something
      activeAgentMapSize.decrementAndGet();
      // Remove future tracking - this cleanup is non-critical if it fails
      activeAgentsFutures.remove(agentType);

      // CRITICAL: Remove from Redis sets - behavior depends on shutdown state
      try (Jedis jedis = jedisPool.getResource()) {
        if (shuttingDown.get()) {
          // During shutdown: Only remove from working to preserve waiting entries
          // Agents in waiting were put there by graceful shutdown for restart
          jedis.zrem(WORKING_SET, agentType);
          log.debug(
              "Removed agent {} from active tracking and working (preserving waiting during shutdown)",
              agentType);
        } else {
          // Normal operation: Remove from both sets
          scriptManager.evalshaWithSelfHeal(
              jedis,
              RedisScriptManager.REMOVE_AGENT,
              java.util.Arrays.asList(WORKING_SET, WAITING_SET),
              java.util.Collections.singletonList(agentType));
          log.debug("Removed agent {} from active tracking and Redis sets", agentType);
        }
      } catch (Exception e) {
        log.error("Failed to remove agent {} from Redis", agentType, e);
      }
    }
  }

  /**
   * Get the number of currently active agents.
   *
   * @return number of active agents
   */
  public int getActiveAgentCount() {
    return activeAgents.size();
  }

  /**
   * Get the total number of registered agents (for stats).
   *
   * @return number of registered agents
   */
  public int getRegisteredAgentCount() {
    return agents.size();
  }

  /**
   * Get a snapshot of active agent futures for graceful shutdown. Returns a copy to avoid
   * concurrent modification issues.
   *
   * @return Map of agent type to Future for active agents
   */
  public Map<String, Future<?>> getActiveAgentsFuturesSnapshot() {
    Map<String, Future<?>> snapshot = new HashMap<>();
    for (Map.Entry<String, Future<?>> entry : activeAgentsFutures.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue());
    }
    return snapshot;
  }

  /**
   * Get a registered agent by type.
   *
   * @param agentType The agent type to retrieve
   * @return The registered Agent, or null if not found
   */
  public Agent getRegisteredAgent(String agentType) {
    AgentWorker worker = agents.get(agentType);
    return worker != null ? worker.getAgent() : null;
  }

  /**
   * Get the active agents map for zombie cleanup.
   *
   * @return map of active agents (agentType -> completionDeadline)
   */
  public Map<String, String> getActiveAgentsMap() {
    return activeAgents;
  }

  /**
   * Get active agents futures for cleanup services.
   *
   * @return Map of active agent futures
   */
  public Map<String, Future<?>> getActiveAgentsFutures() {
    return activeAgentsFutures;
  }

  /**
   * Get the current size of the agent futures map.
   *
   * @return Current number of agent futures being tracked
   */
  public int getFuturesMapSize() {
    return activeAgentsFutures.size();
  }

  public long getOldestOverdueSeconds() {
    return lastOldestOverdueSeconds.get();
  }

  /** Snapshot of last computed ready count. */
  public long getReadyCountSnapshot() {
    return lastReadyCount.get();
  }

  /** Snapshot of last computed capacity per cycle. */
  public long getCapacityPerCycleSnapshot() {
    return lastCapacityPerCycle.get();
  }

  /** Size of the completion queue. */
  public int getCompletionQueueSize() {
    return completionQueue.size();
  }

  /** Current Redis server-client offset in milliseconds (positive => server ahead). */
  public long getServerClientOffsetMs() {
    return serverClientOffset.get();
  }

  /**
   * Returns current wall-clock time adjusted by the Redis server-client offset.
   *
   * <p>Used by cleanup services to avoid additional Redis TIME calls and to keep time sourcing
   * consistent across acquisition and cleanup paths.
   */
  public long nowMsWithOffset() {
    return System.currentTimeMillis() + serverClientOffset.get();
  }

  public boolean isDegraded() {
    return lastDegraded.get();
  }

  public String getDegradedReason() {
    return lastDegradedReason.get();
  }

  /**
   * Get circuit breaker status for monitoring.
   *
   * @return Map containing status of each circuit breaker
   */
  public Map<String, String> getCircuitBreakerStatus() {
    Map<String, String> status = new HashMap<>();
    status.put("acquisition", acquisitionCircuitBreaker.getStatus());
    status.put("redis", redisCircuitBreaker.getStatus());
    return status;
  }

  /** Reset circuit breakers (for recovery/testing). */
  public void resetCircuitBreakers() {
    acquisitionCircuitBreaker.reset();
    redisCircuitBreaker.reset();
    log.info("Circuit breakers manually reset");
  }

  /**
   * Retrieves an agent by its type identifier from the registered agents map. Used during graceful
   * shutdown to properly re-queue active agents.
   *
   * @param agentType The type identifier of the agent to retrieve
   * @return The agent if found, null otherwise
   */
  public Agent getAgentByType(String agentType) {
    AgentWorker worker = agents.get(agentType);
    return worker != null ? worker.getAgent() : null;
  }

  /**
   * Conditionally re-queue an agent during graceful shutdown if it's still in working. This
   * approach respects agents that completed during shutdown and avoids race conditions. Uses
   * ownership verification to ensure we only move agents this instance actually owns.
   */
  public void forceRequeueAgentForShutdown(Agent agent, String expectedScore) {
    String agentType = agent.getAgentType();

    try (Jedis jedis = jedisPool.getResource()) {
      // Calculate cadence-based or jittered execution score for restart (seconds since epoch)
      long offsetMs = computeShutdownRescheduleOffsetMs(agent, expectedScore);
      String nextScore = score(jedis, offsetMs);

      log.debug(
          "Shutdown re-queue attempt: {} expected_score={} next_score={}",
          agentType,
          expectedScore,
          nextScore);
      // Check current state in Redis before attempting swap
      Double currentWorkzScore = jedis.zscore(WORKING_SET, agentType);
      Double currentWaitzScore = jedis.zscore(WAITING_SET, agentType);
      log.debug(
          "Redis state before swap: {} {}={} {}={}",
          agentType,
          WORKING_SET,
          currentWorkzScore,
          WAITING_SET,
          currentWaitzScore);

      // Use MOVE_AGENTS_CONDITIONAL - only moves if agent is in working with expected score
      Object result =
          scriptManager.evalshaWithSelfHeal(
              jedis,
              RedisScriptManager.MOVE_AGENTS_CONDITIONAL,
              java.util.Arrays.asList(WORKING_SET, WAITING_SET),
              java.util.Arrays.asList(agentType, expectedScore, nextScore));

      // Check final state
      Double finalWorkzScore = jedis.zscore(WORKING_SET, agentType);
      Double finalWaitzScore = jedis.zscore(WAITING_SET, agentType);
      log.debug(
          "Redis state after swap: {} {}={} {}={} result={}",
          agentType,
          WORKING_SET,
          finalWorkzScore,
          WAITING_SET,
          finalWaitzScore,
          result);

      if (result != null && "swapped".equals(result)) {
        log.info("Successfully re-queued agent {} for shutdown restart", agentType);
      } else {
        log.warn(
            "Agent {} not re-queued (already completed or moved during shutdown) (expected={}, current={}, result={})",
            agentType,
            expectedScore,
            currentWorkzScore,
            result);
      }

    } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
      log.warn(
          "Redis connection error while re-queuing {} during shutdown: {}",
          agentType,
          e.getMessage());
    } catch (Exception e) {
      log.error("Failed to conditionally re-queue agent {} during shutdown", agentType, e);
    }
  }

  /**
   * Get advanced scheduling statistics.
   *
   * @return AgentAcquisitionStats with detailed metrics
   */
  public AgentAcquisitionStats getAdvancedStats() {
    return new AgentAcquisitionStats(
        agentMapSize.get(),
        activeAgentMapSize.get(),
        agentsAcquired.get(),
        agentsExecuted.get(),
        agentsFailed.get(),
        activeAgentsFutures.size());
  }

  /** Reset execution statistics counters. Useful for periodic reporting. */
  public void resetExecutionStats() {
    agentsAcquired.set(0);
    agentsExecuted.set(0);
    agentsFailed.set(0);
  }

  private boolean isAgentEnabled(Agent agent) {
    String agentType = agent.getAgentType();

    // Check if this agent matches the sharding filter
    if (!shardingFilter.filter(agent)) {
      return false;
    }

    // Check enabled pattern
    if (!enabledAgentPattern.matcher(agentType).matches()) {
      return false;
    }

    // Check if agent is disabled (pattern or list)
    if (isAgentDisabled(agentType)) {
      log.debug("Agent {} is disabled", agentType);
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
    return disabledAgentPattern != null && disabledAgentPattern.matcher(agentType).matches();
  }

  /**
   * Repopulate Redis with known agents from the local agents map. Performs differential sync to
   * avoid unnecessary Redis operations.
   *
   * @param jedis Jedis connection to Redis
   */
  private void repopulateRedisAgents(Jedis jedis) {
    // Take a snapshot to prevent concurrent modification during repopulation
    Map<String, AgentWorker> agentsSnapshot = new ConcurrentHashMap<>(agents);
    int totalAgents = agentsSnapshot.size();
    log.debug("Repopulation check for {} known agents", totalAgents);

    if (totalAgents == 0) {
      log.debug("No agents to repopulate");
      return;
    }

    try {
      // Get current Redis state from both sets
      Set<String> localAgents = agentsSnapshot.keySet();
      Set<String> redisAgents = getCurrentRedisAgents(jedis, localAgents);

      // Calculate what needs to be added (missing agents from this instance)
      Set<String> toAdd =
          localAgents.stream()
              .filter(agent -> !redisAgents.contains(agent))
              .collect(Collectors.toSet());

      // True NOOP if nothing to add
      if (toAdd.isEmpty()) {
        log.debug("Repopulation: Redis state is consistent, no missing agents");
        return;
      }

      log.debug("Repopulation: +{} missing agents to add", toAdd.size());

      // Add missing agents from this instance
      addMissingAgents(jedis, toAdd);

      // Update cached minimum interval after changes in the registered set
      try {
        long minSec =
            agentsSnapshot.values().stream()
                .map(AgentWorker::getAgent)
                .filter(this::isAgentEnabled)
                .mapToLong(a -> intervalProvider.getInterval(a).getInterval() / 1000L)
                .filter(v -> v > 0L)
                .min()
                .orElse(0L);
        cachedMinEnabledIntervalSec.set(minSec);
      } catch (Exception ignore) {
        // Best-effort only
      }

    } catch (Exception e) {
      log.warn("Repopulation failed, falling back to full sync: {}", e.getMessage());
      repopulateRedisAgentsFallback(jedis);
    }
  }

  /**
   * Gets all agent names currently in both Redis sets (working and waiting) using pipelining. This
   * method retrieves all agents from both Redis sorted sets in a single operation.
   *
   * @param jedis Redis connection to use
   * @param localAgentNames Set of local agent names to check in Redis
   * @return Set containing all agent names from both Redis sets
   */
  private Set<String> getCurrentRedisAgents(Jedis jedis, Set<String> localAgentNames) {
    // Avoid full-set scans: check presence for locally registered agents only
    List<String> agentNames = new ArrayList<>(localAgentNames);
    if (agentNames.isEmpty()) {
      return java.util.Collections.emptySet();
    }

    try {
      @SuppressWarnings("unchecked")
      List<String> results =
          (List<String>)
              scriptManager.evalshaWithSelfHeal(
                  jedis,
                  RedisScriptManager.SCORE_AGENTS,
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  agentNames);

      // Results format: [agent, workScore|'null', waitScore|'null', ...]
      Set<String> allAgents = new HashSet<>();
      for (int i = 0; i < results.size(); i += 3) {
        String agent = results.get(i);
        String workScore = results.get(i + 1);
        String waitScore = results.get(i + 2);
        if (!"null".equals(workScore) || !"null".equals(waitScore)) {
          allAgents.add(agent);
        }
      }
      return allAgents;
    } catch (Exception e) {
      // Fallback: full-set scan if script fails
      log.warn(
          "Repopulation presence check failed, falling back to full-set scan: {}", e.getMessage());
      Pipeline pipeline = jedis.pipelined();
      Response<Set<String>> waitingAgents = pipeline.zrange(WAITING_SET, 0, -1);
      Response<Set<String>> workingAgents = pipeline.zrange(WORKING_SET, 0, -1);
      pipeline.sync();
      Set<String> allAgents = new HashSet<>(waitingAgents.get());
      allAgents.addAll(workingAgents.get());
      return allAgents;
    }
  }

  /**
   * Adds missing agents to Redis with appropriate scores. Delegates to either batch or individual
   * processing based on configuration. This is a critical component of the differential update
   * system that only adds agents not already present in Redis.
   *
   * @param jedis Redis connection to use
   * @param agentsToAdd Set of agent types to add to Redis
   */
  private void addMissingAgents(Jedis jedis, Set<String> agentsToAdd) {
    if (schedulerProperties.getBatchOperations().isEnabled() && agentsToAdd.size() > 1) {
      addMissingAgentsBatch(jedis, agentsToAdd);
    } else {
      addMissingAgentsIndividual(jedis, agentsToAdd);
    }
  }

  private void addMissingAgentsBatch(Jedis jedis, Set<String> agentsToAdd) {
    List<String> batchArgs = new ArrayList<>();
    for (String agentType : agentsToAdd) {
      AgentWorker worker = agents.get(agentType);
      if (worker != null) {
        batchArgs.add(agentType);
        long jitterSec = computeInitialRegistrationJitterSeconds();
        batchArgs.add(score(jedis, jitterSec * 1000L));
      }
    }

    if (!batchArgs.isEmpty()) {
      try {
        @SuppressWarnings("unchecked")
        List<Object> result =
            (List<Object>)
                scriptManager.evalshaWithSelfHeal(
                    jedis,
                    RedisScriptManager.ADD_AGENTS,
                    Arrays.asList(WORKING_SET, WAITING_SET),
                    batchArgs);
        int added = result.size() >= 1 ? ((Long) result.get(0)).intValue() : 0;
        log.debug("Batch added {} missing agents to Redis", added);
        if (metrics != null && added > 0) {
          metrics.incrementRepopulateAdded(added);
        }
      } catch (Exception e) {
        log.warn("Batch add failed, using individual mode: {}", e.getMessage());
        addMissingAgentsIndividual(jedis, agentsToAdd);
      }
    }
  }

  /**
   * Add missing agents to Redis one by one.
   *
   * @param jedis Jedis connection to Redis
   * @param agentsToAdd Set of agent types to add
   */
  private void addMissingAgentsIndividual(Jedis jedis, Set<String> agentsToAdd) {
    int added = 0;
    for (String agentType : agentsToAdd) {
      AgentWorker worker = agents.get(agentType);
      if (worker != null) {
        long jitterSec = computeInitialRegistrationJitterSeconds();
        String newScore = score(jedis, jitterSec * 1000L);
        try {
          Object result =
              scriptManager.evalshaWithSelfHeal(
                  jedis,
                  RedisScriptManager.ADD_AGENT,
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  Arrays.asList(agentType, newScore));
          if (result != null && ((Long) result).intValue() == 1) {
            added++;
          }
        } catch (Exception e) {
          log.warn("Failed to add missing agent {}: {}", agentType, e.getMessage());
        }
      }
    }
    log.debug("Individual added {} missing agents to Redis", added);
    if (metrics != null && added > 0) {
      metrics.incrementRepopulateAdded(added);
    }
  }

  /**
   * Compute a non-negative jitter in seconds for initial registration of new agents. Returns 0 when
   * jitter is disabled.
   */
  private long computeInitialRegistrationJitterSeconds() {
    int window = schedulerProperties.getJitter().getInitialRegistrationSeconds();
    if (window <= 0) {
      return 0L;
    }
    // Use [1, window] inclusive to avoid zero-second placements that may appear slightly in the
    // past due to server/client second-boundary races when observed immediately after insert.
    return java.util.concurrent.ThreadLocalRandom.current().nextInt(1, window + 1);
  }

  /**
   * Fallback to full repopulation logic if smart sync fails.
   *
   * @param jedis Jedis connection to Redis
   */
  private void repopulateRedisAgentsFallback(Jedis jedis) {
    int totalAgents = agents.size();
    log.debug("Fallback: Full repopulation of {} agents", totalAgents);

    try {
      // Use batch scoring if enabled and we have multiple agents
      Map<String, String> agentScores;
      if (schedulerProperties.getBatchOperations().isEnabled() && totalAgents > 1) {
        // Batch scoring for multiple agents
        agentScores = batchAgentScore(jedis, agents.values());
        log.debug("Batch scored {} agents", agentScores.size());
      } else {
        // Fallback to individual scoring
        agentScores = new HashMap<>();
        for (AgentWorker worker : agents.values()) {
          agentScores.put(worker.getAgent().getAgentType(), agentScore(worker.getAgent()));
        }
        log.debug("Individual scored {} agents", agentScores.size());
      }

      // Batch add agents to Redis in chunks to avoid memory issues
      int batchSize = schedulerProperties.getBatchOperations().getBatchSize();
      int processed = 0;
      int totalAdded = 0;

      List<String> batchArgs = new ArrayList<>();
      for (Map.Entry<String, String> entry : agentScores.entrySet()) {
        batchArgs.add(entry.getKey()); // agent name
        batchArgs.add(entry.getValue()); // score
        processed++;

        // Process batch when we reach batch size or end of agents
        if (batchArgs.size() >= batchSize * 2 || processed == agentScores.size()) {
          try {
            @SuppressWarnings("unchecked")
            List<Object> result =
                (List<Object>)
                    scriptManager.evalshaWithSelfHeal(
                        jedis,
                        RedisScriptManager.ADD_AGENTS,
                        Arrays.asList(WORKING_SET, WAITING_SET),
                        batchArgs);

            if (result.size() >= 1) {
              totalAdded += ((Long) result.get(0)).intValue();
            }

            log.debug(
                "Batch added {} agents to Redis (batch {} of {})",
                batchArgs.size() / 2,
                (processed + batchSize - 1) / batchSize,
                (totalAgents + batchSize - 1) / batchSize);

          } catch (Exception e) {
            log.warn(
                "Batch repopulation failed for {} agents, using individual mode: {}",
                batchArgs.size() / 2,
                e.getMessage());

            // Fallback: Use pipeline with individual ADD_AGENT script
            Pipeline pipeline = jedis.pipelined();
            for (int i = 0; i < batchArgs.size(); i += 2) {
              pipeline.evalsha(
                  scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT),
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  Arrays.asList(batchArgs.get(i), batchArgs.get(i + 1)));
            }
            List<Object> pipelineResults = pipeline.syncAndReturnAll();

            // Count successful additions (ADD_AGENT returns 1 for success, 0 for already exists)
            for (Object result : pipelineResults) {
              if (result != null && ((Long) result).intValue() == 1) {
                totalAdded++;
              }
            }
          }

          batchArgs.clear();
        }
      }

      log.debug(
          "Repopulated Redis with {} agents ({} actually added/updated)", totalAgents, totalAdded);
      if (metrics != null && totalAdded > 0) {
        metrics.incrementRepopulateAdded(totalAdded);
      }

    } catch (Exception e) {
      log.error(
          "Batch repopulation failed completely, falling back to individual operations: {}",
          e.getMessage());

      // Complete fallback to pipeline with individual ADD_AGENT scripts
      Pipeline pipeline = jedis.pipelined();
      for (AgentWorker worker : agents.values()) {
        String agentType = worker.getAgent().getAgentType();
        String nextScore = agentScore(worker.getAgent());

        // Use individual ADD_AGENT script for reliability
        pipeline.evalsha(
            scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT),
            Arrays.asList(WORKING_SET, WAITING_SET),
            Arrays.asList(agentType, nextScore));
      }
      pipeline.sync(); // Execute all operations in a single network round trip
      log.debug("Pipeline fallback repopulated Redis with {} agents", totalAgents);
    }
  }

  /**
   * Process queued agent completions using the shared Redis connection. Processes agent completions
   * that were queued during previous execution cycles.
   */
  private void processQueuedCompletions(Jedis jedis) {
    List<AgentCompletion> completions = drainCompletionQueue();
    if (completions.isEmpty()) {
      return;
    }

    log.debug("Processing {} queued agent completions", completions.size());

    // Group completions by scheduling offset for batch efficiency
    Map<Long, List<AgentCompletion>> groupedCompletions =
        completions.stream().collect(Collectors.groupingBy(this::getSchedulingOffset));

    int totalProcessed = 0;
    // Process each group with shared connection
    for (Map.Entry<Long, List<AgentCompletion>> entry : groupedCompletions.entrySet()) {
      long offset = entry.getKey();
      List<AgentCompletion> group = entry.getValue();

      if (schedulerProperties.getBatchOperations().isEnabled() && group.size() > 1) {
        totalProcessed += batchScheduleCompletions(jedis, group, offset);
      } else {
        totalProcessed += individualScheduleCompletions(jedis, group, offset);
      }
    }

    log.debug("Processed {} agent completions with shared connection", totalProcessed);
  }

  /**
   * Drain the completion queue in a thread-safe manner. Extracts all agent completions from the
   * queue for processing.
   */
  private List<AgentCompletion> drainCompletionQueue() {
    List<AgentCompletion> completions = new ArrayList<>();
    int queueSize = completionQueue.size();
    log.debug("Draining completion queue, current size: {}", queueSize);

    AgentCompletion completion;
    while ((completion = completionQueue.poll()) != null) {
      log.debug(
          "Drained completion for agent: {} (success={})",
          completion.agent.getAgentType(),
          completion.success);
      completions.add(completion);
    }

    log.debug("Drained {} completions from queue", completions.size());
    return completions;
  }

  /**
   * Calculate scheduling offset based on completion success and shutdown state. Maintains the same
   * logic as the original conditionalReleaseAgent method.
   */
  /**
   * Compute the scheduling offset for a completed agent run.
   *
   * <p>Success: attempts to preserve cadence relative to the original acquire time; falls back to
   * scheduling after the agent's interval.
   *
   * <p>Failure: delegates to {@link #computeFailureOffsetAndUpdateStreak(AgentCompletion)} which
   * applies class-based backoff when enabled; otherwise uses the agent's {@code errorInterval}.
   */
  private long getSchedulingOffset(AgentCompletion completion) {
    if (!completion.success) {
      return computeFailureOffsetAndUpdateStreak(completion);
    }

    // Compute next schedule based on original acquire score when possible to preserve cadence.
    try {
      AgentIntervalProvider.Interval interval = intervalProvider.getInterval(completion.agent);
      long intervalMs = interval.getInterval();

      // Reset failure streak on success
      failureStreaks.remove(completion.agent.getAgentType());

      if (completion.acquireScore != null) {
        try {
          long acquireScoreSeconds = Long.parseLong(completion.acquireScore);
          long agentTimeoutMs = interval.getTimeout();
          long originalAcquireMs = (acquireScoreSeconds * 1000L) - agentTimeoutMs;
          long desiredNextRunMs = originalAcquireMs + intervalMs;
          long nowMs = System.currentTimeMillis() + serverClientOffset.get();
          long offsetMs = desiredNextRunMs - nowMs;
          return Math.max(offsetMs, 0L);
        } catch (NumberFormatException ignored) {
          // Fall through to simple interval scheduling when parsing fails
        }
      }

      // Fallback – schedule for intervalMs from now
      return intervalMs;
    } catch (Exception e) {
      log.warn(
          "Failed to calculate scheduling offset for agent {}, using default interval",
          completion.agent.getAgentType(),
          e);
      try {
        return intervalProvider.getInterval(completion.agent).getInterval();
      } catch (Exception ignored) {
        return 0L;
      }
    }
  }

  /**
   * Compute the delay for a failed run and update the local failure streak.
   *
   * <p>Behavior: - If failure-aware backoff is disabled, returns {@code errorInterval}. -
   * PERMANENT_FORBIDDEN → fixed long backoff (configured). - THROTTLED → exponential backoff (base
   * × multiplier^(streak-1), capped). - TRANSIENT/SERVER_ERROR → immediate retry for the first N
   * attempts (config), else {@code errorInterval}. - UNKNOWN → {@code errorInterval}.
   *
   * <p>Applies jitter to non-zero delays when configured.
   */
  private long computeFailureOffsetAndUpdateStreak(AgentCompletion completion) {
    final String agentType = completion.agent.getAgentType();
    final FailureBackoffProperties backoffCfg = schedulerProperties.getFailureBackoff();

    FailureClass fclass =
        completion.failureClass != null ? completion.failureClass : FailureClass.UNKNOWN;

    // Increment streak locally
    int streak = failureStreaks.merge(agentType, 1, Integer::sum);

    long offsetMs = 0L;
    try {
      AgentIntervalProvider.Interval interval = intervalProvider.getInterval(completion.agent);

      if (!backoffCfg.isEnabled()) {
        // Minimal safe behavior: use errorInterval for any failure
        offsetMs = interval.getErrorInterval();
      } else {
        switch (fclass) {
          case PERMANENT_FORBIDDEN:
            offsetMs = backoffCfg.getPermanentForbiddenBackoffMs();
            break;
          case THROTTLED:
            offsetMs = computeExponentialBackoffMs(backoffCfg, streak);
            break;
          case SERVER_ERROR:
          case TRANSIENT:
            if (streak <= backoffCfg.getMaxImmediateRetries()) {
              offsetMs = 0L;
            } else {
              offsetMs = interval.getErrorInterval();
            }
            break;
          case UNKNOWN:
          default:
            offsetMs = interval.getErrorInterval();
        }
      }

      // Apply jitter if configured and offset > 0
      if (offsetMs > 0L) {
        offsetMs = applyJitter(offsetMs, schedulerProperties.getJitter().getFailureBackoffRatio());
        // Enforce whole-second scheduling for failure backoff to avoid undershooting by truncation
        offsetMs = ((offsetMs + 999L) / 1000L) * 1000L; // ceil to nearest second
      }
    } catch (Exception e) {
      log.warn(
          "Failed to compute failure backoff for agent {} (class: {}, streak: {}), defaulting to 0",
          agentType,
          fclass,
          streak,
          e);
      offsetMs = 0L;
    }

    if (completion.throwableClassName != null) {
      log.warn(
          "Agent {} failed with {} -> applying backoff {} ms (class={}, streak={})",
          agentType,
          completion.throwableClassName,
          offsetMs,
          fclass,
          streak);
    } else {
      log.warn(
          "Agent {} failed -> applying backoff {} ms (class={}, streak={})",
          agentType,
          offsetMs,
          fclass,
          streak);
    }

    return Math.max(0L, offsetMs);
  }

  /**
   * Compute exponential backoff for throttled failures.
   *
   * @param backoffCfg throttled policy (base, multiplier, cap)
   * @param streak current failure streak (1-based)
   * @return backoff in milliseconds (capped)
   */
  private long computeExponentialBackoffMs(FailureBackoffProperties backoffCfg, int streak) {
    long base = backoffCfg.getThrottled().getBaseMs();
    double multiplier = backoffCfg.getThrottled().getMultiplier();
    long cap = backoffCfg.getThrottled().getCapMs();
    double factor = Math.pow(multiplier, Math.max(0, streak - 1));
    long raw = (long) Math.round(base * factor);
    return Math.min(raw, cap);
  }

  /**
   * Apply symmetric jitter in the range [-ratio, +ratio] to a positive base delay.
   *
   * @param baseMs base delay in milliseconds
   * @param jitterRatio ratio in [0.0, 1.0]
   * @return jittered delay (>= 0), coerced to at least 1ms if base > 0
   */
  private long applyJitter(long baseMs, double jitterRatio) {
    if (jitterRatio <= 0.0) {
      return baseMs;
    }
    double r = Math.max(0.0, Math.min(1.0, jitterRatio));
    java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
    double delta = (rnd.nextDouble() * 2.0 * r) - r; // [-r, +r]
    double jittered = baseMs * (1.0 + delta);
    if (jittered < 0.0) {
      return 0L;
    }
    long result = (long) Math.round(jittered);
    return result == 0L ? 1L : result;
  }

  /**
   * Fast backoff computation for fallback paths (queueing failure). Uses a conservative mapping
   * without streaks and without jitter.
   */
  private long computeFailureOffsetFast(Agent agent, FailureClass failureClass) {
    try {
      AgentIntervalProvider.Interval interval = intervalProvider.getInterval(agent);
      FailureBackoffProperties backoffCfg = schedulerProperties.getFailureBackoff();
      if (!backoffCfg.isEnabled()) {
        return interval.getErrorInterval();
      }
      switch (failureClass != null ? failureClass : FailureClass.UNKNOWN) {
        case PERMANENT_FORBIDDEN:
          return backoffCfg.getPermanentForbiddenBackoffMs();
        case THROTTLED:
          return backoffCfg.getThrottled().getBaseMs();
        case SERVER_ERROR:
        case TRANSIENT:
          return interval.getErrorInterval();
        case UNKNOWN:
        default:
          return interval.getErrorInterval();
      }
    } catch (Exception e) {
      return 0L;
    }
  }

  /**
   * Batch schedule multiple completions with the same offset using batch Redis operations. This
   * method is called by processQueuedCompletions after grouping completions by their scheduling
   * offset. It uses the ADD_AGENTS Lua script for efficient multi-agent scheduling.
   *
   * @param jedis Redis connection to use for operations
   * @param completions List of agent completions with the same offset to schedule
   * @param offset Time offset in milliseconds for agent scheduling
   * @return Number of agents successfully scheduled
   */
  private int batchScheduleCompletions(
      Jedis jedis, List<AgentCompletion> completions, long offset) {
    try {
      List<String> batchArgs = new ArrayList<>();
      for (AgentCompletion completion : completions) {
        batchArgs.add(completion.agent.getAgentType());
        batchArgs.add(score(jedis, offset));
      }

      @SuppressWarnings("unchecked")
      List<Object> result =
          (List<Object>)
              scriptManager.evalshaWithSelfHeal(
                  jedis,
                  RedisScriptManager.ADD_AGENTS,
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  batchArgs);

      int scheduled = result.size() >= 1 ? ((Long) result.get(0)).intValue() : 0;
      log.debug("Batch scheduled {} completions with offset {}ms", scheduled, offset);
      return scheduled;

    } catch (Exception e) {
      log.warn("Batch completion scheduling failed, using individual mode: {}", e.getMessage());
      return individualScheduleCompletions(jedis, completions, offset);
    }
  }

  /**
   * Schedule completions individually for agents with the same time offset. Used as a fallback when
   * batch operations fail or when processing small groups of agents. Schedules each agent
   * separately using the ADD_AGENT Lua script.
   *
   * @param jedis Redis connection to use for operations
   * @param completions List of agent completions with the same offset to schedule
   * @param offset Time offset in milliseconds for agent scheduling
   * @return Number of agents successfully scheduled
   */
  private int individualScheduleCompletions(
      Jedis jedis, List<AgentCompletion> completions, long offset) {
    int scheduled = 0;
    String offsetScore = score(jedis, offset); // Calculate once for all agents with same offset

    for (AgentCompletion completion : completions) {
      try {
        Object result =
            scriptManager.evalshaWithSelfHeal(
                jedis,
                RedisScriptManager.ADD_AGENT,
                Arrays.asList(WORKING_SET, WAITING_SET),
                Arrays.asList(completion.agent.getAgentType(), offsetScore));

        if (result != null && ((Long) result).intValue() == 1) {
          scheduled++;
        }

        log.debug(
            "Scheduled completion for agent {} with offset {}ms",
            completion.agent.getAgentType(),
            offset);

      } catch (Exception e) {
        log.warn(
            "Failed to schedule completion for agent {}: {}",
            completion.agent.getAgentType(),
            e.getMessage());
      }
    }

    return scheduled;
  }

  /**
   * Attempt to acquire an agent for execution.
   *
   * @param jedis Jedis connection to Redis
   * @param agent The agent to acquire
   * @return The acquire score if successful, null otherwise
   */
  private String tryAcquireAgent(Jedis jedis, Agent agent) {
    try {
      String agentType = agent.getAgentType();
      // Generate completion deadline: current_time + agent_timeout
      long agentTimeout = intervalProvider.getInterval(agent).getTimeout();
      String acquireScore = score(jedis, agentTimeout);

      // Atomically try to move agent from waiting → working using Lua script
      // Script ensures only one instance can successfully acquire each agent
      // Args: [WORKING_SET, WAITING_SET, agentType, acquireScore]
      Object result =
          scriptManager.evalshaWithSelfHeal(
              jedis,
              RedisScriptManager.MOVE_AGENTS,
              Arrays.asList(WORKING_SET, WAITING_SET), // Redis keys
              Arrays.asList(agentType, acquireScore)); // Agent name and completion deadline

      // MOVE_AGENTS script returns the score on success, nil on failure
      if (result != null) {
        // Handle different return types from Redis/Jedis
        String scoreStr;
        if (result instanceof String) {
          scoreStr = (String) result;
        } else if (result instanceof Long) {
          scoreStr = String.valueOf(result);
        } else if (result instanceof byte[]) {
          scoreStr = new String((byte[]) result, java.nio.charset.StandardCharsets.UTF_8);
        } else {
          log.warn(
              "Unexpected return type from MOVE_AGENTS for agent {}: {}",
              agentType,
              result.getClass().getName());
          if (metrics != null) {
            metrics.incrementAcquireValidationFailure("unexpected_type");
          }
          return null;
        }

        // Validate that the score is numeric (should be Unix timestamp in seconds)
        // This guards against Redis type coercion surprises or external mutations
        if (scoreStr == null || scoreStr.isEmpty()) {
          log.warn("Empty acquire score from MOVE_AGENTS for agent {}", agentType);
          if (metrics != null) {
            metrics.incrementAcquireValidationFailure("empty_score");
          }
          return null;
        }

        boolean numeric = true;
        for (int i = 0; i < scoreStr.length(); i++) {
          char ch = scoreStr.charAt(i);
          if (ch < '0' || ch > '9') {
            numeric = false;
            break;
          }
        }

        if (!numeric) {
          log.warn(
              "Non-numeric acquire score from MOVE_AGENTS for agent {}: '{}' (type={})",
              agentType,
              scoreStr,
              result.getClass().getSimpleName());
          if (metrics != null) {
            metrics.incrementAcquireValidationFailure("non_numeric_score");
          }
          return null;
        }

        return scoreStr; // Return the validated acquire score
      }
      return null; // Agent was acquired by another instance
    } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
      log.warn(
          "Redis connection error while acquiring {}: {}", agent.getAgentType(), e.getMessage());
      return null;
    } catch (Exception e) {
      log.warn("Failed to acquire agent {}", agent.getAgentType(), e);
      return null;
    }
  }

  /**
   * Get the current score of an agent in the working or waiting set.
   *
   * <p>Behavior:
   *
   * <ul>
   *   <li>Working agents: Calculate NEXT execution time (current + interval)
   *   <li>Waiting agents: Keep existing score (regardless of overdue status)
   *   <li>New agents (not in Redis): Execute immediately (score = 0)
   * </ul>
   *
   * @param agent The agent to check
   * @return The current score of the agent, or "unknown" if Redis is unavailable
   */
  private String agentScore(Agent agent) {
    try (Jedis jedis = jedisPool.getResource()) {
      Pipeline pipeline = jedis.pipelined();

      // Queue both score lookups in a single pipeline
      Response<Double> workingScore = pipeline.zscore(WORKING_SET, agent.getAgentType());
      Response<Double> waitingScore = pipeline.zscore(WAITING_SET, agent.getAgentType());

      pipeline.sync();

      // If agent is currently working, calculate next execution from now
      if (workingScore.get() != null) {
        String result = score(jedis, intervalProvider.getInterval(agent).getInterval());
        log.debug("Agent {} working - next execution scheduled: {}", agent.getAgentType(), result);
        return result;
      }

      // If agent is waiting, keep existing score regardless of overdue status
      // Overdue agents will be naturally picked up by saturatePool() since their score <=
      // currentTime
      if (waitingScore.get() != null) {
        // All Redis scores are stored as seconds since epoch for consistent priority scheduling
        long waitingTimeSeconds = waitingScore.get().longValue();
        log.debug(
            "Agent {} waiting - keeping existing score: {} (preserves priority ordering)",
            agent.getAgentType(),
            waitingTimeSeconds);
        return String.valueOf(waitingTimeSeconds);
      }

      // Only NEW agents (not in Redis) get immediate execution priority
      String result = score(jedis, 0L);
      log.debug(
          "Agent {} is new - giving immediate execution priority: {}",
          agent.getAgentType(),
          result);
      return result;
    } catch (Exception e) {
      log.debug(
          "Could not get agent score from Redis for {}: {}", agent.getAgentType(), e.getMessage());
      return "unknown";
    }
  }

  /**
   * Batch version of agentScore() that processes multiple agents in a single Redis call.
   *
   * @param jedis Redis connection to use
   * @param agents Collection of agents to score
   * @return Map of agent type to calculated score
   */
  private Map<String, String> batchAgentScore(Jedis jedis, Collection<AgentWorker> agents) {
    if (agents.isEmpty()) {
      return new HashMap<>();
    }

    try {
      // Prepare agent names for batch lookup
      List<String> agentNames =
          agents.stream()
              .map(worker -> worker.getAgent().getAgentType())
              .collect(Collectors.toList());

      log.debug("Batch scoring {} agents", agentNames.size());

      // Single Redis call to get all agent scores
      @SuppressWarnings("unchecked")
      List<String> results =
          (List<String>)
              scriptManager.evalshaWithSelfHeal(
                  jedis,
                  RedisScriptManager.SCORE_AGENTS,
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  agentNames);

      // Process results: [agent1, workScore1, waitScore1, agent2, workScore2, waitScore2, ...]
      Map<String, String> agentScores = new HashMap<>();
      Map<String, Agent> agentMap =
          agents.stream()
              .collect(
                  Collectors.toMap(
                      worker -> worker.getAgent().getAgentType(), AgentWorker::getAgent));

      for (int i = 0; i < results.size(); i += 3) {
        String agentType = results.get(i);
        String workingScoreStr = results.get(i + 1);
        String waitingScoreStr = results.get(i + 2);

        Agent agent = agentMap.get(agentType);
        if (agent == null) {
          log.warn("Agent {} not found in batch scoring map", agentType);
          continue;
        }

        String calculatedScore =
            calculateAgentScore(jedis, agent, workingScoreStr, waitingScoreStr);
        agentScores.put(agentType, calculatedScore);
      }

      log.debug("Batch scored {} agents successfully", agentScores.size());
      return agentScores;

    } catch (Exception e) {
      log.warn(
          "Batch agent scoring failed, falling back to individual scoring: {}", e.getMessage());
      // Fallback to individual scoring
      Map<String, String> scores = new HashMap<>();
      for (AgentWorker worker : agents) {
        scores.put(worker.getAgent().getAgentType(), agentScore(worker.getAgent()));
      }
      return scores;
    }
  }

  /**
   * Calculate the score for an agent based on its current Redis state. This exactly matches the
   * logic from agentScore() to ensure consistent behavior.
   *
   * <p>The behavior is:
   *
   * <ul>
   *   <li>Working agents: Calculate NEXT execution time (current + interval)
   *   <li>Waiting agents: Keep existing score (regardless of overdue status)
   *   <li>New agents (not in Redis): Execute immediately (score = 0)
   * </ul>
   */
  private String calculateAgentScore(
      Jedis jedis, Agent agent, String workingScoreStr, String waitingScoreStr) {
    try {
      // If agent is currently working, calculate next execution time (current + interval)
      // This matches original agentScore() behavior for working agents
      if (!"null".equals(workingScoreStr)) {
        String result = score(jedis, intervalProvider.getInterval(agent).getInterval());
        log.debug("Agent {} working - next execution scheduled: {}", agent.getAgentType(), result);
        return result;
      }

      // If agent is waiting, keep existing score regardless of overdue status
      // Overdue agents will be naturally picked up by saturatePool() since their score <=
      // currentTime
      if (!"null".equals(waitingScoreStr)) {
        try {
          long waitingTimeSeconds = Long.parseLong(waitingScoreStr);
          log.debug(
              "Agent {} waiting - keeping existing score: {} (preserves priority ordering)",
              agent.getAgentType(),
              waitingScoreStr);
          return String.valueOf(waitingTimeSeconds);
        } catch (NumberFormatException e) {
          log.debug(
              "Invalid waiting score for agent {}: {}", agent.getAgentType(), waitingScoreStr);
        }
      }
    } catch (Exception e) {
      log.error(
          "Error calculating score for agent {}: {}", agent.getAgentType(), e.getMessage(), e);
      // Fall through to default case
    }

    try {
      // Only NEW agents (not in Redis) get immediate execution priority
      String result = score(jedis, 0L);
      log.debug("Agent {} new - immediate execution: {}", agent.getAgentType(), result);
      return result;
    } catch (Exception e) {
      log.error(
          "Error generating immediate execution score for agent {}: {}",
          agent.getAgentType(),
          e.getMessage(),
          e);
      // Return immediate execution score as fallback - this matches agentScore() behavior
      // where Redis failures still allow the agent to be scheduled
      return String.valueOf(System.currentTimeMillis() / 1000);
    }
  }

  /**
   * Generate a Redis score timestamp in seconds with server-client time synchronization.
   *
   * <p>Redis scores must be consistent format for priority scheduling to work correctly. We use
   * seconds (not milliseconds) to match Redis TIME command format and ensure consistent scoring
   * across all agents and instances.
   *
   * <p>This method performs periodic Redis TIME synchronization to handle clock skew between
   * multiple clouddriver instances and the Redis server.
   *
   * @param jedis Redis connection for TIME command synchronization
   * @param offset Offset in milliseconds to add to current time
   * @return Score as seconds since epoch, synchronized with Redis server time
   */
  private String score(Jedis jedis, Long offset) {
    long now = System.currentTimeMillis();
    long lastCheck = lastTimeCheck.get();

    // Get time cache duration from properties (default 10 seconds)
    long timeCacheDurationMs = schedulerProperties.getTimeCacheDurationMs();

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
          log.debug("Updated Redis TIME sync offset: {}ms", serverTimeMs - now);
        }
      } catch (Exception e) {
        // In case of Redis TIME command failure, we'll use client time
        log.warn("Failed to get Redis server time, using client time: {}", e.getMessage());
      }
    }

    // Get the current time accounting for server-client offset
    long adjustedTimeMs = now + serverClientOffset.get() + offset;
    long adjustedTimeSeconds = adjustedTimeMs / 1000;

    return String.valueOf(adjustedTimeSeconds);
  }

  /**
   * Queue or immediately schedule an agent after execution completes.
   *
   * <p>Successes preserve cadence for the next run when possible. Failures are queued with failure
   * metadata that will be used to compute a class-based backoff offset before re-scheduling the
   * agent into waiting.
   *
   * @param agent the agent that finished
   * @param acquireScore the acquire deadline score (working) captured at acquisition time
   * @param success whether execution completed successfully
   * @param failureClass coarse classification for failures (ignored on success)
   * @param cause the original failure (optional; used for logging)
   */
  public void conditionalReleaseAgent(
      Agent agent,
      String acquireScore,
      boolean success,
      FailureClass failureClass,
      Throwable cause) {
    String agentType = agent.getAgentType();

    try {
      // During shutdown, schedule using cadence-based next when possible; fallback to
      // configured shutdown jitter (whole seconds) to avoid bursts.
      if (shuttingDown.get()) {
        long shutdownOffsetMs = computeShutdownRescheduleOffsetMs(agent, acquireScore);
        log.debug(
            "Shutdown re-queue agent {} with offset {} ms (acquireScore={})",
            agentType,
            shutdownOffsetMs,
            acquireScore);
        scheduleAgentInRedis(agent, Math.max(0L, shutdownOffsetMs));
        return;
      }

      // Queue completion for batch processing in next scheduler cycle
      if (!success) {
        completionQueue.offer(
            new AgentCompletion(
                agent,
                acquireScore,
                false,
                failureClass != null ? failureClass : FailureClass.UNKNOWN,
                cause != null ? cause.getClass().getName() : null));
      } else {
        completionQueue.offer(new AgentCompletion(agent, acquireScore, true));
      }
      log.debug(
          "Queued completion for agent {}: success={}, failureClass={}",
          agentType,
          success,
          failureClass);

    } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
      log.warn(
          "Redis connection error while queueing completion for {}: {}", agentType, e.getMessage());
    } catch (Exception e) {
      log.error(
          "Failed to queue agent completion for {}, falling back to immediate scheduling",
          agentType,
          e);
      // Fallback to immediate scheduling on queue failure
      try {
        if (!success) {
          long fallbackOffset = computeFailureOffsetFast(agent, failureClass);
          scheduleAgentInRedis(agent, Math.max(0L, fallbackOffset));
        } else {
          AgentIntervalProvider.Interval interval = intervalProvider.getInterval(agent);
          scheduleAgentInRedis(agent, interval.getInterval());
        }
      } catch (Exception fallbackException) {
        log.error("Failed fallback scheduling for agent {}", agentType, fallbackException);
      }
    }
  }

  /**
   * Compute shutdown requeue offset using acquire-based cadence when available; fallback to a small
   * whole-second jitter window from configuration.
   */
  private long computeShutdownRescheduleOffsetMs(Agent agent, String acquireScore) {
    try {
      AgentIntervalProvider.Interval interval = intervalProvider.getInterval(agent);
      long intervalMs = interval.getInterval();
      if (acquireScore != null) {
        try {
          long acquireScoreSeconds = Long.parseLong(acquireScore);
          long agentTimeoutMs = interval.getTimeout();
          long originalAcquireMs = (acquireScoreSeconds * 1000L) - agentTimeoutMs;
          long desiredNextRunMs = originalAcquireMs + intervalMs;
          long nowMs = System.currentTimeMillis() + serverClientOffset.get();
          return Math.max(desiredNextRunMs - nowMs, 0L);
        } catch (NumberFormatException ignored) {
          // Fall through to jitter fallback
        }
      }
    } catch (Exception e) {
      // Ignore and use jitter fallback
    }

    int windowSec = 0;
    try {
      windowSec = Math.max(0, schedulerProperties.getJitter().getShutdownSeconds());
    } catch (Exception ignored) {
      windowSec = 0;
    }
    if (windowSec <= 0) {
      return 0L;
    }
    int s = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, windowSec + 1);
    return s * 1000L;
  }

  /**
   * Schedule an agent in Redis with the specified offset.
   *
   * @param agent The agent to schedule
   * @param offsetMs Offset from current time in milliseconds
   */
  public void scheduleAgentInRedis(Agent agent, long offsetMs) {
    String agentType = agent.getAgentType();
    int retryCount = 0;
    int maxRetries = 3;

    while (retryCount < maxRetries) {
      try (Jedis jedis = jedisPool.getResource()) {
        String nextScore;

        // Use standard score calculation for all cases
        nextScore = score(jedis, offsetMs);

        log.debug(
            "Scheduling agent {} in Redis with score: {} (attempt {})",
            agentType,
            nextScore,
            retryCount + 1);

        Object result =
            scriptManager.evalshaWithSelfHeal(
                jedis,
                RedisScriptManager.ADD_AGENT,
                java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                java.util.Arrays.asList(agentType, nextScore));

        boolean scheduled = result != null && ((Long) result).intValue() == 1;
        log.debug("Agent {} scheduled in Redis: {}, result: {}", agentType, scheduled, result);
        return; // Success - exit retry loop

      } catch (Exception e) {
        retryCount++;
        if (retryCount >= maxRetries) {
          log.error(
              "Failed to schedule agent {} in Redis after {} attempts", agentType, maxRetries, e);
          // Don't lose the agent - try to re-queue it later
          if (!shuttingDown.get()) {
            log.warn("Will attempt to recover agent {} on next scheduler cycle", agentType);
          }
        } else {
          log.warn(
              "Failed to schedule agent {} in Redis (attempt {}), retrying: {}",
              agentType,
              retryCount,
              e.getMessage());
          try {
            Thread.sleep(100 * retryCount); // Exponential backoff
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during Redis retry backoff for agent {}", agentType);
            return;
          }
        }
      }
    }
  }

  /** Set the shutdown flag to coordinate graceful shutdown across all operations. */
  public void setShuttingDown(boolean shuttingDown) {
    this.shuttingDown.set(shuttingDown);
    log.info("AgentAcquisitionService shutdown flag set to: {}", shuttingDown);

    if (shuttingDown) {
      // Drain and process all queued completions during shutdown
      // to ensure we don't lose any agents
      processCompletionQueueForShutdown();
    }
  }

  /**
   * Drains and processes all queued completions during shutdown. Ensures pending agent completions
   * are properly recorded in Redis.
   */
  private void processCompletionQueueForShutdown() {
    int queueSize = completionQueue.size();
    if (queueSize == 0) {
      log.info("No queued completions to process during shutdown");
      return;
    }

    log.info("Processing {} queued agent completions during shutdown", queueSize);

    try (Jedis jedis = jedisPool.getResource()) {
      // Process all queued completions with immediate scheduling (0ms offset)
      List<AgentCompletion> completions = drainCompletionQueue();
      int processed = 0;

      // Process each completion with cadence-based or jittered offset to avoid restart bursts
      for (AgentCompletion completion : completions) {
        try {
          String agentType = completion.agent.getAgentType();
          long offsetMs =
              computeShutdownRescheduleOffsetMs(completion.agent, completion.acquireScore);
          String score = score(jedis, offsetMs);

          Object result =
              scriptManager.evalshaWithSelfHeal(
                  jedis,
                  RedisScriptManager.ADD_AGENT,
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  Arrays.asList(agentType, score));

          if (result != null && ((Long) result).intValue() == 1) {
            processed++;
            log.debug("Shutdown processed agent completion: {}", agentType);
          }
        } catch (Exception e) {
          log.error(
              "Failed to process agent completion during shutdown: {}",
              completion.agent.getAgentType(),
              e);
        }
      }

      log.info(
          "Successfully processed {}/{} agent completions during shutdown",
          processed,
          completions.size());
    } catch (Exception e) {
      log.error("Failed to process completion queue during shutdown", e);
    }
  }

  /**
   * Checks if the service is in the process of shutting down. This flag affects agent completion
   * handling - during shutdown, agent completions are processed immediately rather than queued.
   *
   * @return true if shutdown is in progress, false otherwise
   */
  public boolean isShuttingDown() {
    return shuttingDown.get();
  }

  /**
   * Sets the graceful shutdown flag to coordinate agent re-queuing during shutdown. When enabled,
   * active agents are moved back to the waiting set with immediate execution scores to ensure they
   * run after service restart.
   *
   * @param gracefulShutdown true to enable graceful shutdown mode, false otherwise
   */
  public void setGracefulShutdown(boolean gracefulShutdown) {
    this.gracefulShutdown.set(gracefulShutdown);
    log.debug("AgentAcquisitionService graceful shutdown flag set to: {}", gracefulShutdown);
  }

  /**
   * Performs a startup consistency check to ensure all agents are properly registered in Redis.
   * Verifies that all local agents are synchronized to the Redis state.
   */
  private void performStartupConsistencyCheck() {
    log.info("Performing startup consistency check for agent reliability");

    // Take snapshot to prevent concurrent modifications during startup check
    Map<String, AgentWorker> agentsSnapshot = new ConcurrentHashMap<>(agents);

    try (Jedis jedis = jedisPool.getResource()) {
      // Get current Redis state from both sets
      Set<String> localAgents = agentsSnapshot.keySet();
      Set<String> redisAgents = getCurrentRedisAgents(jedis, localAgents);

      // Calculate what needs to be added (missing agents)
      Set<String> toAdd =
          localAgents.stream()
              .filter(agent -> !redisAgents.contains(agent))
              .collect(Collectors.toSet());

      if (toAdd.isEmpty()) {
        log.info("Startup consistency check: All agents properly registered in Redis");
      } else {
        log.warn(
            "Startup consistency check: Found {} agents missing from Redis, adding now",
            toAdd.size());
        // Add missing agents with immediate execution to ensure they run soon
        addMissingAgents(jedis, toAdd);
      }
    } catch (Exception e) {
      log.error("Error during startup consistency check", e);
    }
  }

  /**
   * Checks if graceful shutdown is in progress. This flag affects agent handling during shutdown -
   * graceful shutdown attempts to re-queue in-progress agents back to Redis for pickup after
   * restart.
   *
   * @return true if graceful shutdown is in progress, false otherwise
   */
  public boolean isGracefulShutdown() {
    return gracefulShutdown.get();
  }

  /**
   * Simple data holder for Redis acquisition and release scores. This ensures atomic score
   * management and prevents race conditions.
   */
  private static class ScoreTuple {
    private final String acquireScore;
    private final String releaseScore;

    public ScoreTuple(String acquireScore, String releaseScore) {
      this.acquireScore = acquireScore;
      this.releaseScore = releaseScore;
    }

    public String getAcquireScore() {
      return acquireScore;
    }

    public String getReleaseScore() {
      return releaseScore;
    }
  }

  /**
   * Submit an agent to the thread pool with proper rejection handling and permit management.
   *
   * @param worker The agent worker to submit
   * @param agentWorkPool The thread pool to submit to
   * @param runningAgents The semaphore for concurrency control (may be null)
   * @return The Future for the submitted task, or null if submission failed
   */
  private java.util.concurrent.Future<?> submitAgentWithRejectionHandling(
      AgentWorker worker, ExecutorService agentWorkPool, Semaphore runningAgents) {

    String agentType = worker.getAgent().getAgentType();

    try {
      // Submit worker directly to thread pool
      java.util.concurrent.Future<?> future = agentWorkPool.submit(worker);

      log.debug("Successfully submitted agent {} to thread pool", agentType);
      return future;

    } catch (java.util.concurrent.RejectedExecutionException rex) {
      // Handle rejection - release permit and track metric
      log.warn(
          "Agent {} submission rejected by thread pool (queue full or pool shutdown)", agentType);

      // CRITICAL: Release the semaphore permit since the agent won't be executed
      if (runningAgents != null) {
        runningAgents.release();
        log.debug("Released semaphore permit for rejected agent {}", agentType);
      }

      // Track rejection metric
      if (metrics != null) {
        metrics.incrementSubmissionFailure("rejected");
      }

      // Requeue the agent preserving its original readiness priority
      requeueRejectedAgent(worker);

      return null;

    } catch (Exception e) {
      // Handle other submission errors
      log.error("Failed to submit agent {} due to unexpected error", agentType, e);

      // CRITICAL: Release the semaphore permit for any submission failure
      if (runningAgents != null) {
        runningAgents.release();
        log.debug("Released semaphore permit for failed submission of agent {}", agentType);
      }

      // Track generic submission failure
      if (metrics != null) {
        metrics.incrementSubmissionFailure(e.getClass().getSimpleName());
      }

      return null;
    }
  }

  /**
   * Requeue an agent that was rejected due to thread pool saturation. This preserves the agent's
   * original priority in the queue to maintain fairness.
   *
   * @param worker The agent worker that was rejected
   */
  private void requeueRejectedAgent(AgentWorker worker) {
    String agentType = worker.getAgent().getAgentType();

    try (Jedis jedis = jedisPool.getResource()) {
      // Calculate the score to preserve queue position
      String requeueScore;

      if (worker.acquireScore != null) {
        // We have the acquire score (deadline = acquisition_time + timeout)
        // Calculate when the agent was originally ready to maintain its position
        try {
          long acquireScoreSeconds = Long.parseLong(worker.acquireScore);
          long timeoutSeconds =
              intervalProvider.getInterval(worker.getAgent()).getTimeout() / 1000L;
          long originalReadySeconds = acquireScoreSeconds - timeoutSeconds;

          // Preserve exact original ready time to maintain strict FIFO fairness
          requeueScore = String.valueOf(originalReadySeconds);

          log.debug(
              "Requeueing rejected agent {} with score {} (preserve original ready time)",
              agentType,
              requeueScore);
        } catch (Exception e) {
          // Fallback to immediate readiness if calculation fails
          log.warn(
              "Failed to calculate original ready time for agent {}, using immediate score",
              agentType,
              e);
          requeueScore = score(jedis, 0L);
        }
      } else {
        // No acquire score available, make it immediately eligible
        requeueScore = score(jedis, 0L);
        log.debug(
            "Requeueing rejected agent {} with score {} (immediate, no acquire score)",
            agentType,
            requeueScore);
      }

      // Add back to waiting set
      jedis.zadd(WAITING_SET, Double.parseDouble(requeueScore), agentType);
      log.info(
          "Requeued rejected agent {} with score {} - preserving queue fairness",
          agentType,
          requeueScore);

    } catch (Exception e) {
      log.error(
          "Failed to requeue rejected agent {} - will be picked up in next repopulation",
          agentType,
          e);
    }
  }

  /** Runnable wrapper for agent execution that handles resource management and monitoring. */
  public static class AgentWorker implements Runnable {
    private final Agent agent;
    private final AgentExecution agentExecution;
    private final ExecutionInstrumentation executionInstrumentation;
    private final AgentAcquisitionService acquisitionService;
    private Semaphore runningAgents; // Semaphore to release when execution completes

    // Set by acquisition service when agent is acquired
    String acquireScore;

    AgentWorker(
        Agent agent,
        AgentExecution agentExecution,
        ExecutionInstrumentation executionInstrumentation,
        AgentAcquisitionService acquisitionService) {
      this.agent = agent;
      this.agentExecution = agentExecution;
      this.executionInstrumentation = executionInstrumentation;
      this.acquisitionService = acquisitionService;
      this.runningAgents = null; // Will be set before execution
    }

    @Override
    public void run() {
      String agentType = agent.getAgentType();
      long startTimeMs = System.currentTimeMillis();
      boolean success = false;
      FailureClass failureClass = null;
      Throwable capturedCause = null;

      try {
        log.debug("Starting execution of agent {}", agentType);
        executionInstrumentation.executionStarted(agent);
        agentExecution.executeAgent(agent);
        executionInstrumentation.executionCompleted(agent, elapsedTimeMs(startTimeMs));
        success = true;
        acquisitionService.agentsExecuted.incrementAndGet(); // Track successful executions
        log.debug("Agent {} execution completed successfully", agentType);
      } catch (Throwable cause) {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;

        if (cause instanceof InterruptedException) {
          log.warn(
              "Agent {} execution was interrupted (likely due to zombie cleanup or shutdown)",
              agentType);
          Thread.currentThread().interrupt(); // Restore interrupt status
        } else {
          log.error(
              "Agent {} execution failed after {}ms", agentType, elapsedTimeMs(startTimeMs), cause);
        }

        acquisitionService.agentsFailed.incrementAndGet(); // Track failed executions
        executionInstrumentation.executionFailed(agent, cause, elapsedTimeMs(startTimeMs));
        capturedCause = cause;
        failureClass = acquisitionService.classifyFailure(cause);
      } finally {
        // Always clean up agent tracking when execution completes (success or failure)
        // This removes the agent from activeAgents map and working Redis set
        acquisitionService.removeActiveAgent(agentType);

        // Handle conditional agent release (re-queuing on failure/shutdown)
        acquisitionService.conditionalReleaseAgent(
            agent, acquireScore, success, failureClass, capturedCause);

        // CRITICAL: Exactly-once permit release
        RunState rs = acquisitionService.runStates.remove(agentType);
        if (rs == null) {
          // No run-state (e.g., tests calling AgentWorker directly) -> release as before
          if (runningAgents != null) {
            runningAgents.release();
            log.debug("Released semaphore permit for agent {} (no run-state)", agentType);
          }
        } else if (rs.permitHeld.compareAndSet(true, false)) {
          // Normal path: release once
          if (runningAgents != null) {
            runningAgents.release();
            log.debug("Released semaphore permit for agent {}", agentType);
          }
        } else {
          // Permit was pre-released by zombie cleanup; decrement in-flight compensation
          acquisitionService.zombiesInFlight.decrementAndGet();
        }

        log.debug("Agent {} execution cleanup completed", agentType);
      }
    }

    /**
     * Get the agent associated with this worker.
     *
     * @return The agent
     */
    public Agent getAgent() {
      return agent;
    }

    /**
     * Get the acquire score for this agent.
     *
     * @return The acquire score
     */
    public String getAcquireScore() {
      return acquireScore;
    }

    /**
     * Set the semaphore before execution (called from saturatePool)
     *
     * @param runningAgents The semaphore to use for resource management
     */
    void setRunningAgents(Semaphore runningAgents) {
      this.runningAgents = runningAgents;
    }
  }

  /**
   * Classify a failure throwable into a coarse-grained {@code FailureClass} without introducing
   * provider SDK dependencies.
   *
   * <p>Heuristics: - InterruptedException -> TRANSIENT (handled earlier by restoring interrupt) -
   * IO/connectivity/timeouts -> TRANSIENT - AWS AmazonServiceException (via reflection): 403 ->
   * PERMANENT_FORBIDDEN (AccessDenied or similar) 429 -> THROTTLED 5xx -> SERVER_ERROR - Messages
   * containing throttling hints -> THROTTLED - Otherwise -> UNKNOWN
   */
  FailureClass classifyFailure(Throwable cause) {
    if (cause == null) {
      return FailureClass.UNKNOWN;
    }

    if (cause instanceof InterruptedException) {
      return FailureClass.TRANSIENT;
    }

    if (cause instanceof java.net.SocketTimeoutException
        || cause instanceof java.net.ConnectException
        || cause instanceof java.net.SocketException
        || cause instanceof java.io.IOException) {
      return FailureClass.TRANSIENT;
    }

    try {
      Class<?> aseClass = Class.forName("com.amazonaws.AmazonServiceException");
      if (aseClass.isAssignableFrom(cause.getClass())) {
        Integer status = null;
        String errorCode = null;
        try {
          java.lang.reflect.Method getStatusCode = aseClass.getMethod("getStatusCode");
          Object sc = getStatusCode.invoke(cause);
          if (sc instanceof Integer) {
            status = (Integer) sc;
          }
        } catch (Exception ignored) {
        }
        try {
          java.lang.reflect.Method getErrorCode = aseClass.getMethod("getErrorCode");
          Object ec = getErrorCode.invoke(cause);
          if (ec instanceof String) {
            errorCode = (String) ec;
          }
        } catch (Exception ignored) {
        }

        if (status != null) {
          if (status == 403) {
            if (errorCode != null
                && errorCode.toLowerCase(java.util.Locale.ROOT).contains("accessdenied")) {
              return FailureClass.PERMANENT_FORBIDDEN;
            }
            return FailureClass.PERMANENT_FORBIDDEN;
          }
          if (status == 429) {
            return FailureClass.THROTTLED;
          }
          if (status >= 500 && status < 600) {
            return FailureClass.SERVER_ERROR;
          }
          if (status >= 400 && status < 500) {
            return FailureClass.UNKNOWN;
          }
        }
        return FailureClass.UNKNOWN;
      }
    } catch (ClassNotFoundException ignored) {
      // AWS SDK not present in this module
    }

    String msg = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
    if (msg.contains("throttl")
        || msg.contains("rate exceeded")
        || msg.contains("too many requests")) {
      return FailureClass.THROTTLED;
    }

    return FailureClass.UNKNOWN;
  }

  /**
   * Try to manually acquire a lock on an agent.
   *
   * <p>NOTE: Manual locking is not supported by this scheduler to maintain thread safety and proper
   * coordination between multiple scheduler instances. Manual locking would bypass the carefully
   * designed Redis-based coordination mechanisms.
   *
   * @param agent The agent to lock
   * @return Always returns null (manual locking not supported)
   */
  public AgentLock tryLockAgent(Agent agent) {
    // Manual locking is not supported to maintain thread safety and proper coordination
    log.debug(
        "Manual locking not supported for agent {} - use automatic scheduling",
        agent.getAgentType());
    return null;
  }

  /**
   * Try to release a manually acquired agent lock.
   *
   * <p>Since manual locking is not supported, this always returns false.
   *
   * @param lock The lock to release
   * @return Always returns false (manual locking not supported)
   */
  public boolean tryReleaseAgent(AgentLock lock) {
    // Manual locking/releasing is not supported
    log.debug(
        "Manual lock release not supported for agent {} - locks are managed automatically",
        lock.getAgent().getAgentType());
    return false;
  }

  /**
   * Check if an agent lock is still valid.
   *
   * <p>Since manual locking is not supported, this always returns false.
   *
   * @param lock The lock to validate
   * @return Always returns false (manual locking not supported)
   */
  public boolean isLockValid(AgentLock lock) {
    // Manual locking is not supported, so manual locks are never valid
    log.debug(
        "Manual lock validation not supported for agent {} - locks are managed automatically",
        lock.getAgent().getAgentType());
    return false;
  }
}
