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

package com.netflix.spinnaker.clouddriver.artifacts.github;

import com.netflix.spinnaker.clouddriver.artifacts.kustomize.KustomizationFileReader;
import com.netflix.spinnaker.clouddriver.artifacts.kustomize.mapping.Kustomization;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubArtifactKustomize {

  private final KustomizationFileReader kustomizationFileReader;
  private HashSet<String> filesToDownload = new HashSet<String>();

  public List<Artifact> getArtifacts(Artifact artifact){
    List<Artifact> result = new ArrayList<>();
    filesToDownload = new HashSet<>();
    try {
      for (String s :getFilesFromGithub(artifact)) {
        Artifact a = new Artifact();
        a.setReference(s);
        a.setArtifactAccount(artifact.getArtifactAccount());
        a.setCustomKind(artifact.isCustomKind());
        a.setLocation(artifact.getLocation());
        a.setMetadata(artifact.getMetadata());
        a.setName(artifact.getName());
        a.setProvenance(artifact.getProvenance());
        a.setType(artifact.getType());
        a.setUuid(artifact.getUuid());
        a.setVersion(artifact.getVersion());
        result.add(a);
      }
    }catch(IOException e){
      log.error("Error setting references in artifacts from GitHub "+e.getMessage());
    }
    return result;
  }

  private HashSet<String> getFilesFromGithub(Artifact artifact)  throws IOException {
    HashSet<String> toEvaluate = new HashSet<>();
    Path pathe = Paths.get(artifact.getReference());
    Kustomization kustomization = kustomizationFileReader.getKustomization(artifact);
    if(kustomization.getResources()!=null)
      kustomization.getResources().forEach(f->toEvaluate.add(f));
    if (kustomization.getConfigMapGenerator()!=null) {
      kustomization.getConfigMapGenerator().forEach(conf ->{
        conf.getFiles().forEach(f -> {
          filesToDownload.add(pathe.resolve(f).toString());
        });
      });
    }
    if(kustomization.getCdrs()!=null)
      kustomization.getCdrs().forEach(cdr -> toEvaluate.add(cdr));
    if(kustomization.getGenerators()!=null)
      kustomization.getGenerators().forEach(gen -> toEvaluate.add(gen));
    if(kustomization.getPatches()!=null)
      kustomization.getPatches().forEach(p -> toEvaluate.add(p.getPath()));
    if(kustomization.getPatchesStrategicMerge()!=null)
      kustomization.getPatchesStrategicMerge().forEach(patch -> toEvaluate.add(patch));
    if(kustomization.getPatchesJson6902()!=null)
      kustomization.getPatchesJson6902().forEach(json -> toEvaluate.add(json.getPath()));
    if(toEvaluate!=null) {
      for(String s :toEvaluate) {
        if(s.contains(".")){
          String tmp = s.substring(s.lastIndexOf(".") + 1);
          if(!tmp.contains("/")) {
            filesToDownload.add(pathe.resolve(s).toString());
          }else {
            artifact.setReference(getSubFolder(s,pathe));
            getFilesFromGithub(artifact);
          }
        }else {
          artifact.setReference(getSubFolder(s,pathe));
          getFilesFromGithub(artifact);
        }
      }
    }
    return filesToDownload;
  }

  private String getSubFolder(String pRelativePath, Path pPath){
    String pBasePath=pPath.toString();
    String levels = pRelativePath.substring(0, pRelativePath.lastIndexOf("/")+1);
    String sPath = pRelativePath.substring(pRelativePath.lastIndexOf("/")+1);
    if(levels.startsWith("./")) {
      return pBasePath+File.separator+sPath;
    }else {
      int lev = levels.length() - levels.replaceAll("/", "").length();
      for(int i=0; i < lev;i++) {
        pBasePath = pBasePath.substring(0,pBasePath.lastIndexOf("/"));
      }
      return pBasePath+File.separator+sPath;
    }
  }

}
