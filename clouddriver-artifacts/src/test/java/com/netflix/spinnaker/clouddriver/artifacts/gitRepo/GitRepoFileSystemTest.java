/*
 * Copyright 2026 Harness, Inc.
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

package com.netflix.spinnaker.clouddriver.artifacts.gitRepo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

class GitRepoFileSystemTest {

  @Test
  void canRetainCloneWhileClonesAreDeleted() throws Exception {
    GitRepoArtifactProviderProperties properties = new GitRepoArtifactProviderProperties();
    properties.setCloneRetentionMinutes(60);
    properties.setCloneRetentionMaxBytes(Long.MAX_VALUE);
    GitRepoFileSystem fileSystem = new GitRepoFileSystem(properties);

    Path clones =
        Paths.get(System.getProperty("java.io.tmpdir"), "gitrepos", "test-" + UUID.randomUUID());
    AtomicBoolean stop = new AtomicBoolean();
    Thread churn =
        new Thread(
            () -> {
              int i = 0;
              while (!stop.get()) {
                Path clone = clones.resolve("clone-" + (i++ % 4));
                try {
                  Files.createDirectories(clone);
                  for (int f = 0; f < 50; f++) {
                    Files.write(clone.resolve("file-" + f), new byte[16]);
                  }
                  FileUtils.deleteDirectory(clone.toFile());
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              }
            });
    churn.start();

    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < deadline) {
        assertTrue(fileSystem.canRetainClone());
      }
    } finally {
      stop.set(true);
      churn.join();
      FileUtils.deleteDirectory(clones.toFile());
    }
  }
}
