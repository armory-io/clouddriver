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

import com.google.common.base.Preconditions;
import java.util.concurrent.Semaphore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/** Utility methods for the Priority Redis Scheduler. */
@Slf4j
final class SchedulerUtils {

  private SchedulerUtils() {
    // Utility class
  }

  /**
   * Safe null-checked getter with default value.
   *
   * @param value The value to check
   * @param defaultValue Default value if null
   * @return The value or default
   */
  @Nonnull
  static <T> T getOrDefault(@Nullable T value, @Nonnull T defaultValue) {
    Preconditions.checkNotNull(defaultValue, "Default value cannot be null");
    return value != null ? value : defaultValue;
  }

  /**
   * Safe semaphore release with null checking.
   *
   * @param semaphore The semaphore to release (may be null)
   * @param permits Number of permits to release
   */
  static void safeRelease(@Nullable Semaphore semaphore, int permits) {
    if (semaphore != null && permits > 0) {
      try {
        semaphore.release(permits);
      } catch (Exception e) {
        log.warn("Failed to release {} semaphore permits: {}", permits, e.getMessage());
      }
    }
  }

  /**
   * Get current time in milliseconds (centralized for easier testing/mocking).
   *
   * @return Current time in milliseconds
   */
  static long currentTimeMillis() {
    return System.currentTimeMillis();
  }

  /**
   * Check if a time period has elapsed.
   *
   * @param lastTimeMs Last time in milliseconds
   * @param periodMs Period in milliseconds
   * @return true if period has elapsed
   */
  static boolean isPeriodElapsed(long lastTimeMs, long periodMs) {
    return (currentTimeMillis() - lastTimeMs) >= periodMs;
  }
}
