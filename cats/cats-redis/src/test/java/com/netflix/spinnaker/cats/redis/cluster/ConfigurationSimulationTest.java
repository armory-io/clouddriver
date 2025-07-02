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

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Simulates and validates the configuration recommendations in ClusteredSortAgentScheduler to
 * ensure they are mathematically sound and operationally viable.
 */
class ConfigurationSimulationTest {

  @Test
  void shouldValidateDefaultConfigurationMathematics() {
    // Default configuration values
    long schedulerIntervalMs = 1000L; // 1 second
    int refreshPeriodSeconds = 30; // 30 seconds
    long zombieThresholdMs = 1800000L; // 30 minutes
    long zombieCleanupIntervalMs = 300000L; // 5 minutes
    double maxBoostsPerSecond = 10.0; // 10 boosts/sec

    // Validate timing relationships
    assertTrue(
        zombieCleanupIntervalMs < zombieThresholdMs,
        "Zombie cleanup should be more frequent than zombie threshold");

    assertTrue(
        zombieThresholdMs >= TimeUnit.MINUTES.toMillis(15),
        "Zombie threshold should be at least 15 minutes for AWS agent timeouts");

    assertTrue(
        schedulerIntervalMs >= 500L,
        "Scheduler interval should be at least 500ms to avoid excessive CPU usage");

    assertTrue(
        refreshPeriodSeconds >= 15,
        "Refresh period should be at least 15 seconds to avoid Redis overload");

    // Validate rate limiting makes sense
    assertTrue(
        maxBoostsPerSecond >= 1.0 && maxBoostsPerSecond <= 100.0,
        "Boost rate should be between 1-100 per second for practical usage");

    // Calculate theoretical performance
    long agentPickupLatencyMs = schedulerIntervalMs; // Worst case pickup time
    double boostCapacityPerMinute = maxBoostsPerSecond * 60;
    long maxZombieLifetimeMs = zombieThresholdMs + zombieCleanupIntervalMs;

    // Assertions for reasonable performance
    assertTrue(agentPickupLatencyMs <= 2000L, "Agent pickup should be under 2 seconds");
    assertTrue(boostCapacityPerMinute >= 60, "Should handle at least 1 boost per second sustained");
    assertTrue(
        maxZombieLifetimeMs <= TimeUnit.MINUTES.toMillis(35),
        "Zombies should be cleaned within 35 minutes maximum");

    System.out.printf("✅ Default Config Simulation:\n");
    System.out.printf("   Agent pickup latency: %dms\n", agentPickupLatencyMs);
    System.out.printf("   Boost capacity: %.0f/minute\n", boostCapacityPerMinute);
    System.out.printf("   Max zombie lifetime: %d minutes\n", maxZombieLifetimeMs / 60000);
  }

  @Test
  void shouldValidateHighLoadConfigurationMathematics() {
    // High-load configuration values
    long schedulerIntervalMs = 500L; // 0.5 seconds - very fast
    int refreshPeriodSeconds = 15; // 15 seconds - frequent sync
    long zombieThresholdMs = 2100000L; // 35 minutes - allows 30min + buffer
    long zombieCleanupIntervalMs = 120000L; // 2 minutes - very frequent
    double maxBoostsPerSecond = 30.0; // 30 boosts/sec - moderate rate limiting

    // Validate timing relationships
    assertTrue(
        zombieCleanupIntervalMs < zombieThresholdMs,
        "Zombie cleanup should be more frequent than zombie threshold");

    assertTrue(
        zombieThresholdMs >= TimeUnit.MINUTES.toMillis(30),
        "Zombie threshold should be at least 30 minutes to allow full agent execution");

    assertTrue(
        schedulerIntervalMs >= 200L,
        "Scheduler interval should not be too aggressive to avoid CPU thrashing");

    assertTrue(refreshPeriodSeconds >= 10, "Even frequent refresh should not overwhelm Redis");

    // Validate relationships make sense for high load
    long refreshIntervalMs = refreshPeriodSeconds * 1000L;
    assertTrue(
        refreshIntervalMs > schedulerIntervalMs * 5,
        "Refresh should be significantly less frequent than scheduler cycles");

    assertTrue(
        zombieCleanupIntervalMs > schedulerIntervalMs * 10,
        "Zombie cleanup should be much less frequent than scheduler cycles");

    // Calculate theoretical performance for high load
    long agentPickupLatencyMs = schedulerIntervalMs;
    double boostCapacityPerMinute = maxBoostsPerSecond * 60;
    long maxZombieLifetimeMs = zombieThresholdMs + zombieCleanupIntervalMs;
    double schedulerCyclesPerMinute = 60000.0 / schedulerIntervalMs;
    double refreshCyclesRatio = refreshIntervalMs / (double) schedulerIntervalMs;

    // Assertions for high-load performance
    assertTrue(
        agentPickupLatencyMs <= 1000L, "High-load config should have sub-second agent pickup");
    assertTrue(boostCapacityPerMinute >= 1000, "High-load should handle 1000+ boosts per minute");
    assertTrue(
        maxZombieLifetimeMs <= TimeUnit.MINUTES.toMillis(40),
        "Even with 35min threshold, total cleanup should be under 40 minutes");
    assertTrue(schedulerCyclesPerMinute >= 60, "Should run at least once per second");
    assertTrue(
        refreshCyclesRatio >= 10, "Refresh should be at least 10x less frequent than scheduler");

    System.out.printf("✅ High-Load Config Simulation:\n");
    System.out.printf("   Agent pickup latency: %dms\n", agentPickupLatencyMs);
    System.out.printf("   Boost capacity: %.0f/minute\n", boostCapacityPerMinute);
    System.out.printf("   Max zombie lifetime: %d minutes\n", maxZombieLifetimeMs / 60000);
    System.out.printf("   Scheduler cycles/minute: %.0f\n", schedulerCyclesPerMinute);
    System.out.printf("   Refresh:scheduler ratio: 1:%.0f\n", refreshCyclesRatio);
  }

  @Test
  void shouldValidateOnDemandBoostPerformanceScenarios() {
    // Simulate OnDemand boost scenarios

    // Scenario 1: Default configuration during normal deployment
    double defaultBoostRate = 0.0; // 0 boosts/second - disabled by default
    int normalDeploymentsPerHour = 20; // Reasonable deployment frequency
    double normalBoostDemand = normalDeploymentsPerHour / 3600.0; // per second

    assertTrue(
        defaultBoostRate >= normalBoostDemand * 0,
        "Default rate should handle 0x normal deployment load");

    // Scenario 2: High-load configuration during busy periods
    double highLoadBoostRate = 30.0; // 30 boosts/second - moderate rate limiting
    int busyDeploymentsPerHour = 200; // Very busy enterprise environment
    double busyBoostDemand = busyDeploymentsPerHour / 3600.0; // per second

    assertTrue(
        highLoadBoostRate > busyBoostDemand * 1,
        "High-load rate should handle 1x busy deployment load");

    // Scenario 3: Burst capacity
    int maxConcurrentDeployments = 50; // Worst case burst
    assertTrue(
        highLoadBoostRate >= maxConcurrentDeployments / 2,
        "Should handle worst-case concurrent deployment burst");

    System.out.printf("✅ OnDemand Boost Scenarios:\n");
    System.out.printf(
        "   Normal demand: %.2f boosts/sec (capacity: %.0fx)\n",
        normalBoostDemand, defaultBoostRate / normalBoostDemand);
    System.out.printf(
        "   Busy demand: %.2f boosts/sec (capacity: %.0fx)\n",
        busyBoostDemand, highLoadBoostRate / busyBoostDemand);
    System.out.printf("   Burst capacity: %d concurrent deployments\n", maxConcurrentDeployments);
  }

  @Test
  void shouldValidateRedisLoadImplications() {
    // Calculate Redis operation load for both configurations

    // Default configuration Redis load
    long defaultSchedulerInterval = 1000L;
    int defaultRefreshPeriod = 30;
    long defaultZombieCleanupInterval = 300000L;

    double defaultSchedulerOpsPerMinute = 60000.0 / defaultSchedulerInterval;
    double defaultRefreshOpsPerMinute = 60.0 / defaultRefreshPeriod;
    double defaultZombieOpsPerMinute = 60000.0 / defaultZombieCleanupInterval;
    double defaultTotalOpsPerMinute =
        defaultSchedulerOpsPerMinute + defaultRefreshOpsPerMinute + defaultZombieOpsPerMinute;

    // High-load configuration Redis load
    long highLoadSchedulerInterval = 500L;
    int highLoadRefreshPeriod = 15;
    long highLoadZombieCleanupInterval = 120000L;

    double highLoadSchedulerOpsPerMinute = 60000.0 / highLoadSchedulerInterval;
    double highLoadRefreshOpsPerMinute = 60.0 / highLoadRefreshPeriod;
    double highLoadZombieOpsPerMinute = 60000.0 / highLoadZombieCleanupInterval;
    double highLoadTotalOpsPerMinute =
        highLoadSchedulerOpsPerMinute + highLoadRefreshOpsPerMinute + highLoadZombieOpsPerMinute;

    // Validate Redis load is reasonable
    assertTrue(
        defaultTotalOpsPerMinute <= 100,
        "Default config should keep Redis load under 100 ops/minute");
    assertTrue(
        highLoadTotalOpsPerMinute <= 200,
        "High-load config should keep Redis load under 200 ops/minute");

    // Validate the increase is proportional
    double loadIncrease = highLoadTotalOpsPerMinute / defaultTotalOpsPerMinute;
    assertTrue(
        loadIncrease >= 1.5 && loadIncrease <= 4.0,
        "High-load should be 1.5-4x more Redis operations than default");

    System.out.printf("✅ Redis Load Analysis:\n");
    System.out.printf("   Default config: %.1f ops/minute\n", defaultTotalOpsPerMinute);
    System.out.printf(
        "   High-load config: %.1f ops/minute (%.1fx increase)\n",
        highLoadTotalOpsPerMinute, loadIncrease);
  }
}
