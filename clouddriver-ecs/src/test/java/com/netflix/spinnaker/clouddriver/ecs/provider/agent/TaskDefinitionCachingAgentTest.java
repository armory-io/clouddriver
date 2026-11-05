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

package com.netflix.spinnaker.clouddriver.ecs.provider.agent;

import static com.netflix.spinnaker.clouddriver.ecs.cache.Keys.Namespace.SERVICES;
import static com.netflix.spinnaker.clouddriver.ecs.cache.Keys.Namespace.TASK_DEFINITIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.model.AccessDeniedException;
import com.amazonaws.services.ecs.model.AmazonECSException;
import com.amazonaws.services.ecs.model.ClientException;
import com.amazonaws.services.ecs.model.DescribeTaskDefinitionRequest;
import com.amazonaws.services.ecs.model.DescribeTaskDefinitionResult;
import com.amazonaws.services.ecs.model.InvalidParameterException;
import com.amazonaws.services.ecs.model.ServerException;
import com.amazonaws.services.ecs.model.TaskDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spectator.api.NoopRegistry;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.cats.cache.DefaultCacheData;
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider;
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent;
import com.netflix.spinnaker.clouddriver.ecs.cache.Keys;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import spock.lang.Subject;

public class TaskDefinitionCachingAgentTest extends CommonCachingAgent {
  ObjectMapper mapper = new ObjectMapper();

  @Subject
  private final TaskDefinitionCachingAgent agent =
      new TaskDefinitionCachingAgent(
          netflixAmazonCredentials, REGION, clientProvider, credentialsProvider, registry, mapper);

  @Test
  public void shouldGetListOfTaskDefinitions() {
    // Given
    Map<String, Object> serviceAttr = new HashMap<>();
    serviceAttr.put("taskDefinition", TASK_DEFINITION_ARN_1);
    serviceAttr.put("desiredCount", 1);
    serviceAttr.put("serviceName", SERVICE_NAME_1);
    serviceAttr.put("maximumPercent", 200);
    serviceAttr.put("minimumHealthyPercent", 50);
    serviceAttr.put("createdAt", 8976543L);

    DefaultCacheData serviceCache =
        new DefaultCacheData("test-service", serviceAttr, Collections.emptyMap());
    when(providerCache.filterIdentifiers(
            SERVICES.toString(), "ecs;services;test-account;us-west-2;*"))
        .thenReturn(Collections.singletonList("test-service"));
    when(providerCache.getAll(anyString(), any(Set.class)))
        .thenReturn(Collections.singletonList(serviceCache));

    DescribeTaskDefinitionResult describeTaskDefinitionResult =
        new DescribeTaskDefinitionResult()
            .withTaskDefinition(new TaskDefinition().withTaskDefinitionArn(TASK_DEFINITION_ARN_1));
    when(ecs.describeTaskDefinition(any(DescribeTaskDefinitionRequest.class)))
        .thenReturn(describeTaskDefinitionResult);

    // When
    List<TaskDefinition> returnedTaskDefs = agent.getItems(ecs, providerCache);

    // Then
    assertEquals(
        1,
        returnedTaskDefs.size(),
        "Expected the list to contain 1 ECS task definition, but got " + returnedTaskDefs.size());
    for (TaskDefinition taskDef : returnedTaskDefs) {
      assertEquals(
          taskDef.getTaskDefinitionArn(),
          TASK_DEFINITION_ARN_1,
          "Expected the task definition ARN to be  "
              + TASK_DEFINITION_ARN_1
              + " but it was: "
              + taskDef.getTaskDefinitionArn());
    }
  }

  @Test
  public void shouldRetainCachedTaskDefinitions() {
    // Given
    Map<String, Object> serviceAttr = new HashMap<>();
    serviceAttr.put("taskDefinition", TASK_DEFINITION_ARN_1);
    serviceAttr.put("desiredCount", 1);
    serviceAttr.put("serviceName", SERVICE_NAME_1);
    serviceAttr.put("maximumPercent", 200);
    serviceAttr.put("minimumHealthyPercent", 50);
    serviceAttr.put("createdAt", 8976543L);

    DefaultCacheData serviceCache =
        new DefaultCacheData("test-service", serviceAttr, Collections.emptyMap());
    when(providerCache.filterIdentifiers(
            SERVICES.toString(), "ecs;services;test-account;us-west-2;*"))
        .thenReturn(Collections.singletonList("test-service"));
    when(providerCache.getAll(anyString(), any(Set.class)))
        .thenReturn(Collections.singletonList(serviceCache));

    Map<String, Object> taskDefAttr = new HashMap<>();
    taskDefAttr.put("taskDefinitionArn", TASK_DEFINITION_ARN_1);

    DefaultCacheData taskDefCache =
        new DefaultCacheData(TASK_DEFINITION_ARN_1, taskDefAttr, Collections.emptyMap());
    when(providerCache.get(
            TASK_DEFINITIONS.toString(),
            "ecs;taskDefinitions;test-account;us-west-2;" + TASK_DEFINITION_ARN_1))
        .thenReturn(taskDefCache);

    // When
    List<TaskDefinition> returnedTaskDefs = agent.getItems(ecs, providerCache);

    // Then
    assertEquals(
        1,
        returnedTaskDefs.size(),
        "Expected the list to contain 1 ECS task definition, but got " + returnedTaskDefs.size());
    for (TaskDefinition taskDef : returnedTaskDefs) {
      assertEquals(
          taskDef.getTaskDefinitionArn(),
          TASK_DEFINITION_ARN_1,
          "Expected the task definition ARN to be  "
              + TASK_DEFINITION_ARN_1
              + " but it was: "
              + taskDef.getTaskDefinitionArn());
    }
  }

  @Test
  public void shouldGenerateFreshData() {
    // Given
    List<String> taskDefinitionArns = new LinkedList<>();
    taskDefinitionArns.add(TASK_DEFINITION_ARN_1);
    taskDefinitionArns.add(TASK_DEFINITION_ARN_2);

    List<TaskDefinition> tasks = new LinkedList<>();
    Set<String> keys = new HashSet<>();
    for (String taskDefArn : taskDefinitionArns) {
      keys.add(Keys.getTaskDefinitionKey(ACCOUNT, REGION, taskDefArn));

      tasks.add(
          new TaskDefinition()
              .withTaskDefinitionArn(taskDefArn)
              .withContainerDefinitions(Collections.emptyList()));
    }

    // When
    Map<String, Collection<CacheData>> dataMap = agent.generateFreshData(tasks);

    // Then
    assertTrue(
        dataMap.keySet().size() == 1,
        "Expected the data map to contain 1 namespaces, but it contains "
            + dataMap.keySet().size()
            + " namespaces.");
    assertTrue(
        dataMap.containsKey(TASK_DEFINITIONS.toString()),
        "Expected the data map to contain "
            + TASK_DEFINITIONS.toString()
            + " namespace, but it contains "
            + dataMap.keySet()
            + " namespaces.");
    assertTrue(
        dataMap.get(TASK_DEFINITIONS.toString()).size() == 2,
        "Expected there to be 2 CacheData, instead there is  "
            + dataMap.get(TASK_DEFINITIONS.toString()).size());

    for (CacheData cacheData : dataMap.get(TASK_DEFINITIONS.toString())) {
      assertTrue(
          keys.contains(cacheData.getId()),
          "Expected the key to be one of the following keys: "
              + keys.toString()
              + ". The key is: "
              + cacheData.getId()
              + ".");
      assertTrue(
          taskDefinitionArns.contains(cacheData.getAttributes().get("taskDefinitionArn")),
          "Expected the task definition ARN to be one of the following ARNs: "
              + taskDefinitionArns.toString()
              + ". The task definition  ARN is: "
              + cacheData.getAttributes().get("taskDefinitionArn")
              + ".");
    }
  }

  @Test
  public void shouldSkipServicesWithoutTaskDefinition() {
    // Given a service on the EXTERNAL deployment controller, which keeps its task definitions on
    // task sets, so ECS returns it without one
    givenServices(service(TASK_DEFINITION_ARN_1), service(null), service(""));
    AmazonECS ecsClient = mock(AmazonECS.class);
    givenDescribedTaskDefinition(ecsClient, taskDefinition(TASK_DEFINITION_ARN_1));

    // When
    List<TaskDefinition> returnedTaskDefs = agent.getItems(ecsClient, providerCache);

    // Then only the service with a task definition is described
    assertEquals(List.of(TASK_DEFINITION_ARN_1), arns(returnedTaskDefs));
    verify(ecsClient, times(1)).describeTaskDefinition(any(DescribeTaskDefinitionRequest.class));
    verify(ecsClient).describeTaskDefinition(describeRequestFor(TASK_DEFINITION_ARN_1));
  }

  @Test
  public void shouldSkipServicesWithoutTaskDefinitionOnDemand() {
    // Given
    givenServices(service(TASK_DEFINITION_ARN_1), service(TASK_DEFINITION_ARN_2), service(null));
    AmazonECS ecsClient = mock(AmazonECS.class);
    when(ecsClient.describeTaskDefinition(describeRequestFor(TASK_DEFINITION_ARN_1)))
        .thenThrow(invalidParameter());
    givenDescribedTaskDefinition(ecsClient, taskDefinition(TASK_DEFINITION_ARN_2));
    AmazonClientProvider amazonClientProvider = mock(AmazonClientProvider.class);
    when(amazonClientProvider.getAmazonEcs(netflixAmazonCredentials, REGION, false))
        .thenReturn(ecsClient);
    TaskDefinitionCachingAgent onDemandAgent =
        new TaskDefinitionCachingAgent(
            netflixAmazonCredentials,
            REGION,
            amazonClientProvider,
            credentialsProvider,
            new NoopRegistry(),
            mapper);

    // When
    OnDemandAgent.OnDemandResult result =
        onDemandAgent.handle(providerCache, Map.of("account", ACCOUNT, "region", REGION));

    // Then
    assertEquals(
        Set.of(Keys.getTaskDefinitionKey(ACCOUNT, REGION, TASK_DEFINITION_ARN_2)),
        result.getCacheResult().getCacheResults().get(TASK_DEFINITIONS.toString()).stream()
            .map(CacheData::getId)
            .collect(Collectors.toSet()));
  }

  @Test
  public void shouldCacheOtherTaskDefinitionsWhenOneIsRejected() {
    // Given
    givenServices(service(TASK_DEFINITION_ARN_1), service(TASK_DEFINITION_ARN_2));
    AmazonECS ecsClient = mock(AmazonECS.class);
    when(ecsClient.describeTaskDefinition(describeRequestFor(TASK_DEFINITION_ARN_1)))
        .thenThrow(invalidParameter());
    givenDescribedTaskDefinition(ecsClient, taskDefinition(TASK_DEFINITION_ARN_2));

    // When
    List<TaskDefinition> returnedTaskDefs = agent.getItems(ecsClient, providerCache);

    // Then
    assertEquals(List.of(TASK_DEFINITION_ARN_2), arns(returnedTaskDefs));
  }

  @Test
  public void shouldFailOnErrorsNotSpecificToOneTaskDefinition() {
    givenServices(service(TASK_DEFINITION_ARN_1), service(TASK_DEFINITION_ARN_2));

    List<RuntimeException> accountWideErrors =
        List.of(
            throttling(),
            new AccessDeniedException("not authorized"),
            new ClientException("not authorized to perform the action"),
            new ServerException("Service unavailable"),
            new SdkClientException("Unable to load credentials"));

    for (RuntimeException error : accountWideErrors) {
      AmazonECS ecsClient = mock(AmazonECS.class);
      when(ecsClient.describeTaskDefinition(any(DescribeTaskDefinitionRequest.class)))
          .thenThrow(error);

      // the run fails without an authoritative result, so the existing cache is left intact
      RuntimeException thrown =
          assertThrows(RuntimeException.class, () -> agent.getItems(ecsClient, providerCache));
      assertSame(error, thrown);
      verify(ecsClient, times(1)).describeTaskDefinition(any(DescribeTaskDefinitionRequest.class));
    }
  }

  private static AmazonECSException throttling() {
    AmazonECSException throttling = new AmazonECSException("Rate exceeded");
    throttling.setErrorCode("ThrottlingException");
    throttling.setStatusCode(400);
    return throttling;
  }

  private static InvalidParameterException invalidParameter() {
    return new InvalidParameterException("The specified parameter isn't valid.");
  }

  private static DescribeTaskDefinitionRequest describeRequestFor(String arn) {
    return argThat(
        (DescribeTaskDefinitionRequest request) ->
            request != null && arn.equals(request.getTaskDefinition()));
  }

  private static List<String> arns(List<TaskDefinition> taskDefinitions) {
    return taskDefinitions.stream().map(TaskDefinition::getTaskDefinitionArn).sorted().toList();
  }

  private static DefaultCacheData service(String taskDefinitionArn) {
    Map<String, Object> serviceAttr = new HashMap<>();
    serviceAttr.put("taskDefinition", taskDefinitionArn);
    serviceAttr.put("desiredCount", 1);
    serviceAttr.put("serviceName", SERVICE_NAME_1);
    serviceAttr.put("maximumPercent", 200);
    serviceAttr.put("minimumHealthyPercent", 50);
    serviceAttr.put("createdAt", 8976543L);
    return new DefaultCacheData("service-" + taskDefinitionArn, serviceAttr, Map.of());
  }

  private void givenServices(DefaultCacheData... services) {
    when(providerCache.filterIdentifiers(
            SERVICES.toString(), "ecs;services;test-account;us-west-2;*"))
        .thenReturn(
            Arrays.stream(services).map(DefaultCacheData::getId).collect(Collectors.toSet()));
    when(providerCache.getAll(anyString(), any(Set.class))).thenReturn(Arrays.asList(services));
  }

  private static void givenDescribedTaskDefinition(
      AmazonECS ecsClient, TaskDefinition taskDefinition) {
    when(ecsClient.describeTaskDefinition(
            describeRequestFor(taskDefinition.getTaskDefinitionArn())))
        .thenReturn(new DescribeTaskDefinitionResult().withTaskDefinition(taskDefinition));
  }

  private static TaskDefinition taskDefinition(String arn) {
    return new TaskDefinition().withTaskDefinitionArn(arn);
  }
}
