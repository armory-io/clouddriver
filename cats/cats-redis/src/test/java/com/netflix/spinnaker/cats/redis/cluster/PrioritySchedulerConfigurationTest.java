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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Test suite for PrioritySchedulerConfiguration.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Thread pool configuration and creation
 *   <li>Semaphore setup for concurrency control
 *   <li>Pattern compilation for agent filtering
 *   <li>Configuration validation and defaults
 *   <li>Resource management and cleanup
 *   <li>Performance characteristics
 * </ul>
 */
@DisplayName("PriorityConfiguration Tests")
class PrioritySchedulerConfigurationTest {

  private PriorityAgentProperties agentProperties;
  private PrioritySchedulerProperties schedulerProperties;
  private PrioritySchedulerConfiguration configuration;

  @BeforeEach
  void setUp() {
    agentProperties = new PriorityAgentProperties();
    agentProperties.setMaxConcurrentAgents(10);
    agentProperties.setEnabledPattern(".*");
    agentProperties.setDisabledPattern("");

    schedulerProperties = new PrioritySchedulerProperties();
    schedulerProperties.setIntervalMs(1000L);
    schedulerProperties.setRefreshPeriodSeconds(30);
    schedulerProperties.getKeys().setWaitingSet("waiting");
    schedulerProperties.getKeys().setWorkingSet("working");
    schedulerProperties.getKeys().setCleanupLeaderKey("cleanup-leader");

    // Setup zombie cleanup configuration
    schedulerProperties.getZombieCleanup().setEnabled(true);
    schedulerProperties.getZombieCleanup().setThresholdMs(30000L);
    schedulerProperties.getZombieCleanup().setIntervalMs(10000L);

    // Setup orphan cleanup configuration
    schedulerProperties.getOrphanCleanup().setEnabled(true);
    schedulerProperties.getOrphanCleanup().setThresholdMs(60000L);
    schedulerProperties.getOrphanCleanup().setIntervalMs(30000L);

    schedulerProperties.getPool().setCoreSize(5);
    schedulerProperties.getPool().setMaxSize(20);
    schedulerProperties.getPool().setKeepAliveSeconds(60);

    configuration = new PrioritySchedulerConfiguration(agentProperties, schedulerProperties);
  }

  @Nested
  @DisplayName("Thread Pool Configuration Tests")
  class ThreadPoolConfigurationTests {

    @Test
    @DisplayName("Should create agent work pool with correct configuration")
    void shouldCreateAgentWorkPoolWithCorrectConfiguration() {
      // When
      ExecutorService workPool = configuration.getAgentWorkPool();

      // Then
      assertThat(workPool).isNotNull();
      assertThat(workPool).isInstanceOf(ThreadPoolExecutor.class);

      ThreadPoolExecutor threadPool = (ThreadPoolExecutor) workPool;
      assertThat(threadPool.getCorePoolSize()).isEqualTo(5);
      assertThat(threadPool.getMaximumPoolSize()).isEqualTo(20);
      assertThat(threadPool.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60);

      // Should use unbounded queue (LinkedBlockingQueue without capacity)
      assertThat(threadPool.getQueue().remainingCapacity()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("Should create scheduler executor service")
    void shouldCreateSchedulerExecutorService() {
      // When
      ScheduledExecutorService schedulerExecutor = configuration.getSchedulerExecutorService();

      // Then
      assertThat(schedulerExecutor).isNotNull();
      assertThat(schedulerExecutor.isShutdown()).isFalse();
    }
  }

  @Nested
  @DisplayName("Concurrency Control Tests")
  class ConcurrencyControlTests {

    @Test
    @DisplayName("Should create semaphore with correct permit count")
    void shouldCreateSemaphoreWithCorrectPermitCount() {
      // When
      Semaphore semaphore = configuration.getRunningAgents();

      // Then
      assertThat(semaphore).isNotNull();
      assertThat(semaphore.availablePermits()).isEqualTo(10);
    }

    @Test
    @DisplayName("Should return null semaphore when concurrency control disabled")
    void shouldReturnNullSemaphoreWhenConcurrencyControlDisabled() {
      // Given - Disable concurrency control
      agentProperties.setMaxConcurrentAgents(0);
      PrioritySchedulerConfiguration disabledConfig =
          new PrioritySchedulerConfiguration(agentProperties, schedulerProperties);

      // When
      Semaphore semaphore = disabledConfig.getRunningAgents();

      // Then
      assertThat(semaphore).isNull();
    }

    @Test
    @DisplayName("Should handle negative concurrent agents as disabled")
    void shouldHandleNegativeConcurrentAgentsAsDisabled() {
      // Given
      agentProperties.setMaxConcurrentAgents(-1);
      PrioritySchedulerConfiguration negativeConfig =
          new PrioritySchedulerConfiguration(agentProperties, schedulerProperties);

      // When
      Semaphore semaphore = negativeConfig.getRunningAgents();

      // Then
      assertThat(semaphore).isNull();
    }

    @Test
    @DisplayName("Should create semaphore with custom permit count")
    void shouldCreateSemaphoreWithCustomPermitCount() {
      // Given
      agentProperties.setMaxConcurrentAgents(25);
      PrioritySchedulerConfiguration customConfig =
          new PrioritySchedulerConfiguration(agentProperties, schedulerProperties);

      // When
      Semaphore semaphore = customConfig.getRunningAgents();

      // Then
      assertThat(semaphore.availablePermits()).isEqualTo(25);
    }
  }

  @Nested
  @DisplayName("Pattern Configuration Tests")
  class PatternConfigurationTests {

    @Test
    @DisplayName("Should compile enabled agent pattern correctly")
    void shouldCompileEnabledAgentPatternCorrectly() {
      // When
      Pattern pattern = configuration.getEnabledAgentPattern();

      // Then
      assertThat(pattern).isNotNull();
      assertThat(pattern.pattern()).isEqualTo(".*");
      assertThat(pattern.matcher("test-agent").matches()).isTrue();
      assertThat(pattern.matcher("another-agent").matches()).isTrue();
    }

    @Test
    @DisplayName("Should handle custom agent patterns")
    void shouldHandleCustomAgentPatterns() {
      // Given - Use fresh properties to avoid test contamination
      PriorityAgentProperties freshAgentProps = new PriorityAgentProperties();
      freshAgentProps.setEnabledPattern("test-.*");
      PrioritySchedulerConfiguration customConfig =
          new PrioritySchedulerConfiguration(freshAgentProps, schedulerProperties);

      // When
      Pattern pattern = customConfig.getEnabledAgentPattern();

      // Then
      assertThat(pattern.pattern()).isEqualTo("test-.*");
      assertThat(pattern.matcher("test-ec2").matches()).isTrue();
      assertThat(pattern.matcher("aws-ec2").matches()).isFalse();
      assertThat(pattern.matcher("gcp-compute").matches()).isFalse();
    }

    @Test
    @DisplayName("Should handle complex regex patterns")
    void shouldHandleComplexRegexPatterns() {
      // Given - Use fresh properties to avoid test contamination
      PriorityAgentProperties freshAgentProps = new PriorityAgentProperties();
      freshAgentProps.setEnabledPattern("^(aws|gcp)-.*$");
      PrioritySchedulerConfiguration regexConfig =
          new PrioritySchedulerConfiguration(freshAgentProps, schedulerProperties);

      // When
      Pattern pattern = regexConfig.getEnabledAgentPattern();

      // Then
      assertThat(pattern.matcher("aws-ec2").matches()).isTrue();
      assertThat(pattern.matcher("gcp-compute").matches()).isTrue();
      assertThat(pattern.matcher("azure-vm").matches()).isFalse();
      assertThat(pattern.matcher("AWS-ec2").matches()).isFalse(); // Case sensitive
    }
  }

  @Nested
  @DisplayName("Disabled Pattern Configuration Tests")
  class DisabledPatternConfigurationTests {

    @Test
    @DisplayName("Should handle no disabled pattern (empty string)")
    void shouldHandleNoDisabledPattern() {
      // Given - Use fresh properties with no disabled pattern
      PriorityAgentProperties freshAgentProps = new PriorityAgentProperties();
      freshAgentProps.setDisabledPattern("");
      PrioritySchedulerConfiguration noPatternConfig =
          new PrioritySchedulerConfiguration(freshAgentProps, schedulerProperties);

      // When
      Pattern pattern = noPatternConfig.getDisabledAgentPattern();

      // Then
      assertThat(pattern).isNull();
    }

    @Test
    @DisplayName("Should compile simple disabled pattern")
    void shouldCompileSimpleDisabledPattern() {
      // Given - Use fresh properties with simple pattern
      PriorityAgentProperties freshAgentProps = new PriorityAgentProperties();
      freshAgentProps.setDisabledPattern("test-.*");
      PrioritySchedulerConfiguration patternConfig =
          new PrioritySchedulerConfiguration(freshAgentProps, schedulerProperties);

      // When
      Pattern pattern = patternConfig.getDisabledAgentPattern();

      // Then
      assertThat(pattern).isNotNull();
      assertThat(pattern.pattern()).isEqualTo("test-.*");
      assertThat(pattern.matcher("test-ec2").matches()).isTrue();
      assertThat(pattern.matcher("aws-ec2").matches()).isFalse();
    }

    @Test
    @DisplayName("Should handle complex disabled patterns")
    void shouldHandleComplexDisabledPatterns() {
      // Given - Use fresh properties with complex pattern
      PriorityAgentProperties freshAgentProps = new PriorityAgentProperties();
      freshAgentProps.setDisabledPattern("^(aws|gcp)-(test|dev)-.*$");
      PrioritySchedulerConfiguration complexConfig =
          new PrioritySchedulerConfiguration(freshAgentProps, schedulerProperties);

      // When
      Pattern pattern = complexConfig.getDisabledAgentPattern();

      // Then
      assertThat(pattern).isNotNull();
      assertThat(pattern.matcher("aws-test-ec2").matches()).isTrue();
      assertThat(pattern.matcher("gcp-dev-compute").matches()).isTrue();
      assertThat(pattern.matcher("aws-prod-ec2").matches()).isFalse();
      assertThat(pattern.matcher("azure-test-vm").matches()).isFalse();
    }

    @Test
    @DisplayName("Should handle multi-cloud disabled patterns")
    void shouldHandleMultiCloudDisabledPatterns() {
      // Given - Pattern to disable all test environments across clouds
      PriorityAgentProperties freshAgentProps = new PriorityAgentProperties();
      freshAgentProps.setDisabledPattern(".*-(test|testing|dev|development)-.*");
      PrioritySchedulerConfiguration multiCloudConfig =
          new PrioritySchedulerConfiguration(freshAgentProps, schedulerProperties);

      // When
      Pattern pattern = multiCloudConfig.getDisabledAgentPattern();

      // Then
      assertThat(pattern).isNotNull();

      // Should match test environments
      assertThat(pattern.matcher("aws-test-us-east-1").matches()).isTrue();
      assertThat(pattern.matcher("gcp-testing-us-central1").matches()).isTrue();
      assertThat(pattern.matcher("azure-dev-eastus").matches()).isTrue();
      assertThat(pattern.matcher("k8s-development-cluster").matches()).isTrue();

      // Should NOT match production environments
      assertThat(pattern.matcher("aws-prod-us-east-1").matches()).isFalse();
      assertThat(pattern.matcher("gcp-production-us-central1").matches()).isFalse();
      assertThat(pattern.matcher("azure-prod-eastus").matches()).isFalse();
    }

    @Test
    @DisplayName("Should be case sensitive in pattern matching")
    void shouldBeCaseSensitiveInPatternMatching() {
      // Given - Case sensitive pattern
      PriorityAgentProperties freshAgentProps = new PriorityAgentProperties();
      freshAgentProps.setDisabledPattern("aws-.*");
      PrioritySchedulerConfiguration caseConfig =
          new PrioritySchedulerConfiguration(freshAgentProps, schedulerProperties);

      // When
      Pattern pattern = caseConfig.getDisabledAgentPattern();

      // Then
      assertThat(pattern.matcher("aws-ec2").matches()).isTrue();
      assertThat(pattern.matcher("AWS-ec2").matches()).isFalse(); // Case sensitive
      assertThat(pattern.matcher("aws-EC2").matches()).isTrue(); // Only prefix matters
    }
  }

  @Nested
  @DisplayName("Configuration Access Tests")
  class ConfigurationAccessTests {

    @Test
    @DisplayName("Should provide correct scheduler interval")
    void shouldProvideCorrectSchedulerInterval() {
      // When
      long interval = configuration.getSchedulerIntervalMs();

      // Then
      assertThat(interval).isEqualTo(1000L);
    }

    @Test
    @DisplayName("Should provide correct Redis refresh period")
    void shouldProvideCorrectRedisRefreshPeriod() {
      // When
      int refreshPeriod = configuration.getRedisRefreshPeriod();

      // Then
      assertThat(refreshPeriod).isEqualTo(30);
    }

    @Test
    @DisplayName("Should provide correct max concurrent agents")
    void shouldProvideCorrectMaxConcurrentAgents() {
      // When
      int maxConcurrent = configuration.getMaxConcurrentAgents();

      // Then
      assertThat(maxConcurrent).isEqualTo(10);
    }

    @Test
    @DisplayName("Should provide correct zombie configuration")
    void shouldProvideCorrectZombieConfiguration() {
      // When
      long zombieThreshold = configuration.getZombieThresholdMs();
      long zombieCleanupInterval = configuration.getZombieIntervalMs();

      // Then
      assertThat(zombieThreshold).isEqualTo(30000L);
      assertThat(zombieCleanupInterval).isEqualTo(10000L);
    }
  }

  @Nested
  @DisplayName("Resource Management Tests")
  class ResourceManagementTests {

    @Test
    @DisplayName("Should shutdown thread pools gracefully")
    void shouldShutdownThreadPoolsGracefully() {
      // Given
      ExecutorService workPool = configuration.getAgentWorkPool();
      ScheduledExecutorService schedulerExecutor = configuration.getSchedulerExecutorService();

      assertThat(workPool.isShutdown()).isFalse();
      assertThat(schedulerExecutor.isShutdown()).isFalse();

      // When
      configuration.shutdown();

      // Then
      assertThat(workPool.isShutdown()).isTrue();
      assertThat(schedulerExecutor.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("Should handle shutdown timeout gracefully")
    void shouldHandleShutdownTimeoutGracefully() throws InterruptedException {
      // Given - Submit a long-running task to cause shutdown delay
      ExecutorService workPool = configuration.getAgentWorkPool();
      workPool.submit(
          () -> {
            try {
              Thread.sleep(100); // Short delay to test shutdown
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });

      // When
      long startTime = System.currentTimeMillis();
      configuration.shutdown();
      long shutdownTime = System.currentTimeMillis() - startTime;

      // Then - Should complete within reasonable time
      assertThat(shutdownTime).isLessThan(5000); // Less than 5 seconds
      assertThat(workPool.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("Should handle multiple shutdown calls gracefully")
    void shouldHandleMultipleShutdownCallsGracefully() {
      // When - Call shutdown multiple times
      configuration.shutdown();
      configuration.shutdown(); // Second call should not throw

      // Then - Should not throw exception
      assertThat(configuration.getAgentWorkPool().isShutdown()).isTrue();
      assertThat(configuration.getSchedulerExecutorService().isShutdown()).isTrue();
    }
  }

  @Nested
  @DisplayName("Performance Tests")
  class PerformanceTests {

    @Test
    @DisplayName("Should handle high concurrent access to configuration")
    void shouldHandleHighConcurrentAccessToConfiguration() throws InterruptedException {
      // Given
      int threadCount = 50;
      Thread[] threads = new Thread[threadCount];
      final Exception[] threadException = new Exception[1];

      // When - Multiple threads access configuration concurrently
      for (int i = 0; i < threadCount; i++) {
        threads[i] =
            new Thread(
                () -> {
                  try {
                    // Access various configuration methods
                    configuration.getSchedulerIntervalMs();
                    configuration.getMaxConcurrentAgents();
                    configuration.getEnabledAgentPattern();
                    configuration.getAgentWorkPool();
                    configuration.getRunningAgents();
                  } catch (Exception e) {
                    threadException[0] = e;
                  }
                });
        threads[i].start();
      }

      // Wait for all threads to complete
      for (Thread thread : threads) {
        thread.join();
      }

      // Then
      assertThat(threadException[0]).isNull();
    }

    @Test
    @DisplayName("Should provide consistent configuration values")
    void shouldProvideConsistentConfigurationValues() {
      // Given
      long firstIntervalCall = configuration.getSchedulerIntervalMs();
      int firstMaxConcurrentCall = configuration.getMaxConcurrentAgents();
      Pattern firstPatternCall = configuration.getEnabledAgentPattern();

      // When - Call configuration methods multiple times
      long secondIntervalCall = configuration.getSchedulerIntervalMs();
      int secondMaxConcurrentCall = configuration.getMaxConcurrentAgents();
      Pattern secondPatternCall = configuration.getEnabledAgentPattern();

      // Then - Should return consistent values
      assertThat(secondIntervalCall).isEqualTo(firstIntervalCall);
      assertThat(secondMaxConcurrentCall).isEqualTo(firstMaxConcurrentCall);
      assertThat(secondPatternCall.pattern()).isEqualTo(firstPatternCall.pattern());
    }
  }

  @Nested
  @DisplayName("Integration Tests")
  class IntegrationTests {

    @Test
    @DisplayName("Should create fully functional configuration")
    void shouldCreateFullyFunctionalConfiguration() {
      // When - Access all configuration elements
      ExecutorService workPool = configuration.getAgentWorkPool();
      ScheduledExecutorService schedulerExecutor = configuration.getSchedulerExecutorService();
      Semaphore semaphore = configuration.getRunningAgents();
      Pattern pattern = configuration.getEnabledAgentPattern();

      // Then - All elements should be properly configured
      assertThat(workPool).isNotNull();
      assertThat(schedulerExecutor).isNotNull();
      assertThat(semaphore).isNotNull();
      assertThat(pattern).isNotNull();

      // Verify they work together
      assertThat(semaphore.availablePermits()).isEqualTo(configuration.getMaxConcurrentAgents());
      assertThat(pattern.matcher("test-agent").matches()).isTrue();
    }

    @Test
    @DisplayName("Should handle configuration with disabled pattern")
    void shouldHandleConfigurationWithDisabledPattern() {
      // Given
      String disabledPattern = "disabled-agent-.*";
      agentProperties.setDisabledPattern(disabledPattern);
      PrioritySchedulerConfiguration configWithDisabled =
          new PrioritySchedulerConfiguration(agentProperties, schedulerProperties);

      // When
      Pattern retrievedDisabled = configWithDisabled.getDisabledAgentPattern();

      // Then
      assertThat(retrievedDisabled.pattern()).isEqualTo(disabledPattern);
    }
  }
}
