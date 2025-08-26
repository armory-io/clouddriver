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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PrioritySchedulerMetricsConfiguration wiring test")
class PrioritySchedulerMetricsConfigurationTest {

  @Test
  @DisplayName("Bean factory creates PrioritySchedulerMetrics with provided Registry")
  void beanCreatesMetrics() {
    Registry registry = new DefaultRegistry();
    PrioritySchedulerMetricsConfiguration cfg = new PrioritySchedulerMetricsConfiguration();
    PrioritySchedulerMetrics metrics = cfg.prioritySchedulerMetrics(registry);
    assertThat(metrics).isNotNull();
  }
}
