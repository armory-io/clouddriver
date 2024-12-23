/*
 * Copyright 2017 Lookout, Inc.
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
 */

package com.netflix.spinnaker.clouddriver.ecs.cache

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.cloudwatch.AmazonCloudWatch
import com.amazonaws.services.cloudwatch.model.Dimension
import com.amazonaws.services.cloudwatch.model.MetricAlarm
import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.DefaultCacheData
import com.netflix.spinnaker.cats.provider.ProviderCache
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider
import com.netflix.spinnaker.clouddriver.ecs.cache.client.EcsCloudWatchAlarmCacheClient
import com.netflix.spinnaker.clouddriver.ecs.cache.model.EcsMetricAlarm
import com.netflix.spinnaker.clouddriver.ecs.provider.agent.CommonCachingAgent
import com.netflix.spinnaker.clouddriver.ecs.provider.agent.EcsCloudMetricAlarmCachingAgent
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject

class EcsCloudWatchAlarmCacheClientSpec extends Specification {
  @Subject
  EcsCloudWatchAlarmCacheClient client

  @Subject
  EcsCloudMetricAlarmCachingAgent agent

  @Shared
  String ACCOUNT = 'test-account'

  @Shared
  String REGION = 'us-west-1'

  Cache cacheView
  AmazonCloudWatch cloudWatch
  AmazonClientProvider clientProvider
  ProviderCache providerCache
  AWSCredentialsProvider credentialsProvider

  def setup() {
    cacheView = Mock(Cache)
    client = new EcsCloudWatchAlarmCacheClient(cacheView)
    cloudWatch = Mock(AmazonCloudWatch)
    clientProvider = Mock(AmazonClientProvider)
    providerCache = Mock(ProviderCache)
    credentialsProvider = Mock(AWSCredentialsProvider)
    agent = new EcsCloudMetricAlarmCachingAgent(CommonCachingAgent.netflixAmazonCredentials, REGION, clientProvider)
  }

  def 'should convert cache data into object'() {
    given:

    def metricAlarm = new EcsMetricAlarm().withAlarmName("alarm-name").withAlarmArn("alarmArn").withRegion(REGION).withAccountName(ACCOUNT)
    def key = Keys.getAlarmKey(ACCOUNT, REGION, metricAlarm.getAlarmArn())
    def attributes = EcsCloudMetricAlarmCachingAgent.convertMetricAlarmToAttributes(metricAlarm, ACCOUNT, REGION)
    when:
    def returnedMetricAlarm = client.get(key)

    then:
    cacheView.get(Keys.Namespace.ALARMS.ns, key) >> new DefaultCacheData(key, attributes, [:])
    returnedMetricAlarm == metricAlarm
  }


  def 'should return metric alarms for a service - single cluster'() {
    given:
    def serviceName = 'my-service'
    def serviceName2 = 'not-matching-service'

    def ecsClusterName = 'my-cluster'
    def metricAlarms = Set.of(
      new MetricAlarm().withAlarmName("alarm-name").withAlarmArn("alarmArn")
      .withAlarmActions("arn:aws:sns:us-west-1:123456789012:${serviceName}")
      .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName)]),
      new MetricAlarm().withAlarmName("alarm-name-2").withAlarmArn("alarmArn2")
        .withAlarmActions("arn:aws:sns:us-west-1:123456789012:${serviceName}")
        .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName)]),
      new MetricAlarm().withAlarmName("alarm-name").withAlarmArn("alarmArn3")
        .withAlarmActions("arn:aws:sns:us-west-1:123456789012:${serviceName2}")
        .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName)])
    )
    def keys = metricAlarms.collect { alarm ->
      def key = Keys.getAlarmKey(ACCOUNT, REGION, alarm.getAlarmArn())
      def attributes = agent.convertMetricAlarmToAttributes(alarm, ACCOUNT, REGION)
      [key, new DefaultCacheData(key, attributes, [:])]
    }

    cacheView.filterIdentifiers(Keys.Namespace.ALARMS.ns, _) >> keys*.first()
    cacheView.getAll(Keys.Namespace.ALARMS.ns, _) >>  keys*.last()

    when:
    def metricAlarmsReturned = client.getMetricAlarms(serviceName, ACCOUNT, REGION)

    then:
    metricAlarmsReturned.size() == 2
    metricAlarmsReturned*.alarmName.containsAll(["alarm-name", "alarm-name-2"])
    metricAlarmsReturned*.alarmArn.containsAll(["alarmArn", "alarmArn2"])
    !metricAlarmsReturned*.alarmArn.contains(["alarmArn3"])
}

  def 'should return metric alarms for a service - multiple clusters'() {
    given:
    def serviceName = 'my-service'

    def ecsClusterName = 'my-cluster'
    def ecsClusterName2 = 'my-cluster-2'
    def metricAlarm1 = new MetricAlarm().withAlarmName("alarm-name").withAlarmArn("alarmArn")
      .withAlarmActions("arn:aws:sns:us-west-1:123456789012:${serviceName}")
      .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName)])
    def metricAlarm2 = new MetricAlarm().withAlarmName("alarm-name-2").withAlarmArn("alarmArn2")
      .withAlarmActions("arn:aws:sns:us-west-1:123456789012:${serviceName}")
      .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName)])
    def metricAlarm3 =  new MetricAlarm().withAlarmName("alarm-name-3").withAlarmArn("alarmArn3")
      .withAlarmActions("arn:aws:sns:us-west-1:123456789012:${serviceName}")
      .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName2)])

    def key1 = Keys.getAlarmKey(ACCOUNT, REGION, metricAlarm1.getAlarmArn())
    def attributes1 = agent.convertMetricAlarmToAttributes(metricAlarm1, ACCOUNT, REGION)
    def key2 = Keys.getAlarmKey(ACCOUNT, REGION, metricAlarm2.getAlarmArn())
    def attributes2 = agent.convertMetricAlarmToAttributes(metricAlarm2, ACCOUNT, REGION)
    def key3 = Keys.getAlarmKey(ACCOUNT, REGION, metricAlarm3.getAlarmArn())
    def attributes3 = agent.convertMetricAlarmToAttributes(metricAlarm3, ACCOUNT, REGION)


    cacheView.filterIdentifiers(Keys.Namespace.ALARMS.ns, Keys.getAlarmKey(ACCOUNT, REGION, "*")) >> [key1,key2,key3]
    cacheView.getAll(Keys.Namespace.ALARMS.ns, [key1,key2,key3]) >> [
      new DefaultCacheData(key1, attributes1, [:]),
      new DefaultCacheData(key2, attributes2, [:]),
      new DefaultCacheData(key3, attributes3, [:])
    ]
    when:
    def metricAlarmsReturned = client.getMetricAlarms(serviceName, ACCOUNT, REGION)

    then:
    metricAlarmsReturned.size() == 3
    metricAlarmsReturned*.alarmName.containsAll(["alarm-name", "alarm-name-2","alarm-name-3"])
    metricAlarmsReturned*.alarmArn.containsAll(["alarmArn", "alarmArn2","alarmArn3"])
  }

def 'should return empty list if no metric alarms match the service'() {
  given:
  def serviceName = 'my-service'

  def ecsClusterName = 'my-cluster'
  def metricAlarms = Set.of(new MetricAlarm().withAlarmName("alarm-name").withAlarmArn("alarmArn")
    .withAlarmActions("arn:aws:sns:us-west-1:123456789012:${serviceName}")
    .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName)]))
  def key = Keys.getAlarmKey(ACCOUNT, REGION, metricAlarms[0].getAlarmArn())
  def attributes = agent.convertMetricAlarmToAttributes(metricAlarms[0], ACCOUNT, REGION)

  cacheView.filterIdentifiers(Keys.Namespace.ALARMS.ns, _) >> [key]
  cacheView.getAll(Keys.Namespace.ALARMS.ns, _) >> [
    new DefaultCacheData(key, attributes, [:])
  ]

  when:
  def metricAlarmsReturned = client.getMetricAlarms("some-other-service", ACCOUNT, REGION)

  then:
  metricAlarmsReturned.isEmpty()
}

def 'should return metric alarms with actions matching the service'() {
  given:
  def serviceName = 'my-service'

  def ecsClusterName = 'my-cluster'
  def metricAlarms = Set.of(
    new MetricAlarm().withAlarmName("alarm-name").withAlarmArn("alarmArn")
      .withAlarmActions("arn:aws:sns:us-west-1:123456789012:${serviceName}-AlarmsActions")
      .withOKActions("arn:aws:sns:us-west-1:123456789012:${serviceName}-OKActions")
      .withInsufficientDataActions("arn:aws:sns:us-west-1:123456789012:${serviceName}-InsufficientDataActions")
      .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName)]),
    new MetricAlarm().withAlarmName("alarm-name-2").withAlarmArn("alarmArn2")
      .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName)]),
    new MetricAlarm().withAlarmName("alarm-name-3").withAlarmArn("alarmArn3")
      .withAlarmActions(
        "arn:aws:sns:us-west-1:123456789012:${serviceName}-AlarmsActions1",
        "arn:aws:sns:us-west-1:123456789012:${serviceName}-AlarmsActions2",
        "arn:aws:sns:us-west-1:123456789012:${serviceName}-AlarmsActions3"
      )
      .withOKActions(
        "arn:aws:sns:us-west-1:123456789012:${serviceName}-OKActions1"
      )
      .withInsufficientDataActions(
        "arn:aws:sns:us-west-1:123456789012:${serviceName}-InsufficientDataActions1"
      )
      .withDimensions([new Dimension().withName("ClusterName").withValue(ecsClusterName)])
  )
  def keys = metricAlarms.collect { alarm ->
    def key = Keys.getAlarmKey(ACCOUNT, REGION, alarm.getAlarmArn())
    def attributes = agent.convertMetricAlarmToAttributes(alarm, ACCOUNT, REGION)
    [key, new DefaultCacheData(key, attributes, [:])]
  }

  cacheView.filterIdentifiers(Keys.Namespace.ALARMS.ns, _) >> keys*.first()
  cacheView.getAll(Keys.Namespace.ALARMS.ns, _) >>  keys*.last()

  when:
  def metricAlarmsReturned = client.getMetricAlarms(serviceName, ACCOUNT, REGION)

  then:
  metricAlarmsReturned.size() == 2
  metricAlarmsReturned*.alarmName.containsAll(["alarm-name", "alarm-name-3"])
  metricAlarmsReturned*.alarmArn.containsAll(["alarmArn", "alarmArn3"])
  metricAlarmsReturned*.alarmActions.flatten().size() == 4
  metricAlarmsReturned*.OKActions.flatten().size() == 2
  metricAlarmsReturned*.insufficientDataActions.flatten().size() == 2
  metricAlarmsReturned*.OKActions.flatten().sort() == List.of(
    "arn:aws:sns:us-west-1:123456789012:${serviceName}-OKActions",
    "arn:aws:sns:us-west-1:123456789012:${serviceName}-OKActions1"
  )
  metricAlarmsReturned*.alarmActions.flatten().sort() == List.of(
    "arn:aws:sns:us-west-1:123456789012:${serviceName}-AlarmsActions",
    "arn:aws:sns:us-west-1:123456789012:${serviceName}-AlarmsActions1",
    "arn:aws:sns:us-west-1:123456789012:${serviceName}-AlarmsActions2",
    "arn:aws:sns:us-west-1:123456789012:${serviceName}-AlarmsActions3"
  )
  metricAlarmsReturned*.insufficientDataActions.flatten().sort() == List.of(
    "arn:aws:sns:us-west-1:123456789012:${serviceName}-InsufficientDataActions",
    "arn:aws:sns:us-west-1:123456789012:${serviceName}-InsufficientDataActions1"
  )
}

}
