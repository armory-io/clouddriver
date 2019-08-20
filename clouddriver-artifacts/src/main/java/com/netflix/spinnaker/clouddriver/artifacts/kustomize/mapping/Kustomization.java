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

package com.netflix.spinnaker.clouddriver.artifacts.kustomize.mapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
"resources",
"configMapGenerator",
"cdrs",
"generators",
"patches",
"patchesStrategicMerge",
"patchesJson6902"
})
public class Kustomization {

	@JsonProperty("resources")
	private List<String> resources = null;
	@JsonProperty("configMapGenerator")
	private List<ConfigMapGenerator> configMapGenerator = null;
	@JsonProperty("crds")
	private List<String> cdrs = null;
	@JsonProperty("generators")
	private List<String> generators = null;
	@JsonProperty("patches")
	private List<Patch> patches = null;
	@JsonProperty("patchesStrategicMerge")
	private List<String> patchesStrategicMerge = null;
	@JsonProperty("patchesJson6902")
	private List<PatchesJson6902> patchesJson6902 = null;
	
	public List<PatchesJson6902> getPatchesJson6902() {
		return patchesJson6902;
	}
	public void setPatchesJson6902(List<PatchesJson6902> patchesJson6902) {
		this.patchesJson6902 = patchesJson6902;
	}
	public List<String> getPatchesStrategicMerge() {
		return patchesStrategicMerge;
	}
	public void setPatchesStrategicMerge(List<String> patchesStrategicMerge) {
		this.patchesStrategicMerge = patchesStrategicMerge;
	}
	public List<Patch> getPatches() {
		return patches;
	}
	public void setPatches(List<Patch> patches) {
		this.patches = patches;
	}
	public List<String> getGenerators() {
		return generators;
	}
	public void setGenerators(List<String> generators) {
		this.generators = generators;
	}
	public List<String> getCdrs() {
		return cdrs;
	}
	public void setCdrs(List<String> cdrs) {
		this.cdrs = cdrs;
	}

	@JsonIgnore
	private Map<String, Object> additionalProperties = new HashMap<String, Object>();

	public List<String> getResources() {
	return resources;
	}

	public void setResources(List<String> resources) {
	this.resources = resources;
	}

	public List<ConfigMapGenerator> getConfigMapGenerator() {
	return configMapGenerator;
	}

	public void setConfigMapGenerator(List<ConfigMapGenerator> configMapGenerator) {
	this.configMapGenerator = configMapGenerator;
	}

	@JsonAnyGetter
	public Map<String, Object> getAdditionalProperties() {
	return this.additionalProperties;
	}

	@JsonAnySetter
	public void setAdditionalProperty(String name, Object value) {
	this.additionalProperties.put(name, value);
	}

}
