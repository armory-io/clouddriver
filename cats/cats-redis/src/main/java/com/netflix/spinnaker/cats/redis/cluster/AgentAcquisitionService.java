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

import static com.netflix.spinnaker.cats.agent.ExecutionInstrumentation.elapsedTimeMs;

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

/**
 * Service responsible for acquiring agents from Redis and executing them.
 *
 * <p>This service handles the core scheduling logic of moving agents from WAITING → WORKING and
 * executing them. It includes:
 *
 * <ul>
 *   <li>Finding agents ready for execution based on priority (Redis scores)
 *   <li>Atomically acquiring agents using Lua scripts to prevent double execution
 *   <li>Submitting acquired agents to the thread pool for execution
 *   <li>Managing concurrency limits via semaphores
 *   <li>Periodic Redis repopulation for recovery
 * </ul>
 */
@Component
public class AgentAcquisitionService {
  private static final Logger log = LoggerFactory.getLogger(AgentAcquisitionService.class);

  // Redis set names - must match original exactly
  private static final String WAITING_SET = "WAITZ";
  private static final String WORKING_SET = "WORKZ";

  private final JedisPool jedisPool;
  private final RedisScriptManager scriptManager;
  private final AgentIntervalProvider intervalProvider;
  private final ShardingFilter shardingFilter;
  private final ClusteredSortAgentProperties agentProperties;
  private final ClusteredSortSchedulerProperties schedulerProperties;

  // Agent tracking
  private final Map<String, AgentWorker> agents = new ConcurrentHashMap<>();
  private final Map<String, String> activeAgents = new ConcurrentHashMap<>();
  private final Map<String, java.util.concurrent.Future<?>> activeAgentsFutures =
      new ConcurrentHashMap<>();

  // Redis TIME synchronization for multi-instance coordination
  private static final AtomicLong lastTimeCheck = new AtomicLong(0);
  private static final AtomicLong serverClientOffset = new AtomicLong(0);

  // Advanced statistics tracking
  private final AtomicLong agentMapSize = new AtomicLong(0);
  private final AtomicLong activeAgentMapSize = new AtomicLong(0);
  private final AtomicLong agentsAcquired = new AtomicLong(0);
  private final AtomicLong agentsExecuted = new AtomicLong(0);
  private final AtomicLong agentsFailed = new AtomicLong(0);

  // Performance optimization: Reusable collections to reduce GC pressure in high-load scenarios.
  // ThreadLocal is safe here because saturatePool() runs in single-threaded scheduler executor.
  // This avoids creating new HashSet instances on every scheduler cycle (every 1-2 seconds).
  private static final ThreadLocal<Set<AgentWorker>> REUSABLE_WORKERS_SET =
      ThreadLocal.withInitial(HashSet::new);

  // Runtime configuration
  private volatile Pattern enabledAgentPattern;
  private volatile Pattern disabledAgentPattern;
  private volatile int redisRefreshPeriod;

  // Shutdown coordination
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

  public AgentAcquisitionService(
      JedisPool jedisPool,
      RedisScriptManager scriptManager,
      AgentIntervalProvider intervalProvider,
      ShardingFilter shardingFilter,
      ClusteredSortAgentProperties agentProperties,
      ClusteredSortSchedulerProperties schedulerProperties) {
    this.jedisPool = jedisPool;
    this.scriptManager = scriptManager;
    this.intervalProvider = intervalProvider;
    this.shardingFilter = shardingFilter;
    this.agentProperties = agentProperties;
    this.schedulerProperties = schedulerProperties;

    // Initialize runtime configuration
    this.enabledAgentPattern = Pattern.compile(agentProperties.getEnabledPattern());

    // Compile disabled agent pattern if provided
    if (!agentProperties.getDisabledPattern().isEmpty()) {
      this.disabledAgentPattern = Pattern.compile(agentProperties.getDisabledPattern());
    } else {
      this.disabledAgentPattern = null;
    }

    this.redisRefreshPeriod = schedulerProperties.getRefreshPeriodSeconds();
  }

  /**
   * Core scheduling logic: Find ready agents, acquire them, and submit for execution.
   *
   * @param runCount Current run cycle number for periodic refresh
   * @param runningAgents Optional semaphore for instance-wide concurrency control
   * @param agentWorkPool Thread pool for executing agents
   * @return Number of agents successfully acquired and submitted for execution
   */
  public int saturatePool(long runCount, Semaphore runningAgents, ExecutorService agentWorkPool) {
    log.debug("Starting agent acquisition cycle {}, known agents: {}", runCount, agents.size());

    try (Jedis jedis = jedisPool.getResource()) {
      // Check concurrent agent limits before processing
      int maxConcurrentAgents = agentProperties.getMaxConcurrentAgents();
      int currentlyRunning = (int) activeAgentMapSize.get();

      if (currentlyRunning >= maxConcurrentAgents) {
        log.debug(
            "Skipping agent acquisition - at max concurrent limit ({} running, {} max)",
            currentlyRunning,
            maxConcurrentAgents);
        return 0;
      }

      // PHASE 1: Agent Repopulation (Redis Recovery, periodic)
      if (runCount % redisRefreshPeriod == 0) {
        repopulateRedisAgents(jedis);
      }

      // PHASE 2: Find ready agents in priority order
      String currentScore = score(jedis, 0L);
      Set<String> readyAgents =
          jedis.zrangeByScore(WAITING_SET, 0, Double.parseDouble(currentScore));

      log.debug(
          "Found {} agents ready for execution at score {}", readyAgents.size(), currentScore);

      if (readyAgents.isEmpty()) {
        log.debug("No agents ready for execution");
        return 0;
      }

      // PHASE 3: Agent Acquisition and Execution
      // Performance optimization: Reuse thread-local collection to reduce GC pressure
      Set<AgentWorker> workersToSubmit = REUSABLE_WORKERS_SET.get();
      workersToSubmit.clear(); // Clear any previous contents
      int agentsAcquiredThisCycle = 0;

      // Calculate how many new agents this pod can try to acquire
      int availableSlotsForNewAgents = maxConcurrentAgents - currentlyRunning;

      if (availableSlotsForNewAgents <= 0) {
        log.debug(
            "No available slots to acquire new agents this cycle ({} running, {} max). Skipping acquisition phase.",
            currentlyRunning,
            maxConcurrentAgents);
        return 0;
      }

      int effectiveMaxToAcquire = Math.min(availableSlotsForNewAgents, readyAgents.size());
      log.debug(
          "Attempting to acquire agents ({} running, {} max capacity, {} ready in Redis, limited by {} available slots)",
          currentlyRunning,
          maxConcurrentAgents,
          readyAgents.size(),
          availableSlotsForNewAgents);

      for (String agentType : readyAgents) {
        if (agentsAcquiredThisCycle >= availableSlotsForNewAgents) {
          log.debug(
              "Reached available slot limit for new agents this cycle ({} acquired out of {} target slots).",
              agentsAcquiredThisCycle,
              availableSlotsForNewAgents);
          break;
        }

        if (runningAgents != null && !runningAgents.tryAcquire()) {
          log.debug(
              "Instance concurrent agent limit reached (no permits from 'runningAgents' semaphore). Cannot acquire more agents this cycle.");
          break; // Stop trying if semaphore is full
        }

        // Semaphore permit acquired
        AgentWorker worker = agents.get(agentType);
        if (worker == null) {
          log.warn(
              "Ready agent {} not found in local agents map, releasing semaphore permit and skipping.",
              agentType);
          if (runningAgents != null) {
            runningAgents.release();
          }
          continue;
        }

        // Try to acquire this agent from Redis
        String agentAcquireScore = tryAcquireAgent(jedis, agentType);
        if (agentAcquireScore != null) {
          // Successfully acquired agent, prepare for execution
          worker.acquireScore = agentAcquireScore;
          workersToSubmit.add(worker);
          agentsAcquiredThisCycle++;

          // Track active agent
          activeAgents.put(agentType, agentAcquireScore);
          activeAgentMapSize.incrementAndGet();
          agentsAcquired.incrementAndGet(); // Track acquisition statistics

          log.debug("Acquired agent {} with score {}", agentType, agentAcquireScore);
        } else {
          // Failed to acquire (another instance got it first)
          if (runningAgents != null) {
            runningAgents.release();
          }
          log.debug("Agent {} was acquired by another instance, releasing permit", agentType);
        }
      }

      // PHASE 4: Submit all acquired agents for execution
      for (AgentWorker worker : workersToSubmit) {
        // CRITICAL: Set semaphore before execution so it can be released when done
        worker.setRunningAgents(runningAgents);
        java.util.concurrent.Future<?> future = agentWorkPool.submit(worker);
        activeAgentsFutures.put(worker.getAgent().getAgentType(), future);
        log.debug("Submitted agent {} for execution", worker.getAgent().getAgentType());
      }

      log.debug(
          "Completed agent acquisition cycle: {} agents acquired and submitted for execution",
          agentsAcquiredThisCycle);

      return agentsAcquiredThisCycle;

    } catch (Exception e) {
      log.error("Error during agent acquisition", e);
      return 0;
    }
  }

  /**
   * Register an agent for scheduling.
   *
   * @param agent The agent to register
   * @param agentExecution Agent execution callback
   * @param executionInstrumentation Metrics instrumentation
   */
  public void registerAgent(
      Agent agent,
      AgentExecution agentExecution,
      ExecutionInstrumentation executionInstrumentation) {
    if (!isAgentEnabled(agent)) {
      log.debug(
          "Agent is not enabled (agent: {}, agentType: {}, pattern: {})",
          agent.getClass().getSimpleName(),
          agent.getAgentType(),
          enabledAgentPattern.pattern());
      return;
    }

    AgentWorker worker = new AgentWorker(agent, agentExecution, executionInstrumentation, this);
    agents.put(agent.getAgentType(), worker);
    agentMapSize.set(agents.size()); // Update statistics
    log.debug("Registered agent {} for scheduling", agent.getAgentType());
  }

  /**
   * Unregister an agent from scheduling.
   *
   * @param agent The agent to unregister
   */
  public void unregisterAgent(Agent agent) {
    String agentType = agent.getAgentType();
    agents.remove(agentType);

    // Clean up active tracking
    if (activeAgents.remove(agentType) != null) {
      activeAgentMapSize.decrementAndGet();
    }

    log.debug("Unregistered agent {} from scheduling", agentType);
  }

  /**
   * Remove an agent from active tracking (called when agent execution completes).
   *
   * @param agentType The agent type to remove
   */
  public void removeActiveAgent(String agentType) {
    // CRITICAL: Capture removed value to ensure atomic consistency between map and counter
    String removedScore = activeAgents.remove(agentType);
    if (removedScore != null) {
      // Only decrement counter if we actually removed something
      activeAgentMapSize.decrementAndGet();
      // Remove future tracking - this cleanup is non-critical if it fails
      activeAgentsFutures.remove(agentType);

      // CRITICAL: Also remove from Redis WORKZ set
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.evalsha(
            scriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENT_SCRIPT),
            java.util.Arrays.asList(WORKING_SET, WAITING_SET), // Script needs both keys
            java.util.Collections.singletonList(agentType));
        log.debug("Removed agent {} from active tracking and Redis sets", agentType);
      } catch (Exception e) {
        log.error("Failed to remove agent {} from Redis WORKZ set", agentType, e);
      }
    }
  }

  /**
   * Get the number of currently active agents.
   *
   * @return number of active agents
   */
  public int getActiveAgentCount() {
    return activeAgents.size();
  }

  /**
   * Get a snapshot of active agent futures for graceful shutdown. Returns a copy to avoid
   * concurrent modification issues.
   *
   * @return Map of agent type to Future for active agents
   */
  public Map<String, Future<?>> getActiveAgentsFuturesSnapshot() {
    Map<String, Future<?>> snapshot = new HashMap<>();
    for (Map.Entry<String, Future<?>> entry : activeAgentsFutures.entrySet()) {
      snapshot.put(entry.getKey(), entry.getValue());
    }
    return snapshot;
  }

  /**
   * Get a registered agent by type.
   *
   * @param agentType The agent type to retrieve
   * @return The registered Agent, or null if not found
   */
  public Agent getRegisteredAgent(String agentType) {
    AgentWorker worker = agents.get(agentType);
    return worker != null ? worker.getAgent() : null;
  }

  /**
   * Get the total number of registered agents.
   *
   * @return number of registered agents
   */
  public int getRegisteredAgentCount() {
    return agents.size();
  }

  /**
   * Get the active agents map for zombie cleanup.
   *
   * @return map of active agents (agentType -> acquireScore)
   */
  public Map<String, String> getActiveAgentsMap() {
    return activeAgents;
  }

  /**
   * Get active agents futures for cleanup services.
   *
   * @return Map of active agent futures
   */
  public Map<String, Future<?>> getActiveAgentsFutures() {
    return activeAgentsFutures;
  }

  /**
   * Get the current size of the agent futures map.
   *
   * @return Current number of agent futures being tracked
   */
  public int getFuturesMapSize() {
    return activeAgentsFutures.size();
  }

  /**
   * Get advanced scheduling statistics.
   *
   * @return AgentAcquisitionStats with detailed metrics
   */
  public AgentAcquisitionStats getAdvancedStats() {
    return new AgentAcquisitionStats(
        agentMapSize.get(),
        activeAgentMapSize.get(),
        agentsAcquired.get(),
        agentsExecuted.get(),
        agentsFailed.get(),
        activeAgentsFutures.size());
  }

  /** Reset execution statistics counters. Useful for periodic reporting. */
  public void resetExecutionStats() {
    agentsAcquired.set(0);
    agentsExecuted.set(0);
    agentsFailed.set(0);
  }

  private boolean isAgentEnabled(Agent agent) {
    String agentType = agent.getAgentType();

    // Check if this agent matches the sharding filter
    if (!shardingFilter.filter(agent)) {
      return false;
    }

    // Check enabled pattern
    if (!enabledAgentPattern.matcher(agentType).matches()) {
      return false;
    }

    // Check if agent is disabled (pattern or list)
    if (isAgentDisabled(agentType)) {
      log.debug("Agent {} is disabled", agentType);
      return false;
    }

    return true;
  }

  /**
   * Check if an agent is disabled using regex pattern matching.
   *
   * @param agentType the agent type to check
   * @return true if the agent matches the disabled pattern
   */
  private boolean isAgentDisabled(String agentType) {
    return disabledAgentPattern != null && disabledAgentPattern.matcher(agentType).matches();
  }

  private void repopulateRedisAgents(Jedis jedis) {
    log.debug("Repopulating Redis with {} known agents", agents.size());

    Pipeline pipeline = jedis.pipelined();
    int addedCount = 0;

    for (Map.Entry<String, AgentWorker> entry : agents.entrySet()) {
      String agentType = entry.getKey();
      Agent agent = entry.getValue().getAgent();

      // Calculate next execution score
      String nextScore = agentScore(agent);

      // Add to Redis if not already present (script handles the check)
      pipeline.evalsha(
          scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT_SCRIPT),
          java.util.Arrays.asList(WORKING_SET, WAITING_SET),
          java.util.Arrays.asList(agentType, nextScore));
      addedCount++;
    }

    pipeline.sync();
    log.debug("Repopulated Redis with {} agents", addedCount);
  }

  private String tryAcquireAgent(Jedis jedis, String agentType) {
    try {
      // Generate timestamp score for this acquisition attempt
      String acquireScore = score(jedis, 0L);

      // Atomically try to move agent from WAITING → WORKING using Lua script
      // Script ensures only one instance can successfully acquire each agent
      // Args: [WORKING_SET, WAITING_SET, agentType, acquireScore]
      Object result =
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.SWAP_SET_SCRIPT),
              2, // Number of Redis keys (WORKING_SET, WAITING_SET)
              WORKING_SET, // Destination set for acquired agents
              WAITING_SET, // Source set of agents ready for execution
              agentType, // Agent name to acquire
              acquireScore); // Timestamp score for tracking acquisition time

      // Lua script returns the score if successful, null if agent was already taken
      if (result != null) {
        return result.toString();
      }
      return null; // Agent was acquired by another instance
    } catch (Exception e) {
      log.warn("Failed to acquire agent {}", agentType, e);
      return null;
    }
  }

  private String agentScore(Agent agent) {
    try (Jedis jedis = jedisPool.getResource()) {
      Pipeline pipeline = jedis.pipelined();

      // Queue both score lookups in a single pipeline
      Response<Double> workingScore = pipeline.zscore(WORKING_SET, agent.getAgentType());
      Response<Double> waitingScore = pipeline.zscore(WAITING_SET, agent.getAgentType());

      pipeline.sync();

      // If agent is currently working, calculate next execution from now
      if (workingScore.get() != null) {
        return score(jedis, intervalProvider.getInterval(agent).getInterval());
      }

      // If agent is waiting and not overdue, keep existing score
      if (waitingScore.get() != null) {
        long waitingTimeSeconds = waitingScore.get().longValue();
        long currentTimeSeconds = System.currentTimeMillis() / 1000;

        // All Redis scores are stored as seconds since epoch for consistent priority scheduling
        if (waitingTimeSeconds > currentTimeSeconds) {
          return String.valueOf(waitingTimeSeconds);
        }
      }

      // New agent or overdue - execute immediately
      return score(jedis, 0L);
    }
  }

  /**
   * Generate a Redis score timestamp in seconds with server-client time synchronization.
   *
   * <p>Redis scores must be consistent format for priority scheduling to work correctly. We use
   * seconds (not milliseconds) to match Redis TIME command format and ensure consistent scoring
   * across all agents and instances.
   *
   * <p>This method performs periodic Redis TIME synchronization to handle clock skew between
   * multiple clouddriver instances and the Redis server.
   *
   * @param jedis Redis connection for TIME command synchronization
   * @param offset Offset in milliseconds to add to current time
   * @return Score as seconds since epoch, synchronized with Redis server time
   */
  private String score(Jedis jedis, Long offset) {
    long now = System.currentTimeMillis();
    long lastCheck = lastTimeCheck.get();

    // Get time cache duration from properties (default 10 seconds)
    long timeCacheDurationMs = schedulerProperties.getTimeCacheDurationMs();

    // Refresh the server-client offset if needed
    if (now - lastCheck > timeCacheDurationMs) {
      // Use Redis TIME command for server-side time coordination
      try {
        List<String> times = jedis.time();
        if (times != null && times.size() == 2) {
          // Redis TIME returns seconds and microseconds
          long serverTimeSeconds = Long.parseLong(times.get(0));
          long serverTimeMs = serverTimeSeconds * 1000;
          // Update the offset (server time - client time)
          serverClientOffset.set(serverTimeMs - now);
          lastTimeCheck.set(now);
          log.debug("Updated Redis TIME sync offset: {}ms", serverTimeMs - now);
        }
      } catch (Exception e) {
        // In case of Redis TIME command failure, we'll use client time
        log.warn("Failed to get Redis server time, using client time: {}", e.getMessage());
      }
    }

    // Get the current time accounting for server-client offset
    long adjustedTimeMs = now + serverClientOffset.get() + offset;
    long adjustedTimeSeconds = adjustedTimeMs / 1000;

    return String.valueOf(adjustedTimeSeconds);
  }

  /**
   * Conditionally releases an agent back to the waiting queue based on execution status. This is
   * critical for handling failures and shutdown scenarios properly.
   *
   * @param agent The agent that finished execution
   * @param acquireScore The score when the agent was acquired
   * @param success Whether the agent execution was successful
   */
  public void conditionalReleaseAgent(Agent agent, String acquireScore, boolean success) {
    String agentType = agent.getAgentType();

    try {
      // During shutdown, always re-queue agents immediately regardless of success status
      // This ensures agents don't get lost during deployments/restarts
      if (shuttingDown.get()) {
        log.debug("Re-queuing agent {} due to shutdown in progress", agentType);
        scheduleAgentInRedis(agent, 0L); // Schedule for immediate pickup after restart
        return;
      }

      // If execution failed, re-queue the agent for immediate retry
      if (!success) {
        log.debug("Re-queuing agent {} due to execution failure", agentType);
        scheduleAgentInRedis(agent, 0L); // Schedule for immediate retry
      } else {
        // Successful execution - schedule for next interval
        AgentIntervalProvider.Interval interval = intervalProvider.getInterval(agent);
        long nextRunOffset = interval.getInterval();
        log.debug("Scheduling agent {} for next execution in {}ms", agentType, nextRunOffset);
        scheduleAgentInRedis(agent, nextRunOffset);
      }
    } catch (Exception e) {
      log.error("Failed to conditionally release agent {}", agentType, e);
    }
  }

  /**
   * Schedule an agent in Redis with the specified offset.
   *
   * @param agent The agent to schedule
   * @param offsetMs Offset from current time in milliseconds
   */
  private void scheduleAgentInRedis(Agent agent, long offsetMs) {
    String agentType = agent.getAgentType();
    int retryCount = 0;
    int maxRetries = 3;

    while (retryCount < maxRetries) {
      try (Jedis jedis = jedisPool.getResource()) {
        String nextScore = score(jedis, offsetMs);

        log.debug(
            "Scheduling agent {} in Redis with score: {} (attempt {})",
            agentType,
            nextScore,
            retryCount + 1);

        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT_SCRIPT),
                java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                java.util.Arrays.asList(agentType, nextScore));

        log.debug("Agent {} scheduled in Redis, result: {}", agentType, result);
        return; // Success - exit retry loop

      } catch (Exception e) {
        retryCount++;
        if (retryCount >= maxRetries) {
          log.error(
              "Failed to schedule agent {} in Redis after {} attempts", agentType, maxRetries, e);
          // Don't lose the agent - try to re-queue it later
          if (!shuttingDown.get()) {
            log.warn("Will attempt to recover agent {} on next scheduler cycle", agentType);
          }
        } else {
          log.warn(
              "Failed to schedule agent {} in Redis (attempt {}), retrying: {}",
              agentType,
              retryCount,
              e.getMessage());
          try {
            Thread.sleep(100 * retryCount); // Exponential backoff
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during Redis retry backoff for agent {}", agentType);
            return;
          }
        }
      }
    }
  }

  /** Inner class representing an agent worker that can be executed. */
  /** Set the shutdown flag to coordinate graceful shutdown across all operations. */
  public void setShuttingDown(boolean shuttingDown) {
    this.shuttingDown.set(shuttingDown);
    log.info("AgentAcquisitionService shutdown flag set to: {}", shuttingDown);
  }

  /** Check if shutdown is in progress. */
  public boolean isShuttingDown() {
    return shuttingDown.get();
  }

  /**
   * Simple data holder for Redis acquisition and release scores. This ensures atomic score
   * management and prevents race conditions.
   */
  private static class ScoreTuple {
    private final String acquireScore;
    private final String releaseScore;

    public ScoreTuple(String acquireScore, String releaseScore) {
      this.acquireScore = acquireScore;
      this.releaseScore = releaseScore;
    }

    public String getAcquireScore() {
      return acquireScore;
    }

    public String getReleaseScore() {
      return releaseScore;
    }
  }

  public static class AgentWorker implements Runnable {
    private final Agent agent;
    private final AgentExecution agentExecution;
    private final ExecutionInstrumentation executionInstrumentation;
    private final AgentAcquisitionService acquisitionService;
    private Semaphore runningAgents; // Semaphore to release when execution completes

    // Set by acquisition service when agent is acquired
    String acquireScore;

    AgentWorker(
        Agent agent,
        AgentExecution agentExecution,
        ExecutionInstrumentation executionInstrumentation,
        AgentAcquisitionService acquisitionService) {
      this.agent = agent;
      this.agentExecution = agentExecution;
      this.executionInstrumentation = executionInstrumentation;
      this.acquisitionService = acquisitionService;
      this.runningAgents = null; // Will be set before execution
    }

    @Override
    public void run() {
      String agentType = agent.getAgentType();
      long startTimeMs = System.currentTimeMillis();
      boolean success = false;

      try {
        log.debug("Starting execution of agent {}", agentType);
        executionInstrumentation.executionStarted(agent);
        agentExecution.executeAgent(agent);
        executionInstrumentation.executionCompleted(agent, elapsedTimeMs(startTimeMs));
        success = true;
        acquisitionService.agentsExecuted.incrementAndGet(); // Track successful executions
        log.debug("Agent {} execution completed successfully", agentType);
      } catch (Throwable cause) {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;

        if (cause instanceof InterruptedException) {
          log.warn(
              "Agent {} execution was interrupted (likely due to zombie cleanup or shutdown)",
              agentType);
          Thread.currentThread().interrupt(); // Restore interrupt status
        } else {
          log.error(
              "Agent {} execution failed after {}ms", agentType, elapsedTimeMs(startTimeMs), cause);
        }

        acquisitionService.agentsFailed.incrementAndGet(); // Track failed executions
        executionInstrumentation.executionFailed(agent, cause, elapsedTimeMs(startTimeMs));
      } finally {
        // Always clean up agent tracking when execution completes (success or failure)
        // This removes the agent from activeAgents map and WORKZ Redis set
        acquisitionService.removeActiveAgent(agentType);

        // Handle conditional agent release (re-queuing on failure/shutdown)
        acquisitionService.conditionalReleaseAgent(agent, acquireScore, success);

        // CRITICAL: Release semaphore permit to allow new agent acquisitions
        if (runningAgents != null) {
          runningAgents.release();
          log.debug("Released semaphore permit for agent {}", agentType);
        }

        log.debug("Agent {} execution cleanup completed", agentType);
      }
    }

    public Agent getAgent() {
      return agent;
    }

    public String getAcquireScore() {
      return acquireScore;
    }

    // Set the semaphore before execution (called from saturatePool)
    void setRunningAgents(Semaphore runningAgents) {
      this.runningAgents = runningAgents;
    }
  }

  // === MANUAL LOCK MANAGEMENT METHODS ===
  // These methods support the public API for manual agent locking

  /**
   * Try to manually acquire a lock on an agent.
   *
   * <p>NOTE: Manual locking is not supported by this scheduler to maintain thread safety and proper
   * coordination between multiple scheduler instances. Manual locking would bypass the carefully
   * designed Redis-based coordination mechanisms.
   *
   * @param agent The agent to lock
   * @return Always returns null (manual locking not supported)
   */
  public ClusteredSortAgentLock tryLockAgent(Agent agent) {
    // Manual locking is not supported to maintain thread safety and proper coordination
    log.debug(
        "Manual locking not supported for agent {} - use automatic scheduling",
        agent.getAgentType());
    return null;
  }

  /**
   * Try to release a manually acquired agent lock.
   *
   * <p>Since manual locking is not supported, this always returns false.
   *
   * @param lock The lock to release
   * @return Always returns false (manual locking not supported)
   */
  public boolean tryReleaseAgent(ClusteredSortAgentLock lock) {
    // Manual locking/releasing is not supported
    log.debug(
        "Manual lock release not supported for agent {} - locks are managed automatically",
        lock.getAgent().getAgentType());
    return false;
  }

  /**
   * Check if an agent lock is still valid.
   *
   * <p>Since manual locking is not supported, this always returns false.
   *
   * @param lock The lock to validate
   * @return Always returns false (manual locking not supported)
   */
  public boolean isLockValid(ClusteredSortAgentLock lock) {
    // Manual locking is not supported, so manual locks are never valid
    log.debug(
        "Manual lock validation not supported for agent {} - locks are managed automatically",
        lock.getAgent().getAgentType());
    return false;
  }
}
