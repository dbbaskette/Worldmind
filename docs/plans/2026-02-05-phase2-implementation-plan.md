# Phase 2: First Agent — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Execute tasks by spinning up Docker containers running Goose (Agent Coder) that generate code in the user's project directory.

**Architecture:** A `SandboxProvider` interface abstracts container orchestration (Docker for dev, Cloud Foundry for prod). `SandboxManager` handles file change detection and delegates to the provider. `AgentDispatcher` translates between domain records and the sandbox infrastructure. `DispatchAgentNode` plugs into the LangGraph4j graph as a sequential loop after plan approval. Goose runs headlessly via `goose run -t "<instruction>"` inside containers, connecting to LM Studio on the host (dev) or Anthropic API (prod).

**Tech Stack:** Java 21, Docker Java client 3.4.1, Goose CLI (headless), LM Studio (OpenAI-compatible), LangGraph4j 1.8

---

## Task 2.1: SandboxProvider Interface & AgentRequest Record

**Files:**
- Create: `src/main/java/com/worldmind/sandbox/SandboxProvider.java`
- Create: `src/main/java/com/worldmind/sandbox/AgentRequest.java`
- Test: `src/test/java/com/worldmind/sandbox/SandboxProviderTest.java`

**Step 1: Write the test**

```java
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
            2
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
            Map.of(), 2048, 1
        );
        assertEquals("Build the feature", request.instructionText());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=SandboxProviderTest -f /Users/dbbaskette/Projects/Worldmind/pom.xml`
Expected: FAIL — class not found

**Step 3: Write the interface and record**

`SandboxProvider.java`:
```java
package com.worldmind.sandbox;

/**
 * Abstraction for container orchestration.
 * Implementations: DockerSandboxProvider (dev), CloudFoundrySandboxProvider (prod).
 */
public interface SandboxProvider {

    /**
     * Creates and starts a container for a Agent.
     * @return the container/sandbox ID
     */
    String openSandbox(AgentRequest request);

    /**
     * Blocks until the container exits or timeout is reached.
     * @return the container exit code (0 = success)
     */
    int waitForCompletion(String sandboxId, int timeoutSeconds);

    /**
     * Captures stdout/stderr logs from the container.
     */
    String captureOutput(String sandboxId);

    /**
     * Stops and removes the container.
     */
    void teardownSandbox(String sandboxId);
}
```

`AgentRequest.java`:
```java
package com.worldmind.sandbox;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Map;

/**
 * Everything needed to open a Sandbox container.
 *
 * @param agentType  e.g. "coder", "reviewer", "tester"
 * @param taskId    the task this sandbox serves
 * @param projectPath    host path to the project directory (bind-mounted as /workspace)
 * @param instructionText the full instruction markdown for Goose
 * @param envVars        environment variables to inject (GOOSE_PROVIDER, GOOSE_MODEL, etc.)
 * @param memoryLimitMb  memory limit in MB
 * @param cpuCount       CPU count limit
 */
public record AgentRequest(
    String agentType,
    String taskId,
    Path projectPath,
    String instructionText,
    Map<String, String> envVars,
    int memoryLimitMb,
    int cpuCount
) implements Serializable {}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest=SandboxProviderTest -f /Users/dbbaskette/Projects/Worldmind/pom.xml`
Expected: PASS (2 tests)

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/sandbox/SandboxProvider.java \
        src/main/java/com/worldmind/sandbox/AgentRequest.java \
        src/test/java/com/worldmind/sandbox/SandboxProviderTest.java
git commit -m "feat: SandboxProvider interface and AgentRequest record"
```

---

## Task 2.2: SandboxProperties Configuration

**Files:**
- Create: `src/main/java/com/worldmind/sandbox/SandboxProperties.java`
- Modify: `src/main/resources/application.yml:38-46`
- Test: `src/test/java/com/worldmind/sandbox/SandboxPropertiesTest.java`

**Step 1: Write the test**

```java
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
        assertEquals(10, props.getMaxParallel());
    }

    @Test
    void gooseDefaultsAreReasonable() {
        var props = new SandboxProperties();
        assertEquals("openai", props.getGooseProvider());
        assertEquals("http://host.docker.internal:1234/v1", props.getLmStudioUrl());
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

`SandboxProperties.java`:
```java
package com.worldmind.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Sandbox container management and Goose model provider.
 * Bound from application.yml under the "worldmind" prefix.
 */
@Component
@ConfigurationProperties(prefix = "worldmind")
public class SandboxProperties {

    private Sandbox sandbox = new Sandbox();
    private Goose goose = new Goose();

    // -- Sandbox accessors (delegate to nested) --

    public String getProvider() { return sandbox.provider; }
    public int getTimeoutSeconds() { return sandbox.timeoutSeconds; }
    public int getMemoryLimitMb() { return sandbox.memoryLimitMb; }
    public int getCpuCount() { return sandbox.cpuCount; }
    public int getMaxParallel() { return sandbox.maxParallel; }
    public String getImage() { return sandbox.image; }

    // -- Goose accessors (delegate to nested) --

    public String getGooseProvider() { return goose.provider; }
    public String getGooseModel() { return goose.model; }
    public String getLmStudioUrl() { return goose.lmStudioUrl; }

    public Sandbox getSandbox() { return sandbox; }
    public void setSandbox(Sandbox sandbox) { this.sandbox = sandbox; }
    public Goose getGoose() { return goose; }
    public void setGoose(Goose goose) { this.goose = goose; }

    public static class Sandbox {
        private String provider = "docker";
        private int maxParallel = 10;
        private int timeoutSeconds = 300;
        private int memoryLimitMb = 4096;
        private int cpuCount = 2;
        private String image = "worldmind/agent-coder:latest";

        // getters and setters for all fields
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getMaxParallel() { return maxParallel; }
        public void setMaxParallel(int maxParallel) { this.maxParallel = maxParallel; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMemoryLimitMb() { return memoryLimitMb; }
        public void setMemoryLimitMb(int memoryLimitMb) { this.memoryLimitMb = memoryLimitMb; }
        public int getCpuCount() { return cpuCount; }
        public void setCpuCount(int cpuCount) { this.cpuCount = cpuCount; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
    }

    public static class Goose {
        private String provider = "openai";
        private String model = "qwen2.5-coder-32b";
        private String lmStudioUrl = "http://host.docker.internal:1234/v1";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getLmStudioUrl() { return lmStudioUrl; }
        public void setLmStudioUrl(String lmStudioUrl) { this.lmStudioUrl = lmStudioUrl; }
    }
}
```

Then update `application.yml` — replace the existing `worldmind:` block (lines 38-46) with:

```yaml
worldmind:
  goose:
    provider: ${GOOSE_PROVIDER:openai}
    model: ${GOOSE_MODEL:qwen2.5-coder-32b}
    lm-studio-url: ${LM_STUDIO_URL:http://host.docker.internal:1234/v1}
  sandbox:
    provider: ${SANDBOX_PROVIDER:docker}
    max-parallel: 10
    timeout-seconds: 300
    memory-limit-mb: 4096
    cpu-count: 2
    image: worldmind/agent-coder:latest
```

**Step 4: Run test — expect PASS**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/sandbox/SandboxProperties.java \
        src/main/resources/application.yml \
        src/test/java/com/worldmind/sandbox/SandboxPropertiesTest.java
git commit -m "feat: SandboxProperties with Goose provider and container config"
```

---

## Task 2.3: InstructionBuilder

**Files:**
- Create: `src/main/java/com/worldmind/sandbox/InstructionBuilder.java`
- Test: `src/test/java/com/worldmind/sandbox/InstructionBuilderTest.java`

**Step 1: Write the test**

```java
package com.worldmind.sandbox;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InstructionBuilderTest {

    @Test
    void buildIncludesTaskFields() {
        var task = new Task(
            "TASK-001", "CODER", "Create hello.py",
            "Python project with pytest", "File hello.py exists and is valid Python",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var context = new ProjectContext("/tmp/project", "src/\n  main.py",
            "python", "flask", "flask,pytest", 10, "A Flask web app");

        String instruction = InstructionBuilder.build(task, context);

        assertTrue(instruction.contains("TASK-001"));
        assertTrue(instruction.contains("Create hello.py"));
        assertTrue(instruction.contains("File hello.py exists and is valid Python"));
        assertTrue(instruction.contains("python"));
        assertTrue(instruction.contains("flask"));
        assertTrue(instruction.contains("A Flask web app"));
    }

    @Test
    void buildIncludesConstraintsSection() {
        var task = new Task(
            "TASK-002", "CODER", "Add endpoint",
            "", "Endpoint returns 200",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var context = new ProjectContext("/tmp/p", "", "java", "spring", "", 5, "");

        String instruction = InstructionBuilder.build(task, context);

        assertTrue(instruction.contains("Constraints"));
        assertTrue(instruction.contains("Only modify files related to this task"));
    }

    @Test
    void buildWithNullContextDoesNotThrow() {
        var task = new Task(
            "TASK-003", "CODER", "Do something",
            "some context", "It works",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );

        String instruction = InstructionBuilder.build(task, null);

        assertTrue(instruction.contains("TASK-003"));
        assertTrue(instruction.contains("Do something"));
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

```java
package com.worldmind.sandbox;

import com.worldmind.core.model.Task;
import com.worldmind.core.model.ProjectContext;

/**
 * Converts a Task and ProjectContext into a Goose-readable instruction string.
 * Pure function — no Spring dependencies.
 */
public final class InstructionBuilder {

    private InstructionBuilder() {}

    public static String build(Task task, ProjectContext context) {
        var sb = new StringBuilder();

        sb.append("# Task: ").append(task.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append(task.description()).append("\n\n");

        if (task.inputContext() != null && !task.inputContext().isBlank()) {
            sb.append("## Additional Context\n\n");
            sb.append(task.inputContext()).append("\n\n");
        }

        if (context != null) {
            sb.append("## Project Context\n\n");
            sb.append("- **Language:** ").append(context.language()).append("\n");
            sb.append("- **Framework:** ").append(context.framework()).append("\n");
            if (context.summary() != null && !context.summary().isBlank()) {
                sb.append("- **Summary:** ").append(context.summary()).append("\n");
            }
            if (context.dependencies() != null && !context.dependencies().isBlank()) {
                sb.append("- **Dependencies:** ").append(context.dependencies()).append("\n");
            }
            if (context.fileTree() != null && !context.fileTree().isBlank()) {
                sb.append("\n### File Structure\n\n```\n");
                sb.append(context.fileTree()).append("\n```\n");
            }
            sb.append("\n");
        }

        sb.append("## Success Criteria\n\n");
        sb.append(task.successCriteria()).append("\n\n");

        sb.append("## Constraints\n\n");
        sb.append("- Only modify files related to this task\n");
        sb.append("- Do not modify test files (Tester handles tests)\n");
        sb.append("- Commit nothing — file changes are detected externally\n");
        sb.append("- If you encounter an error, attempt to fix it before reporting failure\n");

        return sb.toString();
    }
}
```

**Step 4: Run test — expect PASS**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/sandbox/InstructionBuilder.java \
        src/test/java/com/worldmind/sandbox/InstructionBuilderTest.java
git commit -m "feat: InstructionBuilder converts tasks to Goose instructions"
```

---

## Task 2.4: DockerSandboxProvider

**Files:**
- Create: `src/main/java/com/worldmind/sandbox/DockerSandboxProvider.java`
- Test: `src/test/java/com/worldmind/sandbox/DockerSandboxProviderTest.java`

**Step 1: Write the test**

```java
package com.worldmind.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.WaitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DockerSandboxProviderTest {

    private DockerClient dockerClient;
    private DockerSandboxProvider provider;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        provider = new DockerSandboxProvider(dockerClient);
    }

    @Test
    void openSandboxCreatesAndStartsContainer() {
        when(dockerClient.createContainerCmd(any(String.class))
                .withName(any())
                .withHostConfig(any())
                .withEnv(any(java.util.List.class))
                .exec())
                .thenReturn(mock(CreateContainerResponse.class));

        var request = new AgentRequest(
            "coder", "TASK-001", Path.of("/tmp/project"),
            "Create hello.py", Map.of("GOOSE_PROVIDER", "openai"),
            4096, 2
        );

        // Should not throw
        assertDoesNotThrow(() -> provider.openSandbox(request));
    }

    @Test
    void teardownSandboxStopsAndRemovesContainer() {
        provider.teardownSandbox("container-123");

        verify(dockerClient).stopContainerCmd("container-123");
        verify(dockerClient).removeContainerCmd("container-123");
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

```java
package com.worldmind.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Docker-based SandboxProvider for local development.
 * Creates Docker containers for each Agent task execution.
 */
public class DockerSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxProvider.class);

    private final DockerClient dockerClient;

    public DockerSandboxProvider(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public String openSandbox(AgentRequest request) {
        String containerName = "sandbox-" + request.agentType() + "-" + request.taskId();
        log.info("Opening Sandbox {} for task {}", containerName, request.taskId());

        var envList = new ArrayList<String>();
        request.envVars().forEach((k, v) -> envList.add(k + "=" + v));

        var hostConfig = HostConfig.newHostConfig()
                .withBinds(
                    new Bind(request.projectPath().toString(),
                             new Volume("/workspace"), AccessMode.rw)
                )
                .withMemory((long) request.memoryLimitMb() * 1024 * 1024)
                .withCpuCount((long) request.cpuCount())
                .withExtraHosts(new String[]{"host.docker.internal:host-gateway"});

        var response = dockerClient.createContainerCmd("worldmind/agent-coder:latest")
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withEnv(envList)
                .withCmd("goose", "run", "-t", request.instructionText())
                .withWorkingDir("/workspace")
                .exec();

        String containerId = response.getId();
        dockerClient.startContainerCmd(containerId).exec();
        log.info("Sandbox {} started (container {})", containerName, containerId);
        return containerId;
    }

    @Override
    public int waitForCompletion(String sandboxId, int timeoutSeconds) {
        try {
            var callback = dockerClient.waitContainerCmd(sandboxId)
                    .exec(new WaitContainerResultCallback());
            var result = callback.awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
            return result != null ? result : -1;
        } catch (Exception e) {
            log.error("Timeout or error waiting for sandbox {}", sandboxId, e);
            return -1;
        }
    }

    @Override
    public String captureOutput(String sandboxId) {
        var sb = new StringBuilder();
        try {
            dockerClient.logContainerCmd(sandboxId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .exec(new LogContainerResultCallback() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(frame.getPayload()));
                        }
                    }).awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while capturing output from sandbox {}", sandboxId);
        }
        return sb.toString();
    }

    @Override
    public void teardownSandbox(String sandboxId) {
        try {
            dockerClient.stopContainerCmd(sandboxId).exec();
        } catch (Exception e) {
            log.debug("Container {} may already be stopped", sandboxId);
        }
        try {
            dockerClient.removeContainerCmd(sandboxId).withForce(true).exec();
            log.info("Sandbox {} torn down", sandboxId);
        } catch (Exception e) {
            log.warn("Failed to remove container {}", sandboxId, e);
        }
    }
}
```

**Step 4: Run test — expect PASS (mocked tests)**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/sandbox/DockerSandboxProvider.java \
        src/test/java/com/worldmind/sandbox/DockerSandboxProviderTest.java
git commit -m "feat: DockerSandboxProvider for container lifecycle management"
```

---

## Task 2.5: SandboxManager (File Diffing + Provider Delegation)

**Files:**
- Create: `src/main/java/com/worldmind/sandbox/SandboxManager.java`
- Test: `src/test/java/com/worldmind/sandbox/SandboxManagerTest.java`

**Step 1: Write the test**

```java
package com.worldmind.sandbox;

import com.worldmind.core.model.FileRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SandboxManagerTest {

    private SandboxProvider provider;
    private SandboxProperties properties;
    private SandboxManager manager;

    @BeforeEach
    void setUp() {
        provider = mock(SandboxProvider.class);
        properties = new SandboxProperties();
        manager = new SandboxManager(provider, properties);
    }

    @Test
    void executeTaskCallsProviderLifecycle() {
        when(provider.openSandbox(any())).thenReturn("container-1");
        when(provider.waitForCompletion("container-1", 300)).thenReturn(0);
        when(provider.captureOutput("container-1")).thenReturn("done");

        var result = manager.executeTask(
            "coder", "TASK-001", Path.of("/tmp/test"),
            "Create file", Map.of()
        );

        verify(provider).openSandbox(any());
        verify(provider).waitForCompletion("container-1", 300);
        verify(provider).captureOutput("container-1");
        verify(provider).teardownSandbox("container-1");
        assertEquals(0, result.exitCode());
    }

    @Test
    void executeTaskReportsFailureOnNonZeroExit() {
        when(provider.openSandbox(any())).thenReturn("container-2");
        when(provider.waitForCompletion("container-2", 300)).thenReturn(1);
        when(provider.captureOutput("container-2")).thenReturn("error");

        var result = manager.executeTask(
            "coder", "TASK-002", Path.of("/tmp/test"),
            "Bad instruction", Map.of()
        );

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("error"));
    }

    @Test
    void detectFileChangesFindsNewFiles(@TempDir Path tempDir) throws IOException {
        // Take snapshot with no files
        var before = SandboxManager.snapshotFiles(tempDir);

        // Create a new file
        Files.writeString(tempDir.resolve("hello.py"), "print('hello')");

        // Detect changes
        var changes = SandboxManager.detectChanges(before, tempDir);

        assertEquals(1, changes.size());
        assertEquals("hello.py", changes.get(0).path());
        assertEquals("created", changes.get(0).action());
    }

    @Test
    void detectFileChangesFindsModifiedFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("existing.py"), "old");

        var before = SandboxManager.snapshotFiles(tempDir);

        // Modify the file
        Files.writeString(tempDir.resolve("existing.py"), "new content");

        var changes = SandboxManager.detectChanges(before, tempDir);

        assertEquals(1, changes.size());
        assertEquals("existing.py", changes.get(0).path());
        assertEquals("modified", changes.get(0).action());
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

```java
package com.worldmind.sandbox;

import com.worldmind.core.model.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages Sandbox lifecycle and file change detection.
 * Delegates container operations to the active SandboxProvider.
 */
@Service
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxProvider provider;
    private final SandboxProperties properties;

    public SandboxManager(SandboxProvider provider, SandboxProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    /**
     * Result of a sandbox execution.
     */
    public record ExecutionResult(
        int exitCode,
        String output,
        String sandboxId,
        List<FileRecord> fileChanges,
        long elapsedMs
    ) {}

    /**
     * Executes a task in a Sandbox container.
     */
    public ExecutionResult executeTask(
            String agentType,
            String taskId,
            Path projectPath,
            String instructionText,
            Map<String, String> extraEnv) {

        // Build environment variables
        var envVars = new HashMap<>(extraEnv);
        envVars.put("GOOSE_PROVIDER", properties.getGooseProvider());
        envVars.put("GOOSE_MODEL", properties.getGooseModel());
        if ("openai".equals(properties.getGooseProvider())) {
            envVars.put("OPENAI_HOST", properties.getLmStudioUrl());
            envVars.put("OPENAI_API_KEY", "not-needed-for-local");
        }

        var request = new AgentRequest(
            agentType, taskId, projectPath,
            instructionText, envVars,
            properties.getMemoryLimitMb(), properties.getCpuCount()
        );

        // Snapshot files before execution
        Map<String, Long> beforeSnapshot = snapshotFiles(projectPath);

        long startMs = System.currentTimeMillis();
        String sandboxId = provider.openSandbox(request);

        try {
            int exitCode = provider.waitForCompletion(sandboxId, properties.getTimeoutSeconds());
            String output = provider.captureOutput(sandboxId);
            long elapsedMs = System.currentTimeMillis() - startMs;

            // Detect file changes
            List<FileRecord> changes = detectChanges(beforeSnapshot, projectPath);

            log.info("Sandbox {} completed with exit code {} in {}ms — {} file changes",
                    sandboxId, exitCode, elapsedMs, changes.size());

            return new ExecutionResult(exitCode, output, sandboxId, changes, elapsedMs);
        } finally {
            provider.teardownSandbox(sandboxId);
        }
    }

    /**
     * Snapshots all files under a directory with their last-modified times.
     */
    static Map<String, Long> snapshotFiles(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains(".git"))
                .collect(Collectors.toMap(
                    p -> directory.relativize(p).toString(),
                    p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }
                ));
        } catch (IOException e) {
            return Map.of();
        }
    }

    /**
     * Compares a before-snapshot with the current state to find created/modified files.
     */
    static List<FileRecord> detectChanges(Map<String, Long> before, Path directory) {
        Map<String, Long> after = snapshotFiles(directory);
        var changes = new ArrayList<FileRecord>();

        for (var entry : after.entrySet()) {
            String path = entry.getKey();
            Long afterTime = entry.getValue();
            Long beforeTime = before.get(path);

            if (beforeTime == null) {
                changes.add(new FileRecord(path, "created", 0));
            } else if (!afterTime.equals(beforeTime)) {
                changes.add(new FileRecord(path, "modified", 0));
            }
        }

        return changes;
    }
}
```

**Step 4: Run test — expect PASS**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/sandbox/SandboxManager.java \
        src/test/java/com/worldmind/sandbox/SandboxManagerTest.java
git commit -m "feat: SandboxManager with file change detection"
```

---

## Task 2.6: AgentDispatcher

**Files:**
- Create: `src/main/java/com/worldmind/sandbox/AgentDispatcher.java`
- Test: `src/test/java/com/worldmind/sandbox/AgentDispatcherTest.java`

**Step 1: Write the test**

```java
package com.worldmind.sandbox;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentDispatcherTest {

    private SandboxManager manager;
    private AgentDispatcher bridge;

    @BeforeEach
    void setUp() {
        manager = mock(SandboxManager.class);
        bridge = new AgentDispatcher(manager);
    }

    @Test
    void executeTaskReturnsUpdatedTaskOnSuccess() {
        var task = new Task(
            "TASK-001", "CODER", "Create hello.py",
            "context", "hello.py exists",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var context = new ProjectContext("/tmp/p", "", "python", "none", "", 0, "");
        var fileChanges = List.of(new FileRecord("hello.py", "created", 1));
        var execResult = new SandboxManager.ExecutionResult(0, "done", "c-1", fileChanges, 5000L);

        when(manager.executeTask(
            eq("CODER"), eq("TASK-001"), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeTask(task, context, Path.of("/tmp/p"));

        assertEquals(TaskStatus.PASSED, result.task().status());
        assertEquals(5000L, result.task().elapsedMs());
        assertEquals(1, result.task().filesAffected().size());
        assertNotNull(result.sandboxInfo());
    }

    @Test
    void executeTaskMarksFailedOnNonZeroExit() {
        var task = new Task(
            "TASK-002", "CODER", "Bad task",
            "", "never",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var execResult = new SandboxManager.ExecutionResult(1, "error", "c-2", List.of(), 3000L);

        when(manager.executeTask(any(), any(), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeTask(task, null, Path.of("/tmp"));

        assertEquals(TaskStatus.FAILED, result.task().status());
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

```java
package com.worldmind.sandbox;

import com.worldmind.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Thin orchestration layer between domain records and the SandboxManager.
 * Translates Tasks into sandbox executions and results back into records.
 */
@Service
public class AgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentDispatcher.class);

    private final SandboxManager manager;

    public AgentDispatcher(SandboxManager manager) {
        this.manager = manager;
    }

    /**
     * Result of executing a task through the bridge.
     */
    public record BridgeResult(
        Task task,
        SandboxInfo sandboxInfo,
        String output
    ) {}

    /**
     * Executes a single task via the Sandbox infrastructure.
     */
    public BridgeResult executeTask(Task task, ProjectContext context, Path projectPath) {
        log.info("Executing task {} [{}]: {}",
                task.id(), task.agent(), task.description());

        String instruction = InstructionBuilder.build(task, context);
        Instant startedAt = Instant.now();

        var execResult = manager.executeTask(
            task.agent(),
            task.id(),
            projectPath,
            instruction,
            Map.of()
        );

        Instant completedAt = Instant.now();
        boolean success = execResult.exitCode() == 0;

        // Build updated task with results
        var updatedTask = new Task(
            task.id(),
            task.agent(),
            task.description(),
            task.inputContext(),
            task.successCriteria(),
            task.dependencies(),
            success ? TaskStatus.PASSED : TaskStatus.FAILED,
            task.iteration() + 1,
            task.maxIterations(),
            task.onFailure(),
            execResult.fileChanges(),
            execResult.elapsedMs()
        );

        var sandboxInfo = new SandboxInfo(
            execResult.sandboxId(),
            task.agent(),
            task.id(),
            success ? "completed" : "failed",
            startedAt,
            completedAt
        );

        return new BridgeResult(updatedTask, sandboxInfo, execResult.output());
    }
}
```

**Step 4: Run test — expect PASS**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/sandbox/AgentDispatcher.java \
        src/test/java/com/worldmind/sandbox/AgentDispatcherTest.java
git commit -m "feat: AgentDispatcher translates tasks to sandbox executions"
```

---

## Task 2.7: SandboxConfig (Spring Bean Wiring)

**Files:**
- Create: `src/main/java/com/worldmind/sandbox/SandboxConfig.java`
- Test: (covered by existing tests + integration)

**Step 1: Write the config**

```java
package com.worldmind.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Spring configuration for Sandbox beans.
 * Selects the active SandboxProvider based on worldmind.sandbox.provider property.
 */
@Configuration
public class SandboxConfig {

    @Bean
    @ConditionalOnProperty(name = "worldmind.sandbox.provider", havingValue = "docker", matchIfMissing = true)
    public DockerClient dockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    @Bean
    @ConditionalOnProperty(name = "worldmind.sandbox.provider", havingValue = "docker", matchIfMissing = true)
    public SandboxProvider dockerSandboxProvider(DockerClient dockerClient) {
        return new DockerSandboxProvider(dockerClient);
    }
}
```

**Step 2: Verify the app still compiles**

Run: `mvn compile -f /Users/dbbaskette/Projects/Worldmind/pom.xml`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/worldmind/sandbox/SandboxConfig.java
git commit -m "feat: SandboxConfig wires Docker provider beans"
```

---

## Task 2.8: DispatchAgentNode

**Files:**
- Create: `src/main/java/com/worldmind/core/nodes/DispatchAgentNode.java`
- Test: `src/test/java/com/worldmind/core/nodes/DispatchAgentNodeTest.java`

**Step 1: Write the test**

```java
package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.AgentDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DispatchAgentNodeTest {

    private AgentDispatcher bridge;
    private DispatchAgentNode node;

    @BeforeEach
    void setUp() {
        bridge = mock(AgentDispatcher.class);
        node = new DispatchAgentNode(bridge);
    }

    @Test
    void applyDispatchesNextPendingTask() {
        var task = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var updatedTask = new Task(
            "TASK-001", "CODER", "Create file",
            "", "File exists",
            List.of(), TaskStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(new FileRecord("hello.py", "created", 1)), 5000L
        );
        var sandboxInfo = new SandboxInfo("c-1", "CODER", "TASK-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new AgentDispatcher.BridgeResult(updatedTask, sandboxInfo, "ok");

        when(bridge.executeTask(any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", "", "java", "spring", "", 5, "");
        var state = new WorldmindState(Map.of(
            "tasks", List.of(task),
            "currentTaskIndex", 0,
            "projectContext", context
        ));

        var result = node.apply(state);

        assertNotNull(result);
        assertTrue(result.containsKey("sandboxes"));
        assertTrue(result.containsKey("currentTaskIndex"));
        assertEquals(1, result.get("currentTaskIndex"));
    }

    @Test
    void applySkipsWhenNoTasksPending() {
        var state = new WorldmindState(Map.of(
            "tasks", List.of(),
            "currentTaskIndex", 0
        ));

        var result = node.apply(state);

        assertEquals(MissionStatus.COMPLETED.name(), result.get("status"));
        verifyNoInteractions(bridge);
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

```java
package com.worldmind.core.nodes;

import com.worldmind.core.model.TaskStatus;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.AgentDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangGraph4j node that dispatches the next pending task to a Sandbox.
 * Calls AgentDispatcher to execute the task and returns updated state.
 */
@Component
public class DispatchAgentNode {

    private static final Logger log = LoggerFactory.getLogger(DispatchAgentNode.class);

    private final AgentDispatcher bridge;

    public DispatchAgentNode(AgentDispatcher bridge) {
        this.bridge = bridge;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var tasks = state.tasks();
        int currentIndex = state.currentTaskIndex();

        if (tasks.isEmpty() || currentIndex >= tasks.size()) {
            log.info("No pending tasks — mission complete");
            return Map.of("status", MissionStatus.COMPLETED.name());
        }

        var task = tasks.get(currentIndex);
        if (task.status() != TaskStatus.PENDING) {
            log.info("Task {} already {}, advancing", task.id(), task.status());
            return Map.of("currentTaskIndex", currentIndex + 1);
        }

        log.info("Dispatching task {} [{}]: {}",
                task.id(), task.agent(), task.description());

        var projectContext = state.projectContext().orElse(null);
        String projectPath = projectContext != null ? projectContext.rootPath() : ".";

        var result = bridge.executeTask(
            task, projectContext, Path.of(projectPath)
        );

        var updates = new HashMap<String, Object>();
        updates.put("sandboxes", List.of(result.sandboxInfo()));
        updates.put("currentTaskIndex", currentIndex + 1);
        updates.put("status", MissionStatus.EXECUTING.name());

        if (result.task().status() == TaskStatus.FAILED) {
            updates.put("errors", List.of(
                "Task " + task.id() + " failed: " + result.output()));
        }

        return updates;
    }
}
```

**Step 4: Run test — expect PASS**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/core/nodes/DispatchAgentNode.java \
        src/test/java/com/worldmind/core/nodes/DispatchAgentNodeTest.java
git commit -m "feat: DispatchAgentNode dispatches tasks to Sandboxes"
```

---

## Task 2.9: Wire DispatchAgentNode into Graph

**Files:**
- Modify: `src/main/java/com/worldmind/core/graph/WorldmindGraph.java`
- Test: `src/test/java/com/worldmind/core/graph/GraphTest.java` (update existing)

**Step 1: Update the graph**

Modify `WorldmindGraph.java` to:
1. Inject `DispatchAgentNode` in the constructor
2. Add `"dispatch_agent"` node
3. Change `await_approval` edge to go to `dispatch_agent` instead of END
4. Add conditional edge after `dispatch_agent` that loops or exits
5. For FULL_AUTO mode, route from `plan_mission` to `dispatch_agent` directly

The updated constructor and routing:

```java
public WorldmindGraph(
        ClassifyRequestNode classifyNode,
        UploadContextNode uploadNode,
        PlanMissionNode planNode,
        DispatchAgentNode dispatchNode,
        @Autowired(required = false) BaseCheckpointSaver checkpointSaver) throws Exception {

    var graph = new StateGraph<>(WorldmindState.SCHEMA, WorldmindState::new)
            .addNode("classify_request", node_async(classifyNode::apply))
            .addNode("upload_context", node_async(uploadNode::apply))
            .addNode("plan_mission", node_async(planNode::apply))
            .addNode("await_approval", node_async(
                    state -> Map.of("status", MissionStatus.AWAITING_APPROVAL.name())))
            .addNode("dispatch_agent", node_async(dispatchNode::apply))
            .addEdge(START, "classify_request")
            .addEdge("classify_request", "upload_context")
            .addEdge("upload_context", "plan_mission")
            .addConditionalEdges("plan_mission",
                    edge_async(this::routeAfterPlan),
                    Map.of("await_approval", "await_approval",
                            "dispatch", "dispatch_agent"))
            .addEdge("await_approval", "dispatch_agent")
            .addConditionalEdges("dispatch_agent",
                    edge_async(this::routeAfterDispatch),
                    Map.of("dispatch_agent", "dispatch_agent",
                            "end", END));

    // ... checkpointer config same as before
}

String routeAfterPlan(WorldmindState state) {
    if (state.interactionMode() == InteractionMode.FULL_AUTO) {
        return "dispatch";
    }
    return "await_approval";
}

String routeAfterDispatch(WorldmindState state) {
    int currentIndex = state.currentTaskIndex();
    int totalTasks = state.tasks().size();
    if (currentIndex < totalTasks) {
        return "dispatch_agent";
    }
    return "end";
}
```

**Step 2: Update graph test to verify new nodes and edges exist**

**Step 3: Run all tests**

Run: `mvn test -f /Users/dbbaskette/Projects/Worldmind/pom.xml`
Expected: ALL PASS

**Step 4: Commit**

```bash
git add src/main/java/com/worldmind/core/graph/WorldmindGraph.java \
        src/test/java/com/worldmind/core/graph/GraphTest.java
git commit -m "feat: wire dispatch_agent node into LangGraph4j graph"
```

---

## Task 2.10: Update CLI Output for Sandbox Activity

**Files:**
- Modify: `src/main/java/com/worldmind/dispatch/cli/ConsoleOutput.java`
- Modify: `src/main/java/com/worldmind/dispatch/cli/MissionCommand.java:86-91`

**Step 1: Add sandbox output methods to ConsoleOutput**

Add to `ConsoleOutput.java`:

```java
public static void sandbox(String message) {
    System.out.println(CommandLine.Help.Ansi.AUTO.string(
            "@|fg(magenta) [SANDBOX]|@ " + message));
}

public static void agent(String type, String message) {
    System.out.println(CommandLine.Help.Ansi.AUTO.string(
            "@|fg(blue) [AGENT " + type + "]|@ " + message));
}

public static void fileChange(String action, String path) {
    String symbol = "created".equals(action) ? "+" : "~";
    System.out.println(CommandLine.Help.Ansi.AUTO.string(
            "  @|fg(green) " + symbol + "|@ " + path));
}
```

**Step 2: Update MissionCommand to display execution results**

Replace lines 86-91 of `MissionCommand.java` (the `AWAITING_APPROVAL` block) with logic that also shows sandbox activity and file changes when the mission has executed:

```java
// Display execution results
var sandboxes = finalState.sandboxes();
if (!sandboxes.isEmpty()) {
    System.out.println();
    for (var sg : sandboxes) {
        ConsoleOutput.sandbox(String.format(
            "Agent %s — %s (%s)",
            sg.agentType(), sg.taskId(), sg.status()));
    }
}

// Display file changes from tasks
var allTasks = finalState.tasks();
for (var d : allTasks) {
    if (d.filesAffected() != null) {
        for (var f : d.filesAffected()) {
            ConsoleOutput.fileChange(f.action(), f.path());
        }
    }
}

// Display final status
System.out.println();
if (finalState.status() == MissionStatus.COMPLETED) {
    ConsoleOutput.success("Mission complete.");
} else if (finalState.status() == MissionStatus.AWAITING_APPROVAL) {
    ConsoleOutput.info("Mission planned. Awaiting approval.");
} else if (finalState.status() == MissionStatus.FAILED) {
    ConsoleOutput.error("Mission failed.");
    finalState.errors().forEach(e -> ConsoleOutput.error("  " + e));
} else {
    ConsoleOutput.info("Mission status: " + finalState.status());
}
```

**Step 3: Run tests**

Run: `mvn test -f /Users/dbbaskette/Projects/Worldmind/pom.xml`

**Step 4: Commit**

```bash
git add src/main/java/com/worldmind/dispatch/cli/ConsoleOutput.java \
        src/main/java/com/worldmind/dispatch/cli/MissionCommand.java
git commit -m "feat: CLI output for Sandbox activity and file changes"
```

---

## Task 2.11: Agent Coder Docker Image

**Files:**
- Modify: `docker/agent-base/Dockerfile` — update Goose install (Goose uses `pipx install goose-ai` or `brew install goose`)
- Create: `docker/agent-coder/Dockerfile`

**Step 1: Update the base Dockerfile**

The base image should have Goose CLI properly installed. Update `docker/agent-base/Dockerfile`:

```dockerfile
FROM python:3.12-slim

# Install system dependencies
RUN apt-get update && apt-get install -y curl git && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Goose CLI
RUN pip install --no-cache-dir goose-ai

# Install MCP servers
RUN npm install -g @modelcontextprotocol/server-filesystem

# Create non-root user
RUN useradd -m -s /bin/bash agent

WORKDIR /workspace

USER agent
```

**Step 2: Create the Coder Dockerfile**

`docker/agent-coder/Dockerfile`:

```dockerfile
FROM worldmind/agent-base:latest

# Coder-specific label
LABEL com.worldmind.agent="coder"
LABEL com.worldmind.description="Code generation agent"

# Goose config is provided via environment variables:
#   GOOSE_PROVIDER - openai (LM Studio) or anthropic
#   GOOSE_MODEL - model name
#   OPENAI_HOST - LM Studio URL (when provider=openai)
#   OPENAI_API_KEY - API key (not-needed-for-local when using LM Studio)
#   ANTHROPIC_API_KEY - API key (when provider=anthropic)

# Entry point is overridden by Docker Java client
# Default: goose run -t "<instruction>"
ENTRYPOINT ["goose", "run"]
```

**Step 3: Build the images**

```bash
cd /Users/dbbaskette/Projects/Worldmind
docker build -t worldmind/agent-base:latest docker/agent-base/
docker build -t worldmind/agent-coder:latest docker/agent-coder/
```

Expected: Both images build successfully.

**Step 4: Commit**

```bash
git add docker/agent-base/Dockerfile docker/agent-coder/Dockerfile
git commit -m "feat: Agent Coder Docker image with Goose CLI"
```

---

## Task 2.12: End-to-End Integration Test

**Files:**
- Create: `src/test/java/com/worldmind/integration/CoderIntegrationTest.java`

**Step 1: Write the integration test**

This test requires Docker running and the agent-coder image built. It uses a simple inline instruction (no LLM needed) to verify the full sandbox lifecycle.

```java
package com.worldmind.integration;

import com.worldmind.sandbox.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for Sandbox execution.
 * Requires Docker to be running and the agent-coder image built.
 * Run with: mvn test -Dgroups=integration
 */
@Tag("integration")
class CoderIntegrationTest {

    @Test
    void sandboxExecutesGooseAndCreatesFile(@TempDir Path tempDir) {
        // Setup Docker client
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .build();
        var dockerClient = DockerClientImpl.getInstance(dockerConfig, httpClient);

        var provider = new DockerSandboxProvider(dockerClient);
        var properties = new SandboxProperties();
        var manager = new SandboxManager(provider, properties);

        // Execute a simple file creation task
        var result = manager.executeTask(
            "coder", "INT-001", tempDir,
            "Create a file named hello.py in the current directory with the content: print('Hello from Worldmind')",
            Map.of("GOOSE_PROVIDER", "openai",
                   "GOOSE_MODEL", "qwen2.5-coder-32b",
                   "OPENAI_HOST", "http://host.docker.internal:1234/v1",
                   "OPENAI_API_KEY", "not-needed")
        );

        // Verify execution completed
        assertEquals(0, result.exitCode(), "Goose should exit cleanly. Output: " + result.output());
        assertTrue(result.elapsedMs() > 0);

        // Verify file was created
        Path helloFile = tempDir.resolve("hello.py");
        assertTrue(Files.exists(helloFile), "hello.py should have been created");

        String content = Files.readString(helloFile);
        assertTrue(content.contains("Hello"), "File should contain greeting");

        // Verify file change detection
        assertFalse(result.fileChanges().isEmpty(), "Should detect file changes");
    }
}
```

**Step 2: Run the test (requires Docker + LM Studio)**

Run: `mvn test -Dgroups=integration -f /Users/dbbaskette/Projects/Worldmind/pom.xml`

Note: This will only pass if Docker is running, the image is built, and LM Studio is running with a model loaded. Skip this in CI for now.

**Step 3: Commit**

```bash
git add src/test/java/com/worldmind/integration/CoderIntegrationTest.java
git commit -m "test: end-to-end Agent Coder integration test"
```

---

## Task 2.13: Run All Tests and Final Verification

**Step 1: Run unit tests**

Run: `mvn test -f /Users/dbbaskette/Projects/Worldmind/pom.xml`
Expected: ALL PASS (excluding @Tag("integration"))

**Step 2: Verify the app compiles and packages**

Run: `mvn clean package -DskipTests -f /Users/dbbaskette/Projects/Worldmind/pom.xml`
Expected: BUILD SUCCESS, JAR created in target/

**Step 3: Build Docker images**

```bash
docker build -t worldmind/agent-base:latest /Users/dbbaskette/Projects/Worldmind/docker/agent-base/
docker build -t worldmind/agent-coder:latest /Users/dbbaskette/Projects/Worldmind/docker/agent-coder/
```

**Step 4: Commit any remaining fixes**

```bash
git add -A
git commit -m "chore: Phase 2 final cleanup and test fixes"
```

---

## Summary

| Task | Component | Commit Message |
|------|-----------|---------------|
| 2.1 | SandboxProvider + AgentRequest | `feat: SandboxProvider interface and AgentRequest record` |
| 2.2 | SandboxProperties | `feat: SandboxProperties with Goose provider and container config` |
| 2.3 | InstructionBuilder | `feat: InstructionBuilder converts tasks to Goose instructions` |
| 2.4 | DockerSandboxProvider | `feat: DockerSandboxProvider for container lifecycle management` |
| 2.5 | SandboxManager | `feat: SandboxManager with file change detection` |
| 2.6 | AgentDispatcher | `feat: AgentDispatcher translates tasks to sandbox executions` |
| 2.7 | SandboxConfig | `feat: SandboxConfig wires Docker provider beans` |
| 2.8 | DispatchAgentNode | `feat: DispatchAgentNode dispatches tasks to Sandboxes` |
| 2.9 | Graph wiring | `feat: wire dispatch_agent node into LangGraph4j graph` |
| 2.10 | CLI output | `feat: CLI output for Sandbox activity and file changes` |
| 2.11 | Docker image | `feat: Agent Coder Docker image with Goose CLI` |
| 2.12 | Integration test | `test: end-to-end Agent Coder integration test` |
| 2.13 | Final verification | `chore: Phase 2 final cleanup and test fixes` |
