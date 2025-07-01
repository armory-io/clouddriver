/*
 * Copyright 2025 Armory, Inc.
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

import java.util.Collections;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent filtering configuration properties for Redis scheduler. Follows SQL scheduler pattern with
 * redis.agent.* prefix.
 *
 * <p>This class caches agent-related configuration values to avoid dynamic config calls.
 * Configuration changes are applied through Spring Boot's configuration refresh mechanism.
 */
@Component
@ConfigurationProperties(prefix = "redis.agent")
public class ClusteredSortAgentProperties {

  /**
   * Regex pattern for enabled agents. Only agents matching this pattern will be scheduled. Aligns
   * with SQL scheduler pattern.
   */
  private String enabledPattern = ".*";

  /** List of specific agent types to explicitly disable. Aligns with SQL scheduler pattern. */
  private List<String> disabledAgents = Collections.emptyList();

  /**
   * Maximum number of agents that can run concurrently. Aligns with SQL scheduler
   * maxConcurrentAgents (default 100).
   */
  private int maxConcurrentAgents = 100;

  // Getters and setters

  public String getEnabledPattern() {
    return enabledPattern;
  }

  public void setEnabledPattern(String enabledPattern) {
    this.enabledPattern = enabledPattern;
  }

  public List<String> getDisabledAgents() {
    return disabledAgents;
  }

  public void setDisabledAgents(List<String> disabledAgents) {
    this.disabledAgents = disabledAgents;
  }

  public int getMaxConcurrentAgents() {
    return maxConcurrentAgents;
  }

  public void setMaxConcurrentAgents(int maxConcurrentAgents) {
    this.maxConcurrentAgents = maxConcurrentAgents;
  }
}
