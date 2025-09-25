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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/** Lightweight parsers for Redis Lua script results. */
@Slf4j
final class ScriptResults {
  private ScriptResults() {}

  @Getter
  static final class BatchRemovalResult {
    private final int removedCount;
    private final List<String> members;

    BatchRemovalResult(int removedCount, List<String> members) {
      this.removedCount = Math.max(0, removedCount);
      this.members = members == null ? Collections.emptyList() : members;
    }
  }

  /**
   * Parses the result of REMOVE_AGENTS_CONDITIONAL script which returns [count, [member1, member2,
   * ...]].
   */
  static BatchRemovalResult parseRemoveAgentsConditional(Object result) {
    int count = 0;
    List<String> cleaned = new ArrayList<>();

    if (result instanceof List<?>) {
      List<?> list = (List<?>) result;
      if (list.size() < 2) {
        log.warn(
            "Unexpected Lua script result format: expected [count, list], got size: {}",
            list.size());
      }
      if (list.size() >= 1) {
        Object countObj = list.get(0);
        if (countObj instanceof Number) {
          count = ((Number) countObj).intValue();
        } else {
          log.warn(
              "Unexpected count element type in Lua result: {}",
              countObj != null ? countObj.getClass().getSimpleName() : "null");
        }
      }
      if (list.size() >= 2) {
        Object membersObj = list.get(1);
        if (membersObj instanceof List<?>) {
          for (Object elem : (List<?>) membersObj) {
            if (elem instanceof String) {
              cleaned.add((String) elem);
            }
          }
        } else {
          log.warn(
              "Unexpected second element type in Lua result: {}",
              membersObj != null ? membersObj.getClass().getSimpleName() : "null");
        }
      }
    } else {
      log.warn(
          "Unexpected Lua script result type: expected List, got: {}",
          result != null ? result.getClass().getSimpleName() : "null");
    }

    return new BatchRemovalResult(count, cleaned);
  }
}
