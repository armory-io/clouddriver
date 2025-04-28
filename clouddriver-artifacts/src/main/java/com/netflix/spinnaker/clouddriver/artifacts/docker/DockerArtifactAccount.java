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

import com.google.common.base.Strings;
import com.netflix.spinnaker.clouddriver.artifacts.config.ArtifactAccount;
import com.netflix.spinnaker.kork.annotations.NonnullByDefault;
import java.util.List;
import java.util.Optional;
import javax.annotation.ParametersAreNullableByDefault;
import lombok.Builder;
import lombok.Value;
import org.springframework.boot.context.properties.ConstructorBinding;

@NonnullByDefault
@Value
public class DockerArtifactAccount implements ArtifactAccount {
  private final String name;

  private final Optional<String> username;
  private final Optional<String> password;
  private final Optional<String> passwordFile;
  private final Optional<String> passwordCommand;
  private final Optional<String> dockerconfigFile;
  private final Optional<String> email;
  private final Optional<String> address;
  private final boolean insecureRegistry;
  private final List<String> helmOciRepositories;

  private final String type = DockerArtifactCredentials.CREDENTIALS_TYPE;
  private final String provider = DockerArtifactCredentials.TYPE;

  @Builder
  @ConstructorBinding
  @ParametersAreNullableByDefault
  DockerArtifactAccount(
      String name,
      String username,
      String password,
      String passwordFile,
      String passwordCommand,
      String dockerconfigFile,
      String email,
      String address,
      boolean insecureRegistry,
      List<String> helmOciRepositories) {
    this.name = Strings.nullToEmpty(name);
    this.username = Optional.ofNullable(Strings.emptyToNull(username));
    this.password = Optional.ofNullable(Strings.emptyToNull(password));
    this.passwordFile = Optional.ofNullable(Strings.emptyToNull(passwordFile));
    this.passwordCommand = Optional.ofNullable(Strings.emptyToNull(passwordCommand));
    this.dockerconfigFile = Optional.ofNullable(Strings.emptyToNull(dockerconfigFile));
    this.email = Optional.ofNullable(Strings.emptyToNull(email));
    this.address = Optional.ofNullable(Strings.emptyToNull(address));
    this.insecureRegistry = insecureRegistry;
    this.helmOciRepositories = Optional.ofNullable(helmOciRepositories).orElse(List.of());
  }
}
