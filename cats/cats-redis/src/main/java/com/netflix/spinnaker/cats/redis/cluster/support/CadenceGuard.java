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

package com.netflix.spinnaker.cats.redis.cluster.support;

/**
 * Cadence and budget utilities for the Priority Redis Scheduler.
 *
 * <p>Responsibilities: - Time guards for cadence checks and budget deadlines - Single, testable
 * access to current time via nowMs()
 *
 * <p>Non-responsibilities: - Redis time/clock coordination (use RedisTimeUtils) - Thread/executor
 * utilities (use ExecutorUtils) - Script result parsing (use ScriptResults)
 */
public final class CadenceGuard {
  private CadenceGuard() {}

  /** Return current wall-clock time in milliseconds. */
  public static long nowMs() {
    return System.currentTimeMillis();
  }

  public static boolean isPeriodElapsed(long lastEpochMs, long periodMs) {
    long now = nowMs();
    return now - lastEpochMs >= Math.max(1L, periodMs);
  }

  public static boolean overBudget(long startEpochMs, long budgetMs) {
    return budgetMs > 0 && (nowMs() - startEpochMs) > budgetMs;
  }
}
