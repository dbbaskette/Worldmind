package com.worldmind.stargate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StargatePropertiesTest {

    @Test
    void defaultsAreReasonable() {
        var props = new StargateProperties();
        assertEquals("docker", props.getProvider());
        assertEquals(300, props.getTimeoutSeconds());
        assertEquals(4096, props.getMemoryLimitMb());
        assertEquals(2, props.getCpuCount());
        assertEquals(10, props.getMaxParallel());
    }

    @Test
    void gooseDefaultsAreReasonable() {
        var props = new StargateProperties();
        assertEquals("openai", props.getGooseProvider());
        assertEquals("http://host.docker.internal:1234/v1", props.getLmStudioUrl());
    }
}
