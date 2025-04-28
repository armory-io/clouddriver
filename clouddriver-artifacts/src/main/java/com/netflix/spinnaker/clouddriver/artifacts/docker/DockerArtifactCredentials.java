/*
 * Copyright 2019 Pivotal, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.clouddriver.artifacts.docker;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.netflix.spinnaker.clouddriver.artifacts.config.ArtifactCredentials;
import com.netflix.spinnaker.clouddriver.artifacts.config.BaseHttpArtifactCredentials;
import com.netflix.spinnaker.clouddriver.utils.docker.DockerBearerTokenService;
import com.netflix.spinnaker.kork.annotations.NonnullByDefault;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;

@Slf4j
@NonnullByDefault
public class DockerArtifactCredentials extends BaseHttpArtifactCredentials<DockerArtifactAccount>
    implements ArtifactCredentials {
  public static final String CREDENTIALS_TYPE = "artifacts-docker";
  public static final String TYPE = "docker/image";

  @Getter private final String name;

  @Getter
  private final ImmutableList<String> types =
      Arrays.stream(DockerArtifactType.values())
          .map(DockerArtifactType::getType)
          .collect(toImmutableList());

  private final DockerArtifactAccount account;
  private final HelmChartsFileSystem helmChartsFileSystem;

  public DockerArtifactCredentials(
      DockerArtifactAccount account,
      OkHttpClient okHttpClient,
      HelmChartsFileSystem helmChartsFileSystem) {
    super(okHttpClient, account);
    this.account = account;
    this.name = this.account.getName();
    this.helmChartsFileSystem = helmChartsFileSystem;
  }

  @Override
  public String getType() {
    return CREDENTIALS_TYPE;
  }

  @Override
  public InputStream download(Artifact artifact) throws IOException {
    String helmArtifactName = artifact.getName();
    String helmArtifactVersion = artifact.getVersion();

    Path stagingPath =
        helmChartsFileSystem.getLocalClonePath(helmArtifactName, helmArtifactVersion);

    Path outputFile = Paths.get(stagingPath.toString(), helmArtifactVersion + ".tar.gz");

    try {
      return getLockedInputStream(artifact, outputFile);
    } catch (InterruptedException e) {
      throw new IOException(
          "Interrupted while waiting to acquire file system lock for "
              + helmArtifactName
              + " (version "
              + helmArtifactVersion
              + ").",
          e);
    }
  }

  @NotNull
  private FileInputStream getLockedInputStream(Artifact artifact, Path outputFile)
      throws InterruptedException, IOException {

    String helmArtifactName = artifact.getName();
    String helmArtifactVersion = artifact.getVersion();

    if (helmChartsFileSystem.tryTimedLock(helmArtifactName, helmArtifactVersion)) {
      try {
        return getInputStream(artifact, outputFile);
      } finally {
        if (!helmChartsFileSystem.canRetainClone()) {
          log.debug(
              "Deleting helm chart for {} (version {})", helmArtifactName, helmArtifactVersion);
          FileUtils.deleteDirectory(outputFile.getParent().toFile());
        }
        helmChartsFileSystem.unlock(helmArtifactName, helmArtifactVersion);
      }

    } else {
      throw new IOException(
          "Timeout waiting to acquire file system lock for "
              + helmArtifactName
              + " (version "
              + helmArtifactVersion
              + "). Waited "
              + helmChartsFileSystem.getCloneWaitLockTimeoutSec()
              + " seconds.");
    }
  }

  @NotNull
  private FileInputStream getInputStream(Artifact artifact, Path outputFile) throws IOException {

    if (!outputFile.toFile().exists()) {
      String helmArtifactName = artifact.getName();
      log.info("Creating archive for helm/oci {}", helmArtifactName);
      DockerBearerTokenService tokenService =
          new DockerBearerTokenService(
              account.getUsername().orElse(null), account.getPassword().orElse(null), null);

      try {
        tokenService.downloadArtifact(artifact.getName(), artifact.getVersion(), outputFile);
        // Compress the directory to the output .tar.gz file
      } catch (Exception e) {
        log.error("Failed to get bearer token", e);
        throw new RuntimeException("Failed to authenticate with Docker registry", e);
      }
    }

    log.info("Using cached archive for helm/oci {}", artifact.getName());
    return new FileInputStream(outputFile.toFile());
  }

  @Override
  public List<String> getArtifactNames() {
    return account.getHelmOciRepositories().stream().collect(toImmutableList());
  }

  @Override
  public List<String> getArtifactVersions(String artifactName) {
    DockerBearerTokenService tokenService =
        new DockerBearerTokenService(
            account.getUsername().orElse(null), account.getPassword().orElse(null), null);
    return tokenService.loadTags(artifactName);
  }
}
