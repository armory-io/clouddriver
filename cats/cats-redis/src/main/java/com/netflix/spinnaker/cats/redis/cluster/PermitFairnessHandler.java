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

/**
 * Interface used by cleanup services to perform fairness operations during early cancellation.
 *
 * <p>Responsibilities: - Provide an exactly-once hook for early permit release and zIF compensation
 *
 * <p>Non-responsibilities: - Scheduling logic, cadence, or Redis operations
 */
public interface PermitFairnessHandler {
  /**
   * Try to release a semaphore permit early for a running agent and, if appropriate, increment the
   * zombies-in-flight compensation. Both actions are performed with exactly-once semantics.
   *
   * @param agentType the agent identifier
   */
  void tryEarlyPermitReleaseAndMaybeIncrementZif(String agentType);
}
