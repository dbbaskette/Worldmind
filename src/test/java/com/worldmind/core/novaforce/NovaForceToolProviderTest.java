package com.worldmind.core.novaforce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NovaForceToolProviderTest {

    @Test
    @DisplayName("returns empty array when disabled")
    void returnsEmptyWhenDisabled() {
        var props = new NexusProperties();
        props.setEnabled(false);
        var factory = new NexusClientFactory(props);
        var provider = new NovaForceToolProvider(factory);

        var tools = provider.getToolsForNode("plan");
        assertNotNull(tools);
        assertEquals(0, tools.length);
    }

    @Test
    @DisplayName("returns empty array for all nodes when disabled")
    void returnsEmptyForAllNodesWhenDisabled() {
        var props = new NexusProperties();
        props.setEnabled(false);
        var factory = new NexusClientFactory(props);
        var provider = new NovaForceToolProvider(factory);

        assertEquals(0, provider.getToolsForNode("classify").length);
        assertEquals(0, provider.getToolsForNode("plan").length);
        assertEquals(0, provider.getToolsForNode("converge").length);
        assertEquals(0, provider.getToolsForNode("seal").length);
        assertEquals(0, provider.getToolsForNode("postmission").length);
        assertEquals(0, provider.getToolsForNode("schedule").length);
    }

    @Test
    @DisplayName("isEnabled delegates to factory")
    void isEnabledDelegatesToFactory() {
        var props = new NexusProperties();
        props.setEnabled(false);
        var factory = new NexusClientFactory(props);
        var provider = new NovaForceToolProvider(factory);

        assertFalse(provider.isEnabled());
    }

    @Test
    @DisplayName("isEnabled returns true when factory is enabled")
    void isEnabledReturnsTrueWhenFactoryEnabled() {
        var props = new NexusProperties();
        props.setEnabled(true);
        props.setUrl("http://nexus:8090/mcp");
        var factory = new NexusClientFactory(props);
        var provider = new NovaForceToolProvider(factory);

        assertTrue(provider.isEnabled());
    }

    @Test
    @DisplayName("returns empty array when enabled but no token for node")
    void returnsEmptyWhenNoTokenForNode() {
        var props = new NexusProperties();
        props.setEnabled(true);
        props.setUrl("http://nexus:8090/mcp");
        // No core tokens configured
        var factory = new NexusClientFactory(props);
        var provider = new NovaForceToolProvider(factory);

        // getClient returns null because no token -> returns empty
        var tools = provider.getToolsForNode("schedule");
        assertNotNull(tools);
        assertEquals(0, tools.length);
    }
}
