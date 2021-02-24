/*
 * Copyright 2019 Armory
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

import com.netflix.spinnaker.clouddriver.artifacts.config.ArtifactProvider;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("artifacts.git-repo")
final class GitRepoArtifactProviderProperties implements ArtifactProvider<GitRepoArtifactAccount> {
  private boolean enabled;
  private int cloneRetentionMin = 60 * 24 * 30; // 30 days
  private List<GitRepoArtifactAccount> accounts = new ArrayList<>();
}
