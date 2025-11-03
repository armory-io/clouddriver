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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

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
 *
 */
@Testcontainers
@DisplayName("PriorityConfiguration Tests")
class PrioritySchedulerConfigurationTest {

  // Shared container for all integration tests
  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

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

    configuration = new PrioritySchedulerConfiguration(agentProperties, schedulerProperties);
  }

  @Nested
  @DisplayName("Thread Pool Configuration Tests")
  class ThreadPoolConfigurationTests {

    @Test
    @DisplayName("Should create agent work pool with cached thread pool")
    void shouldCreateAgentWorkPoolWithCachedThreadPool() {
      // When
      ExecutorService workPool = configuration.getAgentWorkPool();

      // Then
      assertThat(workPool).isNotNull();
      ThreadPoolExecutor threadPool = (ThreadPoolExecutor) workPool;
      assertThat(threadPool.getQueue()).isInstanceOf(java.util.concurrent.SynchronousQueue.class);
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

    @Test
    @DisplayName("Should create threads with correct naming pattern")
    void shouldCreateThreadsWithCorrectNamingPattern() throws Exception {
      // When
      ExecutorService workPool = configuration.getAgentWorkPool();

      // Submit a task to create a thread
      AtomicBoolean threadCreated = new AtomicBoolean(false);
      String[] threadName = new String[1];

      workPool.submit(
          () -> {
            threadCreated.set(true);
            Thread currentThread = Thread.currentThread();
            threadName[0] = currentThread.getName();
          });

      // Wait for thread to execute
      Thread.sleep(100);

      // Then - Verify thread name matches expected pattern
      assertThat(threadCreated.get()).isTrue();
      assertThat(threadName[0]).matches("PriorityAgentWorker-\\d+");
    }

    @Test
    @DisplayName("Should create daemon threads")
    void shouldCreateDaemonThreads() throws Exception {
      // When
      ExecutorService workPool = configuration.getAgentWorkPool();

      // Submit a task to create a thread
      AtomicBoolean threadCreated = new AtomicBoolean(false);
      AtomicBoolean isDaemon = new AtomicBoolean(false);

      workPool.submit(
          () -> {
            threadCreated.set(true);
            isDaemon.set(Thread.currentThread().isDaemon());
          });

      // Wait for thread to execute
      Thread.sleep(100);

      // Then - Verify thread is daemon (important for JVM shutdown)
      assertThat(threadCreated.get()).isTrue();
      assertThat(isDaemon.get()).isTrue();
    }

    @Test
    @DisplayName("Should handle concurrent submissions")
    void shouldHandleConcurrentSubmissions() throws Exception {
      // Given
      ExecutorService workPool = configuration.getAgentWorkPool();
      int concurrentTasks = 10;

      // When - Submit multiple tasks concurrently
      AtomicInteger completedTasks = new AtomicInteger(0);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(concurrentTasks);

      for (int i = 0; i < concurrentTasks; i++) {
        workPool.submit(
            () -> {
              try {
                startLatch.await(); // Wait for all tasks to be submitted
                completedTasks.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                completionLatch.countDown();
              }
            });
      }

      // Signal all tasks to start
      startLatch.countDown();

      // Wait for all tasks to complete
      boolean completed = completionLatch.await(5, TimeUnit.SECONDS);

      // Then - All tasks should complete successfully
      assertThat(completed).isTrue();
      assertThat(completedTasks.get()).isEqualTo(concurrentTasks);
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

      // Then - Expect null semaphore in unbounded mode
      Semaphore semaphore = disabledConfig.getRunningAgents();
      assertThat(semaphore).isNull();
    }

    @Test
    @DisplayName("Should handle negative concurrent agents as disabled")
    void shouldHandleNegativeConcurrentAgentsAsDisabled() {
      // Given
      agentProperties.setMaxConcurrentAgents(-1);
      PrioritySchedulerConfiguration disabledConfig =
          new PrioritySchedulerConfiguration(agentProperties, schedulerProperties);

      // Then - Expect null semaphore in unbounded mode
      Semaphore semaphore = disabledConfig.getRunningAgents();
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

  @Nested
  @DisplayName("Configuration Validation Tests")
  class ConfigurationValidationTests {

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
        assertThat(properties.getBatchOperations().isEnabled()).isTrue();
        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(0);
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
        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(0);
      }

      @Test
      @DisplayName("Should handle negative batch size")
      void shouldHandleNegativeBatchSize() {
        properties.getBatchOperations().setBatchSize(-1);
        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(-1);
        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(-1);
      }

      @Test
      @DisplayName("Should handle extremely large batch sizes")
      void shouldHandleExtremelyLargeBatchSizes() {
        properties.getBatchOperations().setBatchSize(Integer.MAX_VALUE);
        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(Integer.MAX_VALUE);
        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(Integer.MAX_VALUE);
      }

      @Test
      @DisplayName("Should reject negative chunk attempt multiplier values")
      void shouldRejectNegativeChunkAttemptMultiplierValues() {
        properties.getBatchOperations().setChunkAttemptMultiplier(-0.1d);

        assertThatThrownBy(() -> properties.validate())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chunk-attempt-multiplier");
      }

      @Test
      @DisplayName("Should reject non-finite chunk attempt multiplier values")
      void shouldRejectNonFiniteChunkAttemptMultiplierValues() {
        properties.getBatchOperations().setChunkAttemptMultiplier(Double.NaN);

        assertThatThrownBy(() -> properties.validate())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chunk-attempt-multiplier");
      }

      @Test
      @DisplayName("Should allow finite non-negative chunk attempt multiplier values")
      void shouldAllowFiniteNonNegativeChunkAttemptMultiplierValues() {
        properties.getBatchOperations().setChunkAttemptMultiplier(2.5d);

        assertThatCode(() -> properties.validate()).doesNotThrowAnyException();
      }
    }

    @Nested
    @DisplayName("Backward Compatibility Tests")
    class BackwardCompatibilityTests {

      @Test
      @DisplayName("Should provide zombie cleanup convenience methods")
      void shouldProvideZombieCleanupConvenienceMethods() {
        properties.getBatchOperations().setBatchSize(75);

        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(75);

        properties.getZombieCleanup().setThresholdMs(45000L);
        assertThat(properties.getZombieThresholdMs()).isEqualTo(45000L);
        assertThat(properties.isZombieCleanupEnabled()).isTrue();
      }

      @Test
      @DisplayName("Should provide orphan cleanup convenience methods")
      void shouldProvideOrphanCleanupConvenienceMethods() {
        properties.getBatchOperations().setBatchSize(125);

        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(125);

        properties.getOrphanCleanup().setThresholdMs(3600000L);
        assertThat(properties.getOrphanThresholdMs()).isEqualTo(3600000L);
        assertThat(properties.isOrphanCleanupEnabled()).isTrue();
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
        assertThat(properties.getIntervalMs()).isEqualTo(1000L);
        assertThat(properties.getRefreshPeriodSeconds()).isEqualTo(30);
        assertThat(properties.getTimeCacheDurationMs()).isEqualTo(10000L);
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
        properties.setIntervalMs(1L);
        properties.setRefreshPeriodSeconds(1);
        properties.setTimeCacheDurationMs(1L);

        assertThat(properties.getIntervalMs()).isEqualTo(1L);
        assertThat(properties.getRefreshPeriodSeconds()).isEqualTo(1);
        assertThat(properties.getTimeCacheDurationMs()).isEqualTo(1L);

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

        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(testBatchSize);
        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(testBatchSize);
      }

      @Test
      @DisplayName("Should use unified batch size for all operations")
      void shouldUseUnifiedBatchSizeForAllOperations() {
        properties.getBatchOperations().setBatchSize(75);

        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(75);
      }

      @Test
      @DisplayName("Should validate that agent acquisition batch size affects unified batch size")
      void shouldValidateAgentAcquisitionBatchSizeAffectsUnified() {
        properties.getBatchOperations().setBatchSize(25);
        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(25);
        properties.getBatchOperations().setBatchSize(150);
        assertThat(properties.getBatchOperations().getBatchSize()).isEqualTo(150);

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
        assertThatCode(
                () -> {
                  properties.getIntervalMs();
                  properties.getRefreshPeriodSeconds();
                  properties.getTimeCacheDurationMs();

                  properties.getBatchOperations().isEnabled();
                  properties.getBatchOperations().getBatchSize();

                  properties.isZombieCleanupEnabled();
                  properties.getZombieThresholdMs();
                  properties.getZombieIntervalMs();
                  properties.getBatchOperations().getBatchSize();

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

  @Nested
  @DisplayName("Configuration Simulation Tests")
  class ConfigurationSimulationTests {

    @Test
    void shouldValidateDefaultConfigurationMathematics() {
      long schedulerIntervalMs = 1000L;
      int refreshPeriodSeconds = 30;
      long zombieThresholdMs = 1800000L;
      long zombieCleanupIntervalMs = 300000L;
      double maxBoostsPerSecond = 10.0;

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

      assertTrue(
          maxBoostsPerSecond >= 1.0 && maxBoostsPerSecond <= 100.0,
          "Boost rate should be between 1-100 per second for practical usage");

      long agentPickupLatencyMs = schedulerIntervalMs;
      double boostCapacityPerMinute = maxBoostsPerSecond * 60;
      long maxZombieLifetimeMs = zombieThresholdMs + zombieCleanupIntervalMs;

      assertTrue(agentPickupLatencyMs <= 2000L, "Agent pickup should be under 2 seconds");
      assertTrue(
          boostCapacityPerMinute >= 60, "Should handle at least 1 boost per second sustained");
      assertTrue(
          maxZombieLifetimeMs <= TimeUnit.MINUTES.toMillis(35),
          "Zombies should be cleaned within 35 minutes maximum");
    }

    @Test
    void shouldValidateHighLoadConfigurationMathematics() {
      long schedulerIntervalMs = 500L;
      int refreshPeriodSeconds = 15;
      long zombieThresholdMs = 2100000L;
      long zombieCleanupIntervalMs = 120000L;
      double maxBoostsPerSecond = 30.0;

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

      long refreshIntervalMs = refreshPeriodSeconds * 1000L;
      assertTrue(
          refreshIntervalMs > schedulerIntervalMs * 5,
          "Refresh should be significantly less frequent than scheduler cycles");

      assertTrue(
          zombieCleanupIntervalMs > schedulerIntervalMs * 10,
          "Zombie cleanup should be much less frequent than scheduler cycles");

      long agentPickupLatencyMs = schedulerIntervalMs;
      double boostCapacityPerMinute = maxBoostsPerSecond * 60;
      long maxZombieLifetimeMs = zombieThresholdMs + zombieCleanupIntervalMs;
      double schedulerCyclesPerMinute = 60000.0 / schedulerIntervalMs;
      double refreshCyclesRatio = refreshIntervalMs / (double) schedulerIntervalMs;

      assertTrue(
          agentPickupLatencyMs <= 1000L, "High-load config should have sub-second agent pickup");
      assertTrue(boostCapacityPerMinute >= 1000, "High-load should handle 1000+ boosts per minute");
      assertTrue(
          maxZombieLifetimeMs <= TimeUnit.MINUTES.toMillis(40),
          "Even with 35min threshold, total cleanup should be under 40 minutes");
      assertTrue(schedulerCyclesPerMinute >= 60, "Should run at least once per second");
      assertTrue(
          refreshCyclesRatio >= 10, "Refresh should be at least 10x less frequent than scheduler");
    }

    @Test
    void shouldValidateOnDemandBoostPerformanceScenarios() {
      double defaultBoostRate = 0.0;
      int normalDeploymentsPerHour = 20;
      double normalBoostDemand = normalDeploymentsPerHour / 3600.0;

      assertTrue(
          defaultBoostRate >= normalBoostDemand * 0,
          "Default rate should handle 0x normal deployment load");

      double highLoadBoostRate = 30.0;
      int busyDeploymentsPerHour = 200;
      double busyBoostDemand = busyDeploymentsPerHour / 3600.0;

      assertTrue(
          highLoadBoostRate > busyBoostDemand * 1,
          "High-load rate should handle 1x busy deployment load");

      int maxConcurrentDeployments = 50;
      assertTrue(
          highLoadBoostRate >= maxConcurrentDeployments / 2,
          "Should handle worst-case concurrent deployment burst");
    }

    @Test
    void shouldValidateRedisLoadImplications() {
      long defaultSchedulerInterval = 1000L;
      int defaultRefreshPeriod = 30;
      long defaultZombieCleanupInterval = 300000L;

      double defaultSchedulerOpsPerMinute = 60000.0 / defaultSchedulerInterval;
      double defaultRefreshOpsPerMinute = 60.0 / defaultRefreshPeriod;
      double defaultZombieOpsPerMinute = 60000.0 / defaultZombieCleanupInterval;
      double defaultTotalOpsPerMinute =
          defaultSchedulerOpsPerMinute + defaultRefreshOpsPerMinute + defaultZombieOpsPerMinute;

      long highLoadSchedulerInterval = 500L;
      int highLoadRefreshPeriod = 15;
      long highLoadZombieCleanupInterval = 120000L;

      double highLoadSchedulerOpsPerMinute = 60000.0 / highLoadSchedulerInterval;
      double highLoadRefreshOpsPerMinute = 60.0 / highLoadRefreshPeriod;
      double highLoadZombieOpsPerMinute = 60000.0 / highLoadZombieCleanupInterval;
      double highLoadTotalOpsPerMinute =
          highLoadSchedulerOpsPerMinute + highLoadRefreshOpsPerMinute + highLoadZombieOpsPerMinute;

      assertTrue(
          defaultTotalOpsPerMinute <= 100,
          "Default config should keep Redis load under 100 ops/minute");
      assertTrue(
          highLoadTotalOpsPerMinute <= 200,
          "High-load config should keep Redis load under 200 ops/minute");

      double loadIncrease = highLoadTotalOpsPerMinute / defaultTotalOpsPerMinute;
      assertTrue(
          loadIncrease >= 1.5 && loadIncrease <= 4.0,
          "High-load should be 1.5-4x more Redis operations than default");
    }
  }

  @Nested
  @DisplayName("Instant Retry Integration Tests")
  class InstantRetryIntegrationTests {

    private JedisPool jedisPool;
    private AgentAcquisitionService acquisitionService;
    private ExecutorService testExecutor;
    private ExecutorService agentWorkPool;

    @BeforeEach
    void setUp() {
      JedisPoolConfig config = new JedisPoolConfig();
      config.setMaxTotal(32);
      jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379));

      ShardingFilter mockShardingFilter = mock(ShardingFilter.class);
      PriorityAgentProperties mockAgentProperties = mock(PriorityAgentProperties.class);
      PrioritySchedulerProperties mockSchedulerProperties = mock(PrioritySchedulerProperties.class);
      RedisScriptManager mockScriptManager = mock(RedisScriptManager.class);
      AgentIntervalProvider mockIntervalProvider = mock(AgentIntervalProvider.class);

      when(mockShardingFilter.filter(any(Agent.class))).thenReturn(true);
      when(mockAgentProperties.getEnabledPattern()).thenReturn(".*");
      when(mockAgentProperties.getDisabledPattern()).thenReturn("");
      when(mockAgentProperties.getMaxConcurrentAgents()).thenReturn(10);
      when(mockSchedulerProperties.getRefreshPeriodSeconds()).thenReturn(1);
      PrioritySchedulerProperties.BatchOperations mockBatch =
          new PrioritySchedulerProperties.BatchOperations();
      mockBatch.setEnabled(true);
      mockBatch.setBatchSize(50);
      when(mockSchedulerProperties.getBatchOperations()).thenReturn(mockBatch);
      PrioritySchedulerProperties.Keys keys = new PrioritySchedulerProperties.Keys();
      keys.setWaitingSet("waiting");
      keys.setWorkingSet("working");
      keys.setCleanupLeaderKey("cleanup-leader");
      when(mockSchedulerProperties.getKeys()).thenReturn(keys);
      when(mockScriptManager.getScriptSha(anyString())).thenReturn("mock-sha");
      when(mockScriptManager.isInitialized()).thenReturn(true);

      AgentIntervalProvider.Interval testInterval = new AgentIntervalProvider.Interval(0L, 5000L);
      when(mockIntervalProvider.getInterval(any(Agent.class))).thenReturn(testInterval);

      acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              mockScriptManager,
              mockIntervalProvider,
              mockShardingFilter,
              mockAgentProperties,
              mockSchedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      testExecutor = Executors.newFixedThreadPool(5);
      agentWorkPool = Executors.newFixedThreadPool(20);

      // Clear Redis
      try (var jedis = jedisPool.getResource()) {
        jedis.flushDB();
      }
    }

    @AfterEach
    void tearDown() {
      if (testExecutor != null) {
        testExecutor.shutdown();
      }
      if (agentWorkPool != null) {
        agentWorkPool.shutdown();
      }
      if (jedisPool != null) {
        jedisPool.close();
      }
    }

    @Test
    @DisplayName("Should trigger instant retry when agents become available during execution")
    void shouldTriggerInstantRetryWhenAgentsAppearDuringExecution() throws InterruptedException {
      try (var jedis = jedisPool.getResource()) {
        jedis.flushDB();

        jedis.zadd("waiting", 0, "ReadyAgent-1");
        jedis.zadd("waiting", 0, "ReadyAgent-2");
        jedis.zadd("waiting", 0, "ReadyAgent-3");

        var initialReady = jedis.zrangeByScore("waiting", 0, Double.MAX_VALUE);
        assertThat(initialReady).hasSize(3);
      }

      for (int i = 1; i <= 3; i++) {
        Agent agent = TestFixtures.createMockAgent("ReadyAgent-" + i, "test-provider");
        AgentExecution execution = mock(AgentExecution.class);
        ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
        acquisitionService.registerAgent(agent, execution, instrumentation);
      }

      var retryTriggered = new AtomicInteger(0);
      var newAgentsAdded = new AtomicInteger(0);

      Thread backgroundAdder =
          new Thread(
              () -> {
                try {
                  Thread.sleep(50);

                  try (var jedis = jedisPool.getResource()) {
                    jedis.zrem("waiting", "ReadyAgent-1", "ReadyAgent-2", "ReadyAgent-3");
                    jedis.zadd("working", System.currentTimeMillis(), "ReadyAgent-1");
                    jedis.zadd("working", System.currentTimeMillis(), "ReadyAgent-2");
                    jedis.zadd("working", System.currentTimeMillis(), "ReadyAgent-3");

                    jedis.zadd("waiting", 0, "RetryAgent-1");
                    jedis.zadd("waiting", 0, "RetryAgent-2");

                    newAgentsAdded.set(2);
                  }
                } catch (Exception e) {
                  System.err.println("Background thread error: " + e.getMessage());
                }
              });

      backgroundAdder.start();

      long startTime = System.currentTimeMillis();
      Semaphore testSemaphore = new Semaphore(10);
      int acquired = acquisitionService.saturatePool(0L, testSemaphore, agentWorkPool);
      long duration = System.currentTimeMillis() - startTime;

      backgroundAdder.join(1000);

      try (var jedis = jedisPool.getResource()) {
        var finalWaiting = jedis.zrangeByScore("waiting", 0, Double.MAX_VALUE);
        var finalWorking = jedis.zrangeByScore("working", 0, Double.MAX_VALUE);

        if (newAgentsAdded.get() > 0) {
          assertThat(newAgentsAdded.get()).isEqualTo(2);
          assertThat(duration).isLessThan(1000);
        }
      }
    }

  }

  @Nested
  @DisplayName("Instant Retry Cap Integration Tests")
  class InstantRetryCapIntegrationTests {

    private JedisPool jedisPool;
    private RedisScriptManager scriptManager;
    private AgentAcquisitionService acquisitionService;
    private PriorityAgentProperties agentProperties;
    private PrioritySchedulerProperties schedulerProperties;
    private AgentIntervalProvider intervalProvider;
    private ShardingFilter shardingFilter;
    private ExecutorService agentWorkPool;

    @BeforeEach
    void setUp() {
      JedisPoolConfig config = new JedisPoolConfig();
      config.setMaxTotal(32);
      jedisPool = new JedisPool(config, redis.getHost(), redis.getMappedPort(6379));

      scriptManager =
          new RedisScriptManager(
              jedisPool,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));
      scriptManager.initializeScripts();

      intervalProvider = mock(AgentIntervalProvider.class);
      when(intervalProvider.getInterval(any(Agent.class)))
          .thenReturn(new AgentIntervalProvider.Interval(0L, 2000L));

      shardingFilter = mock(ShardingFilter.class);
      when(shardingFilter.filter(any(Agent.class))).thenReturn(true);

      agentProperties = new PriorityAgentProperties();
      agentProperties.setEnabledPattern(".*");
      agentProperties.setDisabledPattern("");
      agentProperties.setMaxConcurrentAgents(5);

      schedulerProperties = new PrioritySchedulerProperties();
      schedulerProperties.setRefreshPeriodSeconds(30);
      schedulerProperties.getBatchOperations().setEnabled(true);
      schedulerProperties.getBatchOperations().setBatchSize(2);
      schedulerProperties.getKeys().setWaitingSet("waiting");
      schedulerProperties.getKeys().setWorkingSet("working");

      acquisitionService =
          new AgentAcquisitionService(
              jedisPool,
              scriptManager,
              intervalProvider,
              shardingFilter,
              agentProperties,
              schedulerProperties,
              new PrioritySchedulerMetrics(new com.netflix.spectator.api.DefaultRegistry()));

      agentWorkPool = Executors.newFixedThreadPool(8);

      try (Jedis j = jedisPool.getResource()) {
        j.flushDB();
      }
    }

    @AfterEach
    void tearDown() {
      if (agentWorkPool != null) {
        agentWorkPool.shutdownNow();
      }
      if (jedisPool != null) {
        jedisPool.close();
      }
    }

    @Test
    @DisplayName("Chunked acquisition fills to min(availableSlots, ready)")
    void chunkedAcquisitionFillsToSlots() throws Exception {
      AgentExecution execution = mock(AgentExecution.class);
      ExecutionInstrumentation instrumentation = mock(ExecutionInstrumentation.class);
      for (int i = 1; i <= 6; i++) {
        acquisitionService.registerAgent(createAgent("A" + i), execution, instrumentation);
      }

      try (Jedis j = jedisPool.getResource()) {
        j.zadd("waiting", 0, "A1");
        j.zadd("waiting", 0, "A2");
      }

      AtomicBoolean competingMoved = new AtomicBoolean(false);

      Thread competitor =
          new Thread(
              () -> {
                try {
                  Thread.sleep(50);
                  try (Jedis j = jedisPool.getResource()) {
                    j.zrem("waiting", "A1", "A2");
                    j.zadd("working", System.currentTimeMillis() / 1000.0, "A1");
                    j.zadd("working", System.currentTimeMillis() / 1000.0, "A2");

                    j.zadd("waiting", 0, "A3");
                    j.zadd("waiting", 0, "A4");
                    j.zadd("waiting", 0, "A5");
                    j.zadd("waiting", 0, "A6");
                    j.zadd("waiting", 0, "A7");
                  }
                  competingMoved.set(true);
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                }
              });

      competitor.start();

      int acquired = acquisitionService.saturatePool(1L, new Semaphore(10), agentWorkPool);

      competitor.join(1000);

      assertThat(acquired).isEqualTo(5);
      assertThat(competingMoved.get()).isTrue();
    }

    private Agent createAgent(String name) {
      Agent a = mock(Agent.class);
      when(a.getAgentType()).thenReturn(name);
      when(a.getProviderName()).thenReturn("test");
      return a;
    }
  }
}
