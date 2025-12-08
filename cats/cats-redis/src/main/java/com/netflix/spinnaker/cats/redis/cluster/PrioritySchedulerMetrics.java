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

import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.patterns.PolledMeter;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Metrics collection for the Priority Scheduler.
 *
 * <p>Provides Spectator metrics for monitoring scheduler health and performance. All metrics use
 * the namespace {@code cats.priorityScheduler.<domain>.<metric>} and include a common {@code
 * scheduler=priority} tag for filtering.
 */
@Component
public final class PrioritySchedulerMetrics {

  private static final Logger log = LoggerFactory.getLogger(PrioritySchedulerMetrics.class);

  /** Metric namespace prefix for all priority scheduler metrics. */
  private static final String METRIC_PREFIX = "cats.priorityScheduler.";

  private final Registry registry;

  // Run cycle metrics
  private final Id runCycleTimeId;
  private final Id runFailuresId;

  // Acquisition metrics
  private final Id acquireAttemptsId;
  private final Id acquiredCountId;
  private final Id acquireTimeId;
  private final Id submissionFailuresId;
  private final Id batchFallbacksId;
  private final Id stallDetectedId;

  // Circuit breaker metrics
  private final Id circuitBreakerTripId;
  private final Id circuitBreakerRecoveryId;
  private final Id circuitBreakerBlockedId;

  // Validation metrics
  private final Id acquireValidationFailureId;
  private final Id invalidMemberId;
  private final Id invalidPairId;
  private final Id scriptResultTypeErrorId;
  private final Id stateInconsistentActiveId;

  // Repopulation metrics
  private final Id repopulateTimeId;
  private final Id repopulateAddedId;
  private final Id repopulateErrorsId;

  // Cleanup metrics
  private final Id cleanupTimeId;
  private final Id cleanupCleanedId;
  private final Id cleanupSkippedId;
  private final Id cleanupTimeoutId;

  // Script execution metrics
  private final Id scriptsEvalId;
  private final Id scriptsErrorsId;
  private final Id scriptsLatencyId;
  private final Id scriptsReloadsId;

  // Redis pool health metrics
  private final Id redisPoolErrorsId;

  // Agent removal metrics
  private final Id removeAgentFallbackId;

  // Permit accounting metrics
  private final Id casContentionId;
  private final Id zombiesInFlightNegativeId;

  // Schedule metrics
  private final Id scheduleRetryExhaustedId;
  private final Id scheduleRecoveryId;

  // Guard against duplicate PolledMeter registrations
  private volatile boolean gaugesRegistered = false;

  /**
   * Creates a new metrics collector bound to the provided registry.
   *
   * @param registry Spectator registry used to create meters
   */
  public PrioritySchedulerMetrics(Registry registry) {
    this.registry = registry;

    // Run cycle metrics
    this.runCycleTimeId = createId("run.cycleTime");
    this.runFailuresId = createId("run.failures");

    // Acquisition metrics
    this.acquireAttemptsId = createId("acquire.attempts");
    this.acquiredCountId = createId("acquire.acquired");
    this.acquireTimeId = createId("acquire.time");
    this.submissionFailuresId = createId("acquire.submissionFailures");
    this.batchFallbacksId = createId("acquire.batchFallbacks");
    this.stallDetectedId = createId("acquire.stallDetected");

    // Circuit breaker metrics
    this.circuitBreakerTripId = createId("circuitBreaker.trip");
    this.circuitBreakerRecoveryId = createId("circuitBreaker.recovery");
    this.circuitBreakerBlockedId = createId("circuitBreaker.blocked");

    // Validation metrics (consolidated under validation domain)
    this.acquireValidationFailureId = createId("validation.acquireFailures");
    this.invalidMemberId = createId("validation.invalidMember");
    this.invalidPairId = createId("validation.invalidPair");
    this.scriptResultTypeErrorId = createId("validation.scriptResultTypeError");
    this.stateInconsistentActiveId = createId("validation.inconsistentActive");

    // Repopulation metrics
    this.repopulateTimeId = createId("repopulate.time");
    this.repopulateAddedId = createId("repopulate.added");
    this.repopulateErrorsId = createId("repopulate.errors");

    // Cleanup metrics
    this.cleanupTimeId = createId("cleanup.time");
    this.cleanupCleanedId = createId("cleanup.cleaned");
    this.cleanupSkippedId = createId("cleanup.skipped");
    this.cleanupTimeoutId = createId("cleanup.timeout");

    // Script execution metrics
    this.scriptsEvalId = createId("scripts.eval");
    this.scriptsErrorsId = createId("scripts.errors");
    this.scriptsLatencyId = createId("scripts.latency");
    this.scriptsReloadsId = createId("scripts.reloads");

    // Redis pool health
    this.redisPoolErrorsId = createId("redisPool.errors");

    // Agent removal
    this.removeAgentFallbackId = createId("removeAgent.fallbackUsed");

    // Permit accounting
    this.casContentionId = createId("cas.contention");
    this.zombiesInFlightNegativeId = createId("scheduler.zombiesInFlight.negative");

    // Schedule metrics
    this.scheduleRetryExhaustedId = createId("schedule.retryExhausted");
    this.scheduleRecoveryId = createId("schedule.recovery");
  }

  /**
   * Creates a metric ID with the standard prefix and base tags.
   *
   * @param suffix the metric name suffix (e.g., "acquire.attempts")
   * @return fully qualified metric ID with scheduler=priority tag
   */
  private Id createId(String suffix) {
    return registry.createId(METRIC_PREFIX + suffix).withTag("scheduler", "priority");
  }

  /**
   * Records the duration of a scheduler run cycle.
   *
   * @param success whether the cycle completed successfully
   * @param elapsedMs elapsed time in milliseconds
   */
  public void recordRunCycle(boolean success, long elapsedMs) {
    registry
        .timer(runCycleTimeId.withTag("success", Boolean.toString(success)))
        .record(elapsedMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Increments the run failure counter with a tagged reason.
   *
   * @param reason failure category (low-cardinality)
   */
  public void incrementRunFailure(String reason) {
    registry.counter(runFailuresId.withTag("reason", safe(reason))).increment();
  }

  /**
   * Records a per-agent execution failure for debugging agent-specific issues.
   *
   * @param agentType the agent type identifier
   * @param provider the provider name
   * @param reason failure category (low-cardinality)
   */
  public void incrementRunFailure(String agentType, String provider, String reason) {
    registry
        .counter(
            runFailuresId
                .withTag("reason", safe(reason))
                .withTag("agentType", safe(agentType))
                .withTag("provider", safe(provider)))
        .increment();
  }

  /** Increments the acquisition attempts counter. */
  public void incrementAcquireAttempts() {
    registry.counter(acquireAttemptsId).increment();
  }

  /**
   * Increments the acquired counter by the provided amount if positive.
   *
   * @param count number of agents acquired in the cycle
   */
  public void incrementAcquired(long count) {
    if (count > 0) {
      registry.counter(acquiredCountId).increment(count);
    }
  }

  /**
   * Records acquisition latency with a mode tag (e.g., batch/single).
   *
   * @param mode acquisition mode label
   * @param elapsedMs elapsed time in milliseconds
   */
  public void recordAcquireTime(String mode, long elapsedMs) {
    registry
        .timer(acquireTimeId.withTag("mode", safe(mode)))
        .record(elapsedMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Records per-agent acquisition latency for debugging agent-specific acquisition patterns.
   *
   * @param mode acquisition mode label
   * @param agentType the agent type identifier
   * @param provider the provider name
   * @param elapsedMs elapsed time in milliseconds
   */
  public void recordAcquireTime(String mode, String agentType, String provider, long elapsedMs) {
    registry
        .timer(
            acquireTimeId
                .withTag("mode", safe(mode))
                .withTag("agentType", safe(agentType))
                .withTag("provider", safe(provider)))
        .record(elapsedMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Increments submission failure counter with reason (e.g., rejected/interrupted).
   *
   * @param reason failure category
   */
  public void incrementSubmissionFailure(String reason) {
    registry.counter(submissionFailuresId.withTag("reason", safe(reason))).increment();
  }

  /** Increments counter for batch fallback events. */
  public void incrementBatchFallback() {
    registry.counter(batchFallbacksId).increment();
  }

  /** Increments counter for detected acquisition stalls. */
  public void incrementStallDetected() {
    registry.counter(stallDetectedId).increment();
  }

  /**
   * Records a circuit breaker trip event.
   *
   * @param name breaker name
   * @param reason trip reason
   */
  public void recordCircuitBreakerTrip(String name, String reason) {
    registry
        .counter(circuitBreakerTripId.withTag("name", safe(name)).withTag("reason", safe(reason)))
        .increment();
  }

  /**
   * Records a circuit breaker recovery event.
   *
   * @param name breaker name
   */
  public void recordCircuitBreakerRecovery(String name) {
    registry.counter(circuitBreakerRecoveryId.withTag("name", safe(name))).increment();
  }

  /**
   * Records a circuit breaker blocked event.
   *
   * @param name breaker name
   */
  public void recordCircuitBreakerBlocked(String name) {
    registry.counter(circuitBreakerBlockedId.withTag("name", safe(name))).increment();
  }

  /**
   * Increments acquisition validation failure counter with reason.
   *
   * @param reason validation failure reason
   */
  public void incrementAcquireValidationFailure(String reason) {
    registry.counter(acquireValidationFailureId.withTag("reason", safe(reason))).increment();
  }

  /**
   * Records repopulation duration.
   *
   * @param elapsedMs elapsed time in milliseconds
   */
  public void recordRepopulateTime(long elapsedMs) {
    registry.timer(repopulateTimeId).record(elapsedMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Increments repopulation added count by the provided amount if positive.
   *
   * @param added number of agents inserted
   */
  public void incrementRepopulateAdded(long added) {
    if (added > 0) {
      registry.counter(repopulateAddedId).increment(added);
    }
  }

  /**
   * Increments repopulation error counter with reason.
   *
   * @param reason error category
   */
  public void incrementRepopulateError(String reason) {
    registry.counter(repopulateErrorsId.withTag("reason", safe(reason))).increment();
  }

  /**
   * Records cleanup duration tagged by type (zombie/orphan/reconcile).
   *
   * @param type cleanup type label
   * @param elapsedMs elapsed time in milliseconds
   */
  public void recordCleanupTime(String type, long elapsedMs) {
    registry
        .timer(cleanupTimeId.withTag("type", safe(type)))
        .record(elapsedMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Increments cleaned counter by type when positive.
   *
   * @param type cleanup type
   * @param cleaned number of items cleaned
   */
  public void incrementCleanupCleaned(String type, long cleaned) {
    if (cleaned > 0) {
      registry.counter(cleanupCleanedId.withTag("type", safe(type))).increment(cleaned);
    }
  }

  /**
   * Records a script evaluation event and latency.
   *
   * @param script script name
   * @param elapsedMs elapsed time in milliseconds
   */
  public void recordScriptEval(String script, long elapsedMs) {
    registry.counter(scriptsEvalId.withTag("script", safe(script))).increment();
    registry
        .timer(scriptsLatencyId.withTag("script", safe(script)))
        .record(elapsedMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Increments script error counter tagged by script and reason.
   *
   * @param script script name
   * @param reason error category
   */
  public void incrementScriptError(String script, String reason) {
    registry
        .counter(scriptsErrorsId.withTag("script", safe(script)).withTag("reason", safe(reason)))
        .increment();
  }

  /** Increments script reload counter. */
  public void incrementScriptsReload() {
    registry.counter(scriptsReloadsId).increment();
  }

  /**
   * Increments invalid Redis member counter with location tag.
   *
   * @param where location where invalid member was detected
   */
  public void incrementInvalidMember(String where) {
    registry.counter(invalidMemberId.withTag("where", safe(where))).increment();
  }

  /**
   * Increments invalid pair counter with phase tag.
   *
   * @param phase processing phase where invalid pair was detected
   */
  public void incrementInvalidPair(String phase) {
    registry.counter(invalidPairId.withTag("phase", safe(phase))).increment();
  }

  /**
   * Increments type error counter for script results.
   *
   * @param script script name that returned unexpected type
   */
  public void incrementScriptResultTypeError(String script) {
    registry.counter(scriptResultTypeErrorId.withTag("script", safe(script))).increment();
  }

  /** Increments counter for inconsistent active state observations. */
  public void incrementStateInconsistentActive() {
    registry.counter(stateInconsistentActiveId).increment();
  }

  /**
   * Increments counter when Redis pool gauge collection fails due to pool exceptions. This provides
   * visibility into Redis connectivity issues that would otherwise be masked by returning 0.
   *
   * @param metric the metric name that failed (e.g., "active", "idle", "waiters")
   */
  public void incrementRedisPoolError(String metric) {
    registry.counter(redisPoolErrorsId.withTag("metric", safe(metric))).increment();
  }

  /**
   * Increments counter when atomic removeAgent script fails and fallback path is used. This
   * indicates potential race conditions in agent removal that could lead to agent loss.
   */
  public void incrementRemoveAgentFallback() {
    registry.counter(removeAgentFallbackId).increment();
  }

  /**
   * Increments counter when cleanup is skipped because a previous run is still in progress. A
   * sustained high rate indicates cleanup is falling behind and zombies/orphans may accumulate.
   *
   * @param type cleanup type ("zombie" or "orphan")
   */
  public void incrementCleanupSkipped(String type) {
    registry.counter(cleanupSkippedId.withTag("type", safe(type))).increment();
  }

  /**
   * Increments counter when cleanup is cancelled due to exceeding the external timeout. This
   * indicates the cleanup task hung on a Redis operation or future.cancel() and was forcibly
   * cancelled to prevent permanent cleanup failure on this pod.
   *
   * @param type cleanup type ("zombie" or "orphan")
   */
  public void incrementCleanupTimeout(String type) {
    registry.counter(cleanupTimeoutId.withTag("type", safe(type))).increment();
  }

  /**
   * Increments counter when a CAS (compare-and-set) operation on permit state fails. This indicates
   * contention between concurrent operations (e.g., zombie cleanup racing with worker completion).
   * High rates may indicate accounting issues or unexpected concurrency patterns.
   *
   * @param location code location where contention occurred (e.g., "zombie_cleanup",
   *     "worker_completion")
   */
  public void incrementCasContention(String location) {
    registry.counter(casContentionId.withTag("location", safe(location))).increment();
  }

  /**
   * Increments counter when zombiesInFlight decrement would go negative. This indicates an
   * accounting bug where more decrements occurred than increments. The counter is clamped to 0, but
   * this metric tracks occurrences for debugging.
   */
  public void incrementZombiesInFlightNegative() {
    registry.counter(zombiesInFlightNegativeId).increment();
  }

  /**
   * Records the permit mismatch gauge value. Expected value is 0; non-zero indicates accounting
   * drift. Calculated as: (permitsHeld + availablePermits + zombiesInFlight) - totalPermits
   *
   * @param mismatch the calculated mismatch value
   */
  public void recordPermitMismatch(int mismatch) {
    registry.gauge(createId("scheduler.permitMismatch")).set(mismatch);
  }

  /**
   * Records the high-water mark for zombiesInFlight since last reporting period. Helps identify
   * peak zombie accumulation that may not be visible in point-in-time gauges.
   *
   * @param highWater the maximum zombiesInFlight value observed
   */
  public void recordZombiesInFlightHighWater(int highWater) {
    registry.gauge(createId("scheduler.zombiesInFlight.highWater")).set(highWater);
  }

  /**
   * Increments counter when Redis scheduling retries are exhausted and an agent is queued for
   * recovery. This indicates transient Redis failures during agent completion processing.
   */
  public void incrementScheduleRetryExhausted() {
    registry.counter(scheduleRetryExhaustedId).increment();
  }

  /**
   * Increments counter when an agent is successfully recovered after schedule retry exhaustion.
   *
   * @param success true if recovery succeeded, false if it failed again
   */
  public void incrementScheduleRecovery(boolean success) {
    registry.counter(scheduleRecoveryId.withTag("success", Boolean.toString(success))).increment();
  }

  /**
   * Registers gauges that are shared across scheduler services. Safe to call multiple times.
   *
   * @param jedisPool Redis connection pool for pool metrics (may be null)
   * @param registeredAgents supplier for registered agent count
   * @param activeAgents supplier for active agent count
   * @param readyCount supplier for ready-to-run agent count
   * @param oldestOverdueSeconds supplier for oldest overdue agent age in seconds
   * @param degraded supplier for degraded state indicator (1=degraded, 0=healthy)
   * @param capacityPerCycle supplier for capacity per scheduling cycle
   * @param queueDepth supplier for executor queue depth
   * @param semaphoreAvailable supplier for available semaphore permits
   * @param completionQueueSize supplier for completion queue size
   * @param timeOffsetMs supplier for Redis/local clock offset in milliseconds
   * @param readyToCapacityRatio supplier for ready-to-capacity ratio
   * @param zombiesInFlight supplier for zombies-in-flight count
   */
  public synchronized void registerGauges(
      JedisPool jedisPool,
      Supplier<Number> registeredAgents,
      Supplier<Number> activeAgents,
      Supplier<Number> readyCount,
      Supplier<Number> oldestOverdueSeconds,
      Supplier<Number> degraded,
      Supplier<Number> capacityPerCycle,
      Supplier<Number> queueDepth,
      Supplier<Number> semaphoreAvailable,
      Supplier<Number> completionQueueSize,
      Supplier<Number> timeOffsetMs,
      Supplier<Number> readyToCapacityRatio,
      Supplier<Number> zombiesInFlight) {

    if (gaugesRegistered) {
      return;
    }

    // Scheduler state gauges (consolidated under scheduler.* domain)
    registerGauge("scheduler.registeredAgents", registeredAgents);
    registerGauge("scheduler.activeAgents", activeAgents);
    registerGauge("scheduler.readyCount", readyCount);
    registerGauge("scheduler.oldestOverdueSeconds", oldestOverdueSeconds);
    registerGauge("scheduler.degraded", degraded);
    registerGauge("scheduler.capacityPerCycle", capacityPerCycle);
    registerGauge("scheduler.queueDepth", queueDepth);
    registerGauge("scheduler.semaphoreAvailable", semaphoreAvailable);
    registerGauge("scheduler.completionQueueSize", completionQueueSize);
    registerGauge("scheduler.timeOffsetMs", timeOffsetMs);
    registerGauge("scheduler.readyToCapacityRatio", readyToCapacityRatio);
    registerGauge("scheduler.zombiesInFlight", zombiesInFlight);

    // JedisPool gauges
    if (jedisPool != null) {
      registerJedisPoolGauges(jedisPool);
    }

    gaugesRegistered = true;
  }

  /**
   * Registers executor thread pool gauges for observability.
   *
   * @param executor the thread pool executor to monitor (may be null)
   */
  public void registerExecutorGauges(ThreadPoolExecutor executor) {
    if (executor == null) {
      return;
    }

    Object activeAnchor = new Object();
    Object poolSizeAnchor = new Object();
    Object queueSizeAnchor = new Object();
    Object completedAnchor = new Object();

    PolledMeter.using(registry)
        .withId(createId("executor.activeThreads"))
        .monitorValue(activeAnchor, o -> executor.getActiveCount());

    PolledMeter.using(registry)
        .withId(createId("executor.poolSize"))
        .monitorValue(poolSizeAnchor, o -> executor.getPoolSize());

    PolledMeter.using(registry)
        .withId(createId("executor.queueSize"))
        .monitorValue(queueSizeAnchor, o -> executor.getQueue().size());

    PolledMeter.using(registry)
        .withId(createId("executor.completedTasks"))
        .monitorValue(completedAnchor, o -> executor.getCompletedTaskCount());
  }

  private void registerJedisPoolGauges(JedisPool jedisPool) {
    Object activeAnchor = new Object();
    Object idleAnchor = new Object();
    Object waitersAnchor = new Object();

    PolledMeter.using(registry)
        .withId(createId("redisPool.active"))
        .monitorValue(
            activeAnchor,
            o -> {
              try {
                return jedisPool.getNumActive();
              } catch (Exception e) {
                log.debug(
                    "Failed to get Redis pool active connections count for metric cats.priorityScheduler.redisPool.active",
                    e);
                incrementRedisPoolError("active");
                return 0;
              }
            });

    PolledMeter.using(registry)
        .withId(createId("redisPool.idle"))
        .monitorValue(
            idleAnchor,
            o -> {
              try {
                return jedisPool.getNumIdle();
              } catch (Exception e) {
                log.debug(
                    "Failed to get Redis pool idle connections count for metric cats.priorityScheduler.redisPool.idle",
                    e);
                incrementRedisPoolError("idle");
                return 0;
              }
            });

    PolledMeter.using(registry)
        .withId(createId("redisPool.waiters"))
        .monitorValue(
            waitersAnchor,
            o -> {
              try {
                return jedisPool.getNumWaiters();
              } catch (Exception e) {
                log.debug(
                    "Failed to get Redis pool waiters count for metric cats.priorityScheduler.redisPool.waiters",
                    e);
                incrementRedisPoolError("waiters");
                return 0;
              }
            });
  }

  private void registerGauge(String suffix, Supplier<Number> supplier) {
    if (supplier == null) {
      return;
    }
    Object anchor = new Object();
    PolledMeter.using(registry)
        .withId(createId(suffix))
        .monitorValue(anchor, o -> toDouble(supplier.get()));
  }

  private static double toDouble(Number n) {
    if (n == null) {
      return 0.0d;
    }
    return n.doubleValue();
  }

  private static String safe(String value) {
    return (value == null || value.isEmpty()) ? "unknown" : value;
  }
}
