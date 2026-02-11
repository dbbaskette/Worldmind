package com.worldmind.starblaster.cf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client for the Cloud Foundry Cloud Controller API v3.
 *
 * <p>Replaces CF CLI shell-outs (which require the {@code cf} binary)
 * with direct REST calls. This is essential because the Java buildpack
 * container on CF does not include the CF CLI.
 *
 * <p>Authentication uses a UAA password grant with the credentials from
 * {@link CloudFoundryProperties#getCfUsername()} and
 * {@link CloudFoundryProperties#getCfPassword()}.
 */
public class CfApiClient {

    private static final Logger log = LoggerFactory.getLogger(CfApiClient.class);

    private final CloudFoundryProperties cfProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private String accessToken;
    private Instant tokenExpiry = Instant.MIN;
    private String uaaUrl;
    private String spaceGuid;
    private final ConcurrentHashMap<String, String> appGuidCache = new ConcurrentHashMap<>();

    public CfApiClient(CloudFoundryProperties cfProperties) {
        this.cfProperties = cfProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates a CF task on the specified app.
     *
     * @param appName   the CF app name (e.g. "centurion-forge")
     * @param command   the shell command to run inside the task container
     * @param taskName  unique task name (e.g. "starblaster-forge-DIR-001")
     * @param memoryMb  memory limit in MB
     * @param diskMb    disk limit in MB
     * @return the task GUID assigned by CF
     */
    public String createTask(String appName, String command, String taskName,
                             int memoryMb, int diskMb) {
        var appGuid = resolveAppGuid(appName);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("command", command);
        body.put("name", taskName);
        body.put("memory_in_mb", memoryMb);
        body.put("disk_in_mb", diskMb);

        var response = cfPost("/v3/apps/" + appGuid + "/tasks", body.toString());

        var taskGuid = response.get("guid").asText();
        var state = response.get("state").asText();
        log.info("Created CF task '{}' (guid={}, state={})", taskName, taskGuid, state);
        return taskGuid;
    }

    /**
     * Gets the current state of a task by its GUID.
     *
     * @param taskGuid the task GUID returned by {@link #createTask}
     * @return the task state (PENDING, RUNNING, SUCCEEDED, FAILED, CANCELING)
     */
    public String getTaskState(String taskGuid) {
        var response = cfGet("/v3/tasks/" + taskGuid);
        return response.get("state").asText();
    }

    // Overload retained for backward compatibility (tests)
    public String getTaskState(String appName, String taskName) {
        return getTaskState(resolveTaskGuid(appName, taskName));
    }

    /**
     * Gets the failure reason for a task by its GUID.
     *
     * @param taskGuid the task GUID
     * @return the failure reason, or empty string if not failed
     */
    public String getTaskFailureReason(String taskGuid) {
        var response = cfGet("/v3/tasks/" + taskGuid);
        var result = response.get("result");
        if (result != null && result.has("failure_reason")) {
            var reason = result.get("failure_reason");
            return reason.isNull() ? "" : reason.asText();
        }
        return "";
    }

    // Overload retained for backward compatibility (tests)
    public String getTaskFailureReason(String appName, String taskName) {
        return getTaskFailureReason(resolveTaskGuid(appName, taskName));
    }

    /**
     * Cancels a CF task by its GUID.
     *
     * @param taskGuid the task GUID to cancel
     */
    public void cancelTask(String taskGuid) {
        cfPost("/v3/tasks/" + taskGuid + "/actions/cancel", "{}");
        log.info("Cancelled CF task (guid={})", taskGuid);
    }

    // Overload retained for backward compatibility (tests)
    public void cancelTask(String appName, String taskName) {
        cancelTask(resolveTaskGuid(appName, taskName));
    }

    /**
     * Resolves a task name to its GUID via the CF API (finds the most recent task with that name).
     */
    private String resolveTaskGuid(String appName, String taskName) {
        var appGuid = resolveAppGuid(appName);
        var response = cfGet("/v3/apps/" + appGuid + "/tasks?names=" + encode(taskName)
                + "&order_by=-created_at&per_page=1");

        var resources = response.get("resources");
        if (resources == null || resources.isEmpty()) {
            throw new RuntimeException("Task not found: " + taskName + " on app " + appName);
        }
        return resources.get(0).get("guid").asText();
    }

    /**
     * Resolves a CF app name to its GUID, with caching.
     */
    String resolveAppGuid(String appName) {
        return appGuidCache.computeIfAbsent(appName, name -> {
            var spGuid = resolveSpaceGuid();
            var response = cfGet("/v3/apps?names=" + encode(name) + "&space_guids=" + spGuid);

            var resources = response.get("resources");
            if (resources == null || resources.isEmpty()) {
                throw new RuntimeException("CF app not found: " + name);
            }
            var guid = resources.get(0).get("guid").asText();
            log.info("Resolved CF app '{}' to GUID {}", name, guid);
            return guid;
        });
    }

    private synchronized String resolveSpaceGuid() {
        if (spaceGuid != null) return spaceGuid;

        var orgName = cfProperties.getOrg();
        var spaceName = cfProperties.getSpace();

        var orgResponse = cfGet("/v3/organizations?names=" + encode(orgName));
        var orgResources = orgResponse.get("resources");
        if (orgResources == null || orgResources.isEmpty()) {
            throw new RuntimeException("CF org not found: " + orgName);
        }
        var orgGuid = orgResources.get(0).get("guid").asText();

        var spaceResponse = cfGet("/v3/spaces?names=" + encode(spaceName)
                + "&organization_guids=" + orgGuid);
        var spaceResources = spaceResponse.get("resources");
        if (spaceResources == null || spaceResources.isEmpty()) {
            throw new RuntimeException(
                    "CF space not found: %s in org %s".formatted(spaceName, orgName));
        }

        spaceGuid = spaceResources.get(0).get("guid").asText();
        log.info("Resolved CF space '{}/{}' to GUID {}", orgName, spaceName, spaceGuid);
        return spaceGuid;
    }

    private synchronized String getToken() {
        if (accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }

        if (uaaUrl == null) {
            discoverUaaUrl();
        }

        var username = cfProperties.getCfUsername();
        var password = cfProperties.getCfPassword();

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new RuntimeException(
                    "CF credentials not configured. Set CF_USERNAME and CF_PASSWORD env vars.");
        }

        // Standard CF password grant using the "cf" client (same as the CLI)
        var body = "grant_type=password&client_id=cf&client_secret=&username=%s&password=%s"
                .formatted(encode(username), encode(password));

        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(uaaUrl + "/oauth/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("UAA token request failed (HTTP %d): %s"
                        .formatted(response.statusCode(), response.body()));
            }

            var json = objectMapper.readTree(response.body());
            accessToken = json.get("access_token").asText();
            int expiresIn = json.get("expires_in").asInt();
            tokenExpiry = Instant.now().plusSeconds(Math.max(expiresIn - 60, 10));

            log.info("Obtained CF UAA token (expires in {}s)", expiresIn);
            return accessToken;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to obtain UAA token", e);
        }
    }

    private void discoverUaaUrl() {
        try {
            var apiUrl = cfProperties.getApiUrl();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/"))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var json = objectMapper.readTree(response.body());

            if (json.has("links") && json.get("links").has("uaa")) {
                uaaUrl = json.get("links").get("uaa").get("href").asText();
            } else if (json.has("token_endpoint")) {
                uaaUrl = json.get("token_endpoint").asText();
            } else {
                throw new RuntimeException(
                        "Cannot discover UAA URL from CF API at " + apiUrl);
            }

            log.info("Discovered CF UAA URL: {}", uaaUrl);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to discover UAA URL", e);
        }
    }

    JsonNode cfGet(String path) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(cfProperties.getApiUrl() + path))
                    .header("Authorization", "Bearer " + getToken())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("CF API GET %s failed (HTTP %d): %s"
                        .formatted(path, response.statusCode(), response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("CF API request failed: GET " + path, e);
        }
    }

    JsonNode cfPost(String path, String body) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(cfProperties.getApiUrl() + path))
                    .header("Authorization", "Bearer " + getToken())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("CF API POST %s failed (HTTP %d): %s"
                        .formatted(path, response.statusCode(), response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("CF API request failed: POST " + path, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
