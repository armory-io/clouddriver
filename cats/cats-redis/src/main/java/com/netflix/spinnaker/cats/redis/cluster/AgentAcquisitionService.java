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

import com.netflix.spinnaker.cats.agent.Agent;
import com.netflix.spinnaker.cats.agent.AgentExecution;
import com.netflix.spinnaker.cats.agent.ExecutionInstrumentation;
import com.netflix.spinnaker.cats.cluster.AgentIntervalProvider;
import com.netflix.spinnaker.cats.cluster.ShardingFilter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
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
  private final AtomicLong activeAgentMapSize = new AtomicLong(0);

  // Runtime configuration
  private volatile Pattern enabledAgentPattern;
  private volatile Pattern disabledAgentPattern;
  private volatile int redisRefreshPeriod;

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
      Set<AgentWorker> workersToSubmit = new HashSet<>();
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

    AgentWorker worker = new AgentWorker(agent, agentExecution, executionInstrumentation);
    agents.put(agent.getAgentType(), worker);
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
    if (activeAgents.remove(agentType) != null) {
      activeAgentMapSize.decrementAndGet();
      activeAgentsFutures.remove(agentType);
      log.debug("Removed agent {} from active tracking", agentType);
    }
  }

  /**
   * Get the number of currently active agents.
   *
   * @return number of active agents
   */
  public int getActiveAgentCount() {
    return (int) activeAgentMapSize.get();
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
          2, // Key count
          WORKING_SET,
          WAITING_SET,
          agentType,
          nextScore);
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
        long waitingTime = waitingScore.get().longValue();
        long currentTime = System.currentTimeMillis();
        if (waitingTime > currentTime) {
          return waitingTime + "";
        }
      }

      // New agent or overdue - execute immediately
      return score(jedis, 0L);
    }
  }

  private String score(Jedis jedis, Long offset) {
    return String.valueOf(System.currentTimeMillis() + offset);
  }

  /** Inner class representing an agent worker that can be executed. */
  public static class AgentWorker implements Runnable {
    private final Agent agent;
    private final AgentExecution agentExecution;
    private final ExecutionInstrumentation executionInstrumentation;

    // Set by acquisition service when agent is acquired
    String acquireScore;

    AgentWorker(
        Agent agent,
        AgentExecution agentExecution,
        ExecutionInstrumentation executionInstrumentation) {
      this.agent = agent;
      this.agentExecution = agentExecution;
      this.executionInstrumentation = executionInstrumentation;
    }

    @Override
    public void run() {
      String agentType = agent.getAgentType();
      long startTimeMs = System.currentTimeMillis();

      try {
        log.debug("Starting execution of agent {}", agentType);
        executionInstrumentation.executionStarted(agent);
        agentExecution.executeAgent(agent);
        executionInstrumentation.executionCompleted(
            agent, System.currentTimeMillis() - startTimeMs);
        log.debug("Agent {} execution completed successfully", agentType);
      } catch (Throwable cause) {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        log.error("Agent {} execution failed after {}ms", agentType, elapsedMs, cause);
        executionInstrumentation.executionFailed(agent, cause, elapsedMs);
      }
    }

    public Agent getAgent() {
      return agent;
    }

    public String getAcquireScore() {
      return acquireScore;
    }
  }
}
