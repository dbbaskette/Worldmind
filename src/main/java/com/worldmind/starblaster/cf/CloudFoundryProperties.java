package com.worldmind.starblaster.cf;

import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CF-specific configuration properties bound from {@code worldmind.cf.*}.
 * <p>
 * Not annotated with {@code @Component} — will be conditionally enabled
 * by {@code CfStarblasterConfig} when the CF provider is active.
 */
@ConfigurationProperties(prefix = "worldmind.cf")
public class CloudFoundryProperties {

    /** CF API URL, e.g. https://api.cf.example.com */
    private String apiUrl = "";

    /** CF org name */
    private String org = "";

    /** CF space name */
    private String space = "";

    /** Git remote URL for workspace sharing between orchestrator and centurions */
    private String gitRemoteUrl = "";

    /**
     * Centurion app names — maps centurion type to CF app name.
     * e.g. {"forge": "centurion-forge", "gauntlet": "centurion-gauntlet"}
     */
    private Map<String, String> centurionApps = new HashMap<>();

    /** Ensure all centurion types have defaults after property binding */
    @PostConstruct
    public void applyDefaults() {
        centurionApps.putIfAbsent("forge", "centurion-forge");
        centurionApps.putIfAbsent("gauntlet", "centurion-gauntlet");
        centurionApps.putIfAbsent("vigil", "centurion-vigil");
        centurionApps.putIfAbsent("pulse", "centurion-pulse");
        centurionApps.putIfAbsent("prism", "centurion-prism");
    }

    /** Task timeout in seconds (default 10 minutes) */
    private int taskTimeoutSeconds = 600;

    /** Task memory limit in MB (default 2 GB) */
    private int taskMemoryMb = 2048;

    /** Task disk limit in MB (default 4 GB) */
    private int taskDiskMb = 4096;

    /** CF username for API authentication (used to obtain UAA token) */
    private String cfUsername = "";

    /** CF password for API authentication */
    private String cfPassword = "";

    /** Orchestrator base URL, used by CF tasks to fetch instructions via HTTP */
    private String orchestratorUrl = "";

    // -- Getters and Setters --

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getOrg() {
        return org;
    }

    public void setOrg(String org) {
        this.org = org;
    }

    public String getSpace() {
        return space;
    }

    public void setSpace(String space) {
        this.space = space;
    }

    public String getGitRemoteUrl() {
        return gitRemoteUrl;
    }

    public void setGitRemoteUrl(String gitRemoteUrl) {
        this.gitRemoteUrl = gitRemoteUrl;
    }

    public Map<String, String> getCenturionApps() {
        return centurionApps;
    }

    public void setCenturionApps(Map<String, String> centurionApps) {
        this.centurionApps = centurionApps;
    }

    public int getTaskTimeoutSeconds() {
        return taskTimeoutSeconds;
    }

    public void setTaskTimeoutSeconds(int taskTimeoutSeconds) {
        this.taskTimeoutSeconds = taskTimeoutSeconds;
    }

    public int getTaskMemoryMb() {
        return taskMemoryMb;
    }

    public void setTaskMemoryMb(int taskMemoryMb) {
        this.taskMemoryMb = taskMemoryMb;
    }

    public int getTaskDiskMb() {
        return taskDiskMb;
    }

    public void setTaskDiskMb(int taskDiskMb) {
        this.taskDiskMb = taskDiskMb;
    }

    public String getCfUsername() {
        return cfUsername;
    }

    public void setCfUsername(String cfUsername) {
        this.cfUsername = cfUsername;
    }

    public String getCfPassword() {
        return cfPassword;
    }

    public void setCfPassword(String cfPassword) {
        this.cfPassword = cfPassword;
    }

    public String getOrchestratorUrl() {
        return orchestratorUrl;
    }

    public void setOrchestratorUrl(String orchestratorUrl) {
        this.orchestratorUrl = orchestratorUrl;
    }
}
