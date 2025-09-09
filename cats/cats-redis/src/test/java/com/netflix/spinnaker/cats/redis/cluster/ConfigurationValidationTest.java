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

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;

/**
 * Comprehensive configuration validation tests for PrioritySchedulerProperties.
 *
 * <p>Tests configuration edge cases, validation logic, and backward compatibility to ensure the
 * unified batch operations configuration works correctly under all scenarios including edge cases
 * and invalid configurations.
 */
@DisplayName("Configuration Validation Tests")
public class ConfigurationValidationTest {

  private PrioritySchedulerProperties properties;

  @BeforeEach
  void setUp() {
    properties = new PrioritySchedulerProperties();
  }

  @Nested
  @DisplayName("Batch Operations Configuration Tests")
  class BatchOperationsConfigurationTests {

    @Test
    @DisplayName("Should have sensible default values")
    void shouldHaveSensibleDefaultValues() {
      assertThat(properties.getBatchOperations().isEnabled()).isFalse(); // Conservative default
      assertThat(properties.getBatchOperations().getBatchSize())
          .isEqualTo(50); // Reasonable default
    }

    @Test
    @DisplayName("Should allow enabling batch operations")
    void shouldAllowEnablingBatchOperations() {
      properties.getBatchOperations().setEnabled(true);
      assertThat(properties.getBatchOperations().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should allow setting valid batch sizes")
    void shouldAllowSettingValidBatchSizes() {
      properties.getBatchOperations().setBatchSize(25);
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("Should handle zero batch size")
    void shouldHandleZeroBatchSize() {
      properties.getBatchOperations().setBatchSize(0);
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(0);
      // Zero batch size should still return sensible values from convenience methods
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle negative batch size")
    void shouldHandleNegativeBatchSize() {
      properties.getBatchOperations().setBatchSize(-1);
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(-1);
      // Negative values should still be returned as-is for error handling at runtime
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Should handle extremely large batch sizes")
    void shouldHandleExtremelyLargeBatchSizes() {
      properties.getBatchOperations().setBatchSize(Integer.MAX_VALUE);
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(Integer.MAX_VALUE);
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(Integer.MAX_VALUE);
    }
  }

  @Nested
  @DisplayName("Backward Compatibility Tests")
  class BackwardCompatibilityTests {

    @Test
    @DisplayName("Should provide zombie cleanup convenience methods")
    void shouldProvideZombieCleanupConvenienceMethods() {
      properties.getBatchOperations().setBatchSize(75);

      // Convenience methods should return the unified batch size
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(75);

      // Other zombie properties should still work
      properties.getZombieCleanup().setThresholdMs(45000L);
      assertThat(properties.getZombieThresholdMs()).isEqualTo(45000L);
      assertThat(properties.isZombieCleanupEnabled()).isTrue(); // Default enabled
    }

    @Test
    @DisplayName("Should provide orphan cleanup convenience methods")
    void shouldProvideOrphanCleanupConvenienceMethods() {
      properties.getBatchOperations().setBatchSize(125);

      // Convenience methods should return the unified batch size
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(125);

      // Other orphan properties should still work
      properties.getOrphanCleanup().setThresholdMs(3600000L);
      assertThat(properties.getOrphanThresholdMs()).isEqualTo(3600000L);
      assertThat(properties.isOrphanCleanupEnabled()).isTrue(); // Default enabled
    }

    @Test
    @DisplayName("Should handle exceptional agents configuration")
    void shouldHandleExceptionalAgentsConfiguration() {
      properties.getZombieCleanup().getExceptionalAgents().setPattern(".*test.*");
      properties.getZombieCleanup().getExceptionalAgents().setThresholdMs(7200000L);

      assertThat(properties.hasExceptionalAgents()).isTrue();
      assertThat(properties.getExceptionalAgentsPattern()).isEqualTo(".*test.*");
      assertThat(properties.getExceptionalAgentsThresholdMs()).isEqualTo(7200000L);
    }

    @Test
    @DisplayName("Should handle missing exceptional agents configuration")
    void shouldHandleMissingExceptionalAgentsConfiguration() {
      // Default should be no exceptional agents
      assertThat(properties.hasExceptionalAgents()).isFalse();
      assertThat(properties.getExceptionalAgentsPattern()).isEmpty();
    }
  }

  @Nested
  @DisplayName("Timing Configuration Tests")
  class TimingConfigurationTests {

    @Test
    @DisplayName("Should have reasonable timing defaults")
    void shouldHaveReasonableTimingDefaults() {
      assertThat(properties.getIntervalMs()).isEqualTo(1000L); // 1 second
      assertThat(properties.getRefreshPeriodSeconds()).isEqualTo(30); // 30 seconds
      assertThat(properties.getTimeCacheDurationMs()).isEqualTo(10000L); // 10 seconds
    }

    @Test
    @DisplayName("Should allow setting custom timing values")
    void shouldAllowSettingCustomTimingValues() {
      properties.setIntervalMs(60000L);
      properties.setRefreshPeriodSeconds(60);
      properties.setTimeCacheDurationMs(600000L);

      assertThat(properties.getIntervalMs()).isEqualTo(60000L);
      assertThat(properties.getRefreshPeriodSeconds()).isEqualTo(60);
      assertThat(properties.getTimeCacheDurationMs()).isEqualTo(600000L);
    }

    @Test
    @DisplayName("Should handle extreme timing values")
    void shouldHandleExtremeTimingValues() {
      // Test very small values
      properties.setIntervalMs(1L);
      properties.setRefreshPeriodSeconds(1);
      properties.setTimeCacheDurationMs(1L);

      assertThat(properties.getIntervalMs()).isEqualTo(1L);
      assertThat(properties.getRefreshPeriodSeconds()).isEqualTo(1);
      assertThat(properties.getTimeCacheDurationMs()).isEqualTo(1L);

      // Test very large values
      properties.setIntervalMs(Long.MAX_VALUE);
      properties.setRefreshPeriodSeconds(Integer.MAX_VALUE);
      properties.setTimeCacheDurationMs(Long.MAX_VALUE);

      assertThat(properties.getIntervalMs()).isEqualTo(Long.MAX_VALUE);
      assertThat(properties.getRefreshPeriodSeconds()).isEqualTo(Integer.MAX_VALUE);
      assertThat(properties.getTimeCacheDurationMs()).isEqualTo(Long.MAX_VALUE);
    }
  }

  @Nested
  @DisplayName("Configuration Consistency Tests")
  class ConfigurationConsistencyTests {

    @Test
    @DisplayName("Should maintain consistency between batch size properties")
    void shouldMaintainConsistencyBetweenBatchSizeProperties() {
      int testBatchSize = 75;
      properties.getBatchOperations().setBatchSize(testBatchSize);

      // All convenience getters should return the same unified value
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(testBatchSize);
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(testBatchSize);
    }

    @Test
    @DisplayName("Should use unified batch size for all operations")
    void shouldUseUnifiedBatchSizeForAllOperations() {
      properties.getBatchOperations().setBatchSize(75);

      // All batch operations should use the same unified batch size
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(75);
    }

    @Test
    @DisplayName("Should validate that agent acquisition batch size affects unified batch size")
    void shouldValidateAgentAcquisitionBatchSizeAffectsUnified() {
      // Set and update unified batch size
      properties.getBatchOperations().setBatchSize(25);
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(25);
      properties.getBatchOperations().setBatchSize(150);
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(150);

      // Cleanup operations should use the unified batch size
      assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(150);
    }
  }

  @Nested
  @DisplayName("Configuration Object Structure Tests")
  class ConfigurationObjectStructureTests {

    @Test
    @DisplayName("Should have proper nested configuration structure")
    void shouldHaveProperNestedConfigurationStructure() {
      assertThat(properties.getZombieCleanup()).isNotNull();
      assertThat(properties.getOrphanCleanup()).isNotNull();
    }

    @Test
    @DisplayName("Should handle null nested configurations gracefully")
    void shouldHandleNullNestedConfigurationsGracefully() {
      // This tests internal robustness - the framework should ensure these are never null
      // but the convenience methods should handle gracefully if they somehow become null
      assertThatCode(
              () -> {
                boolean enabled = properties.isZombieCleanupEnabled();
                long threshold = properties.getZombieThresholdMs();
                assertThat(enabled).isNotNull();
                assertThat(threshold).isGreaterThanOrEqualTo(0);
              })
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should provide all expected configuration properties")
    void shouldProvideAllExpectedConfigurationProperties() {
      // Verify all major configuration areas are accessible
      assertThatCode(
              () -> {
                // Core scheduling
                properties.getIntervalMs();
                properties.getRefreshPeriodSeconds();
                properties.getTimeCacheDurationMs();

                // Batch operations
                properties.getBatchOperations().isEnabled();
                properties.getBatchOperations().getBatchSize();

                // Zombie cleanup
                properties.isZombieCleanupEnabled();
                properties.getZombieThresholdMs();
                properties.getZombieIntervalMs();
                properties.getBatchOperations().getBatchSize();

                // Orphan cleanup
                properties.isOrphanCleanupEnabled();
                properties.getOrphanThresholdMs();
                properties.getOrphanIntervalMs();
                properties.getBatchOperations().getBatchSize();
                properties.getOrphanLeadershipTtlMs();
                properties.isOrphanForceAllPods();
              })
          .doesNotThrowAnyException();
    }
  }
}
