package com.worldmind.core.novaforce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NexusPropertiesTest {

    @Test
    @DisplayName("defaults to disabled with empty URL")
    void defaultsToDisabled() {
        var props = new NexusProperties();
        assertFalse(props.isEnabled());
        assertEquals("", props.getUrl());
    }

    @Test
    @DisplayName("validate passes when disabled")
    void validatePassesWhenDisabled() {
        var props = new NexusProperties();
        props.setEnabled(false);
        assertDoesNotThrow(props::validate);
    }

    @Test
    @DisplayName("validate fails when enabled but URL is missing")
    void validateFailsWhenEnabledWithoutUrl() {
        var props = new NexusProperties();
        props.setEnabled(true);
        props.setUrl("");
        assertThrows(IllegalStateException.class, props::validate);
    }

    @Test
    @DisplayName("validate fails when enabled with null URL")
    void validateFailsWhenEnabledWithNullUrl() {
        var props = new NexusProperties();
        props.setEnabled(true);
        props.setUrl(null);
        assertThrows(IllegalStateException.class, props::validate);
    }

    @Test
    @DisplayName("validate passes when enabled with valid URL")
    void validatePassesWhenEnabledWithUrl() {
        var props = new NexusProperties();
        props.setEnabled(true);
        props.setUrl("http://nexus:8090/mcp");
        assertDoesNotThrow(props::validate);
    }

    @Test
    @DisplayName("getCoreToken returns token for configured node")
    void getCoreTokenReturnsConfiguredToken() {
        var props = new NexusProperties();
        var tc = new NexusProperties.TokenConfig();
        tc.setToken("classify-token-123");
        props.setCore(Map.of("classify", tc));

        assertEquals("classify-token-123", props.getCoreToken("classify"));
        assertEquals("classify-token-123", props.getCoreToken("CLASSIFY"));
    }

    @Test
    @DisplayName("getCoreToken returns null for unconfigured node")
    void getCoreTokenReturnsNullForUnconfigured() {
        var props = new NexusProperties();
        assertNull(props.getCoreToken("classify"));
    }

    @Test
    @DisplayName("getCenturionToken returns token for configured type")
    void getCenturionTokenReturnsConfiguredToken() {
        var props = new NexusProperties();
        var tc = new NexusProperties.TokenConfig();
        tc.setToken("forge-token-456");
        props.setCenturions(Map.of("forge", tc));

        assertEquals("forge-token-456", props.getCenturionToken("forge"));
        assertEquals("forge-token-456", props.getCenturionToken("FORGE"));
    }

    @Test
    @DisplayName("getCenturionToken returns null for unconfigured type")
    void getCenturionTokenReturnsNullForUnconfigured() {
        var props = new NexusProperties();
        assertNull(props.getCenturionToken("forge"));
    }

    @Test
    @DisplayName("TokenConfig defaults to empty string")
    void tokenConfigDefaults() {
        var tc = new NexusProperties.TokenConfig();
        assertEquals("", tc.getToken());
    }
}
