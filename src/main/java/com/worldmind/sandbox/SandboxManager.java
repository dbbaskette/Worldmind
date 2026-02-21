package com.worldmind.sandbox;

import com.worldmind.core.model.FileRecord;
import com.worldmind.mcp.McpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Orchestrates Agent execution inside Sandbox containers.
 *
 * <p>This class is intentionally agent-type-agnostic: it does not maintain a
 * whitelist of recognized agent types. The {@code agentType} parameter is passed
 * through to the {@link SandboxProvider}, which handles all type-specific behavior
 * (e.g. branch strategy, credential injection, CF app mapping). New agent types
 * (coder, tester, reviewer, deployer, researcher, etc.) can be added without
 * modifying this class.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Assembles environment variables from {@link SandboxProperties}</li>
 *   <li>Delegates container lifecycle to {@link SandboxProvider}</li>
 *   <li>Detects file changes via before/after directory snapshots</li>
 *   <li>Returns an {@link ExecutionResult} with exit code, output, and file changes</li>
 * </ul>
 */
@Service
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxProvider provider;
    private final SandboxProperties properties;
    private final McpProperties mcpProperties;

    public SandboxManager(SandboxProvider provider, SandboxProperties properties,
                              @Autowired(required = false) McpProperties mcpProperties) {
        this.provider = provider;
        this.properties = properties;
        this.mcpProperties = mcpProperties;
    }

    /**
     * Result of executing a task inside a Sandbox container.
     *
     * @param exitCode    container exit code (0 = success)
     * @param output      captured stdout/stderr from the container
     * @param sandboxId  the container ID that ran this task
     * @param fileChanges files created or modified during execution
     * @param elapsedMs   wall-clock time in milliseconds
     */
    public record ExecutionResult(
        int exitCode,
        String output,
        String sandboxId,
        List<FileRecord> fileChanges,
        long elapsedMs
    ) {}

    /**
     * Executes a task inside a Sandbox container.
     *
     * <p>Flow: snapshot files -> open sandbox -> wait -> capture output ->
     * detect changes -> teardown -> return result.
     *
     * @param agentType  e.g. "coder", "reviewer", "tester"
     * @param taskId    unique task identifier
     * @param projectPath    host path to the project directory
     * @param instructionText the instruction markdown for Goose
     * @param extraEnv       additional environment variables
     * @param iteration      current iteration count (0 = first attempt, 1+ = retry)
     * @return execution result with exit code, output, file changes, and timing
     */
    public ExecutionResult executeTask(
            String agentType,
            String taskId,
            Path projectPath,
            String instructionText,
            Map<String, String> extraEnv,
            String gitRemoteUrl,
            String runtimeTag,
            int iteration) {

        // When running inside Docker, use the shared workspace volume path
        // instead of the host project path for task files and snapshots
        String workspaceVolume = System.getenv("WORKSPACE_VOLUME");
        Path effectivePath = workspaceVolume != null ? Path.of("/workspace") : projectPath;

        var envVars = new HashMap<>(extraEnv);

        // Only set GOOSE_PROVIDER/GOOSE_MODEL/API keys when explicitly configured.
        // When not configured, agent's entrypoint.sh resolves provider, model,
        // and API key from VCAP_SERVICES (CF service bindings / CredHub).
        if (properties.isGooseProviderConfigured()) {
            envVars.put("GOOSE_PROVIDER", properties.getGooseProvider());
            envVars.put("GOOSE_MODEL", properties.getGooseModel());

            // Forward provider API keys from the environment.
            // When GOOSE_PROVIDER__API_KEY is set, entrypoint.sh skips VCAP_SERVICES
            // entirely — direct keys take precedence over CF service bindings.
            var providerKeyMap = Map.of(
                    "anthropic", "ANTHROPIC_API_KEY",
                    "openai", "OPENAI_API_KEY",
                    "google", "GOOGLE_API_KEY"
            );
            String activeKeyName = providerKeyMap.get(properties.getGooseProvider());
            for (var entry : providerKeyMap.values()) {
                String val = System.getenv(entry);
                if (val != null && !val.isBlank()) {
                    envVars.put(entry, val);
                }
            }
            // Set the generic Goose key for the active provider — signals entrypoint.sh to skip VCAP.
            String directKey = activeKeyName != null ? System.getenv(activeKeyName) : null;
            if (directKey == null || directKey.isBlank()) {
                directKey = System.getenv("GOOSE_PROVIDER__API_KEY");
            }
            if (directKey != null && !directKey.isBlank()) {
                envVars.put("GOOSE_PROVIDER__API_KEY", directKey);
            }
            // For openai-compatible local endpoints (LM Studio, etc.), pass the host URL
            if ("openai".equals(properties.getGooseProvider())) {
                String lmUrl = properties.getLmStudioUrl();
                if (lmUrl != null && !lmUrl.isBlank()) {
                    if (!lmUrl.endsWith("/")) lmUrl += "/";
                    envVars.put("OPENAI_HOST", lmUrl);
                }
            }
            log.info("Goose provider explicitly configured: provider={}, model={}",
                    properties.getGooseProvider(), properties.getGooseModel());
        } else {
            // Forward GENAI_SERVICE_NAME so entrypoint.sh picks the right VCAP binding
            // when multiple genai services are bound (e.g. worldmind-model vs worldmind-model-2).
            String serviceName = properties.getGooseServiceName();
            if (serviceName != null && !serviceName.isBlank()) {
                envVars.put("GENAI_SERVICE_NAME", serviceName);
                log.info("No Goose provider configured — agent will resolve from VCAP_SERVICES (service: {})", serviceName);
            } else {
                log.info("No Goose provider configured — agent will resolve from VCAP_SERVICES");
            }
        }

        // Inject MCP server configs for agent's Goose MCP extensions
        if (mcpProperties != null && mcpProperties.isConfigured()) {
            var serverNames = new ArrayList<String>();
            for (var entry : mcpProperties.getServers().entrySet()) {
                String name = entry.getKey();
                var config = entry.getValue();
                if (config.getUrl() == null || config.getUrl().isBlank()) continue;
                String envName = name.toUpperCase().replace("-", "_");
                serverNames.add(envName);
                envVars.put("MCP_SERVER_" + envName + "_URL", config.getUrl());
                String token = config.getTokenFor(agentType);
                if (token != null && !token.isBlank()) {
                    envVars.put("MCP_SERVER_" + envName + "_TOKEN", token);
                }
            }
            if (!serverNames.isEmpty()) {
                envVars.put("MCP_SERVERS", String.join(",", serverNames));
            }
        }

        // Append MCP tool instructions if MCP servers are configured for this agent
        if (mcpProperties != null && mcpProperties.isConfigured()) {
            var mcpServerNames = mcpProperties.getServers().entrySet().stream()
                    .filter(e -> e.getValue().getUrl() != null && !e.getValue().getUrl().isBlank())
                    .map(Map.Entry::getKey)
                    .toList();
            instructionText = InstructionBuilder.withMcpTools(instructionText, agentType, mcpServerNames);
        }

        var request = new AgentRequest(
            agentType, taskId, projectPath,
            instructionText, envVars,
            properties.getMemoryLimitMb(), properties.getCpuCount(),
            gitRemoteUrl,
            runtimeTag != null ? runtimeTag : "base",
            iteration
        );

        // Write instruction file for Goose CLI (expects a markdown file path, not inline text).
        // When in Docker, write to the volume root (/workspace/tasks/) so agent
        // containers can read them at /instructions/tasks/ via a separate volume mount.
        // When running locally, write under the project directory.
        Path taskDir = workspaceVolume != null
                ? Path.of("/workspace/tasks")
                : effectivePath.resolve(".worldmind/tasks");
        Path instructionFile = taskDir.resolve(taskId + ".md");
        try {
            Files.createDirectories(taskDir);
            Files.writeString(instructionFile, instructionText);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write instruction file for " + taskId, e);
        }

        // Snapshot project files before execution.
        // Provider-based snapshot (Docker helper container) takes priority over local snapshot
        // because in Docker-in-Docker mode, the local filesystem is the instruction volume,
        // not the host project directory where agents write code.
        Map<String, Long> providerSnapshot = provider.snapshotProjectFiles(projectPath);
        Map<String, Long> beforeSnapshot = providerSnapshot != null
                ? providerSnapshot
                : snapshotFiles(effectivePath);

        long startMs = System.currentTimeMillis();
        String sandboxId = provider.openSandbox(request);

        try {
            int exitCode = provider.waitForCompletion(sandboxId, properties.getTimeoutSeconds());
            String output = provider.captureOutput(sandboxId);
            long elapsedMs = System.currentTimeMillis() - startMs;

            // Detect file changes using the best available method:
            // 1. Provider-specific detection (CF git diff)
            // 2. Provider-based snapshot comparison (Docker helper containers)
            // 3. Local filesystem snapshot comparison (direct host access)
            List<FileRecord> providerChanges = provider.detectChanges(request.taskId(), projectPath);
            if (providerChanges == null && providerSnapshot != null) {
                providerChanges = provider.detectChangesBySnapshot(beforeSnapshot, projectPath);
            }
            List<FileRecord> changes = providerChanges != null
                    ? providerChanges
                    : detectChanges(beforeSnapshot, effectivePath);

            log.info("Sandbox {} completed with exit code {} in {}ms — {} file changes",
                    sandboxId, exitCode, elapsedMs, changes.size());

            return new ExecutionResult(exitCode, output, sandboxId, changes, elapsedMs);
        } finally {
            provider.teardownSandbox(sandboxId);
            try {
                Files.deleteIfExists(instructionFile);
            } catch (IOException e) {
                log.debug("Could not clean up instruction file: {}", instructionFile);
            }
        }
    }

    /**
     * Takes a snapshot of all regular files in a directory, mapping relative path
     * to last-modified timestamp. Excludes .git directories.
     *
     * @param directory the directory to snapshot
     * @return map of relative path -> last modified millis
     */
    static Map<String, Long> snapshotFiles(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains(".git"))
                .filter(p -> !p.toString().contains(".worldmind"))
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
     * Compares a before-snapshot with the current state of a directory
     * to detect created and modified files.
     *
     * @param before   snapshot taken before execution
     * @param directory the directory to compare against
     * @return list of file changes (created or modified)
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
