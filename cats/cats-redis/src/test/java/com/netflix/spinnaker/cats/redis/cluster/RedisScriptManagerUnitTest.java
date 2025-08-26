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
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@DisplayName("RedisScriptManager unit tests")
class RedisScriptManagerUnitTest {

  private static class FakePool extends JedisPool {
    private final Jedis jedis;

    FakePool(Jedis j) {
      this.jedis = j;
    }

    @Override
    public Jedis getResource() {
      return jedis;
    }
  }

  @Test
  @DisplayName(
      "evalshaWithSelfHeal records reloads and eval metrics on NOSCRIPT, falls back to EVAL")
  void evalshaSelfHealAndEvalFallback() {
    // Fake Jedis: scriptLoad loads; evalsha throws NOSCRIPT; eval succeeds
    class FakeJedis extends Jedis {
      @Override
      public String scriptLoad(String script) {
        return "sha";
      }

      @Override
      public Object evalsha(String sha1, java.util.List<String> keys, java.util.List<String> args) {
        throw new redis.clients.jedis.exceptions.JedisDataException("NOSCRIPT No matching script");
      }

      @Override
      public Object eval(String script, java.util.List<String> keys, java.util.List<String> args) {
        return 1L;
      }
    }

    FakeJedis j = new FakeJedis();
    Registry registry = new DefaultRegistry();
    PrioritySchedulerMetrics metrics = new PrioritySchedulerMetrics(registry);
    RedisScriptManager mgr = new RedisScriptManager(new FakePool(j), metrics);

    // Ensure initialized so getScriptSha works after loadAllScripts
    mgr.initializeScripts();

    Object r =
        mgr.evalshaWithSelfHeal(
            j,
            RedisScriptManager.ADD_AGENT,
            Arrays.asList("working", "waiting"),
            Arrays.asList("a", "1"));
    assertThat(r).isEqualTo(1L);

    // Verify at least latency timer recorded (sum across tags)
    long timerSum = 0L;
    for (com.netflix.spectator.api.Meter m : registry) {
      if (m.id().name().equals("cats.redisPriority.scripts.latency")) {
        for (com.netflix.spectator.api.Measurement ms : m.measure()) {
          timerSum += (long) ms.value();
        }
      }
    }
    assertThat(timerSum).isGreaterThanOrEqualTo(1L);
  }
}
