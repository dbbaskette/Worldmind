package com.worldmind.stargate;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StargateProviderTest {

    @Test
    void stargateRequestBuildsWithAllFields() {
        var request = new StargateRequest(
            "forge",
            "directive-001",
            Path.of("/tmp/project"),
            "Create hello.py that prints hello world",
            Map.of("GOOSE_PROVIDER", "openai"),
            4096,
            2
        );
        assertEquals("forge", request.centurionType());
        assertEquals("directive-001", request.directiveId());
        assertEquals(Path.of("/tmp/project"), request.projectPath());
        assertEquals(4096, request.memoryLimitMb());
        assertEquals(2, request.cpuCount());
    }

    @Test
    void stargateRequestInstructionTextIsPreserved() {
        var request = new StargateRequest(
            "forge", "d-001", Path.of("/tmp"),
            "Build the feature",
            Map.of(), 2048, 1
        );
        assertEquals("Build the feature", request.instructionText());
    }
}
