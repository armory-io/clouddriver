/*
 * Copyright 2025 Netflix, Inc.
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

package com.netflix.spinnaker.clouddriver.artifacts.docker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

@Slf4j
public class DockerRegistryAuthUtil {
  private static final String AUTH_REALM = "https://auth.docker.io";
  private static final String TOKEN_PATH = "token";
  private static final String SERVICE = "registry.docker.io";

  @Data
  private static class TokenResponse {
    @JsonProperty("token")
    private String token;

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("expires_in")
    private Integer expiresIn;
  }

  /**
   * Creates a basic auth token from username and password
   *
   * @param username Docker registry username
   * @param password Docker registry password
   * @return Basic auth token
   */
  public static String createBasicAuthToken(String username, String password) {
    String auth = username + ":" + password;
    return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Gets a bearer token for Docker registry authentication
   *
   * @param username Docker registry username
   * @param password Docker registry password
   * @param repository Docker repository (format: user/repository)
   * @return Bearer token
   */
  public static String getBearerToken(String username, String password, String repository)
      throws Exception {
    String basicAuth = createBasicAuthToken(username, password);

    OkHttpClient client = new OkHttpClient();
    HttpUrl url =
        HttpUrl.parse(AUTH_REALM)
            .newBuilder()
            .addPathSegment(TOKEN_PATH)
            .addQueryParameter("service", SERVICE)
            .addQueryParameter("scope", "repository:" + repository + ":pull")
            .build();

    Request request = new Request.Builder().url(url).header("Authorization", basicAuth).build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new RuntimeException("Failed to get bearer token: " + response.code());
      }

      ObjectMapper mapper = new ObjectMapper();
      TokenResponse tokenResponse = mapper.readValue(response.body().string(), TokenResponse.class);

      // Docker registry may return either token or access_token
      String token =
          tokenResponse.getToken() != null
              ? tokenResponse.getToken()
              : tokenResponse.getAccessToken();
      if (token == null) {
        throw new RuntimeException("No token found in response");
      }

      return "Bearer " + token;
    }
  }
}
