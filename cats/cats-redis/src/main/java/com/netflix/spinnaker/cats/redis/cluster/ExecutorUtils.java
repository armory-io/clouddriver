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

import lombok.extern.slf4j.Slf4j;

/**
 * Utility helpers for creating executors used by the Priority Redis Scheduler.
 *
 * <p>Provides factories that minimize idle threads and standardize thread naming. Keeps the class
 * package-private to limit API surface.
 */
@Slf4j
final class ExecutorUtils {
  private ExecutorUtils() {}

  /**
   * Create an on-demand single-thread executor: no core threads, one max thread, configurable
   * keep-alive, and daemon threads with a friendly name. The thread is created only when a task is
   * submitted and will be terminated after the idle period, keeping thread metrics clean during
   * idle windows.
   */
  static java.util.concurrent.ExecutorService newOnDemandSingleThreadExecutor(
      String threadNamePattern, long keepAliveMs) {
    java.util.concurrent.ThreadPoolExecutor exec =
        new java.util.concurrent.ThreadPoolExecutor(
            0,
            1,
            Math.max(1L, keepAliveMs),
            java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.SynchronousQueue<>(),
            r -> {
              Thread t = new Thread(r, threadNamePattern.replace("#", "0"));
              t.setDaemon(true);
              t.setUncaughtExceptionHandler(
                  (thread, throwable) ->
                      log.error(
                          "Uncaught exception in {}: {}",
                          thread.getName(),
                          String.valueOf(throwable.getMessage()),
                          throwable));
              return t;
            });
    exec.allowCoreThreadTimeOut(true);
    return exec;
  }
}
