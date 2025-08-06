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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

  // Redis set names
  private static final String WAITING_SET = "WAITZ";
  private static final String WORKING_SET = "WORKZ";

  private final JedisPool jedisPool;
  private final RedisScriptManager scriptManager;
  private final AgentIntervalProvider intervalProvider;
  private final ShardingFilter shardingFilter;
  private final PriorityAgentProperties agentProperties;
  private final PrioritySchedulerProperties schedulerProperties;

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

  // Reusable collections to reduce GC pressure in high-load scenarios.
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
  private final AtomicBoolean gracefulShutdown = new AtomicBoolean(false);

  // Queue agent completions for batch processing
  private final ConcurrentLinkedQueue<AgentCompletion> completionQueue =
      new ConcurrentLinkedQueue<>();

  /** Represents an agent completion waiting to be processed in the next scheduler cycle. */
  private static class AgentCompletion {
    final Agent agent;
    final String acquireScore;
    final boolean success;
    final long timestamp;

    AgentCompletion(Agent agent, String acquireScore, boolean success) {
      this.agent = agent;
      this.acquireScore = acquireScore;
      this.success = success;
      this.timestamp = System.currentTimeMillis();
    }
  }

  public AgentAcquisitionService(
      JedisPool jedisPool,
      RedisScriptManager scriptManager,
      AgentIntervalProvider intervalProvider,
      ShardingFilter shardingFilter,
      PriorityAgentProperties agentProperties,
      PrioritySchedulerProperties schedulerProperties) {
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

      // PHASE 1: Process queued agent completions
      processQueuedCompletions(jedis);

      // PHASE 2: Agent Repopulation (Redis Recovery, periodic)
      if (runCount % redisRefreshPeriod == 0) {
        repopulateRedisAgents(jedis);
      }

      // PHASE 3: Find ready agents in priority order
      String currentScore = score(jedis, 0L);
      Set<String> readyAgents = jedis.zrangeByScore(WAITING_SET, "-inf", currentScore);

      log.debug(
          "Found {} agents ready for execution at score {}", readyAgents.size(), currentScore);

      if (readyAgents.isEmpty()) {
        log.debug("No agents ready for execution");
        return 0;
      }

      // PHASE 4: Agent Acquisition and Execution
      // Reusing thread-local collection to avoid memory allocations
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

      // Use batch acquisition if enabled and there are multiple agents ready
      if (schedulerProperties.isBatchOperationsEnabled() && readyAgents.size() > 1) {
        try {
          agentsAcquiredThisCycle =
              saturatePoolBatch(
                  jedis, readyAgents, availableSlotsForNewAgents, runningAgents, workersToSubmit);
        } catch (Exception e) {
          log.warn(
              "Batch agent acquisition failed, falling back to individual mode: {}",
              e.getMessage());
          // Clear any partially processed workers from the failed batch attempt
          workersToSubmit.clear();
          // Fallback to individual acquisition
          agentsAcquiredThisCycle =
              saturatePoolIndividual(
                  jedis, readyAgents, availableSlotsForNewAgents, runningAgents, workersToSubmit);
        }
      } else {
        // Fallback: Individual agent acquisition (legacy mode)
        agentsAcquiredThisCycle =
            saturatePoolIndividual(
                jedis, readyAgents, availableSlotsForNewAgents, runningAgents, workersToSubmit);
      }

      // PHASE 4.5: Instant retry on zero acquisition to handle concurrency conditions
      if (agentsAcquiredThisCycle == 0
          && !readyAgents.isEmpty()
          && schedulerProperties.isBatchOperationsEnabled()) {
        log.debug(
            "Zero agents acquired from {} ready agents, checking for new arrivals",
            readyAgents.size());

        // Quick check: Are there new agents available now?
        Set<String> newReadyAgents = jedis.zrangeByScore(WAITING_SET, "-inf", currentScore);

        if (!newReadyAgents.isEmpty() && !newReadyAgents.equals(readyAgents)) {
          log.debug(
              "Found {} new ready agents (was {}), attempting instant retry",
              newReadyAgents.size(),
              readyAgents.size());

          // Single retry attempt - try batch first, then individual if needed
          try {
            agentsAcquiredThisCycle =
                saturatePoolBatch(
                    jedis,
                    newReadyAgents,
                    availableSlotsForNewAgents,
                    runningAgents,
                    workersToSubmit);

            if (agentsAcquiredThisCycle > 0) {
              log.debug("Instant retry succeeded: acquired {} agents", agentsAcquiredThisCycle);
            }
          } catch (Exception e) {
            log.debug("Instant retry batch failed, trying individual: {}", e.getMessage());
            // Clear any partial state from failed retry
            workersToSubmit.clear();
            agentsAcquiredThisCycle =
                saturatePoolIndividual(
                    jedis,
                    newReadyAgents,
                    availableSlotsForNewAgents,
                    runningAgents,
                    workersToSubmit);
          }
        } else {
          log.debug(
              "No new agents found for instant retry (still {} ready)", newReadyAgents.size());
        }
      }

      // PHASE 5: Submit all acquired agents for execution
      try {
        for (AgentWorker worker : workersToSubmit) {
          // CRITICAL: Set semaphore before execution so it can be released when done
          worker.setRunningAgents(runningAgents);
          java.util.concurrent.Future<?> future = agentWorkPool.submit(worker);
          activeAgentsFutures.put(worker.getAgent().getAgentType(), future);
          log.debug("Submitted agent {} for execution", worker.getAgent().getAgentType());
        }
      } catch (Exception e) {
        log.error(
            "Error submitting agents for execution, but returning acquisition count anyway", e);
        // Don't return 0 here - agents were successfully acquired from Redis
        // The submission error is a separate issue and shouldn't affect the acquisition count
      }

      log.debug(
          "Completed agent acquisition cycle: {} agents acquired and submitted for execution",
          agentsAcquiredThisCycle);

      return agentsAcquiredThisCycle;

    } catch (Exception e) {
      log.error("Error during agent acquisition cycle", e);
      return 0;
    }
  }

  /**
   * Acquires agents in batches to control memory consumption and lock contention in large
   * deployments. Uses {@code agentAcquisitionBatchSize} to limit the number of agents processed in
   * each Redis operation.
   *
   * @param jedis Redis connection
   * @param readyAgents Set of agent types ready for execution
   * @param maxToAcquire Maximum number of agents to acquire (concurrency limit)
   * @param runningAgents Semaphore for concurrency control
   * @param workersToSubmit Collection to add successfully acquired workers
   * @return Number of agents successfully acquired
   */
  private int saturatePoolBatch(
      Jedis jedis,
      Set<String> readyAgents,
      int maxToAcquire,
      Semaphore runningAgents,
      Set<AgentWorker> workersToSubmit) {

    // Apply batch size limit to prevent overwhelming Redis and memory
    int configuredBatchSize = schedulerProperties.getAgentAcquisitionBatchSize();
    int effectiveBatchSize = Math.min(maxToAcquire, configuredBatchSize);

    log.debug(
        "Using batch agent acquisition: {} ready agents, max: {}, batch size: {}",
        readyAgents.size(),
        maxToAcquire,
        effectiveBatchSize);

    int candidateCount = 0;
    List<String> candidateAgents = new ArrayList<>();
    List<AgentWorker> candidateWorkers = new ArrayList<>();

    // PHASE 1: Prepare candidates and acquire semaphore permits
    // Note: We respect BOTH the concurrency limit (maxToAcquire) AND batch size limit
    for (String agentType : readyAgents) {
      if (candidateCount >= effectiveBatchSize) {
        log.debug(
            "Reached batch size limit: {} agents prepared for acquisition", effectiveBatchSize);
        break;
      }

      if (runningAgents != null && !runningAgents.tryAcquire()) {
        log.debug("Semaphore limit reached at {} agents", candidateCount);
        break;
      }

      AgentWorker worker = agents.get(agentType);
      if (worker == null) {
        log.warn("Agent {} not found in local registry, skipping", agentType);
        if (runningAgents != null) {
          runningAgents.release();
        }
        continue;
      }

      candidateAgents.add(agentType);
      candidateWorkers.add(worker);
      candidateCount++; // Track candidates prepared
    }

    if (candidateAgents.isEmpty()) {
      log.debug("No valid candidate agents for batch acquisition");
      return 0;
    }

    // PHASE 2: Batch acquire agents using Redis Lua script
    try {
      // Prepare Redis Lua script arguments: [agent1, score1, agent2, score2, ...]
      // The Lua script expects alternating agent names and scores
      List<String> agentScorePairs = new ArrayList<>();

      for (int i = 0; i < candidateAgents.size(); i++) {
        String agentType = candidateAgents.get(i);
        AgentWorker worker = candidateWorkers.get(i);

        // Generate completion deadline for this agent (current time + timeout)
        long agentTimeout = intervalProvider.getInterval(worker.getAgent()).getTimeout();
        String acquireScore = score(jedis, agentTimeout);

        // Add to script arguments: agent name, then its score
        agentScorePairs.add(agentType); // Even index: agent name
        agentScorePairs.add(acquireScore); // Odd index: agent score
      }

      // Execute batch acquisition Lua script
      Object result =
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.ACQUIRE_AGENTS),
              Arrays.asList(WORKING_SET, WAITING_SET),
              agentScorePairs);

      // PHASE 3: Process batch acquisition results
      if (result instanceof List) {
        List<Object> resultList = (List<Object>) result;
        long successCount = (Long) resultList.get(0);
        List<String> acquiredAgentTypes = (List<String>) resultList.get(1);

        log.debug(
            "Batch acquisition completed: {} successes out of {} attempts",
            successCount,
            candidateAgents.size());

        // Process each candidate agent to see if it was successfully acquired
        for (int i = 0; i < candidateAgents.size(); i++) {
          String agentType = candidateAgents.get(i);

          if (acquiredAgentTypes.contains(agentType)) {
            // SUCCESS: This pod acquired the agent - set up for execution
            AgentWorker worker = candidateWorkers.get(i);

            // Extract the agent's score from agentScorePairs array
            // Array structure: [agent1, score1, agent2, score2, ...]
            // For agent at index i: score is at position (i * 2 + 1)

            String acquireScore = agentScorePairs.get(i * 2 + 1);

            worker.acquireScore = acquireScore;
            workersToSubmit.add(worker);
            activeAgents.put(agentType, acquireScore);
            activeAgentMapSize.incrementAndGet();
            agentsAcquired.incrementAndGet();

            log.debug("Batch acquired agent {} with score {}", agentType, acquireScore);
          } else {
            // FAILURE: Agent lost to another pod in race condition
            // Release the semaphore permit we pre-acquired
            if (runningAgents != null) {
              runningAgents.release();
            }
            log.debug("Agent {} was acquired by another pod", agentType);
          }
        }

        log.info(
            "Batch acquisition completed: {}/{} agents acquired",
            successCount,
            candidateAgents.size());
        return (int) successCount; // Return actual successful acquisitions from Redis
      }

      log.warn("Unexpected batch acquisition result: {}", result);
      // Release all semaphore permits on batch failure
      if (runningAgents != null) {
        for (int i = 0; i < candidateAgents.size(); i++) {
          runningAgents.release();
        }
      }
      return 0;

    } catch (Exception e) {
      log.error(
          "Batch agent acquisition failed, falling back to individual mode: {}", e.getMessage());
      // Release all semaphore permits on batch failure
      if (runningAgents != null) {
        for (int i = 0; i < candidateAgents.size(); i++) {
          runningAgents.release();
        }
      }

      // Fallback to individual acquisition
      return saturatePoolIndividual(
          jedis, new HashSet<>(candidateAgents), maxToAcquire, runningAgents, workersToSubmit);
    }
  }

  /**
   * This is the original individual acquisition logic, kept as fallback when batch operations are
   * disabled or fail.
   *
   * @param jedis Redis connection
   * @param readyAgents Set of agent types ready for execution
   * @param maxToAcquire Maximum number of agents to acquire
   * @param runningAgents Semaphore for concurrency control
   * @param workersToSubmit Collection to add successfully acquired workers
   * @return Number of agents successfully acquired
   */
  private int saturatePoolIndividual(
      Jedis jedis,
      Set<String> readyAgents,
      int maxToAcquire,
      Semaphore runningAgents,
      Set<AgentWorker> workersToSubmit) {

    log.debug(
        "Using individual agent acquisition for {} ready agents (max: {})",
        readyAgents.size(),
        maxToAcquire);

    int agentsAcquiredThisCycle = 0;

    for (String agentType : readyAgents) {
      if (agentsAcquiredThisCycle >= maxToAcquire) {
        log.debug(
            "Reached available slot limit for new agents this cycle ({} acquired out of {} target slots).",
            agentsAcquiredThisCycle,
            maxToAcquire);
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
      String agentAcquireScore = tryAcquireAgent(jedis, worker.getAgent());
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

    return agentsAcquiredThisCycle;
  }

  /**
   * Register an agent for scheduling.
   *
   * @param agent The agent to register
   * @param agentExecution The execution wrapper for the agent
   * @param executionInstrumentation Instrumentation for tracking agent execution
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

    // Log enhanced registration info with operational details
    AgentIntervalProvider.Interval interval = intervalProvider.getInterval(agent);
    String initialScore = agentScore(agent);

    log.debug(
        "Registered agent {} (interval {}s/timeout {}s) for scheduling [score {}/total agents {}]",
        agent.getAgentType(),
        interval.getInterval() / 1000,
        interval.getTimeout() / 1000,
        initialScore,
        agents.size());

    // Persist the agent into Redis immediately with its first-run score so that it is visible
    // cluster-wide even before the first repopulation cycle. This write is idempotent because the
    // ADD_AGENT Lua script uses NX semantics when the agent already exists.
    try {
      // Schedule for immediate execution on initial registration to preserve legacy behavior and
      // ensure new agents are picked up in the very first acquisition cycle. The regular interval
      // will be applied after the first successful execution when the agent is re-queued.
      scheduleAgentInRedis(agent, 0L);
    } catch (Exception e) {
      log.warn(
          "Failed to write initial Redis entry for agent {} – will rely on repopulation: {}",
          agent.getAgentType(),
          e.getMessage());
    }
  }

  /**
   * Unregisters an agent from the scheduler. This removes the agent from local tracking and
   * prevents it from being scheduled for execution.
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
   * Removes an agent from active tracking. Called when an agent execution completes or is
   * interrupted.
   *
   * @param agentType The type identifier of the agent to remove
   */
  public void removeActiveAgent(String agentType) {
    // CRITICAL: Capture removed value to ensure atomic consistency between map and counter
    String removedScore = activeAgents.remove(agentType);
    if (removedScore != null) {
      // Only decrement counter if we actually removed something
      activeAgentMapSize.decrementAndGet();
      // Remove future tracking - this cleanup is non-critical if it fails
      activeAgentsFutures.remove(agentType);

      // CRITICAL: Remove from Redis sets - behavior depends on shutdown state
      try (Jedis jedis = jedisPool.getResource()) {
        if (shuttingDown.get()) {
          // During shutdown: Only remove from WORKZ to preserve WAITZ entries
          // Agents in WAITZ were put there by graceful shutdown for restart
          jedis.zrem(WORKING_SET, agentType);
          log.debug(
              "Removed agent {} from active tracking and WORKZ (preserving WAITZ during shutdown)",
              agentType);
        } else {
          // Normal operation: Remove from both sets
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.REMOVE_AGENT),
              java.util.Arrays.asList(WORKING_SET, WAITING_SET), // Script needs both keys
              java.util.Collections.singletonList(agentType));
          log.debug("Removed agent {} from active tracking and Redis sets", agentType);
        }
      } catch (Exception e) {
        log.error("Failed to remove agent {} from Redis", agentType, e);
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
   * Get the total number of registered agents (for stats).
   *
   * @return number of registered agents
   */
  public int getRegisteredAgentCount() {
    return agents.size();
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
   * Get the active agents map for zombie cleanup.
   *
   * @return map of active agents (agentType -> completionDeadline)
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
   * Retrieves an agent by its type identifier from the registered agents map. Used during graceful
   * shutdown to properly re-queue active agents.
   *
   * @param agentType The type identifier of the agent to retrieve
   * @return The agent if found, null otherwise
   */
  public Agent getAgentByType(String agentType) {
    AgentWorker worker = agents.get(agentType);
    return worker != null ? worker.getAgent() : null;
  }

  /**
   * Conditionally re-queue an agent during graceful shutdown if it's still in WORKZ. This approach
   * respects agents that completed during shutdown and avoids race conditions. Uses ownership
   * verification to ensure we only move agents this instance actually owns.
   */
  public void forceRequeueAgentForShutdown(Agent agent, String expectedScore) {
    String agentType = agent.getAgentType();

    try (Jedis jedis = jedisPool.getResource()) {
      // Calculate immediate execution score for restart
      String nextScore = score(jedis, 0L);

      log.debug(
          "Shutdown re-queue attempt: {} expected_score={} next_score={}",
          agentType,
          expectedScore,
          nextScore);
      // TODO: fix score format to be s vs ms
      // Check current state in Redis before attempting swap
      Double currentWorkzScore = jedis.zscore(WORKING_SET, agentType);
      Double currentWaitzScore = jedis.zscore(WAITING_SET, agentType);
      log.debug(
          "Redis state before swap: {} WORKZ={} WAITZ={}",
          agentType,
          currentWorkzScore,
          currentWaitzScore);

      // Use MOVE_AGENTS_CONDITIONAL - only moves if agent is in WORKZ with expected score
      Object result =
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.MOVE_AGENTS_CONDITIONAL),
              java.util.Arrays.asList(WORKING_SET, WAITING_SET),
              java.util.Arrays.asList(agentType, expectedScore, nextScore));

      // Check final state
      Double finalWorkzScore = jedis.zscore(WORKING_SET, agentType);
      Double finalWaitzScore = jedis.zscore(WAITING_SET, agentType);
      log.debug(
          "Redis state after swap: {} WORKZ={} WAITZ={} result={}",
          agentType,
          finalWorkzScore,
          finalWaitzScore,
          result);

      if (result != null && "swapped".equals(result)) {
        log.info("Successfully re-queued agent {} for shutdown restart", agentType);
      } else {
        log.warn(
            "Agent {} not re-queued (already completed or moved during shutdown) (expected={}, current={}, result={})",
            agentType,
            expectedScore,
            currentWorkzScore,
            result);
      }

    } catch (Exception e) {
      log.error("Failed to conditionally re-queue agent {} during shutdown", agentType, e);
    }
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

  /**
   * Repopulate Redis with known agents from the local agents map. Performs differential sync to
   * avoid unnecessary Redis operations.
   *
   * @param jedis Jedis connection to Redis
   */
  private void repopulateRedisAgents(Jedis jedis) {
    int totalAgents = agents.size();
    log.debug("Repopulation check for {} known agents", totalAgents);

    if (totalAgents == 0) {
      log.debug("No agents to repopulate");
      return;
    }

    try {
      // Get current Redis state from both sets
      Set<String> redisAgents = getCurrentRedisAgents(jedis);
      Set<String> localAgents = agents.keySet();

      // Calculate what needs to be added (missing agents from this instance)
      Set<String> toAdd =
          localAgents.stream()
              .filter(agent -> !redisAgents.contains(agent))
              .collect(Collectors.toSet());

      // True NOOP if nothing to add
      if (toAdd.isEmpty()) {
        log.debug("Repopulation: Redis state is consistent, no missing agents");
        return;
      }

      log.debug("Repopulation: +{} missing agents to add", toAdd.size());

      // Add missing agents from this instance
      addMissingAgents(jedis, toAdd);

    } catch (Exception e) {
      log.warn("Repopulation failed, falling back to full sync: {}", e.getMessage());
      repopulateRedisAgentsFallback(jedis);
    }
  }

  /**
   * Gets all agent names currently in both Redis sets (WORKZ and WAITZ) using pipelining. This
   * method retrieves all agents from both Redis sorted sets in a single operation.
   *
   * @param jedis Redis connection to use
   * @return Set containing all agent names from both Redis sets
   */
  private Set<String> getCurrentRedisAgents(Jedis jedis) {
    Pipeline pipeline = jedis.pipelined();
    // Using zrange(0, -1) to get all members by index position rather than by score range
    // This is equivalent to zrangeByScore("-inf", "+inf") but more direct when we need all elements
    // regardless of score value
    Response<Set<String>> waitingAgents = pipeline.zrange(WAITING_SET, 0, -1);
    Response<Set<String>> workingAgents = pipeline.zrange(WORKING_SET, 0, -1);
    pipeline.sync();

    Set<String> allAgents = new HashSet<>(waitingAgents.get());
    allAgents.addAll(workingAgents.get());
    return allAgents;
  }

  /**
   * Adds missing agents to Redis with appropriate scores. Delegates to either batch or individual
   * processing based on configuration. This is a critical component of the differential update
   * system that only adds agents not already present in Redis.
   *
   * @param jedis Redis connection to use
   * @param agentsToAdd Set of agent types to add to Redis
   */
  private void addMissingAgents(Jedis jedis, Set<String> agentsToAdd) {
    if (schedulerProperties.isBatchOperationsEnabled() && agentsToAdd.size() > 1) {
      addMissingAgentsBatch(jedis, agentsToAdd);
    } else {
      addMissingAgentsIndividual(jedis, agentsToAdd);
    }
  }

  private void addMissingAgentsBatch(Jedis jedis, Set<String> agentsToAdd) {
    List<String> batchArgs = new ArrayList<>();
    for (String agentType : agentsToAdd) {
      AgentWorker worker = agents.get(agentType);
      if (worker != null) {
        batchArgs.add(agentType);
        batchArgs.add(score(jedis, 0L)); // New agents get immediate execution
      }
    }

    if (!batchArgs.isEmpty()) {
      try {
        @SuppressWarnings("unchecked")
        List<Object> result =
            (List<Object>)
                jedis.evalsha(
                    scriptManager.getScriptSha(RedisScriptManager.ADD_AGENTS),
                    Arrays.asList(WORKING_SET, WAITING_SET),
                    batchArgs);
        int added = result.size() >= 1 ? ((Long) result.get(0)).intValue() : 0;
        log.debug("Batch added {} missing agents to Redis", added);
      } catch (Exception e) {
        log.warn("Batch add failed, using individual mode: {}", e.getMessage());
        addMissingAgentsIndividual(jedis, agentsToAdd);
      }
    }
  }

  /**
   * Add missing agents to Redis one by one.
   *
   * @param jedis Jedis connection to Redis
   * @param agentsToAdd Set of agent types to add
   */
  private void addMissingAgentsIndividual(Jedis jedis, Set<String> agentsToAdd) {
    int added = 0;
    for (String agentType : agentsToAdd) {
      AgentWorker worker = agents.get(agentType);
      if (worker != null) {
        String newScore = score(jedis, 0L); // New agents get immediate execution
        try {
          Object result =
              jedis.evalsha(
                  scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT),
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  Arrays.asList(agentType, newScore));
          if (result != null && ((Long) result).intValue() == 1) {
            added++;
          }
        } catch (Exception e) {
          log.warn("Failed to add missing agent {}: {}", agentType, e.getMessage());
        }
      }
    }
    log.debug("Individual added {} missing agents to Redis", added);
  }

  /**
   * Fallback to full repopulation logic if smart sync fails.
   *
   * @param jedis Jedis connection to Redis
   */
  private void repopulateRedisAgentsFallback(Jedis jedis) {
    int totalAgents = agents.size();
    log.debug("Fallback: Full repopulation of {} agents", totalAgents);

    try {
      // Use batch scoring if enabled and we have multiple agents
      Map<String, String> agentScores;
      if (schedulerProperties.isBatchOperationsEnabled() && totalAgents > 1) {
        // Batch scoring for multiple agents
        agentScores = batchAgentScore(jedis, agents.values());
        log.debug("Batch scored {} agents", agentScores.size());
      } else {
        // Fallback to individual scoring
        agentScores = new HashMap<>();
        for (AgentWorker worker : agents.values()) {
          agentScores.put(worker.getAgent().getAgentType(), agentScore(worker.getAgent()));
        }
        log.debug("Individual scored {} agents", agentScores.size());
      }

      // Batch add agents to Redis in chunks to avoid memory issues
      int batchSize = schedulerProperties.getBatchOperationsBatchSize();
      int processed = 0;
      int totalAdded = 0;

      List<String> batchArgs = new ArrayList<>();
      for (Map.Entry<String, String> entry : agentScores.entrySet()) {
        batchArgs.add(entry.getKey()); // agent name
        batchArgs.add(entry.getValue()); // score
        processed++;

        // Process batch when we reach batch size or end of agents
        if (batchArgs.size() >= batchSize * 2 || processed == agentScores.size()) {
          try {
            @SuppressWarnings("unchecked")
            List<Object> result =
                (List<Object>)
                    jedis.evalsha(
                        scriptManager.getScriptSha(RedisScriptManager.ADD_AGENTS),
                        Arrays.asList(WORKING_SET, WAITING_SET),
                        batchArgs);

            if (result.size() >= 1) {
              totalAdded += ((Long) result.get(0)).intValue();
            }

            log.debug(
                "Batch added {} agents to Redis (batch {} of {})",
                batchArgs.size() / 2,
                (processed + batchSize - 1) / batchSize,
                (totalAgents + batchSize - 1) / batchSize);

          } catch (Exception e) {
            log.warn(
                "Batch repopulation failed for {} agents, using individual mode: {}",
                batchArgs.size() / 2,
                e.getMessage());

            // Fallback: Use pipeline with individual ADD_AGENT script
            Pipeline pipeline = jedis.pipelined();
            for (int i = 0; i < batchArgs.size(); i += 2) {
              pipeline.evalsha(
                  scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT),
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  Arrays.asList(batchArgs.get(i), batchArgs.get(i + 1)));
            }
            List<Object> pipelineResults = pipeline.syncAndReturnAll();

            // Count successful additions (ADD_AGENT returns 1 for success, 0 for already exists)
            for (Object result : pipelineResults) {
              if (result != null && ((Long) result).intValue() == 1) {
                totalAdded++;
              }
            }
          }

          batchArgs.clear();
        }
      }

      log.debug(
          "Repopulated Redis with {} agents ({} actually added/updated)", totalAgents, totalAdded);

    } catch (Exception e) {
      log.error(
          "Batch repopulation failed completely, falling back to legacy mode: {}", e.getMessage());

      // Complete fallback to pipeline with individual ADD_AGENT scripts
      Pipeline pipeline = jedis.pipelined();
      for (AgentWorker worker : agents.values()) {
        String agentType = worker.getAgent().getAgentType();
        String nextScore = agentScore(worker.getAgent());

        // Use individual ADD_AGENT script for pipeline compatibility
        pipeline.evalsha(
            scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT),
            Arrays.asList(WORKING_SET, WAITING_SET),
            Arrays.asList(agentType, nextScore));
      }
      pipeline.sync(); // Execute all operations in a single network round trip
      log.debug("Pipeline fallback repopulated Redis with {} agents", totalAgents);
    }
  }

  /**
   * Process queued agent completions using the shared Redis connection. Processes agent completions
   * that were queued during previous execution cycles.
   */
  private void processQueuedCompletions(Jedis jedis) {
    List<AgentCompletion> completions = drainCompletionQueue();
    if (completions.isEmpty()) {
      return;
    }

    log.debug("Processing {} queued agent completions", completions.size());

    // Group completions by scheduling offset for batch efficiency
    Map<Long, List<AgentCompletion>> groupedCompletions =
        completions.stream().collect(Collectors.groupingBy(this::getSchedulingOffset));

    int totalProcessed = 0;
    // Process each group with shared connection
    for (Map.Entry<Long, List<AgentCompletion>> entry : groupedCompletions.entrySet()) {
      long offset = entry.getKey();
      List<AgentCompletion> group = entry.getValue();

      if (schedulerProperties.isBatchOperationsEnabled() && group.size() > 1) {
        totalProcessed += batchScheduleCompletions(jedis, group, offset);
      } else {
        totalProcessed += individualScheduleCompletions(jedis, group, offset);
      }
    }

    log.debug("Processed {} agent completions with shared connection", totalProcessed);
  }

  /**
   * Drain the completion queue in a thread-safe manner. Extracts all agent completions from the
   * queue for processing.
   */
  private List<AgentCompletion> drainCompletionQueue() {
    List<AgentCompletion> completions = new ArrayList<>();
    int queueSize = completionQueue.size();
    log.debug("Draining completion queue, current size: {}", queueSize);

    AgentCompletion completion;
    while ((completion = completionQueue.poll()) != null) {
      log.debug(
          "Drained completion for agent: {} (success={})",
          completion.agent.getAgentType(),
          completion.success);
      completions.add(completion);
    }

    log.debug("Drained {} completions from queue", completions.size());
    return completions;
  }

  /**
   * Calculate scheduling offset based on completion success and shutdown state. Maintains the same
   * logic as the original conditionalReleaseAgent method.
   */
  private long getSchedulingOffset(AgentCompletion completion) {
    // Failed executions get immediate retry
    if (!completion.success) {
      return 0L;
    }

    // Compute next schedule based on original acquire score when possible to preserve cadence.
    try {
      AgentIntervalProvider.Interval interval = intervalProvider.getInterval(completion.agent);
      long intervalMs = interval.getInterval();

      // If acquireScore is available, attempt to keep consistent cadence relative to the original
      // acquisition time. The acquireScore stored on completion represents the completion deadline
      // (acquireTime + timeout) in seconds. We approximate the original acquisition moment by
      // subtracting the timeout from this score, then add the normal interval to get the ideal next
      // execution time. If any part of this calculation fails, we simply fall back to intervalMs.
      if (completion.acquireScore != null) {
        try {
          long acquireScoreSeconds = Long.parseLong(completion.acquireScore);
          long agentTimeoutMs = interval.getTimeout();
          long originalAcquireMs = (acquireScoreSeconds * 1000L) - agentTimeoutMs;
          long desiredNextRunMs = originalAcquireMs + intervalMs;
          long nowMs = System.currentTimeMillis();
          long offsetMs = desiredNextRunMs - nowMs;
          return Math.max(offsetMs, 0L);
        } catch (NumberFormatException ignored) {
          // Fall through to simple interval scheduling when parsing fails
        }
      }

      // Fallback – schedule for intervalMs from now
      return intervalMs;
    } catch (Exception e) {
      log.warn(
          "Failed to calculate scheduling offset for agent {}, using immediate scheduling",
          completion.agent.getAgentType(),
          e);
      return 0L;
    }
  }

  /**
   * Batch schedule multiple completions with the same offset using batch Redis operations. This
   * method is called by processQueuedCompletions after grouping completions by their scheduling
   * offset. It uses the ADD_AGENTS Lua script for efficient multi-agent scheduling.
   *
   * @param jedis Redis connection to use for operations
   * @param completions List of agent completions with the same offset to schedule
   * @param offset Time offset in milliseconds for agent scheduling
   * @return Number of agents successfully scheduled
   */
  private int batchScheduleCompletions(
      Jedis jedis, List<AgentCompletion> completions, long offset) {
    try {
      List<String> batchArgs = new ArrayList<>();
      for (AgentCompletion completion : completions) {
        batchArgs.add(completion.agent.getAgentType());
        batchArgs.add(score(jedis, offset));
      }

      @SuppressWarnings("unchecked")
      List<Object> result =
          (List<Object>)
              jedis.evalsha(
                  scriptManager.getScriptSha(RedisScriptManager.ADD_AGENTS),
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  batchArgs);

      int scheduled = result.size() >= 1 ? ((Long) result.get(0)).intValue() : 0;
      log.debug("Batch scheduled {} completions with offset {}ms", scheduled, offset);
      return scheduled;

    } catch (Exception e) {
      log.warn("Batch completion scheduling failed, using individual mode: {}", e.getMessage());
      return individualScheduleCompletions(jedis, completions, offset);
    }
  }

  /**
   * Schedule completions individually for agents with the same time offset. Used as a fallback when
   * batch operations fail or when processing small groups of agents. Schedules each agent
   * separately using the ADD_AGENT Lua script.
   *
   * @param jedis Redis connection to use for operations
   * @param completions List of agent completions with the same offset to schedule
   * @param offset Time offset in milliseconds for agent scheduling
   * @return Number of agents successfully scheduled
   */
  private int individualScheduleCompletions(
      Jedis jedis, List<AgentCompletion> completions, long offset) {
    int scheduled = 0;
    String offsetScore = score(jedis, offset); // Calculate once for all agents with same offset

    for (AgentCompletion completion : completions) {
      try {
        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT),
                Arrays.asList(WORKING_SET, WAITING_SET),
                Arrays.asList(completion.agent.getAgentType(), offsetScore));

        if (result != null && ((Long) result).intValue() == 1) {
          scheduled++;
        }

        log.debug(
            "Scheduled completion for agent {} with offset {}ms",
            completion.agent.getAgentType(),
            offset);

      } catch (Exception e) {
        log.warn(
            "Failed to schedule completion for agent {}: {}",
            completion.agent.getAgentType(),
            e.getMessage());
      }
    }

    return scheduled;
  }

  /**
   * Attempt to acquire an agent for execution.
   *
   * @param jedis Jedis connection to Redis
   * @param agent The agent to acquire
   * @return The acquire score if successful, null otherwise
   */
  private String tryAcquireAgent(Jedis jedis, Agent agent) {
    try {
      String agentType = agent.getAgentType();
      // Generate completion deadline: current_time + agent_timeout
      long agentTimeout = intervalProvider.getInterval(agent).getTimeout();
      String acquireScore = score(jedis, agentTimeout);

      // Atomically try to move agent from WAITING → WORKING using Lua script
      // Script ensures only one instance can successfully acquire each agent
      // Args: [WORKING_SET, WAITING_SET, agentType, acquireScore]
      Object result =
          jedis.evalsha(
              scriptManager.getScriptSha(RedisScriptManager.MOVE_AGENTS),
              Arrays.asList(WORKING_SET, WAITING_SET), // Redis keys
              Arrays.asList(agentType, acquireScore)); // Agent name and completion deadline

      // MOVE_AGENTS script returns the score on success, nil on failure
      if (result != null) {
        return result.toString(); // Return the acquire score from script
      }
      return null; // Agent was acquired by another instance
    } catch (Exception e) {
      log.warn("Failed to acquire agent {}", agent.getAgentType(), e);
      return null;
    }
  }

  /**
   * Get the current score of an agent in the working or waiting set.
   *
   * <p>Behavior:
   *
   * <ul>
   *   <li>Working agents: Calculate NEXT execution time (current + interval)
   *   <li>Waiting agents: Keep existing score (regardless of overdue status)
   *   <li>New agents (not in Redis): Execute immediately (score = 0)
   * </ul>
   *
   * @param agent The agent to check
   * @return The current score of the agent, or "unknown" if Redis is unavailable
   */
  private String agentScore(Agent agent) {
    try (Jedis jedis = jedisPool.getResource()) {
      Pipeline pipeline = jedis.pipelined();

      // Queue both score lookups in a single pipeline
      Response<Double> workingScore = pipeline.zscore(WORKING_SET, agent.getAgentType());
      Response<Double> waitingScore = pipeline.zscore(WAITING_SET, agent.getAgentType());

      pipeline.sync();

      // If agent is currently working, calculate next execution from now
      if (workingScore.get() != null) {
        String result = score(jedis, intervalProvider.getInterval(agent).getInterval());
        log.debug("Agent {} working - next execution scheduled: {}", agent.getAgentType(), result);
        return result;
      }

      // If agent is waiting, keep existing score regardless of overdue status
      // Overdue agents will be naturally picked up by saturatePool() since their score <=
      // currentTime
      if (waitingScore.get() != null) {
        // All Redis scores are stored as seconds since epoch for consistent priority scheduling
        long waitingTimeSeconds = waitingScore.get().longValue();
        log.debug(
            "Agent {} waiting - keeping existing score: {} (preserves priority ordering)",
            agent.getAgentType(),
            waitingTimeSeconds);
        return String.valueOf(waitingTimeSeconds);
      }

      // Only NEW agents (not in Redis) get immediate execution priority
      String result = score(jedis, 0L);
      log.debug(
          "Agent {} is new - giving immediate execution priority: {}",
          agent.getAgentType(),
          result);
      return result;
    } catch (Exception e) {
      log.debug(
          "Could not get agent score from Redis for {}: {}", agent.getAgentType(), e.getMessage());
      return "unknown";
    }
  }

  /**
   * Batch version of agentScore() that processes multiple agents in a single Redis call.
   *
   * @param jedis Redis connection to use
   * @param agents Collection of agents to score
   * @return Map of agent type to calculated score
   */
  private Map<String, String> batchAgentScore(Jedis jedis, Collection<AgentWorker> agents) {
    if (agents.isEmpty()) {
      return new HashMap<>();
    }

    try {
      // Prepare agent names for batch lookup
      List<String> agentNames =
          agents.stream()
              .map(worker -> worker.getAgent().getAgentType())
              .collect(Collectors.toList());

      log.debug("Batch scoring {} agents", agentNames.size());

      // Single Redis call to get all agent scores
      @SuppressWarnings("unchecked")
      List<String> results =
          (List<String>)
              jedis.evalsha(
                  scriptManager.getScriptSha(RedisScriptManager.SCORE_AGENTS),
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  agentNames);

      // Process results: [agent1, workScore1, waitScore1, agent2, workScore2, waitScore2, ...]
      Map<String, String> agentScores = new HashMap<>();
      Map<String, Agent> agentMap =
          agents.stream()
              .collect(
                  Collectors.toMap(
                      worker -> worker.getAgent().getAgentType(), AgentWorker::getAgent));

      for (int i = 0; i < results.size(); i += 3) {
        String agentType = results.get(i);
        String workingScoreStr = results.get(i + 1);
        String waitingScoreStr = results.get(i + 2);

        Agent agent = agentMap.get(agentType);
        if (agent == null) {
          log.warn("Agent {} not found in batch scoring map", agentType);
          continue;
        }

        String calculatedScore =
            calculateAgentScore(jedis, agent, workingScoreStr, waitingScoreStr);
        agentScores.put(agentType, calculatedScore);
      }

      log.debug("Batch scored {} agents successfully", agentScores.size());
      return agentScores;

    } catch (Exception e) {
      log.warn(
          "Batch agent scoring failed, falling back to individual scoring: {}", e.getMessage());
      // Fallback to individual scoring
      Map<String, String> scores = new HashMap<>();
      for (AgentWorker worker : agents) {
        scores.put(worker.getAgent().getAgentType(), agentScore(worker.getAgent()));
      }
      return scores;
    }
  }

  /**
   * Calculate the score for an agent based on its current Redis state. This exactly matches the
   * logic from agentScore() to ensure consistent behavior.
   *
   * <p>The behavior is:
   *
   * <ul>
   *   <li>Working agents: Calculate NEXT execution time (current + interval)
   *   <li>Waiting agents: Keep existing score (regardless of overdue status)
   *   <li>New agents (not in Redis): Execute immediately (score = 0)
   * </ul>
   */
  private String calculateAgentScore(
      Jedis jedis, Agent agent, String workingScoreStr, String waitingScoreStr) {
    try {
      // If agent is currently working, calculate next execution time (current + interval)
      // This matches original agentScore() behavior for working agents
      if (!"null".equals(workingScoreStr)) {
        String result = score(jedis, intervalProvider.getInterval(agent).getInterval());
        log.debug("Agent {} working - next execution scheduled: {}", agent.getAgentType(), result);
        return result;
      }

      // If agent is waiting, keep existing score regardless of overdue status
      // Overdue agents will be naturally picked up by saturatePool() since their score <=
      // currentTime
      if (!"null".equals(waitingScoreStr)) {
        try {
          long waitingTimeSeconds = Long.parseLong(waitingScoreStr);
          log.debug(
              "Agent {} waiting - keeping existing score: {} (preserves priority ordering)",
              agent.getAgentType(),
              waitingScoreStr);
          return String.valueOf(waitingTimeSeconds);
        } catch (NumberFormatException e) {
          log.debug(
              "Invalid waiting score for agent {}: {}", agent.getAgentType(), waitingScoreStr);
        }
      }
    } catch (Exception e) {
      log.error(
          "Error calculating score for agent {}: {}", agent.getAgentType(), e.getMessage(), e);
      // Fall through to default case
    }

    try {
      // Only NEW agents (not in Redis) get immediate execution priority
      String result = score(jedis, 0L);
      log.debug("Agent {} new - immediate execution: {}", agent.getAgentType(), result);
      return result;
    } catch (Exception e) {
      log.error(
          "Error generating immediate execution score for agent {}: {}",
          agent.getAgentType(),
          e.getMessage(),
          e);
      // Return immediate execution score as fallback - this matches agentScore() behavior
      // where Redis failures still allow the agent to be scheduled
      return String.valueOf(System.currentTimeMillis() / 1000);
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
   * @param acquireScore The completion deadline when the agent was acquired (current_time +
   *     timeout)
   * @param success Whether the agent execution was successful
   */
  public void conditionalReleaseAgent(Agent agent, String acquireScore, boolean success) {
    String agentType = agent.getAgentType();

    try {
      // During shutdown, immediately schedule (bypass queue for urgent shutdown handling)
      if (shuttingDown.get()) {
        log.debug("Immediate re-queuing agent {} due to shutdown in progress", agentType);
        scheduleAgentInRedis(agent, 0L); // Schedule for immediate pickup after restart
        return;
      }

      // Queue completion for batch processing in next scheduler cycle
      completionQueue.offer(new AgentCompletion(agent, acquireScore, success));
      log.debug("Queued completion for agent {}: success={}", agentType, success);

    } catch (Exception e) {
      log.error(
          "Failed to queue agent completion for {}, falling back to immediate scheduling",
          agentType,
          e);
      // Fallback to immediate scheduling on queue failure
      try {
        if (!success) {
          scheduleAgentInRedis(agent, 0L);
        } else {
          AgentIntervalProvider.Interval interval = intervalProvider.getInterval(agent);
          scheduleAgentInRedis(agent, interval.getInterval());
        }
      } catch (Exception fallbackException) {
        log.error("Failed fallback scheduling for agent {}", agentType, fallbackException);
      }
    }
  }

  /**
   * Schedule an agent in Redis with the specified offset.
   *
   * @param agent The agent to schedule
   * @param offsetMs Offset from current time in milliseconds
   */
  public void scheduleAgentInRedis(Agent agent, long offsetMs) {
    String agentType = agent.getAgentType();
    int retryCount = 0;
    int maxRetries = 3;

    while (retryCount < maxRetries) {
      try (Jedis jedis = jedisPool.getResource()) {
        String nextScore;

        // Use standard score calculation for all cases
        nextScore = score(jedis, offsetMs);

        log.debug(
            "Scheduling agent {} in Redis with score: {} (attempt {})",
            agentType,
            nextScore,
            retryCount + 1);

        Object result =
            jedis.evalsha(
                scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT),
                java.util.Arrays.asList(WORKING_SET, WAITING_SET),
                java.util.Arrays.asList(agentType, nextScore));

        boolean scheduled = result != null && ((Long) result).intValue() == 1;
        log.debug("Agent {} scheduled in Redis: {}, result: {}", agentType, scheduled, result);
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

  /** Set the shutdown flag to coordinate graceful shutdown across all operations. */
  public void setShuttingDown(boolean shuttingDown) {
    this.shuttingDown.set(shuttingDown);
    log.info("AgentAcquisitionService shutdown flag set to: {}", shuttingDown);

    if (shuttingDown) {
      // Drain and process all queued completions during shutdown
      // to ensure we don't lose any agents
      processCompletionQueueForShutdown();
    }
  }

  /**
   * Drains and processes all queued completions during shutdown. Ensures pending agent completions
   * are properly recorded in Redis.
   */
  private void processCompletionQueueForShutdown() {
    int queueSize = completionQueue.size();
    if (queueSize == 0) {
      log.info("No queued completions to process during shutdown");
      return;
    }

    log.info("Processing {} queued agent completions during shutdown", queueSize);

    try (Jedis jedis = jedisPool.getResource()) {
      // Process all queued completions with immediate scheduling (0ms offset)
      List<AgentCompletion> completions = drainCompletionQueue();
      int processed = 0;

      // Process each completion immediately with 0ms offset (instant execution on restart)
      for (AgentCompletion completion : completions) {
        try {
          String agentType = completion.agent.getAgentType();
          String score = score(jedis, 0L); // Schedule for immediate execution after restart

          Object result =
              jedis.evalsha(
                  scriptManager.getScriptSha(RedisScriptManager.ADD_AGENT),
                  Arrays.asList(WORKING_SET, WAITING_SET),
                  Arrays.asList(agentType, score));

          if (result != null && ((Long) result).intValue() == 1) {
            processed++;
            log.debug("Shutdown processed agent completion: {}", agentType);
          }
        } catch (Exception e) {
          log.error(
              "Failed to process agent completion during shutdown: {}",
              completion.agent.getAgentType(),
              e);
        }
      }

      log.info(
          "Successfully processed {}/{} agent completions during shutdown",
          processed,
          completions.size());
    } catch (Exception e) {
      log.error("Failed to process completion queue during shutdown", e);
    }
  }

  /**
   * Checks if the service is in the process of shutting down. This flag affects agent completion
   * handling - during shutdown, agent completions are processed immediately rather than queued.
   *
   * @return true if shutdown is in progress, false otherwise
   */
  public boolean isShuttingDown() {
    return shuttingDown.get();
  }

  /**
   * Sets the graceful shutdown flag to coordinate agent re-queuing during shutdown. When enabled,
   * active agents are moved back to the WAITZ set with immediate execution scores to ensure they
   * run after service restart.
   *
   * @param gracefulShutdown true to enable graceful shutdown mode, false otherwise
   */
  public void setGracefulShutdown(boolean gracefulShutdown) {
    this.gracefulShutdown.set(gracefulShutdown);
    log.debug("AgentAcquisitionService graceful shutdown flag set to: {}", gracefulShutdown);
  }

  /**
   * Performs a startup consistency check to ensure all agents are properly registered in Redis.
   * Verifies that all local agents are synchronized to the Redis state.
   */
  private void performStartupConsistencyCheck() {
    log.info("Performing startup consistency check for agent reliability");

    try (Jedis jedis = jedisPool.getResource()) {
      // Get current Redis state from both sets
      Set<String> redisAgents = getCurrentRedisAgents(jedis);
      Set<String> localAgents = agents.keySet();

      // Calculate what needs to be added (missing agents)
      Set<String> toAdd =
          localAgents.stream()
              .filter(agent -> !redisAgents.contains(agent))
              .collect(Collectors.toSet());

      if (toAdd.isEmpty()) {
        log.info("Startup consistency check: All agents properly registered in Redis");
      } else {
        log.warn(
            "Startup consistency check: Found {} agents missing from Redis, adding now",
            toAdd.size());
        // Add missing agents with immediate execution to ensure they run soon
        addMissingAgents(jedis, toAdd);
      }
    } catch (Exception e) {
      log.error("Error during startup consistency check", e);
    }
  }

  /**
   * Checks if graceful shutdown is in progress. This flag affects agent handling during shutdown -
   * graceful shutdown attempts to re-queue in-progress agents back to Redis for pickup after
   * restart.
   *
   * @return true if graceful shutdown is in progress, false otherwise
   */
  public boolean isGracefulShutdown() {
    return gracefulShutdown.get();
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

  /** Runnable wrapper for agent execution that handles resource management and monitoring. */
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

    /**
     * Get the agent associated with this worker.
     *
     * @return The agent
     */
    public Agent getAgent() {
      return agent;
    }

    /**
     * Get the acquire score for this agent.
     *
     * @return The acquire score
     */
    public String getAcquireScore() {
      return acquireScore;
    }

    /**
     * Set the semaphore before execution (called from saturatePool)
     *
     * @param runningAgents The semaphore to use for resource management
     */
    void setRunningAgents(Semaphore runningAgents) {
      this.runningAgents = runningAgents;
    }
  }

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
  public AgentLock tryLockAgent(Agent agent) {
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
  public boolean tryReleaseAgent(AgentLock lock) {
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
  public boolean isLockValid(AgentLock lock) {
    // Manual locking is not supported, so manual locks are never valid
    log.debug(
        "Manual lock validation not supported for agent {} - locks are managed automatically",
        lock.getAgent().getAgentType());
    return false;
  }
}
