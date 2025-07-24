/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

public class AgentLock extends com.netflix.spinnaker.cats.agent.AgentLock {
  // The score the agent was acquired with (Used to ensure we own this agent on release).
  private final String acquireScore;
  // The score the agent was release from the WAITING set with (Used to ensure it is readded to the
  // WAITING set with the right score).
  private final String releaseScore;

  /**
   * Constructor for AgentLock.
   *
   * @param agent The agent associated with this lock
   * @param acquireScore The score the agent was acquired with
   * @param releaseScore The score the agent was released from the WAITING set with
   */
  public AgentLock(Agent agent, String acquireScore, String releaseScore) {
    super(agent);
    this.acquireScore = acquireScore;
    this.releaseScore = releaseScore;
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
   * Get the release score for this agent.
   *
   * @return The release score
   */
  public String getReleaseScore() {
    return releaseScore;
  }
}
