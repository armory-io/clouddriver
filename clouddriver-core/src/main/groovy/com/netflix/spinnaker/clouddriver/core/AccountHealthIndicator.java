/*
 * Copyright 2020 Google, Inc.
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

package com.netflix.spinnaker.clouddriver.core;

import com.google.common.collect.ImmutableMap;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.patterns.PolledMeter;
import com.netflix.spinnaker.clouddriver.config.ThreadConfigurationProperties;
import com.netflix.spinnaker.clouddriver.security.AccountCredentials;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * This class represents a health indicator that reports on the status of individual cloud provider
 * accounts.
 *
 * <p>It will always report a status of UP, to prevent issues with a single cloud provider from
 * bringing down all of clouddriver, but any errors associated with individual accounts will appear
 * in the detailed health information.
 *
 * <p>The number of unhealthy accounts will be reported as the metric health.id.errors, where id is
 * the id supplied to the constructor.
 *
 * @param <T> The type of account credentials this health indicator supports
 */
public abstract class AccountHealthIndicator<T extends AccountCredentials>
    implements HealthIndicator {
  @Nonnull private Health health = new Health.Builder().up().build();
  @Nonnull private final AtomicLong unhealthyAccounts = new AtomicLong(0);

  @Autowired private ThreadConfigurationProperties threadConfigurationProperties;

  private ExecutorService executorService;

  /**
   * Create an {@code AccountHealthIndicator} reporting metrics to the supplied registry, using the
   * supplied id.
   *
   * @param id A unique identifier for the health indicator, used for reporting metrics
   * @param registry The registry to which metrics should be reported
   */
  protected AccountHealthIndicator(String id, Registry registry) {
    PolledMeter.using(registry).withName(metricName(id)).monitorValue(unhealthyAccounts);
  }

  private static String metricName(String id) {
    return "health." + id + ".errors";
  }

  @Override
  public final Health health() {
    return health;
  }

  class AccountHealth implements Callable<Optional<String>> {
    T account;

    public AccountHealth(T account) {
      this.account = account;
    }

    public Optional<String> call() throws Exception {
      return accountHealth(account);
    }
  }

  @Scheduled(fixedDelay = 300000L)
  public void checkHealth() {
    long errors = 0;
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    if (threadConfigurationProperties == null
        || threadConfigurationProperties.getAccountThreads() == null
        || threadConfigurationProperties.getAccountThreads() < 2) {
      for (T account : getAccounts()) {
        Optional<String> error = accountHealth(account);
        if (error.isPresent()) {
          errors++;
          builder.put(account.getName(), error.get());
        }
      }
    } else {
      if (executorService == null) {
        executorService =
            Executors.newFixedThreadPool(threadConfigurationProperties.getAccountThreads());
      }

      List<Callable<Optional<String>>> callableTasks = new ArrayList<>();
      for (T account : getAccounts()) {
        callableTasks.add(new AccountHealth(account));
      }

      List<Future<Optional<String>>> futures = null;
      try {
        futures = executorService.invokeAll(callableTasks);
      } catch (InterruptedException e) {
        return;
      }

      int i = 0;
      for (T account : getAccounts()) {
        Future<Optional<String>> future = futures.get(i);
        Optional<String> error = null;
        try {
          error = future.get();
          // By using invokeAll all tasks will have finished at this point.
        } catch (ExecutionException | InterruptedException e) {
          return;
        }
        if (error.isPresent()) {
          errors++;
          builder.put(account.getName(), error.get());
        }
        i++;
      }
    }

    unhealthyAccounts.set(errors);
    health = new Health.Builder().up().withDetails(builder.build()).build();
  }

  /**
   * Returns the accounts that should be considered by this health indicator.
   *
   * @return The accounts to be considered by this health indicator
   */
  protected abstract Iterable<? extends T> getAccounts();

  /**
   * Checks the health of a given account.
   *
   * @return An empty {@code Optional} if the account is healthy. Otherwise, an {@code
   *     Optional<String>} containing an error message describing why the account is unhealthy.
   */
  protected abstract Optional<String> accountHealth(T account);
}
