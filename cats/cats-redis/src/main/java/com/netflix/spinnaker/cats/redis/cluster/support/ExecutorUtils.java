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

import lombok.extern.slf4j.Slf4j;

/**
 * Executor helpers for the Priority Redis Scheduler.
 *
 * <p>Responsibilities: - Provide named executors with daemon threads and consistent error handling
 * - Avoid idle thread bloat via on-demand single-thread executors
 *
 * <p>Non-responsibilities: - Cadence or time logic (use CadenceGuard/RedisTimeUtils) - Redis
 * script/result handling (use RedisScriptManager/ScriptResults)
 */
@Slf4j
public final class ExecutorUtils {
  private ExecutorUtils() {}

  /**
   * Create an on-demand single-thread executor: no core threads, one max thread, configurable
   * keep-alive, and daemon threads with a friendly name. The thread is created only when a task is
   * submitted and will be terminated after the idle period, keeping thread metrics clean during
   * idle windows.
   */
  public static java.util.concurrent.ExecutorService newOnDemandSingleThreadExecutor(
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

  /**
   * Create a cached thread pool with a named thread factory.
   *
   * <p>Threads are daemon and use the provided name format (e.g., "PriorityAgentWorker-%d").
   */
  public static java.util.concurrent.ExecutorService newNamedCachedThreadPool(String nameFormat) {
    com.google.common.util.concurrent.ThreadFactoryBuilder builder =
        new com.google.common.util.concurrent.ThreadFactoryBuilder()
            .setNameFormat(nameFormat)
            .setDaemon(true)
            .setUncaughtExceptionHandler(
                (thread, throwable) ->
                    log.error(
                        "Uncaught exception in {}: {}",
                        thread.getName(),
                        String.valueOf(throwable.getMessage()),
                        throwable));
    return java.util.concurrent.Executors.newCachedThreadPool(builder.build());
  }

  /**
   * Create a single-thread scheduled executor with a named thread factory.
   *
   * <p>Thread is daemon and uses the provided name format (e.g., "PriorityAgentScheduler-%d").
   */
  public static java.util.concurrent.ScheduledExecutorService newNamedSingleThreadScheduledExecutor(
      String nameFormat) {
    com.google.common.util.concurrent.ThreadFactoryBuilder builder =
        new com.google.common.util.concurrent.ThreadFactoryBuilder()
            .setNameFormat(nameFormat)
            .setDaemon(true)
            .setUncaughtExceptionHandler(
                (thread, throwable) ->
                    log.error(
                        "Uncaught exception in {}: {}",
                        thread.getName(),
                        String.valueOf(throwable.getMessage()),
                        throwable));
    return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(builder.build());
  }
}
