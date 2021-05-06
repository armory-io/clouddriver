/*
 * Copyright 2021 Netflix, Inc.
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

package com.netflix.spinnaker.clouddriver.orchestration;

import com.netflix.spinnaker.clouddriver.deploy.DescriptionValidator;
import com.netflix.spinnaker.clouddriver.deploy.ExtensibleDescriptionValidator;
import com.netflix.spinnaker.clouddriver.deploy.ValidationErrors;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CompositeDescriptionValidator<T> extends DescriptionValidator<T> {

  private final String cloudProvider;
  private final DescriptionValidator<T> validator;
  private final List<ExtensibleDescriptionValidator> extensibleValidators;

  public CompositeDescriptionValidator(
      String cloudProvider,
      DescriptionValidator<T> validator,
      List<ExtensibleDescriptionValidator> extensibleValidators) {
    this.cloudProvider = cloudProvider;
    this.validator = validator;
    this.extensibleValidators = extensibleValidators;
  }

  @Override
  public void validate(List<T> priorDescriptions, T description, ValidationErrors errors) {
    extensibleValidators.forEach(
        v -> {
          v.validate(priorDescriptions, description, errors);
        });
    if (validator == null) {
      String operationName =
          Optional.ofNullable(description)
              .map(it -> it.getClass().getSimpleName())
              .orElse("UNKNOWN");
      log.warn(
          "No validator found for operation {} and cloud provider {}",
          operationName,
          cloudProvider);
    } else {
      validator.validate(priorDescriptions, description, errors);
    }
  }
}
