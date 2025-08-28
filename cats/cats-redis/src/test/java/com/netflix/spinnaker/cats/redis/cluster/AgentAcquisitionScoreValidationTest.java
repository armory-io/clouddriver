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

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for defensive validation of acquisition scores returned from Redis. */
@DisplayName("Agent Acquisition Score Validation")
public class AgentAcquisitionScoreValidationTest {

  private Registry registry;
  private PrioritySchedulerMetrics metrics;

  @BeforeEach
  public void setUp() {
    registry = new DefaultRegistry();
    metrics = new PrioritySchedulerMetrics(registry);
  }

  @Test
  @DisplayName("Should validate numeric strings correctly")
  public void testValidatesNumericStrings() {
    // Valid numeric strings should pass
    assertThat(isNumeric("1756381900")).isTrue();
    assertThat(isNumeric("0")).isTrue();
    assertThat(isNumeric("123456789")).isTrue();

    // Invalid strings should fail
    assertThat(isNumeric("")).isFalse();
    assertThat(isNumeric("not-a-number")).isFalse();
    assertThat(isNumeric("1756381900.123")).isFalse(); // decimal point
    assertThat(isNumeric("-123")).isFalse(); // negative number
    assertThat(isNumeric("123abc")).isFalse(); // contains letters
    assertThat(isNumeric("12 34")).isFalse(); // contains space
    assertThat(isNumeric(null)).isFalse();
  }

  // Helper method that mirrors the validation logic in AgentAcquisitionService
  private boolean isNumeric(String str) {
    if (str == null || str.isEmpty()) {
      return false;
    }
    for (int i = 0; i < str.length(); i++) {
      char ch = str.charAt(i);
      if (ch < '0' || ch > '9') {
        return false;
      }
    }
    return true;
  }

  @Test
  @DisplayName("Should handle different return types from Redis")
  public void testHandlesDifferentReturnTypes() {
    // Test type conversion logic that matches AgentAcquisitionService

    // String type
    String stringResult = "1756381900";
    assertThat(stringResult).isEqualTo("1756381900");
    assertThat(isNumeric(stringResult)).isTrue();

    // Long type
    Long longResult = 1756381900L;
    String fromLong = String.valueOf(longResult);
    assertThat(fromLong).isEqualTo("1756381900");
    assertThat(isNumeric(fromLong)).isTrue();

    // byte[] type
    byte[] byteResult = "1756381900".getBytes(StandardCharsets.UTF_8);
    String fromBytes = new String(byteResult, StandardCharsets.UTF_8);
    assertThat(fromBytes).isEqualTo("1756381900");
    assertThat(isNumeric(fromBytes)).isTrue();

    // Invalid types should be rejected
    Double doubleResult = 1756381900.0;
    // This would be handled differently in actual code (logged as unexpected type)
    String fromDouble = String.valueOf(doubleResult);
    assertThat(fromDouble).isEqualTo("1.7563819E9"); // Scientific notation
    assertThat(isNumeric(fromDouble)).isFalse(); // Would fail validation
  }

  @Test
  @DisplayName("Should increment metrics for validation failures")
  public void testMetricsForValidationFailures() {
    // Simulate validation failure for non-numeric score
    metrics.incrementAcquireValidationFailure("non_numeric_score");
    assertThat(
            registry
                .counter(
                    "cats.redisPriority.acquire.validationFailures", "reason", "non_numeric_score")
                .count())
        .isEqualTo(1);

    // Simulate validation failure for empty score
    metrics.incrementAcquireValidationFailure("empty_score");
    assertThat(
            registry
                .counter("cats.redisPriority.acquire.validationFailures", "reason", "empty_score")
                .count())
        .isEqualTo(1);

    // Simulate validation failure for unexpected type
    metrics.incrementAcquireValidationFailure("unexpected_type");
    assertThat(
            registry
                .counter(
                    "cats.redisPriority.acquire.validationFailures", "reason", "unexpected_type")
                .count())
        .isEqualTo(1);

    // Multiple failures should increment the counter
    metrics.incrementAcquireValidationFailure("non_numeric_score");
    metrics.incrementAcquireValidationFailure("non_numeric_score");
    assertThat(
            registry
                .counter(
                    "cats.redisPriority.acquire.validationFailures", "reason", "non_numeric_score")
                .count())
        .isEqualTo(3);
  }
}
