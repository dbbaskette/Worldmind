package com.worldmind.sandbox.cf;

import java.util.HashMap;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CF-specific configuration properties bound from {@code worldmind.cf.*}.
 * <p>
 * Not annotated with {@code @Component} — will be conditionally enabled
 * by {@code CfSandboxConfig} when the CF provider is active.
 */
@ConfigurationProperties(prefix = "worldmind.cf")
public class CloudFoundryProperties {

    /** CF API URL, e.g. https://api.cf.example.com */
    private String apiUrl = "";

    /** CF org name */
    private String org = "";

    /** CF space name */
    private String space = "";

    /** Git remote URL for workspace sharing between orchestrator and agents */
    private String gitRemoteUrl = "";

    /** Git token (e.g. GitHub PAT) for push authentication — injected into HTTPS URLs */
    private String gitToken = "";

    /**
     * Agent app names — maps agent type to CF app name.
     * e.g. {"coder": "agent-coder", "tester": "agent-tester"}
     */
    private Map<String, String> agentApps = new HashMap<>();

    /** Ensure all agent types have defaults after property binding */
    @PostConstruct
    public void applyDefaults() {
        agentApps.putIfAbsent("coder", "agent-coder");
        agentApps.putIfAbsent("tester", "agent-tester");
        agentApps.putIfAbsent("reviewer", "agent-reviewer");
        agentApps.putIfAbsent("researcher", "agent-researcher");
        agentApps.putIfAbsent("refactorer", "agent-refactorer");
        agentApps.putIfAbsent("deployer", "agent-deployer");
    }

    /** Task timeout in seconds (default 20 minutes) */
    private int taskTimeoutSeconds = 1200;

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

    public String getGitToken() {
        return gitToken;
    }

    public void setGitToken(String gitToken) {
        this.gitToken = gitToken;
    }

    public Map<String, String> getAgentApps() {
        return agentApps;
    }

    public void setAgentApps(Map<String, String> agentApps) {
        this.agentApps = agentApps;
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
