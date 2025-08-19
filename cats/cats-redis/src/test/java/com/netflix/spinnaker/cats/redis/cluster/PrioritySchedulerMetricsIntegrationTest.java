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
import org.junit.jupiter.api.Test;

class PrioritySchedulerMetricsIntegrationTest {

  @Test
  void countersAndTimersIncrement() {
    Registry registry = new DefaultRegistry();
    PrioritySchedulerMetrics metrics = new PrioritySchedulerMetrics(registry);

    metrics.recordRunCycle(true, 12);
    metrics.incrementRunFailure("IllegalStateException");
    metrics.incrementAcquireAttempts();
    metrics.incrementAcquired(5);
    metrics.recordAcquireTime("batch", 7);
    metrics.incrementSubmissionFailure("RejectedExecutionException");
    metrics.incrementBatchFallback();
    metrics.recordRepopulateTime(4);
    metrics.incrementRepopulateAdded(3);
    metrics.incrementRepopulateError("JedisConnectionException");
    metrics.recordCleanupTime("zombie", 11);
    metrics.incrementCleanupCleaned("zombie", 2);
    metrics.recordScriptEval("ADD_AGENTS", 6);
    metrics.incrementScriptError("ADD_AGENTS", "NOSCRIPT");
    metrics.incrementScriptsReload();

    assertThat(registry.counter("cats.redisPriority.acquire.attempts").count())
        .isGreaterThanOrEqualTo(1);
    assertThat(registry.counter("cats.redisPriority.acquire.acquired").count())
        .isGreaterThanOrEqualTo(5);
    assertThat(registry.counter("cats.redisPriority.batch.fallbacks").count())
        .isGreaterThanOrEqualTo(1);
    assertThat(registry.counter("cats.redisPriority.repopulate.added").count())
        .isGreaterThanOrEqualTo(3);
    assertThat(registry.counter("cats.redisPriority.scripts.reloads").count())
        .isGreaterThanOrEqualTo(1);
    assertThat(registry.counter("cats.redisPriority.run.failures").count())
        .isGreaterThanOrEqualTo(0);
  }
}
