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
import com.amazonaws.services.lambda.model.GetFunctionConcurrencyRequest;
import com.amazonaws.services.lambda.model.GetFunctionConcurrencyResult;
import com.netflix.spinnaker.clouddriver.lambda.deploy.description.GetLambdaReservedConcurrencyDescription;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import java.util.List;

public class GetLambdaReservedConcurrencyAtomicOperation
    extends AbstractLambdaAtomicOperation<
        GetLambdaReservedConcurrencyDescription, GetFunctionConcurrencyResult>
    implements AtomicOperation<GetFunctionConcurrencyResult> {

  public GetLambdaReservedConcurrencyAtomicOperation(
      GetLambdaReservedConcurrencyDescription description) {
    super(description, "GET_LAMBDA_FUNCTION_RESERVED_CONCURRENCY");
  }

  @Override
  public GetFunctionConcurrencyResult operate(List priorOutputs) {
    updateTaskStatus("Initializing Atomic Operation AWS Lambda for GetReservedConcurrency...");
    return getReservedFunctionConcurrency(description.getFunctionName());
  }

  private GetFunctionConcurrencyResult getReservedFunctionConcurrency(String functionName) {
    AWSLambda client = getLambdaClient();
    GetFunctionConcurrencyRequest req =
        new GetFunctionConcurrencyRequest().withFunctionName(functionName);

    GetFunctionConcurrencyResult result = client.getFunctionConcurrency(req);
    updateTaskStatus("Finished Atomic Operation AWS Lambda for GetReservedConcurrency...");
    return result;
  }
}
