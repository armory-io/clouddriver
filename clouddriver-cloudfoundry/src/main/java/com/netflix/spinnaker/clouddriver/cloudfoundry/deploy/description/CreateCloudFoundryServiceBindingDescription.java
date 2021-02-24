/*
 * Copyright 2020 Armory, Inc.
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
 *
 */

package com.netflix.spinnaker.clouddriver.cloudfoundry.deploy.description;

import com.netflix.spinnaker.clouddriver.cloudfoundry.model.CloudFoundrySpace;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
public class CreateCloudFoundryServiceBindingDescription
    extends AbstractCloudFoundryServerGroupDescription {

  private CloudFoundrySpace space;
  private List<ServiceBindingRequest> serviceBindingRequests;
  private boolean restageRequired = true;
  private boolean restartRequired;

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class ServiceBindingRequest {
    private String serviceInstanceName;
    private Map<String, Object> parameters;
    private boolean updatable;
  }
}
