package com.worldmind.sandbox;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SandboxProviderTest {

    @Test
    void sandboxRequestBuildsWithAllFields() {
        var request = new AgentRequest(
            "coder",
            "task-001",
            Path.of("/tmp/project"),
            "Create hello.py that prints hello world",
            Map.of("GOOSE_PROVIDER", "openai"),
            4096,
            2,
            "",
            "base"
        );
        assertEquals("coder", request.agentType());
        assertEquals("task-001", request.taskId());
        assertEquals(Path.of("/tmp/project"), request.projectPath());
        assertEquals(4096, request.memoryLimitMb());
        assertEquals(2, request.cpuCount());
    }

    @Test
    void sandboxRequestInstructionTextIsPreserved() {
        var request = new AgentRequest(
            "coder", "d-001", Path.of("/tmp"),
            "Build the feature",
            Map.of(), 2048, 1,
            "", "base"
        );
        assertEquals("Build the feature", request.instructionText());
    }
}
