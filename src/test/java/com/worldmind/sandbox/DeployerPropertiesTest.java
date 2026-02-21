package com.worldmind.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeployerPropertiesTest {

    @Test
    @DisplayName("health timeout defaults to 300 seconds")
    void healthTimeoutDefaults() {
        var props = new DeployerProperties();
        assertEquals(300, props.getHealthTimeout());
    }

    @Test
    @DisplayName("memory defaults to 1G")
    void memoryDefaults() {
        var props = new DeployerProperties();
        assertEquals("1G", props.getDefaults().getMemory());
    }

    @Test
    @DisplayName("instances defaults to 1")
    void instancesDefaults() {
        var props = new DeployerProperties();
        assertEquals(1, props.getDefaults().getInstances());
    }

    @Test
    @DisplayName("health check type defaults to http")
    void healthCheckTypeDefaults() {
        var props = new DeployerProperties();
        assertEquals("http", props.getDefaults().getHealthCheckType());
    }

    @Test
    @DisplayName("health check path defaults to /actuator/health")
    void healthCheckPathDefaults() {
        var props = new DeployerProperties();
        assertEquals("/actuator/health", props.getDefaults().getHealthCheckPath());
    }

    @Test
    @DisplayName("buildpack defaults to java_buildpack_offline")
    void buildpackDefaults() {
        var props = new DeployerProperties();
        assertEquals("java_buildpack_offline", props.getDefaults().getBuildpack());
    }

    @Test
    @DisplayName("java version defaults to 21")
    void javaVersionDefaults() {
        var props = new DeployerProperties();
        assertEquals(21, props.getDefaults().getJavaVersion());
    }

    @Test
    @DisplayName("all properties are configurable via setters")
    void propertiesAreConfigurable() {
        var props = new DeployerProperties();
        props.setHealthTimeout(600);

        var defaults = props.getDefaults();
        defaults.setMemory("2G");
        defaults.setInstances(3);
        defaults.setHealthCheckType("port");
        defaults.setHealthCheckPath("/health");
        defaults.setBuildpack("java_buildpack");
        defaults.setJavaVersion(17);

        assertEquals(600, props.getHealthTimeout());
        assertEquals("2G", defaults.getMemory());
        assertEquals(3, defaults.getInstances());
        assertEquals("port", defaults.getHealthCheckType());
        assertEquals("/health", defaults.getHealthCheckPath());
        assertEquals("java_buildpack", defaults.getBuildpack());
        assertEquals(17, defaults.getJavaVersion());
    }

    @Test
    @DisplayName("defaults object can be replaced entirely")
    void defaultsObjectReplaceable() {
        var props = new DeployerProperties();
        var newDefaults = new DeployerProperties.DeployerDefaults();
        newDefaults.setMemory("512M");

        props.setDefaults(newDefaults);

        assertEquals("512M", props.getDefaults().getMemory());
    }
}
