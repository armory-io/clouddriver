/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.spinnaker.clouddriver.artifacts.kustomize;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import com.netflix.spinnaker.clouddriver.artifacts.ArtifactCredentialsRepository;
import com.netflix.spinnaker.clouddriver.artifacts.kustomize.mapping.Kustomization;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KustomizationFileReader {

  private final ArtifactCredentialsRepository artifactCredentialsRepository;
  private static final String KUSTOMIZATION_FILE = "kustomization";

  public Kustomization getKustomization(Artifact artifact) throws FileNotFoundException {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    Kustomization kustomization = null;
    Path githubPath = Paths.get(artifact.getReference());
      artifact.setReference(githubPath.resolve(KUSTOMIZATION_FILE+".yaml").toString());
      try {
        kustomization = mapper.readValue(downloadFile(artifact), Kustomization.class);
      }catch(IOException yamlException){
        log.error("File does not exist in GitHub "+artifact.getReference());
        artifact.setReference(githubPath.resolve(KUSTOMIZATION_FILE+".yml").toString());
        try {
          kustomization = mapper.readValue(downloadFile(artifact), Kustomization.class);
        }catch(IOException ymlException){
          log.error("File does not exist in GitHub "+artifact.getReference());
          artifact.setReference(githubPath.resolve(KUSTOMIZATION_FILE).toString());
          try {
            kustomization = mapper.readValue(downloadFile(artifact), Kustomization.class);
          }catch(IOException e){
            log.error("File does not exist in GitHub "+artifact.getReference());
          }
        }
      }
    return kustomization;
  }

  InputStream downloadFile(Artifact artifact) throws IOException {
      return artifactCredentialsRepository
        .getCredentials(artifact.getArtifactAccount(), artifact.getType())
        .download(artifact);
  }

}
