package com.worldmind.core.novaforce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NexusClientFactoryTest {

    @Test
    @DisplayName("getClient returns null when disabled")
    void getClientReturnsNullWhenDisabled() {
        var props = new NexusProperties();
        props.setEnabled(false);
        var factory = new NexusClientFactory(props);

        assertNull(factory.getClient("plan"));
    }

    @Test
    @DisplayName("getClient returns null when enabled but no token")
    void getClientReturnsNullWhenNoToken() {
        var props = new NexusProperties();
        props.setEnabled(true);
        props.setUrl("http://nexus:8090/mcp");
        // No core tokens configured
        var factory = new NexusClientFactory(props);

        assertNull(factory.getClient("plan"));
    }

    @Test
    @DisplayName("getCenturionToken returns null when disabled")
    void getCenturionTokenReturnsNullWhenDisabled() {
        var props = new NexusProperties();
        props.setEnabled(false);
        var factory = new NexusClientFactory(props);

        assertNull(factory.getCenturionToken("forge"));
    }

    @Test
    @DisplayName("getCenturionToken returns token when enabled")
    void getCenturionTokenReturnsToken() {
        var props = new NexusProperties();
        props.setEnabled(true);
        props.setUrl("http://nexus:8090/mcp");
        var tc = new NexusProperties.TokenConfig();
        tc.setToken("forge-token-789");
        props.setCenturions(Map.of("forge", tc));

        var factory = new NexusClientFactory(props);
        assertEquals("forge-token-789", factory.getCenturionToken("forge"));
    }

    @Test
    @DisplayName("isEnabled returns false when disabled")
    void isEnabledReturnsFalseWhenDisabled() {
        var props = new NexusProperties();
        props.setEnabled(false);
        var factory = new NexusClientFactory(props);

        assertFalse(factory.isEnabled());
    }

    @Test
    @DisplayName("isEnabled returns true when enabled with URL")
    void isEnabledReturnsTrueWithUrl() {
        var props = new NexusProperties();
        props.setEnabled(true);
        props.setUrl("http://nexus:8090/mcp");
        var factory = new NexusClientFactory(props);

        assertTrue(factory.isEnabled());
    }

    @Test
    @DisplayName("isEnabled returns false when enabled but URL is blank")
    void isEnabledReturnsFalseWithBlankUrl() {
        var props = new NexusProperties();
        props.setEnabled(true);
        props.setUrl("");
        var factory = new NexusClientFactory(props);

        assertFalse(factory.isEnabled());
    }

    @Test
    @DisplayName("getNexusUrl returns configured URL")
    void getNexusUrlReturnsUrl() {
        var props = new NexusProperties();
        props.setUrl("http://nexus:8090/mcp");
        var factory = new NexusClientFactory(props);

        assertEquals("http://nexus:8090/mcp", factory.getNexusUrl());
    }

    @Test
    @DisplayName("hasClients returns false initially")
    void hasClientsReturnsFalseInitially() {
        var props = new NexusProperties();
        var factory = new NexusClientFactory(props);

        assertFalse(factory.hasClients());
    }

    @Test
    @DisplayName("shutdown is safe when no clients exist")
    void shutdownSafeWithNoClients() {
        var props = new NexusProperties();
        var factory = new NexusClientFactory(props);

        assertDoesNotThrow(factory::shutdown);
    }
}
