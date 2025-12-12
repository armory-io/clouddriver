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

package com.netflix.spinnaker.cats.cluster;

import com.google.common.hash.Hashing;

/**
 * Jump Consistent Hash strategy for minimal key movement during scaling.
 *
 * <h2>Algorithm</h2>
 *
 * <p>Jump Consistent Hash is a fast, minimal-memory consistent hashing algorithm developed by John
 * Lamping and Eric Veach at Google. It deterministically assigns keys to buckets with two key
 * properties:
 *
 * <ol>
 *   <li><b>Consistency:</b> Given the same key and bucket count, always returns the same bucket
 *   <li><b>Minimal movement:</b> When bucket count changes from n to n+1, only 1/(n+1) of keys move
 * </ol>
 *
 * <h2>Scaling Behavior</h2>
 *
 * <table border="1">
 * <tr><th>Scale Event</th><th>Modulo</th><th>Jump</th></tr>
 * <tr><td>3 → 4 pods</td><td>~75% keys move</td><td>~25% keys move</td></tr>
 * <tr><td>10 → 11 pods</td><td>~90% keys move</td><td>~9% keys move</td></tr>
 * <tr><td>10 → 9 pods</td><td>~90% keys move</td><td>~10% keys move</td></tr>
 * </table>
 *
 * <h2>Implementation</h2>
 *
 * <p>Uses Guava's {@link Hashing#consistentHash(long, int)} which implements the algorithm from the
 * paper. Keys are first hashed with murmur3_128 for better distribution than Java's hashCode().
 *
 * <h2>Limitations</h2>
 *
 * <p>Buckets must be numbered sequentially (0 to n-1). This is suitable for our use case where pods
 * are assigned indices based on sorted pod IDs.
 *
 * @see <a href="https://arxiv.org/abs/1406.2294">A Fast, Minimal Memory, Consistent Hash Algorithm
 *     (Lamping & Veach, 2014)</a>
 * @see Hashing#consistentHash(long, int)
 */
public class JumpConsistentHashStrategy implements ShardingStrategy {

  public static final String NAME = "jump";

  /**
   * {@inheritDoc}
   *
   * <p>The algorithm works by simulating the assignment of a key as if buckets were added one at a
   * time. For each bucket count b, it decides whether the key should stay in its current bucket or
   * move to bucket b. The decision is deterministic based on the hash, ensuring consistency.
   */
  @Override
  public int computeOwner(String key, int totalPods) {
    if (totalPods <= 1) {
      return 0;
    }

    // Step 1: Hash the key with murmur3_128 for better distribution.
    // - String.hashCode() has known weaknesses (e.g., "Aa" and "BB" have same hash)
    // - murmur3_128 provides 128-bit output; we take 64 bits for consistentHash
    long hash = Hashing.murmur3_128().hashUnencodedChars(key).asLong();

    // Step 2: Guava's consistentHash implements the Jump algorithm from the paper.
    // Time complexity: O(ln(totalPods)) - very fast even for large clusters
    // Space complexity: O(1) - no storage required
    return Hashing.consistentHash(hash, totalPods);
  }

  @Override
  public String getName() {
    return NAME;
  }
}

