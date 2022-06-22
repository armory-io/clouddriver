/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates.
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

package com.netflix.spinnaker.clouddriver.lambda.deploy.ops;

import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.model.GetProvisionedConcurrencyConfigRequest;
import com.amazonaws.services.lambda.model.GetProvisionedConcurrencyConfigResult;
import com.netflix.spinnaker.clouddriver.lambda.deploy.description.GetLambdaProvisionedConcurrencyDescription;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import java.util.List;

public class GetLambdaProvisionedConcurrencyAtomicOperation
    extends AbstractLambdaAtomicOperation<
        GetLambdaProvisionedConcurrencyDescription, GetProvisionedConcurrencyConfigResult>
    implements AtomicOperation<GetProvisionedConcurrencyConfigResult> {

  public GetLambdaProvisionedConcurrencyAtomicOperation(
      GetLambdaProvisionedConcurrencyDescription description) {
    super(description, "GET_LAMBDA_FUNCTION_PROVISIONED_CONCURRENCY");
  }

  @Override
  public GetProvisionedConcurrencyConfigResult operate(List priorOutputs) {
    updateTaskStatus("Initializing Atomic Operation AWS Lambda for GetProvisionedConcurrency...");
    return getProvisionedFunctionConcurrency(
        description.getFunctionName(), description.getQualifier());
  }

  private GetProvisionedConcurrencyConfigResult getProvisionedFunctionConcurrency(
      String functionName, String qualifier) {
    AWSLambda client = getLambdaClient();
    GetProvisionedConcurrencyConfigRequest req =
        new GetProvisionedConcurrencyConfigRequest()
            .withFunctionName(functionName)
            .withQualifier(qualifier);

    GetProvisionedConcurrencyConfigResult result = client.getProvisionedConcurrencyConfig(req);
    updateTaskStatus("Finished Atomic Operation AWS Lambda for GetProvisionedConcurrency...");
    return result;
  }
}
