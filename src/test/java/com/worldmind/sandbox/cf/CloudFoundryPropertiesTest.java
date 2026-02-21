package com.worldmind.sandbox.cf;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CloudFoundryPropertiesTest {

    @Test
    void defaultValuesAreSet() {
        var props = new CloudFoundryProperties();

        assertEquals("", props.getApiUrl());
        assertEquals("", props.getOrg());
        assertEquals("", props.getSpace());
        assertEquals("", props.getGitRemoteUrl());
        assertTrue(props.getAgentApps().isEmpty());
        assertEquals(1200, props.getTaskTimeoutSeconds());
        assertEquals(2048, props.getTaskMemoryMb());
        assertEquals(4096, props.getTaskDiskMb());
    }

    @Test
    void propertiesCanBeSetViaSetters() {
        var props = new CloudFoundryProperties();

        props.setApiUrl("https://api.cf.example.com");
        props.setOrg("worldmind-org");
        props.setSpace("production");
        props.setGitRemoteUrl("git@github.com:org/project.git");
        props.setTaskTimeoutSeconds(900);
        props.setTaskMemoryMb(4096);
        props.setTaskDiskMb(8192);

        assertEquals("https://api.cf.example.com", props.getApiUrl());
        assertEquals("worldmind-org", props.getOrg());
        assertEquals("production", props.getSpace());
        assertEquals("git@github.com:org/project.git", props.getGitRemoteUrl());
        assertEquals(900, props.getTaskTimeoutSeconds());
        assertEquals(4096, props.getTaskMemoryMb());
        assertEquals(8192, props.getTaskDiskMb());
    }

    @Test
    void agentAppsMapWorksCorrectly() {
        var props = new CloudFoundryProperties();

        props.setAgentApps(Map.of(
                "coder", "agent-coder",
                "tester", "agent-tester",
                "reviewer", "agent-reviewer"
        ));

        assertEquals(3, props.getAgentApps().size());
        assertEquals("agent-coder", props.getAgentApps().get("coder"));
        assertEquals("agent-tester", props.getAgentApps().get("tester"));
        assertEquals("agent-reviewer", props.getAgentApps().get("reviewer"));
    }

    @Test
    void agentAppsDefaultMapIsMutable() {
        var props = new CloudFoundryProperties();

        props.getAgentApps().put("coder", "my-coder-app");

        assertEquals(1, props.getAgentApps().size());
        assertEquals("my-coder-app", props.getAgentApps().get("coder"));
    }

    @Test
    void applyDefaultsPopulatesAllAgentTypes() {
        var props = new CloudFoundryProperties();

        props.applyDefaults();

        Map<String, String> apps = props.getAgentApps();
        assertEquals(6, apps.size());
        assertEquals("agent-coder", apps.get("coder"));
        assertEquals("agent-tester", apps.get("tester"));
        assertEquals("agent-reviewer", apps.get("reviewer"));
        assertEquals("agent-researcher", apps.get("researcher"));
        assertEquals("agent-refactorer", apps.get("refactorer"));
        assertEquals("agent-deployer", apps.get("deployer"));
    }

    @Test
    void applyDefaultsDoesNotOverrideExistingEntries() {
        var props = new CloudFoundryProperties();
        props.getAgentApps().put("coder", "custom-coder");
        props.getAgentApps().put("deployer", "custom-deployer");

        props.applyDefaults();

        Map<String, String> apps = props.getAgentApps();
        assertEquals(6, apps.size());
        assertEquals("custom-coder", apps.get("coder"));
        assertEquals("custom-deployer", apps.get("deployer"));
        assertEquals("agent-tester", apps.get("tester"));
    }
}
