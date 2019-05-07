/*
 * Copyright 2018 Datadog, Inc.
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

package com.netflix.spinnaker.clouddriver.artifacts.s3;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.netflix.spinnaker.clouddriver.artifacts.config.ArtifactCredentials;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import groovy.util.logging.Slf4j;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Slf4j
public class S3ArtifactCredentials implements ArtifactCredentials {
  private final static String DEFAULT_STS_SESSION_NAME = "Clouddriver";
  @Getter
  private final String name;
  @Getter
  private final List<String> types = Collections.singletonList("s3/object");

  private final String apiEndpoint;
  private final String apiRegion;
  private final String region;
  private final String awsAccessKeyId;
  private final String awsSecretAccessKey;
  private final String assumeRole;
  private final String assumeRoleAccountId;

  S3ArtifactCredentials(S3ArtifactAccount account) throws IllegalArgumentException {
    name = account.getName();
    apiEndpoint = account.getApiEndpoint();
    apiRegion = account.getApiRegion();
    region = account.getRegion();
    awsAccessKeyId = account.getAwsAccessKeyId();
    awsSecretAccessKey = account.getAwsSecretAccessKey();
    assumeRole = account.getAssumeRole();
    assumeRoleAccountId = account.getAssumeRoleAccountId();
  }

  private AmazonS3 getS3Client() {
    AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();

    if (!StringUtils.isEmpty(apiEndpoint)) {
      AwsClientBuilder.EndpointConfiguration endpoint = new AwsClientBuilder.EndpointConfiguration(apiEndpoint, apiRegion);
      builder.setEndpointConfiguration(endpoint);
      builder.setPathStyleAccessEnabled(true);
    } else if (!StringUtils.isEmpty(region)) {
      builder.setRegion(region);
    }

    AWSCredentialsProvider credentialsProvider = getAWSCredentialsProvider();
    if (credentialsProvider != null) {
      builder.withCredentials(credentialsProvider);
    }
    return builder.build();
  }

  private AWSCredentialsProvider getAWSCredentialsProvider() {
    AWSCredentialsProvider staticCredProvider = null;
    if (!StringUtils.isEmpty(awsAccessKeyId) && !StringUtils.isEmpty(awsSecretAccessKey)) {
      BasicAWSCredentials awsStaticCreds = new BasicAWSCredentials(awsAccessKeyId, awsSecretAccessKey);
      staticCredProvider = new AWSStaticCredentialsProvider(awsStaticCreds);
    }

    if (!StringUtils.isEmpty(assumeRole)) {
      AWSSecurityTokenServiceClientBuilder builder = AWSSecurityTokenServiceClientBuilder.standard();
      if (staticCredProvider != null) {
        builder.withCredentials(staticCredProvider);
      }
      AWSSecurityTokenService sts = builder.build();
      return new STSAssumeRoleSessionCredentialsProvider.Builder(awsAssumeRoleArn(), DEFAULT_STS_SESSION_NAME)
        .withStsClient(sts)
        .build();
    }
    return staticCredProvider;
  }

  protected String awsAssumeRoleArn() {
    if (assumeRole.startsWith("arn:")) {
      return assumeRole;
    }
    return String.format("arn:aws:iam::%s:%s", Objects.requireNonNull(assumeRoleAccountId, "accountId"), assumeRole);
  }


  @Override
  public InputStream download(Artifact artifact) throws IllegalArgumentException {
    String reference = artifact.getReference();
    if (reference.startsWith("s3://")) {
      reference = reference.substring("s3://".length());
    }

    int slash = reference.indexOf("/");
    if (slash <= 0) {
      throw new IllegalArgumentException("S3 references must be of the format s3://<bucket>/<file-path>, got: " + artifact);
    }
    String bucketName = reference.substring(0, slash);
    String path = reference.substring(slash + 1);
    S3Object s3obj = getS3Client().getObject(bucketName, path);
    return s3obj.getObjectContent();
  }
}
