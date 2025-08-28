/*
 * Copyright 2025 Harness, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.cats.redis.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.exceptions.JedisConnectionException;

/** Tests for the Priority Scheduler Circuit Breaker. */
public class PrioritySchedulerCircuitBreakerTest {

  private Registry registry;
  private PrioritySchedulerMetrics metrics;
  private PrioritySchedulerCircuitBreaker circuitBreaker;

  @BeforeEach
  public void setUp() {
    registry = new DefaultRegistry();
    metrics = new PrioritySchedulerMetrics(registry);
    circuitBreaker =
        new PrioritySchedulerCircuitBreaker(
            "test", 3, // 3 failures to trip
            5000, // 5 second window
            1000, // 1 second cooldown for testing
            500, // 0.5 second half-open
            metrics);
  }

  @Test
  @Timeout(5)
  public void testCircuitBreakerStartsClosed() {
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.CLOSED);
    assertThat(circuitBreaker.allowRequest()).isTrue();
  }

  @Test
  @Timeout(5)
  public void testCircuitBreakerTripsAfterThreshold() {
    // Circuit should be closed initially
    assertThat(circuitBreaker.allowRequest()).isTrue();

    // Record failures up to threshold
    JedisConnectionException error = new JedisConnectionException("Connection failed");
    circuitBreaker.recordFailure(error);
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.CLOSED);
    assertThat(circuitBreaker.allowRequest()).isTrue();

    circuitBreaker.recordFailure(error);
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.CLOSED);
    assertThat(circuitBreaker.allowRequest()).isTrue();

    // Third failure should trip the circuit
    circuitBreaker.recordFailure(error);
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.OPEN);
    assertThat(circuitBreaker.allowRequest()).isFalse();

    // Verify metric was recorded
    assertThat(
            registry
                .counter(
                    "cats.redisPriority.circuitBreaker.trip",
                    "name",
                    "test",
                    "reason",
                    "JedisConnectionException")
                .count())
        .isEqualTo(1);
  }

  @Test
  @Timeout(5)
  public void testCircuitBreakerTransitionsToHalfOpen() throws InterruptedException {
    // Trip the circuit
    JedisConnectionException error = new JedisConnectionException("Connection failed");
    for (int i = 0; i < 3; i++) {
      circuitBreaker.recordFailure(error);
    }
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.OPEN);
    assertThat(circuitBreaker.allowRequest()).isFalse();

    // Wait for cooldown period
    Thread.sleep(1100); // Cooldown is 1 second

    // Should transition to half-open and allow a probe request
    assertThat(circuitBreaker.allowRequest()).isTrue();
    assertThat(circuitBreaker.getState())
        .isEqualTo(PrioritySchedulerCircuitBreaker.State.HALF_OPEN);
  }

  @Test
  @Timeout(5)
  public void testCircuitBreakerRecovery() throws InterruptedException {
    // Trip the circuit
    JedisConnectionException error = new JedisConnectionException("Connection failed");
    for (int i = 0; i < 3; i++) {
      circuitBreaker.recordFailure(error);
    }
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.OPEN);

    // Wait for cooldown
    Thread.sleep(1100);

    // Transition to half-open
    assertThat(circuitBreaker.allowRequest()).isTrue();
    assertThat(circuitBreaker.getState())
        .isEqualTo(PrioritySchedulerCircuitBreaker.State.HALF_OPEN);

    // Successful probe should close the circuit
    circuitBreaker.recordSuccess();
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.CLOSED);
    assertThat(circuitBreaker.allowRequest()).isTrue();

    // Verify recovery metric was recorded
    assertThat(
            registry.counter("cats.redisPriority.circuitBreaker.recovery", "name", "test").count())
        .isEqualTo(1);
  }

  @Test
  @Timeout(5)
  public void testCircuitBreakerHalfOpenFailureReturnsToOpen() throws InterruptedException {
    // Trip the circuit
    JedisConnectionException error = new JedisConnectionException("Connection failed");
    for (int i = 0; i < 3; i++) {
      circuitBreaker.recordFailure(error);
    }

    // Wait for cooldown
    Thread.sleep(1100);

    // Transition to half-open
    assertThat(circuitBreaker.allowRequest()).isTrue();
    assertThat(circuitBreaker.getState())
        .isEqualTo(PrioritySchedulerCircuitBreaker.State.HALF_OPEN);

    // Failure during probe should return to open
    circuitBreaker.recordFailure(error);
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.OPEN);
    assertThat(circuitBreaker.allowRequest()).isFalse();
  }

  @Test
  @Timeout(5)
  public void testCircuitBreakerReset() {
    // Trip the circuit
    JedisConnectionException error = new JedisConnectionException("Connection failed");
    for (int i = 0; i < 3; i++) {
      circuitBreaker.recordFailure(error);
    }
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.OPEN);

    // Manual reset should close the circuit
    circuitBreaker.reset();
    assertThat(circuitBreaker.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.CLOSED);
    assertThat(circuitBreaker.allowRequest()).isTrue();
  }

  @Test
  @Timeout(5)
  public void testCircuitBreakerBlocksRequestsWhenOpen() {
    // Trip the circuit
    JedisConnectionException error = new JedisConnectionException("Connection failed");
    for (int i = 0; i < 3; i++) {
      circuitBreaker.recordFailure(error);
    }

    // Multiple requests should be blocked
    for (int i = 0; i < 10; i++) {
      assertThat(circuitBreaker.allowRequest()).isFalse();
    }

    // Verify blocked metric was recorded
    assertThat(
            registry.counter("cats.redisPriority.circuitBreaker.blocked", "name", "test").count())
        .isEqualTo(10);
  }

  @Test
  @Timeout(5)
  public void testCircuitBreakerStatusMessages() {
    // Closed state
    assertThat(circuitBreaker.getStatus()).contains("CLOSED").contains("0/3"); // failures/threshold

    // Record some failures
    JedisConnectionException error = new JedisConnectionException("Connection failed");
    circuitBreaker.recordFailure(error);
    circuitBreaker.recordFailure(error);
    assertThat(circuitBreaker.getStatus()).contains("CLOSED").contains("2/3");

    // Trip the circuit
    circuitBreaker.recordFailure(error);
    assertThat(circuitBreaker.getStatus()).contains("OPEN").contains("cooldown remaining");
  }

  @Test
  @Timeout(5)
  public void testCircuitBreakerStatistics() {
    // Allow some requests
    for (int i = 0; i < 5; i++) {
      assertThat(circuitBreaker.allowRequest()).isTrue();
    }

    // Trip the circuit
    JedisConnectionException error = new JedisConnectionException("Connection failed");
    for (int i = 0; i < 3; i++) {
      circuitBreaker.recordFailure(error);
    }

    // Block some requests
    for (int i = 0; i < 3; i++) {
      assertThat(circuitBreaker.allowRequest()).isFalse();
    }

    // Check statistics
    PrioritySchedulerCircuitBreaker.CircuitBreakerStats stats = circuitBreaker.getStats();
    assertThat(stats.getName()).isEqualTo("test");
    assertThat(stats.getState()).isEqualTo(PrioritySchedulerCircuitBreaker.State.OPEN);
    assertThat(stats.getFailureCount()).isEqualTo(3);
    assertThat(stats.getTotalAllowed()).isEqualTo(5);
    assertThat(stats.getTotalBlocked()).isEqualTo(3);
  }
}
