package com.worldmind.starblaster.cf;

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
        assertTrue(props.getCenturionApps().isEmpty());
        assertEquals(600, props.getTaskTimeoutSeconds());
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
    void centurionAppsMapWorksCorrectly() {
        var props = new CloudFoundryProperties();

        props.setCenturionApps(Map.of(
                "forge", "centurion-forge",
                "gauntlet", "centurion-gauntlet",
                "vigil", "centurion-vigil"
        ));

        assertEquals(3, props.getCenturionApps().size());
        assertEquals("centurion-forge", props.getCenturionApps().get("forge"));
        assertEquals("centurion-gauntlet", props.getCenturionApps().get("gauntlet"));
        assertEquals("centurion-vigil", props.getCenturionApps().get("vigil"));
    }

    @Test
    void centurionAppsDefaultMapIsMutable() {
        var props = new CloudFoundryProperties();

        props.getCenturionApps().put("forge", "my-forge-app");

        assertEquals(1, props.getCenturionApps().size());
        assertEquals("my-forge-app", props.getCenturionApps().get("forge"));
    }
}
