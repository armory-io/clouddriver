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

package com.netflix.spinnaker.clouddriver.utils.docker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.netflix.spinnaker.kork.retrofit.Retrofit2SyncCall;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.*;

@Slf4j
@Service
public class DockerBearerTokenService {
  private final Map<String, TokenService> realmToService = new ConcurrentHashMap<>();
  private final Map<String, DockerBearerToken> cachedTokens = new ConcurrentHashMap<>();
  @Nullable private final String username;
  @Nullable private final String password;
  @Nullable private final String passwordCommand;
  @Nullable private final File passwordFile;
  @Nullable private String authWarning;
  static final String userAgent = "Spinnaker/v1";

  /** Default constructor for cases where no authentication is needed. */
  public DockerBearerTokenService() {
    this.username = null;
    this.password = null;
    this.passwordCommand = null;
    this.passwordFile = null;
    this.authWarning = null;
  }

  /**
   * Constructor for username/password based authentication with optional password command.
   *
   * @param username The username for authentication
   * @param password The password for authentication (can be null if passwordCommand is provided)
   * @param passwordCommand Optional command to execute to obtain the password
   */
  public DockerBearerTokenService(
      @Nullable String username, @Nullable String password, @Nullable String passwordCommand) {
    this.username = username;
    this.password = password;
    this.passwordCommand = passwordCommand;
    this.passwordFile = null;
    this.authWarning = null;
  }

  /**
   * Constructor for username with password file based authentication.
   *
   * @param username The username for authentication
   * @param passwordFile File containing the password
   */
  public DockerBearerTokenService(@Nullable String username, @Nullable File passwordFile) {
    this.username = username;
    this.password = null;
    this.passwordCommand = null;
    this.passwordFile = passwordFile;
    this.authWarning = null;
  }

  @Data
  public static class DockerBearerToken {
    @JsonProperty("token")
    private String token;

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("expires_in")
    private Integer expiresIn;

    @JsonProperty("issued_at")
    private String issuedAt;

    public String getToken() {
      return token != null ? token : accessToken;
    }
  }

  @Data
  public static class DockerManifest {
    @JsonProperty("schemaVersion")
    private int schemaVersion;

    @JsonProperty("config")
    private DockerConfig config;

    @JsonProperty("layers")
    private List<DockerLayer> layers;

    @JsonProperty("annotations")
    private Map<String, String> annotations;
  }

  @Data
  public static class DockerRegistryTags {
    @JsonProperty("name")
    private String name;

    @JsonProperty("tags")
    private List<String> tags;
  }

  @Data
  public static class DockerConfig {
    @JsonProperty("mediaType")
    private String mediaType;

    @JsonProperty("digest")
    private String digest;

    @JsonProperty("size")
    private int size;
  }

  @Data
  public static class DockerLayer {
    @JsonProperty("mediaType")
    private String mediaType;

    @JsonProperty("digest")
    private String digest;

    @JsonProperty("size")
    private int size;
  }

  @Data
  public static class AuthenticateDetails {
    private String realm;
    private String service;
    private String scope;
  }

  public interface TokenService {
    @GET("/{path}")
    @Headers({"Docker-Distribution-API-Version: registry/2.0"})
    Call<DockerBearerToken> getToken(
        @Path(value = "path", encoded = true) String path,
        @Query(value = "service") String service,
        @Query(value = "scope") String scope,
        @Header("Authorization") String auth,
        @Header("User-Agent") String userAgent);

    @GET("/{path}")
    @Headers({"Docker-Distribution-API-Version: registry/2.0"})
    Call<DockerManifest> getManifest(
        @Path(value = "path", encoded = true) String path,
        @Header("Authorization") String auth,
        @Header("User-Agent") String userAgent);

    @GET("/{path}")
    @Headers({"Docker-Distribution-API-Version: registry/2.0"})
    Call<ResponseBody> downloadBlob(
        @Path(value = "path", encoded = true) String path,
        @Header("Authorization") String auth,
        @Header("User-Agent") String userAgent);

    @GET("/v2/{repository}/tags/list")
    @Headers({"Docker-Distribution-API-Version: registry/2.0"})
    Call<DockerRegistryTags> getTags(
        @Path(value = "repository", encoded = true) String repository,
        @Header("Authorization") String auth,
        @Header("User-Agent") String userAgent);
  }

  private String readStream(InputStream inputStream) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
      StringBuilder output = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line);
      }
      return output.toString();
    }
  }

  private String resolvePasswordFromCommand() {
    try {
      ProcessBuilder pb = new ProcessBuilder("bash", "-c", passwordCommand);
      Process process = pb.start();
      int errCode = process.waitFor();
      log.debug("Full command is: {}", String.join(" ", pb.command()));

      if (errCode != 0) {
        String err = readStream(process.getErrorStream());
        log.error("Password command returned a non 0 return code, stderr/stdout was: '{}'", err);
      }
      return readStream(process.getInputStream()).trim();
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException("Failed to get password from command", e);
    }
  }

  private String resolvePasswordFromFile() {
    try (BufferedReader reader = new BufferedReader(new FileReader(passwordFile))) {
      return reader.readLine();
    } catch (IOException e) {
      throw new RuntimeException("Failed to read password file", e);
    }
  }

  private void checkPasswordWhitespace(String resolvedPassword) {
    if (resolvedPassword != null && !resolvedPassword.isEmpty()) {
      String message =
          "Your registry password has %s whitespace, if this is unintentional authentication will fail.";
      if (Character.isWhitespace(resolvedPassword.charAt(0))) {
        authWarning = String.format(message, "leading");
      }
      if (Character.isWhitespace(resolvedPassword.charAt(resolvedPassword.length() - 1))) {
        authWarning = String.format(message, "trailing");
      }
    }
  }

  public String getBasicAuth() {
    if (username == null && password == null && passwordCommand == null && passwordFile == null) {
      return null;
    }

    String resolvedPassword = null;
    if (password != null) {
      resolvedPassword = password;
    } else if (passwordCommand != null) {
      resolvedPassword = resolvePasswordFromCommand();
      log.debug("resolvedPassword is {}", resolvedPassword);
    } else if (passwordFile != null) {
      resolvedPassword = resolvePasswordFromFile();
    } else {
      resolvedPassword = ""; // Empty password is allowed if username is specified
    }

    checkPasswordWhitespace(resolvedPassword);

    String basicAuth = username + ":" + (resolvedPassword != null ? resolvedPassword : "");
    return "Basic "
        + Base64.getEncoder().encodeToString(basicAuth.getBytes(StandardCharsets.UTF_8));
  }

  public AuthenticateDetails parseBearerAuthenticateHeader(String header) {
    AuthenticateDetails result = new AuthenticateDetails();

    // Parse realm
    int realmStart = header.indexOf("realm=\"") + 7;
    int realmEnd = header.indexOf("\"", realmStart);
    if (realmStart >= 7 && realmEnd > realmStart) {
      result.setRealm(header.substring(realmStart, realmEnd));
    }

    // Parse service
    int serviceStart = header.indexOf("service=\"") + 9;
    int serviceEnd = header.indexOf("\"", serviceStart);
    if (serviceStart >= 9 && serviceEnd > serviceStart) {
      result.setService(header.substring(serviceStart, serviceEnd));
    }

    // Parse scope
    int scopeStart = header.indexOf("scope=\"") + 7;
    int scopeEnd = header.indexOf("\"", scopeStart);
    if (scopeStart >= 7 && scopeEnd > scopeStart) {
      result.setScope(header.substring(scopeStart, scopeEnd));
    }

    return result;
  }

  @Nullable
  private TokenService getTokenService(String realm) {
    return realmToService.computeIfAbsent(
        realm,
        r -> {
          Retrofit retrofit =
              new Retrofit.Builder()
                  .baseUrl(r)
                  .addConverterFactory(JacksonConverterFactory.create())
                  .build();
          return retrofit.create(TokenService.class);
        });
  }

  public DockerBearerToken getToken(String repository) {
    // Check cache first
    DockerBearerToken cachedToken = cachedTokens.get(repository);
    if (cachedToken != null) {
      // TODO: Check if token is expired
      return cachedToken;
    }

    String basicAuth = getBasicAuth();
    if (basicAuth == null) {
      throw new RuntimeException("No authentication credentials provided");
    }

    // Get token service for the realm
    TokenService tokenService = getTokenService("https://auth.docker.io");
    if (tokenService == null) {
      throw new RuntimeException("Failed to create token service");
    }

    try {
      DockerBearerToken token =
          Retrofit2SyncCall.execute(
              tokenService.getToken(
                  "token",
                  "registry.docker.io",
                  "repository:" + repository + ":pull",
                  basicAuth,
                  userAgent));
      if (token == null || token.getToken() == null) {
        throw new RuntimeException("Failed to get token from registry");
      }

      // Cache the token
      cachedTokens.put(repository, token);
      return token;
    } catch (Exception e) {
      throw new RuntimeException("Failed to authenticate with Docker registry", e);
    }
  }

  public DockerManifest getManifest(String repository, String tag) {
    DockerBearerToken token = getToken(repository);
    String manifestUrl = "v2/" + repository + "/manifests/" + tag;

    TokenService tokenService = getTokenService("https://registry-1.docker.io");
    if (tokenService == null) {
      throw new RuntimeException("Failed to create token service");
    }

    try {
      DockerManifest manifest =
          Retrofit2SyncCall.execute(
              tokenService.getManifest(manifestUrl, "Bearer " + token.getToken(), userAgent));
      return manifest;
    } catch (Exception e) {
      throw new RuntimeException("Failed to get manifest from registry", e);
    }
  }

  public void downloadArtifact(String repository, String version, java.nio.file.Path outputFile)
      throws IOException {

    java.nio.file.Path repoPath = Paths.get(outputFile.getParent().toString());
    if (!repoPath.toFile().mkdirs()) {
      throw new IOException("Unable to create directory " + outputFile.toString());
    }
    DockerManifest manifest = getManifest(repository, version);

    // Ensure there is at least one layer
    if (manifest.getLayers() == null || manifest.getLayers().isEmpty()) {
      throw new RuntimeException("No layers found in manifest");
    }
    // Use the digest of the first layer (usually the artifact, e.g., Helm chart)
    String digest = manifest.getLayers().get(0).getDigest();
    String layerUrl = "v2/" + repository + "/blobs/" + digest;

    DockerBearerToken token = getToken(repository);
    TokenService tokenService = getTokenService("https://registry-1.docker.io");
    if (tokenService == null) {
      throw new RuntimeException("Failed to create token service");
    }

    try {
      ResponseBody responseBody =
          Retrofit2SyncCall.execute(
              tokenService.downloadBlob(layerUrl, "Bearer " + token.getToken(), userAgent));

      if (responseBody == null) {
        throw new RuntimeException("No response body received from registry");
      }

      // Write the input stream to the output file (should be .tgz)
      try (InputStream inputStream = responseBody.byteStream();
          FileOutputStream outputStream = new FileOutputStream(outputFile.toFile())) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
          outputStream.write(buffer, 0, bytesRead);
        }
      }

      // Return type should be void or Path, not the ResponseBody/InputStream,
      // since the stream is already closed and consumed.
    } catch (Exception e) {
      throw new RuntimeException("Failed to download artifact blob from registry", e);
    }
  }

  public List<String> loadTags(String repository) {
    DockerBearerToken token = getToken(repository);

    TokenService tokenService = getTokenService("https://registry-1.docker.io");
    if (tokenService == null) {
      throw new RuntimeException("Failed to create token service");
    }

    try {
      DockerRegistryTags dockerRegistryTags =
          Retrofit2SyncCall.execute(
              tokenService.getTags(repository, "Bearer " + token.getToken(), userAgent));

      if (dockerRegistryTags == null) {
        throw new RuntimeException("No response body received from registry");
      }

      return dockerRegistryTags.getTags();
    } catch (Exception e) {
      throw new RuntimeException("Failed to load tags from registry", e);
    }
  }
}
