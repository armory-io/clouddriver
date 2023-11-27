/*
 * Copyright 2016 Google, Inc.
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

package com.netflix.spinnaker.clouddriver.config;

import com.netflix.spinnaker.clouddriver.jobs.JobExecutor;
import com.netflix.spinnaker.clouddriver.jobs.local.JobExecutorLocal;
import lombok.*;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LocalJobConfig {
  @Bean
  @ConditionalOnMissingBean(JobExecutor.class)
  public JobExecutor jobExecutorLocal() {
    return new JobExecutorLocal(localJobConfigProperties());
  }

  @Bean
  LocalJobConfigProperties localJobConfigProperties() {
    return new LocalJobConfigProperties();
  }

  @ConfigurationProperties(prefix = "jobs.local")
  @NoArgsConstructor
  @AllArgsConstructor
  @Getter
  @Setter
  @Accessors(chain = true)
  public static class LocalJobConfigProperties {

    private boolean fixedThreadPoolEnabled;
    private int numberOfThreadsInFixedPool = 500;
    private long timeoutMinutes = 10;
  }
}
