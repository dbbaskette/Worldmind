# Phase 2: First Centurion — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Execute directives by spinning up Docker containers running Goose (Centurion Forge) that generate code in the user's project directory.

**Architecture:** A `StarblasterProvider` interface abstracts container orchestration (Docker for dev, Cloud Foundry for prod). `StarblasterManager` handles file change detection and delegates to the provider. `StarblasterBridge` translates between domain records and the starblaster infrastructure. `DispatchCenturionNode` plugs into the LangGraph4j graph as a sequential loop after plan approval. Goose runs headlessly via `goose run -t "<instruction>"` inside containers, connecting to LM Studio on the host (dev) or Anthropic API (prod).

**Tech Stack:** Java 21, Docker Java client 3.4.1, Goose CLI (headless), LM Studio (OpenAI-compatible), LangGraph4j 1.8

---

## Task 2.1: StarblasterProvider Interface & StarblasterRequest Record

**Files:**
- Create: `src/main/java/com/worldmind/starblaster/StarblasterProvider.java`
- Create: `src/main/java/com/worldmind/starblaster/StarblasterRequest.java`
- Test: `src/test/java/com/worldmind/starblaster/StarblasterProviderTest.java`

**Step 1: Write the test**

```java
package com.worldmind.starblaster;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StarblasterProviderTest {

    @Test
    void starblasterRequestBuildsWithAllFields() {
        var request = new StarblasterRequest(
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
    void starblasterRequestInstructionTextIsPreserved() {
        var request = new StarblasterRequest(
            "forge", "d-001", Path.of("/tmp"),
            "Build the feature",
            Map.of(), 2048, 1
        );
        assertEquals("Build the feature", request.instructionText());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=StarblasterProviderTest -f /Users/dbbaskette/Projects/Worldmind/pom.xml`
Expected: FAIL — class not found

**Step 3: Write the interface and record**

`StarblasterProvider.java`:
```java
package com.worldmind.starblaster;

/**
 * Abstraction for container orchestration.
 * Implementations: DockerStarblasterProvider (dev), CloudFoundryStarblasterProvider (prod).
 */
public interface StarblasterProvider {

    /**
     * Creates and starts a container for a Centurion.
     * @return the container/starblaster ID
     */
    String openStarblaster(StarblasterRequest request);

    /**
     * Blocks until the container exits or timeout is reached.
     * @return the container exit code (0 = success)
     */
    int waitForCompletion(String starblasterId, int timeoutSeconds);

    /**
     * Captures stdout/stderr logs from the container.
     */
    String captureOutput(String starblasterId);

    /**
     * Stops and removes the container.
     */
    void teardownStarblaster(String starblasterId);
}
```

`StarblasterRequest.java`:
```java
package com.worldmind.starblaster;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Map;

/**
 * Everything needed to open a Starblaster container.
 *
 * @param centurionType  e.g. "forge", "vigil", "gauntlet"
 * @param directiveId    the directive this starblaster serves
 * @param projectPath    host path to the project directory (bind-mounted as /workspace)
 * @param instructionText the full instruction markdown for Goose
 * @param envVars        environment variables to inject (GOOSE_PROVIDER, GOOSE_MODEL, etc.)
 * @param memoryLimitMb  memory limit in MB
 * @param cpuCount       CPU count limit
 */
public record StarblasterRequest(
    String centurionType,
    String directiveId,
    Path projectPath,
    String instructionText,
    Map<String, String> envVars,
    int memoryLimitMb,
    int cpuCount
) implements Serializable {}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest=StarblasterProviderTest -f /Users/dbbaskette/Projects/Worldmind/pom.xml`
Expected: PASS (2 tests)

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/starblaster/StarblasterProvider.java \
        src/main/java/com/worldmind/starblaster/StarblasterRequest.java \
        src/test/java/com/worldmind/starblaster/StarblasterProviderTest.java
git commit -m "feat: StarblasterProvider interface and StarblasterRequest record"
```

---

## Task 2.2: StarblasterProperties Configuration

**Files:**
- Create: `src/main/java/com/worldmind/starblaster/StarblasterProperties.java`
- Modify: `src/main/resources/application.yml:38-46`
- Test: `src/test/java/com/worldmind/starblaster/StarblasterPropertiesTest.java`

**Step 1: Write the test**

```java
package com.worldmind.starblaster;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarblasterPropertiesTest {

    @Test
    void defaultsAreReasonable() {
        var props = new StarblasterProperties();
        assertEquals("docker", props.getProvider());
        assertEquals(300, props.getTimeoutSeconds());
        assertEquals(4096, props.getMemoryLimitMb());
        assertEquals(2, props.getCpuCount());
        assertEquals(10, props.getMaxParallel());
    }

    @Test
    void gooseDefaultsAreReasonable() {
        var props = new StarblasterProperties();
        assertEquals("openai", props.getGooseProvider());
        assertEquals("http://host.docker.internal:1234/v1", props.getLmStudioUrl());
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

`StarblasterProperties.java`:
```java
package com.worldmind.starblaster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Starblaster container management and Goose model provider.
 * Bound from application.yml under the "worldmind" prefix.
 */
@Component
@ConfigurationProperties(prefix = "worldmind")
public class StarblasterProperties {

    private Starblaster starblaster = new Starblaster();
    private Goose goose = new Goose();

    // -- Starblaster accessors (delegate to nested) --

    public String getProvider() { return starblaster.provider; }
    public int getTimeoutSeconds() { return starblaster.timeoutSeconds; }
    public int getMemoryLimitMb() { return starblaster.memoryLimitMb; }
    public int getCpuCount() { return starblaster.cpuCount; }
    public int getMaxParallel() { return starblaster.maxParallel; }
    public String getImage() { return starblaster.image; }

    // -- Goose accessors (delegate to nested) --

    public String getGooseProvider() { return goose.provider; }
    public String getGooseModel() { return goose.model; }
    public String getLmStudioUrl() { return goose.lmStudioUrl; }

    public Starblaster getStarblaster() { return starblaster; }
    public void setStarblaster(Starblaster starblaster) { this.starblaster = starblaster; }
    public Goose getGoose() { return goose; }
    public void setGoose(Goose goose) { this.goose = goose; }

    public static class Starblaster {
        private String provider = "docker";
        private int maxParallel = 10;
        private int timeoutSeconds = 300;
        private int memoryLimitMb = 4096;
        private int cpuCount = 2;
        private String image = "worldmind/centurion-forge:latest";

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
  starblaster:
    provider: ${STARBLASTER_PROVIDER:docker}
    max-parallel: 10
    timeout-seconds: 300
    memory-limit-mb: 4096
    cpu-count: 2
    image: worldmind/centurion-forge:latest
```

**Step 4: Run test — expect PASS**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/starblaster/StarblasterProperties.java \
        src/main/resources/application.yml \
        src/test/java/com/worldmind/starblaster/StarblasterPropertiesTest.java
git commit -m "feat: StarblasterProperties with Goose provider and container config"
```

---

## Task 2.3: InstructionBuilder

**Files:**
- Create: `src/main/java/com/worldmind/starblaster/InstructionBuilder.java`
- Test: `src/test/java/com/worldmind/starblaster/InstructionBuilderTest.java`

**Step 1: Write the test**

```java
package com.worldmind.starblaster;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InstructionBuilderTest {

    @Test
    void buildIncludesDirectiveFields() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create hello.py",
            "Python project with pytest", "File hello.py exists and is valid Python",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var context = new ProjectContext("/tmp/project", "src/\n  main.py",
            "python", "flask", "flask,pytest", 10, "A Flask web app");

        String instruction = InstructionBuilder.build(directive, context);

        assertTrue(instruction.contains("DIR-001"));
        assertTrue(instruction.contains("Create hello.py"));
        assertTrue(instruction.contains("File hello.py exists and is valid Python"));
        assertTrue(instruction.contains("python"));
        assertTrue(instruction.contains("flask"));
        assertTrue(instruction.contains("A Flask web app"));
    }

    @Test
    void buildIncludesConstraintsSection() {
        var directive = new Directive(
            "DIR-002", "FORGE", "Add endpoint",
            "", "Endpoint returns 200",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var context = new ProjectContext("/tmp/p", "", "java", "spring", "", 5, "");

        String instruction = InstructionBuilder.build(directive, context);

        assertTrue(instruction.contains("Constraints"));
        assertTrue(instruction.contains("Only modify files related to this directive"));
    }

    @Test
    void buildWithNullContextDoesNotThrow() {
        var directive = new Directive(
            "DIR-003", "FORGE", "Do something",
            "some context", "It works",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );

        String instruction = InstructionBuilder.build(directive, null);

        assertTrue(instruction.contains("DIR-003"));
        assertTrue(instruction.contains("Do something"));
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

```java
package com.worldmind.starblaster;

import com.worldmind.core.model.Directive;
import com.worldmind.core.model.ProjectContext;

/**
 * Converts a Directive and ProjectContext into a Goose-readable instruction string.
 * Pure function — no Spring dependencies.
 */
public final class InstructionBuilder {

    private InstructionBuilder() {}

    public static String build(Directive directive, ProjectContext context) {
        var sb = new StringBuilder();

        sb.append("# Directive: ").append(directive.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append(directive.description()).append("\n\n");

        if (directive.inputContext() != null && !directive.inputContext().isBlank()) {
            sb.append("## Additional Context\n\n");
            sb.append(directive.inputContext()).append("\n\n");
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
        sb.append(directive.successCriteria()).append("\n\n");

        sb.append("## Constraints\n\n");
        sb.append("- Only modify files related to this directive\n");
        sb.append("- Do not modify test files (Gauntlet handles tests)\n");
        sb.append("- Commit nothing — file changes are detected externally\n");
        sb.append("- If you encounter an error, attempt to fix it before reporting failure\n");

        return sb.toString();
    }
}
```

**Step 4: Run test — expect PASS**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/starblaster/InstructionBuilder.java \
        src/test/java/com/worldmind/starblaster/InstructionBuilderTest.java
git commit -m "feat: InstructionBuilder converts directives to Goose instructions"
```

---

## Task 2.4: DockerStarblasterProvider

**Files:**
- Create: `src/main/java/com/worldmind/starblaster/DockerStarblasterProvider.java`
- Test: `src/test/java/com/worldmind/starblaster/DockerStarblasterProviderTest.java`

**Step 1: Write the test**

```java
package com.worldmind.starblaster;

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

class DockerStarblasterProviderTest {

    private DockerClient dockerClient;
    private DockerStarblasterProvider provider;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        provider = new DockerStarblasterProvider(dockerClient);
    }

    @Test
    void openStarblasterCreatesAndStartsContainer() {
        when(dockerClient.createContainerCmd(any(String.class))
                .withName(any())
                .withHostConfig(any())
                .withEnv(any(java.util.List.class))
                .exec())
                .thenReturn(mock(CreateContainerResponse.class));

        var request = new StarblasterRequest(
            "forge", "DIR-001", Path.of("/tmp/project"),
            "Create hello.py", Map.of("GOOSE_PROVIDER", "openai"),
            4096, 2
        );

        // Should not throw
        assertDoesNotThrow(() -> provider.openStarblaster(request));
    }

    @Test
    void teardownStarblasterStopsAndRemovesContainer() {
        provider.teardownStarblaster("container-123");

        verify(dockerClient).stopContainerCmd("container-123");
        verify(dockerClient).removeContainerCmd("container-123");
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

```java
package com.worldmind.starblaster;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Docker-based StarblasterProvider for local development.
 * Creates Docker containers for each Centurion directive execution.
 */
public class DockerStarblasterProvider implements StarblasterProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerStarblasterProvider.class);

    private final DockerClient dockerClient;

    public DockerStarblasterProvider(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public String openStarblaster(StarblasterRequest request) {
        String containerName = "starblaster-" + request.centurionType() + "-" + request.directiveId();
        log.info("Opening Starblaster {} for directive {}", containerName, request.directiveId());

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

        var response = dockerClient.createContainerCmd("worldmind/centurion-forge:latest")
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withEnv(envList)
                .withCmd("goose", "run", "-t", request.instructionText())
                .withWorkingDir("/workspace")
                .exec();

        String containerId = response.getId();
        dockerClient.startContainerCmd(containerId).exec();
        log.info("Starblaster {} started (container {})", containerName, containerId);
        return containerId;
    }

    @Override
    public int waitForCompletion(String starblasterId, int timeoutSeconds) {
        try {
            var callback = dockerClient.waitContainerCmd(starblasterId)
                    .exec(new WaitContainerResultCallback());
            var result = callback.awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
            return result != null ? result : -1;
        } catch (Exception e) {
            log.error("Timeout or error waiting for starblaster {}", starblasterId, e);
            return -1;
        }
    }

    @Override
    public String captureOutput(String starblasterId) {
        var sb = new StringBuilder();
        try {
            dockerClient.logContainerCmd(starblasterId)
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
            log.warn("Interrupted while capturing output from starblaster {}", starblasterId);
        }
        return sb.toString();
    }

    @Override
    public void teardownStarblaster(String starblasterId) {
        try {
            dockerClient.stopContainerCmd(starblasterId).exec();
        } catch (Exception e) {
            log.debug("Container {} may already be stopped", starblasterId);
        }
        try {
            dockerClient.removeContainerCmd(starblasterId).withForce(true).exec();
            log.info("Starblaster {} torn down", starblasterId);
        } catch (Exception e) {
            log.warn("Failed to remove container {}", starblasterId, e);
        }
    }
}
```

**Step 4: Run test — expect PASS (mocked tests)**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/starblaster/DockerStarblasterProvider.java \
        src/test/java/com/worldmind/starblaster/DockerStarblasterProviderTest.java
git commit -m "feat: DockerStarblasterProvider for container lifecycle management"
```

---

## Task 2.5: StarblasterManager (File Diffing + Provider Delegation)

**Files:**
- Create: `src/main/java/com/worldmind/starblaster/StarblasterManager.java`
- Test: `src/test/java/com/worldmind/starblaster/StarblasterManagerTest.java`

**Step 1: Write the test**

```java
package com.worldmind.starblaster;

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

class StarblasterManagerTest {

    private StarblasterProvider provider;
    private StarblasterProperties properties;
    private StarblasterManager manager;

    @BeforeEach
    void setUp() {
        provider = mock(StarblasterProvider.class);
        properties = new StarblasterProperties();
        manager = new StarblasterManager(provider, properties);
    }

    @Test
    void executeDirectiveCallsProviderLifecycle() {
        when(provider.openStarblaster(any())).thenReturn("container-1");
        when(provider.waitForCompletion("container-1", 300)).thenReturn(0);
        when(provider.captureOutput("container-1")).thenReturn("done");

        var result = manager.executeDirective(
            "forge", "DIR-001", Path.of("/tmp/test"),
            "Create file", Map.of()
        );

        verify(provider).openStarblaster(any());
        verify(provider).waitForCompletion("container-1", 300);
        verify(provider).captureOutput("container-1");
        verify(provider).teardownStarblaster("container-1");
        assertEquals(0, result.exitCode());
    }

    @Test
    void executeDirectiveReportsFailureOnNonZeroExit() {
        when(provider.openStarblaster(any())).thenReturn("container-2");
        when(provider.waitForCompletion("container-2", 300)).thenReturn(1);
        when(provider.captureOutput("container-2")).thenReturn("error");

        var result = manager.executeDirective(
            "forge", "DIR-002", Path.of("/tmp/test"),
            "Bad instruction", Map.of()
        );

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("error"));
    }

    @Test
    void detectFileChangesFindsNewFiles(@TempDir Path tempDir) throws IOException {
        // Take snapshot with no files
        var before = StarblasterManager.snapshotFiles(tempDir);

        // Create a new file
        Files.writeString(tempDir.resolve("hello.py"), "print('hello')");

        // Detect changes
        var changes = StarblasterManager.detectChanges(before, tempDir);

        assertEquals(1, changes.size());
        assertEquals("hello.py", changes.get(0).path());
        assertEquals("created", changes.get(0).action());
    }

    @Test
    void detectFileChangesFindsModifiedFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("existing.py"), "old");

        var before = StarblasterManager.snapshotFiles(tempDir);

        // Modify the file
        Files.writeString(tempDir.resolve("existing.py"), "new content");

        var changes = StarblasterManager.detectChanges(before, tempDir);

        assertEquals(1, changes.size());
        assertEquals("existing.py", changes.get(0).path());
        assertEquals("modified", changes.get(0).action());
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

```java
package com.worldmind.starblaster;

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
 * Manages Starblaster lifecycle and file change detection.
 * Delegates container operations to the active StarblasterProvider.
 */
@Service
public class StarblasterManager {

    private static final Logger log = LoggerFactory.getLogger(StarblasterManager.class);

    private final StarblasterProvider provider;
    private final StarblasterProperties properties;

    public StarblasterManager(StarblasterProvider provider, StarblasterProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    /**
     * Result of a starblaster execution.
     */
    public record ExecutionResult(
        int exitCode,
        String output,
        String starblasterId,
        List<FileRecord> fileChanges,
        long elapsedMs
    ) {}

    /**
     * Executes a directive in a Starblaster container.
     */
    public ExecutionResult executeDirective(
            String centurionType,
            String directiveId,
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

        var request = new StarblasterRequest(
            centurionType, directiveId, projectPath,
            instructionText, envVars,
            properties.getMemoryLimitMb(), properties.getCpuCount()
        );

        // Snapshot files before execution
        Map<String, Long> beforeSnapshot = snapshotFiles(projectPath);

        long startMs = System.currentTimeMillis();
        String starblasterId = provider.openStarblaster(request);

        try {
            int exitCode = provider.waitForCompletion(starblasterId, properties.getTimeoutSeconds());
            String output = provider.captureOutput(starblasterId);
            long elapsedMs = System.currentTimeMillis() - startMs;

            // Detect file changes
            List<FileRecord> changes = detectChanges(beforeSnapshot, projectPath);

            log.info("Starblaster {} completed with exit code {} in {}ms — {} file changes",
                    starblasterId, exitCode, elapsedMs, changes.size());

            return new ExecutionResult(exitCode, output, starblasterId, changes, elapsedMs);
        } finally {
            provider.teardownStarblaster(starblasterId);
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
git add src/main/java/com/worldmind/starblaster/StarblasterManager.java \
        src/test/java/com/worldmind/starblaster/StarblasterManagerTest.java
git commit -m "feat: StarblasterManager with file change detection"
```

---

## Task 2.6: StarblasterBridge

**Files:**
- Create: `src/main/java/com/worldmind/starblaster/StarblasterBridge.java`
- Test: `src/test/java/com/worldmind/starblaster/StarblasterBridgeTest.java`

**Step 1: Write the test**

```java
package com.worldmind.starblaster;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StarblasterBridgeTest {

    private StarblasterManager manager;
    private StarblasterBridge bridge;

    @BeforeEach
    void setUp() {
        manager = mock(StarblasterManager.class);
        bridge = new StarblasterBridge(manager);
    }

    @Test
    void executeDirectiveReturnsUpdatedDirectiveOnSuccess() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create hello.py",
            "context", "hello.py exists",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var context = new ProjectContext("/tmp/p", "", "python", "none", "", 0, "");
        var fileChanges = List.of(new FileRecord("hello.py", "created", 1));
        var execResult = new StarblasterManager.ExecutionResult(0, "done", "c-1", fileChanges, 5000L);

        when(manager.executeDirective(
            eq("FORGE"), eq("DIR-001"), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeDirective(directive, context, Path.of("/tmp/p"));

        assertEquals(DirectiveStatus.PASSED, result.directive().status());
        assertEquals(5000L, result.directive().elapsedMs());
        assertEquals(1, result.directive().filesAffected().size());
        assertNotNull(result.starblasterInfo());
    }

    @Test
    void executeDirectiveMarksFailedOnNonZeroExit() {
        var directive = new Directive(
            "DIR-002", "FORGE", "Bad task",
            "", "never",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var execResult = new StarblasterManager.ExecutionResult(1, "error", "c-2", List.of(), 3000L);

        when(manager.executeDirective(any(), any(), any(), anyString(), any()))
            .thenReturn(execResult);

        var result = bridge.executeDirective(directive, null, Path.of("/tmp"));

        assertEquals(DirectiveStatus.FAILED, result.directive().status());
    }
}
```

**Step 2: Run test — expect FAIL**

**Step 3: Write implementation**

```java
package com.worldmind.starblaster;

import com.worldmind.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Thin orchestration layer between domain records and the StarblasterManager.
 * Translates Directives into starblaster executions and results back into records.
 */
@Service
public class StarblasterBridge {

    private static final Logger log = LoggerFactory.getLogger(StarblasterBridge.class);

    private final StarblasterManager manager;

    public StarblasterBridge(StarblasterManager manager) {
        this.manager = manager;
    }

    /**
     * Result of executing a directive through the bridge.
     */
    public record BridgeResult(
        Directive directive,
        StarblasterInfo starblasterInfo,
        String output
    ) {}

    /**
     * Executes a single directive via the Starblaster infrastructure.
     */
    public BridgeResult executeDirective(Directive directive, ProjectContext context, Path projectPath) {
        log.info("Executing directive {} [{}]: {}",
                directive.id(), directive.centurion(), directive.description());

        String instruction = InstructionBuilder.build(directive, context);
        Instant startedAt = Instant.now();

        var execResult = manager.executeDirective(
            directive.centurion(),
            directive.id(),
            projectPath,
            instruction,
            Map.of()
        );

        Instant completedAt = Instant.now();
        boolean success = execResult.exitCode() == 0;

        // Build updated directive with results
        var updatedDirective = new Directive(
            directive.id(),
            directive.centurion(),
            directive.description(),
            directive.inputContext(),
            directive.successCriteria(),
            directive.dependencies(),
            success ? DirectiveStatus.PASSED : DirectiveStatus.FAILED,
            directive.iteration() + 1,
            directive.maxIterations(),
            directive.onFailure(),
            execResult.fileChanges(),
            execResult.elapsedMs()
        );

        var starblasterInfo = new StarblasterInfo(
            execResult.starblasterId(),
            directive.centurion(),
            directive.id(),
            success ? "completed" : "failed",
            startedAt,
            completedAt
        );

        return new BridgeResult(updatedDirective, starblasterInfo, execResult.output());
    }
}
```

**Step 4: Run test — expect PASS**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/starblaster/StarblasterBridge.java \
        src/test/java/com/worldmind/starblaster/StarblasterBridgeTest.java
git commit -m "feat: StarblasterBridge translates directives to starblaster executions"
```

---

## Task 2.7: StarblasterConfig (Spring Bean Wiring)

**Files:**
- Create: `src/main/java/com/worldmind/starblaster/StarblasterConfig.java`
- Test: (covered by existing tests + integration)

**Step 1: Write the config**

```java
package com.worldmind.starblaster;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Spring configuration for Starblaster beans.
 * Selects the active StarblasterProvider based on worldmind.starblaster.provider property.
 */
@Configuration
public class StarblasterConfig {

    @Bean
    @ConditionalOnProperty(name = "worldmind.starblaster.provider", havingValue = "docker", matchIfMissing = true)
    public DockerClient dockerClient() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    @Bean
    @ConditionalOnProperty(name = "worldmind.starblaster.provider", havingValue = "docker", matchIfMissing = true)
    public StarblasterProvider dockerStarblasterProvider(DockerClient dockerClient) {
        return new DockerStarblasterProvider(dockerClient);
    }
}
```

**Step 2: Verify the app still compiles**

Run: `mvn compile -f /Users/dbbaskette/Projects/Worldmind/pom.xml`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/worldmind/starblaster/StarblasterConfig.java
git commit -m "feat: StarblasterConfig wires Docker provider beans"
```

---

## Task 2.8: DispatchCenturionNode

**Files:**
- Create: `src/main/java/com/worldmind/core/nodes/DispatchCenturionNode.java`
- Test: `src/test/java/com/worldmind/core/nodes/DispatchCenturionNodeTest.java`

**Step 1: Write the test**

```java
package com.worldmind.core.nodes;

import com.worldmind.core.model.*;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.starblaster.StarblasterBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DispatchCenturionNodeTest {

    private StarblasterBridge bridge;
    private DispatchCenturionNode node;

    @BeforeEach
    void setUp() {
        bridge = mock(StarblasterBridge.class);
        node = new DispatchCenturionNode(bridge);
    }

    @Test
    void applyDispatchesNextPendingDirective() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var updatedDirective = new Directive(
            "DIR-001", "FORGE", "Create file",
            "", "File exists",
            List.of(), DirectiveStatus.PASSED, 1, 3,
            FailureStrategy.RETRY, List.of(new FileRecord("hello.py", "created", 1)), 5000L
        );
        var starblasterInfo = new StarblasterInfo("c-1", "FORGE", "DIR-001", "completed",
            Instant.now(), Instant.now());
        var bridgeResult = new StarblasterBridge.BridgeResult(updatedDirective, starblasterInfo, "ok");

        when(bridge.executeDirective(any(), any(), any())).thenReturn(bridgeResult);

        var context = new ProjectContext("/tmp/p", "", "java", "spring", "", 5, "");
        var state = new WorldmindState(Map.of(
            "directives", List.of(directive),
            "currentDirectiveIndex", 0,
            "projectContext", context
        ));

        var result = node.apply(state);

        assertNotNull(result);
        assertTrue(result.containsKey("starblasters"));
        assertTrue(result.containsKey("currentDirectiveIndex"));
        assertEquals(1, result.get("currentDirectiveIndex"));
    }

    @Test
    void applySkipsWhenNoDirectivesPending() {
        var state = new WorldmindState(Map.of(
            "directives", List.of(),
            "currentDirectiveIndex", 0
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

import com.worldmind.core.model.DirectiveStatus;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.starblaster.StarblasterBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangGraph4j node that dispatches the next pending directive to a Starblaster.
 * Calls StarblasterBridge to execute the directive and returns updated state.
 */
@Component
public class DispatchCenturionNode {

    private static final Logger log = LoggerFactory.getLogger(DispatchCenturionNode.class);

    private final StarblasterBridge bridge;

    public DispatchCenturionNode(StarblasterBridge bridge) {
        this.bridge = bridge;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var directives = state.directives();
        int currentIndex = state.currentDirectiveIndex();

        if (directives.isEmpty() || currentIndex >= directives.size()) {
            log.info("No pending directives — mission complete");
            return Map.of("status", MissionStatus.COMPLETED.name());
        }

        var directive = directives.get(currentIndex);
        if (directive.status() != DirectiveStatus.PENDING) {
            log.info("Directive {} already {}, advancing", directive.id(), directive.status());
            return Map.of("currentDirectiveIndex", currentIndex + 1);
        }

        log.info("Dispatching directive {} [{}]: {}",
                directive.id(), directive.centurion(), directive.description());

        var projectContext = state.projectContext().orElse(null);
        String projectPath = projectContext != null ? projectContext.rootPath() : ".";

        var result = bridge.executeDirective(
            directive, projectContext, Path.of(projectPath)
        );

        var updates = new HashMap<String, Object>();
        updates.put("starblasters", List.of(result.starblasterInfo()));
        updates.put("currentDirectiveIndex", currentIndex + 1);
        updates.put("status", MissionStatus.EXECUTING.name());

        if (result.directive().status() == DirectiveStatus.FAILED) {
            updates.put("errors", List.of(
                "Directive " + directive.id() + " failed: " + result.output()));
        }

        return updates;
    }
}
```

**Step 4: Run test — expect PASS**

**Step 5: Commit**

```bash
git add src/main/java/com/worldmind/core/nodes/DispatchCenturionNode.java \
        src/test/java/com/worldmind/core/nodes/DispatchCenturionNodeTest.java
git commit -m "feat: DispatchCenturionNode dispatches directives to Starblasters"
```

---

## Task 2.9: Wire DispatchCenturionNode into Graph

**Files:**
- Modify: `src/main/java/com/worldmind/core/graph/WorldmindGraph.java`
- Test: `src/test/java/com/worldmind/core/graph/GraphTest.java` (update existing)

**Step 1: Update the graph**

Modify `WorldmindGraph.java` to:
1. Inject `DispatchCenturionNode` in the constructor
2. Add `"dispatch_centurion"` node
3. Change `await_approval` edge to go to `dispatch_centurion` instead of END
4. Add conditional edge after `dispatch_centurion` that loops or exits
5. For FULL_AUTO mode, route from `plan_mission` to `dispatch_centurion` directly

The updated constructor and routing:

```java
public WorldmindGraph(
        ClassifyRequestNode classifyNode,
        UploadContextNode uploadNode,
        PlanMissionNode planNode,
        DispatchCenturionNode dispatchNode,
        @Autowired(required = false) BaseCheckpointSaver checkpointSaver) throws Exception {

    var graph = new StateGraph<>(WorldmindState.SCHEMA, WorldmindState::new)
            .addNode("classify_request", node_async(classifyNode::apply))
            .addNode("upload_context", node_async(uploadNode::apply))
            .addNode("plan_mission", node_async(planNode::apply))
            .addNode("await_approval", node_async(
                    state -> Map.of("status", MissionStatus.AWAITING_APPROVAL.name())))
            .addNode("dispatch_centurion", node_async(dispatchNode::apply))
            .addEdge(START, "classify_request")
            .addEdge("classify_request", "upload_context")
            .addEdge("upload_context", "plan_mission")
            .addConditionalEdges("plan_mission",
                    edge_async(this::routeAfterPlan),
                    Map.of("await_approval", "await_approval",
                            "dispatch", "dispatch_centurion"))
            .addEdge("await_approval", "dispatch_centurion")
            .addConditionalEdges("dispatch_centurion",
                    edge_async(this::routeAfterDispatch),
                    Map.of("dispatch_centurion", "dispatch_centurion",
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
    int currentIndex = state.currentDirectiveIndex();
    int totalDirectives = state.directives().size();
    if (currentIndex < totalDirectives) {
        return "dispatch_centurion";
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
git commit -m "feat: wire dispatch_centurion node into LangGraph4j graph"
```

---

## Task 2.10: Update CLI Output for Starblaster Activity

**Files:**
- Modify: `src/main/java/com/worldmind/dispatch/cli/ConsoleOutput.java`
- Modify: `src/main/java/com/worldmind/dispatch/cli/MissionCommand.java:86-91`

**Step 1: Add starblaster output methods to ConsoleOutput**

Add to `ConsoleOutput.java`:

```java
public static void starblaster(String message) {
    System.out.println(CommandLine.Help.Ansi.AUTO.string(
            "@|fg(magenta) [STARBLASTER]|@ " + message));
}

public static void centurion(String type, String message) {
    System.out.println(CommandLine.Help.Ansi.AUTO.string(
            "@|fg(blue) [CENTURION " + type + "]|@ " + message));
}

public static void fileChange(String action, String path) {
    String symbol = "created".equals(action) ? "+" : "~";
    System.out.println(CommandLine.Help.Ansi.AUTO.string(
            "  @|fg(green) " + symbol + "|@ " + path));
}
```

**Step 2: Update MissionCommand to display execution results**

Replace lines 86-91 of `MissionCommand.java` (the `AWAITING_APPROVAL` block) with logic that also shows starblaster activity and file changes when the mission has executed:

```java
// Display execution results
var starblasters = finalState.starblasters();
if (!starblasters.isEmpty()) {
    System.out.println();
    for (var sg : starblasters) {
        ConsoleOutput.starblaster(String.format(
            "Centurion %s — %s (%s)",
            sg.centurionType(), sg.directiveId(), sg.status()));
    }
}

// Display file changes from directives
var allDirectives = finalState.directives();
for (var d : allDirectives) {
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
git commit -m "feat: CLI output for Starblaster activity and file changes"
```

---

## Task 2.11: Centurion Forge Docker Image

**Files:**
- Modify: `docker/centurion-base/Dockerfile` — update Goose install (Goose uses `pipx install goose-ai` or `brew install goose`)
- Create: `docker/centurion-forge/Dockerfile`

**Step 1: Update the base Dockerfile**

The base image should have Goose CLI properly installed. Update `docker/centurion-base/Dockerfile`:

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
RUN useradd -m -s /bin/bash centurion

WORKDIR /workspace

USER centurion
```

**Step 2: Create the Forge Dockerfile**

`docker/centurion-forge/Dockerfile`:

```dockerfile
FROM worldmind/centurion-base:latest

# Forge-specific label
LABEL com.worldmind.centurion="forge"
LABEL com.worldmind.description="Code generation centurion"

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
docker build -t worldmind/centurion-base:latest docker/centurion-base/
docker build -t worldmind/centurion-forge:latest docker/centurion-forge/
```

Expected: Both images build successfully.

**Step 4: Commit**

```bash
git add docker/centurion-base/Dockerfile docker/centurion-forge/Dockerfile
git commit -m "feat: Centurion Forge Docker image with Goose CLI"
```

---

## Task 2.12: End-to-End Integration Test

**Files:**
- Create: `src/test/java/com/worldmind/integration/ForgeIntegrationTest.java`

**Step 1: Write the integration test**

This test requires Docker running and the centurion-forge image built. It uses a simple inline instruction (no LLM needed) to verify the full starblaster lifecycle.

```java
package com.worldmind.integration;

import com.worldmind.starblaster.*;
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
 * End-to-end integration test for Starblaster execution.
 * Requires Docker to be running and the centurion-forge image built.
 * Run with: mvn test -Dgroups=integration
 */
@Tag("integration")
class ForgeIntegrationTest {

    @Test
    void starblasterExecutesGooseAndCreatesFile(@TempDir Path tempDir) {
        // Setup Docker client
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .build();
        var dockerClient = DockerClientImpl.getInstance(dockerConfig, httpClient);

        var provider = new DockerStarblasterProvider(dockerClient);
        var properties = new StarblasterProperties();
        var manager = new StarblasterManager(provider, properties);

        // Execute a simple file creation task
        var result = manager.executeDirective(
            "forge", "INT-001", tempDir,
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
git add src/test/java/com/worldmind/integration/ForgeIntegrationTest.java
git commit -m "test: end-to-end Centurion Forge integration test"
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
docker build -t worldmind/centurion-base:latest /Users/dbbaskette/Projects/Worldmind/docker/centurion-base/
docker build -t worldmind/centurion-forge:latest /Users/dbbaskette/Projects/Worldmind/docker/centurion-forge/
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
| 2.1 | StarblasterProvider + StarblasterRequest | `feat: StarblasterProvider interface and StarblasterRequest record` |
| 2.2 | StarblasterProperties | `feat: StarblasterProperties with Goose provider and container config` |
| 2.3 | InstructionBuilder | `feat: InstructionBuilder converts directives to Goose instructions` |
| 2.4 | DockerStarblasterProvider | `feat: DockerStarblasterProvider for container lifecycle management` |
| 2.5 | StarblasterManager | `feat: StarblasterManager with file change detection` |
| 2.6 | StarblasterBridge | `feat: StarblasterBridge translates directives to starblaster executions` |
| 2.7 | StarblasterConfig | `feat: StarblasterConfig wires Docker provider beans` |
| 2.8 | DispatchCenturionNode | `feat: DispatchCenturionNode dispatches directives to Starblasters` |
| 2.9 | Graph wiring | `feat: wire dispatch_centurion node into LangGraph4j graph` |
| 2.10 | CLI output | `feat: CLI output for Starblaster activity and file changes` |
| 2.11 | Docker image | `feat: Centurion Forge Docker image with Goose CLI` |
| 2.12 | Integration test | `test: end-to-end Centurion Forge integration test` |
| 2.13 | Final verification | `chore: Phase 2 final cleanup and test fixes` |
