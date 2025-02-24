/*
 * Copyright 2016 Netflix, Inc.
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

package com.netflix.spinnaker.clouddriver.aws.agent

import com.amazonaws.services.cloudwatch.model.DeleteAlarmsRequest
import com.amazonaws.services.cloudwatch.model.DescribeAlarmsRequest
import com.amazonaws.services.cloudwatch.model.MetricAlarm
import com.amazonaws.services.cloudwatch.model.StateValue
import com.amazonaws.services.ecs.model.ListClustersRequest
import com.amazonaws.services.ecs.model.ListServicesRequest
import com.netflix.spinnaker.cats.agent.RunnableAgent
import com.netflix.spinnaker.clouddriver.aws.provider.AwsCleanupProvider
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider
import com.netflix.spinnaker.clouddriver.aws.security.AmazonCredentials
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials
import com.netflix.spinnaker.clouddriver.cache.CustomScheduledAgent
import com.netflix.spinnaker.credentials.CredentialsRepository
import groovy.util.logging.Slf4j
import org.joda.time.DateTime

import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@Slf4j
class CleanupAlarmsAgent implements RunnableAgent, CustomScheduledAgent {
  public static final long POLL_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(24)
  public static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30)

  public final Pattern ALARM_NAME_PATTERN = Pattern.compile(alarmsNamePattern)

  final AmazonClientProvider amazonClientProvider
  final CredentialsRepository<NetflixAmazonCredentials> credentialsRepository
  final long pollIntervalMillis
  final long timeoutMillis
  final int daysToLeave
  final String alarmsNamePattern;
  final boolean dryRun;


  CleanupAlarmsAgent(AmazonClientProvider amazonClientProvider,
                     CredentialsRepository<NetflixAmazonCredentials> credentialsRepository,
                     int daysToLeave,
                     String alarmsNamePattern,
                     boolean dryRun) {
    this(amazonClientProvider, credentialsRepository, POLL_INTERVAL_MILLIS, DEFAULT_TIMEOUT_MILLIS, daysToLeave, alarmsNamePattern, dryRun)
  }

  CleanupAlarmsAgent(AmazonClientProvider amazonClientProvider,
                     CredentialsRepository<NetflixAmazonCredentials> credentialsRepository,
                     long pollIntervalMillis,
                     long timeoutMills,
                     int daysToLeave,
                     String alarmsNamePattern,
                     boolean dryRun) {
    this.amazonClientProvider = amazonClientProvider
    this.credentialsRepository = credentialsRepository
    this.pollIntervalMillis = pollIntervalMillis
    this.timeoutMillis = timeoutMills
    this.daysToLeave = daysToLeave
    this.alarmsNamePattern = alarmsNamePattern
    this.dryRun = dryRun
  }

  @Override
  String getAgentType() {
    "${CleanupAlarmsAgent.simpleName}"
  }

  @Override
  String getProviderName() {
    return AwsCleanupProvider.PROVIDER_NAME
  }

  @Override
  void run() {
    getAccounts().each { NetflixAmazonCredentials credentials ->
      credentials.regions.each { AmazonCredentials.AWSRegion region ->
        log.info("Looking for alarms to delete")
        try {

          def cloudWatch = amazonClientProvider.getCloudWatch(credentials, region.name)
          def ecs = amazonClientProvider.getAmazonEcs(credentials, region.name, true)

          Set<MetricAlarm> autoscalingAlarms = []
          def describeAlarmsRequest = new DescribeAlarmsRequest().withStateValue(StateValue.INSUFFICIENT_DATA)

          while (true) {
            def result = cloudWatch.describeAlarms(describeAlarmsRequest)
            autoscalingAlarms.addAll(result.metricAlarms.findAll {
              it.stateUpdatedTimestamp.before(DateTime.now().minusDays(daysToLeave).toDate()) &&
                ALARM_NAME_PATTERN.matcher(it.alarmName).matches()
            })

            if (result.nextToken) {
              describeAlarmsRequest.withNextToken(result.nextToken)
            } else {
              break
            }
          }
          Pattern ALARM_NAME_PATTERN_2 = Pattern.compile("v[0-9]{3}")
          Map<String, List<MetricAlarm>> autoscalingMap = autoscalingAlarms.groupBy {
            def matcher = ALARM_NAME_PATTERN_2.matcher(it.alarmName)
            matcher.find() ? it.alarmName.substring(0, matcher.end()) : null
          }


          log.info("Number of services whose autoscaling alarms still exist: ${autoscalingMap.size()}")

          // Hunt through the ECS clusters looking for ECS services and match them to the alarms
          def listClustersRequest = new ListClustersRequest()
          while (true) {
            def clustersResult = ecs.listClusters(listClustersRequest)
            clustersResult.clusterArns.each { clusterArn ->
              def clusterName = clusterArn.split('/').last()
              def listServicesRequest = new ListServicesRequest().withCluster(clusterName)
              while (true) {
                def servicesResult = ecs.listServices(listServicesRequest)
                servicesResult.serviceArns.each { serviceArn ->
                  log.info("checking service $serviceArn")
                  def serviceName = serviceArn.split('/').last()
                  autoscalingMap.remove(serviceName)
                }

                if (servicesResult.nextToken) {
                  listServicesRequest.withNextToken(servicesResult.nextToken)
                } else {
                  break
                }
              }
            }

            if (clustersResult.nextToken) {
              listClustersRequest.withNextToken(clustersResult.nextToken)
            } else {
              break
            }
          }

          log.info("Number of services whose autoscaling alarms still exist, but the services do not: ${autoscalingMap.size()}")

          if (autoscalingMap) {
            def alarmNamesToDelete = autoscalingMap.values().flatten().collect { it }
            log.info("Removing ${alarmNamesToDelete.size()} alarms...")
            alarmNamesToDelete.collate(100).each {
              log.info("Deleting ${it.size()} alarms in ${credentials.name}/${region.name} " +
                "(alarms: ${it.alarmName.join(", ")})")
              if (!dryRun) {
                cloudWatch.deleteAlarms(new DeleteAlarmsRequest().withAlarmNames(it.alarmName))
              }
              Thread.sleep(500)
            }
          }
        } catch (Exception e) {
          log.error("Failed to cleanup alarms for ${credentials.name}/${region.name}", e)
        }
      }
    }
  }

  private Set<NetflixAmazonCredentials> getAccounts() {
    return credentialsRepository.getAll()
  }
}
