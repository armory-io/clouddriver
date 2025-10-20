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
import java.lang.reflect.Field;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PrioritySchedulerMetrics unit tests")
class PrioritySchedulerMetricsUnitTest {

  @Test
  @DisplayName("registerGauges is idempotent (second call is a no-op)")
  void registerGaugesIdempotent() throws Exception {
    Registry registry = new DefaultRegistry();
    PrioritySchedulerMetrics m = new PrioritySchedulerMetrics(registry);

    Supplier<Number> s0 = () -> 0;
    Supplier<Number> s1 = () -> 1;

    // First registration
    m.registerGauges(null, s0, s0, s0, s0, s0, s0, s0, s0, s0, s0, s0, s0);

    // Reflect the internal flag
    Field f = PrioritySchedulerMetrics.class.getDeclaredField("gaugesRegistered");
    f.setAccessible(true);
    boolean first = (boolean) f.get(m);
    assertThat(first).isTrue();

    // Second registration with different suppliers should be a no-op and not throw
    m.registerGauges(null, s1, s1, s1, s1, s1, s1, s1, s1, s1, s1, s1, s1);
    boolean second = (boolean) f.get(m);
    assertThat(second).isTrue();
  }
}
