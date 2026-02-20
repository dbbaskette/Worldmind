package com.worldmind.sandbox;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SandboxPropertiesTest {

    @Test
    void defaultsAreReasonable() {
        var props = new SandboxProperties();
        assertEquals("docker", props.getProvider());
        assertEquals(300, props.getTimeoutSeconds());
        assertEquals(4096, props.getMemoryLimitMb());
        assertEquals(2, props.getCpuCount());
        assertEquals(1, props.getMaxParallel());
    }

    @Test
    void gooseDefaultsAreReasonable() {
        var props = new SandboxProperties();
        assertEquals("openai", props.getGooseProvider());
        assertEquals("http://host.docker.internal:1234/v1", props.getLmStudioUrl());
    }
}
