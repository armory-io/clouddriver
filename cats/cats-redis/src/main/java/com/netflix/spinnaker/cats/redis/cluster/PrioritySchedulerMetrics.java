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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Metrics collection for the Priority Redis scheduler.
 *
 * <p>Provides Spectator metrics for monitoring scheduler health and performance.
 */
@Component
public final class PrioritySchedulerMetrics {

  private static final Logger log = LoggerFactory.getLogger(PrioritySchedulerMetrics.class);

  private final Registry registry;

  private final Id runCycleTimeId;
  private final Id runFailuresId;

  private final Id acquireAttemptsId;
  private final Id acquiredCountId;
  private final Id acquireTimeId;
  private final Id submissionFailuresId;
  private final Id batchFallbacksId;
  private final Id stallDetectedId;
  private final Id circuitBreakerTripId;
  private final Id circuitBreakerRecoveryId;
  private final Id circuitBreakerBlockedId;
  private final Id acquireValidationFailureId;

  private final Id repopulateTimeId;
  private final Id repopulateAddedId;
  private final Id repopulateErrorsId;

  private final Id cleanupTimeId;
  private final Id cleanupCleanedId;

  private final Id scriptsEvalId;
  private final Id scriptsErrorsId;
  private final Id scriptsLatencyId;
  private final Id scriptsReloadsId;

  // Validation/consistency metrics
  private final Id invalidMemberId;
  private final Id invalidPairId;
  private final Id scriptResultTypeErrorId;
  private final Id stateInconsistentActiveId;

  // Redis pool health metrics
  private final Id redisPoolErrorsId;

  // Agent removal metrics
  private final Id removeAgentFallbackId;

  // Cleanup metrics
  private final Id cleanupSkippedId;
  private final Id cleanupTimeoutId;

  // Guard against duplicate PolledMeter registrations
  private volatile boolean gaugesRegistered = false;

  /**
   * Creates a new metrics collector bound to the provided registry.
   *
   * @param registry Spectator registry used to create meters
   */
  public PrioritySchedulerMetrics(Registry registry) {
    this.registry = registry;

    this.runCycleTimeId =
        registry
            .createId("cats.redisPriority.run.cycleTime")
            .withTag("scheduler", "priority")
            .withTag("component", "scheduler");
    this.runFailuresId = registry.createId("cats.redisPriority.run.failures");

    this.acquireAttemptsId = registry.createId("cats.redisPriority.acquire.attempts");
    this.acquiredCountId = registry.createId("cats.redisPriority.acquire.acquired");
    this.acquireTimeId = registry.createId("cats.redisPriority.acquire.time");
    this.submissionFailuresId = registry.createId("cats.redisPriority.acquire.submissionFailures");
    this.batchFallbacksId = registry.createId("cats.redisPriority.batch.fallbacks");
    this.stallDetectedId = registry.createId("cats.redisPriority.acquire.stallDetected");
    this.circuitBreakerTripId = registry.createId("cats.redisPriority.circuitBreaker.trip");
    this.circuitBreakerRecoveryId = registry.createId("cats.redisPriority.circuitBreaker.recovery");
    this.circuitBreakerBlockedId = registry.createId("cats.redisPriority.circuitBreaker.blocked");
    this.acquireValidationFailureId =
        registry.createId("cats.redisPriority.acquire.validationFailures");

    this.repopulateTimeId = registry.createId("cats.redisPriority.repopulate.time");
    this.repopulateAddedId = registry.createId("cats.redisPriority.repopulate.added");
    this.repopulateErrorsId = registry.createId("cats.redisPriority.repopulate.errors");

    this.cleanupTimeId = registry.createId("cats.redisPriority.cleanup.time");
    this.cleanupCleanedId = registry.createId("cats.redisPriority.cleanup.cleaned");

    this.scriptsEvalId = registry.createId("cats.redisPriority.scripts.eval");
    this.scriptsErrorsId = registry.createId("cats.redisPriority.scripts.errors");
    this.scriptsLatencyId = registry.createId("cats.redisPriority.scripts.latency");
    this.scriptsReloadsId = registry.createId("cats.redisPriority.scripts.reloads");

    // Validation/consistency
    this.invalidMemberId = registry.createId("cats.redisPriority.redis.invalidMember");
    this.invalidPairId = registry.createId("cats.redisPriority.add.invalidPair");
    this.scriptResultTypeErrorId = registry.createId("cats.redisPriority.scripts.resultTypeError");
    this.stateInconsistentActiveId =
        registry.createId("cats.redisPriority.state.inconsistentActive");

    // Redis pool health
    this.redisPoolErrorsId = registry.createId("cats.redisPriority.redisPool.errors");

    // Agent removal
    this.removeAgentFallbackId = registry.createId("cats.redisPriority.removeAgent.fallbackUsed");

    // Cleanup
    this.cleanupSkippedId = registry.createId("cats.redisPriority.cleanup.skipped");
    this.cleanupTimeoutId = registry.createId("cats.redisPriority.cleanup.timeout");
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

    // Acquisition/scheduler state gauges
    registerGauge("cats.redisPriority.registeredAgents", registeredAgents);
    registerGauge("cats.redisPriority.activeAgents", activeAgents);
    registerGauge("cats.redisPriority.readyCount", readyCount);
    registerGauge("cats.redisPriority.oldestOverdueSeconds", oldestOverdueSeconds);
    registerGauge("cats.redisPriority.degraded", degraded);
    registerGauge("cats.redisPriority.capacityPerCycle", capacityPerCycle);
    registerGauge("cats.redisPriority.queueDepth", queueDepth);
    registerGauge("cats.redisPriority.semaphore.available", semaphoreAvailable);
    registerGauge("cats.redisPriority.completionQueue.size", completionQueueSize);
    registerGauge("cats.redisPriority.timeOffsetMs", timeOffsetMs);
    registerGauge("cats.redisPriority.readyToCapacityRatio", readyToCapacityRatio);
    registerGauge("cats.redisPriority.zombiesInFlight", zombiesInFlight);

    // JedisPool gauges
    if (jedisPool != null) {
      // Anchor object ensures unique meter identity
      Object activeAnchor = new Object();
      Object idleAnchor = new Object();
      Object waitersAnchor = new Object();
      PolledMeter.using(registry)
          .withId(registry.createId("cats.redisPriority.redisPool.active"))
          .monitorValue(
              activeAnchor,
              o -> {
                try {
                  return jedisPool.getNumActive();
                } catch (Exception e) {
                  // Use DEBUG to avoid log spam during transient pool issues
                  log.debug(
                      "Failed to get Redis pool active connections count for metric cats.redisPriority.redisPool.active",
                      e);
                  incrementRedisPoolError("active");
                  return 0;
                }
              });
      PolledMeter.using(registry)
          .withId(registry.createId("cats.redisPriority.redisPool.idle"))
          .monitorValue(
              idleAnchor,
              o -> {
                try {
                  return jedisPool.getNumIdle();
                } catch (Exception e) {
                  // Use DEBUG to avoid log spam during transient pool issues
                  log.debug(
                      "Failed to get Redis pool idle connections count for metric cats.redisPriority.redisPool.idle",
                      e);
                  incrementRedisPoolError("idle");
                  return 0;
                }
              });
      PolledMeter.using(registry)
          .withId(registry.createId("cats.redisPriority.redisPool.waiters"))
          .monitorValue(
              waitersAnchor,
              o -> {
                try {
                  return jedisPool.getNumWaiters();
                } catch (Exception e) {
                  // Use DEBUG to avoid log spam during transient pool issues
                  log.debug(
                      "Failed to get Redis pool waiters count for metric cats.redisPriority.redisPool.waiters",
                      e);
                  incrementRedisPoolError("waiters");
                  return 0;
                }
              });
    }

    gaugesRegistered = true;
  }

  private void registerGauge(String name, Supplier<Number> supplier) {
    if (supplier == null) {
      return;
    }
    Object anchor = new Object();
    PolledMeter.using(registry)
        .withId(registry.createId(name))
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
