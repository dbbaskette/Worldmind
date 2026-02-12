package com.worldmind.starblaster;

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
 * Orchestrates Centurion execution inside Starblaster containers.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Assembles environment variables from {@link StarblasterProperties}</li>
 *   <li>Delegates container lifecycle to {@link StarblasterProvider}</li>
 *   <li>Detects file changes via before/after directory snapshots</li>
 *   <li>Returns an {@link ExecutionResult} with exit code, output, and file changes</li>
 * </ul>
 */
@Service
public class StarblasterManager {

    private static final Logger log = LoggerFactory.getLogger(StarblasterManager.class);

    private final StarblasterProvider provider;
    private final StarblasterProperties properties;
    private final McpProperties mcpProperties;

    public StarblasterManager(StarblasterProvider provider, StarblasterProperties properties,
                              @Autowired(required = false) McpProperties mcpProperties) {
        this.provider = provider;
        this.properties = properties;
        this.mcpProperties = mcpProperties;
    }

    /**
     * Result of executing a directive inside a Starblaster container.
     *
     * @param exitCode    container exit code (0 = success)
     * @param output      captured stdout/stderr from the container
     * @param starblasterId  the container ID that ran this directive
     * @param fileChanges files created or modified during execution
     * @param elapsedMs   wall-clock time in milliseconds
     */
    public record ExecutionResult(
        int exitCode,
        String output,
        String starblasterId,
        List<FileRecord> fileChanges,
        long elapsedMs
    ) {}

    /**
     * Executes a directive inside a Starblaster container.
     *
     * <p>Flow: snapshot files -> open starblaster -> wait -> capture output ->
     * detect changes -> teardown -> return result.
     *
     * @param centurionType  e.g. "forge", "vigil", "gauntlet"
     * @param directiveId    unique directive identifier
     * @param projectPath    host path to the project directory
     * @param instructionText the instruction markdown for Goose
     * @param extraEnv       additional environment variables
     * @return execution result with exit code, output, file changes, and timing
     */
    public ExecutionResult executeDirective(
            String centurionType,
            String directiveId,
            Path projectPath,
            String instructionText,
            Map<String, String> extraEnv,
            String gitRemoteUrl,
            String runtimeTag) {

        // When running inside Docker, use the shared workspace volume path
        // instead of the host project path for directive files and snapshots
        String workspaceVolume = System.getenv("WORKSPACE_VOLUME");
        Path effectivePath = workspaceVolume != null ? Path.of("/workspace") : projectPath;

        var envVars = new HashMap<>(extraEnv);
        envVars.put("GOOSE_PROVIDER", properties.getGooseProvider());
        envVars.put("GOOSE_MODEL", properties.getGooseModel());

        // Forward provider API keys from the environment.
        // When GOOSE_PROVIDER__API_KEY is set, entrypoint.sh skips VCAP_SERVICES
        // entirely — direct keys take precedence over CF service bindings.
        // When no direct key is provided, VCAP_SERVICES (CredHub) is used.
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
        // Check provider-specific key first, then fall back to GOOSE_PROVIDER__API_KEY from env
        // (for providers not in the map above).
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

        // Inject MCP server configs for centurion's Goose MCP extensions
        if (mcpProperties != null && mcpProperties.isConfigured()) {
            var serverNames = new ArrayList<String>();
            for (var entry : mcpProperties.getServers().entrySet()) {
                String name = entry.getKey();
                var config = entry.getValue();
                if (config.getUrl() == null || config.getUrl().isBlank()) continue;
                String envName = name.toUpperCase().replace("-", "_");
                serverNames.add(envName);
                envVars.put("MCP_SERVER_" + envName + "_URL", config.getUrl());
                String token = config.getTokenFor(centurionType);
                if (token != null && !token.isBlank()) {
                    envVars.put("MCP_SERVER_" + envName + "_TOKEN", token);
                }
            }
            if (!serverNames.isEmpty()) {
                envVars.put("MCP_SERVERS", String.join(",", serverNames));
            }
        }

        var request = new StarblasterRequest(
            centurionType, directiveId, projectPath,
            instructionText, envVars,
            properties.getMemoryLimitMb(), properties.getCpuCount(),
            gitRemoteUrl,
            runtimeTag != null ? runtimeTag : "base"
        );

        // Write instruction file for Goose CLI (expects a markdown file path, not inline text).
        // When in Docker, write to the volume root (/workspace/directives/) so centurion
        // containers can read them at /instructions/directives/ via a separate volume mount.
        // When running locally, write under the project directory.
        Path directiveDir = workspaceVolume != null
                ? Path.of("/workspace/directives")
                : effectivePath.resolve(".worldmind/directives");
        Path instructionFile = directiveDir.resolve(directiveId + ".md");
        try {
            Files.createDirectories(directiveDir);
            Files.writeString(instructionFile, instructionText);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write instruction file for " + directiveId, e);
        }

        Map<String, Long> beforeSnapshot = snapshotFiles(effectivePath);

        long startMs = System.currentTimeMillis();
        String starblasterId = provider.openStarblaster(request);

        try {
            int exitCode = provider.waitForCompletion(starblasterId, properties.getTimeoutSeconds());
            String output = provider.captureOutput(starblasterId);
            long elapsedMs = System.currentTimeMillis() - startMs;

            // Use provider-based change detection if available (e.g. git diff on CF),
            // otherwise fall back to filesystem snapshot comparison.
            List<FileRecord> providerChanges = provider.detectChanges(request.directiveId(), projectPath);
            List<FileRecord> changes = providerChanges != null
                    ? providerChanges
                    : detectChanges(beforeSnapshot, effectivePath);

            log.info("Starblaster {} completed with exit code {} in {}ms — {} file changes",
                    starblasterId, exitCode, elapsedMs, changes.size());

            return new ExecutionResult(exitCode, output, starblasterId, changes, elapsedMs);
        } finally {
            provider.teardownStarblaster(starblasterId);
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
