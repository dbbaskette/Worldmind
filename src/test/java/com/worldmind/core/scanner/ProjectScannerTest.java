package com.worldmind.core.scanner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProjectScanner}.
 * <p>
 * Uses JUnit 5's {@code @TempDir} for isolated filesystem tests so that
 * results are deterministic and independent of the host machine.
 */
class ProjectScannerTest {

    @TempDir
    Path tempDir;

    ProjectScanner scanner = new ProjectScanner();

    // ── Empty project ────────────────────────────────────────────────

    @Test
    @DisplayName("scans empty directory and returns zero files")
    void scansEmptyDirectory() throws IOException {
        var context = scanner.scan(tempDir);
        assertEquals(0, context.fileCount());
        assertEquals("unknown", context.language());
        assertEquals("unknown", context.framework());
        assertTrue(context.fileTree().isEmpty());
    }

    // ── Java / Maven detection ───────────────────────────────────────

    @Test
    @DisplayName("detects Java/Maven project from pom.xml")
    void detectsJavaProject() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(tempDir.resolve("src/main/java/App.java"), "class App {}");

        var context = scanner.scan(tempDir);
        assertEquals("java", context.language());
        assertEquals("maven", context.framework());
        assertTrue(context.fileTree().contains("pom.xml"));
        assertTrue(context.fileTree().stream().anyMatch(f -> f.endsWith("App.java")));
    }

    // ── .git directory filtering ─────────────────────────────────────

    @Test
    @DisplayName("ignores .git directory contents")
    void ignoresGitDirectory() throws IOException {
        Files.createDirectories(tempDir.resolve(".git/objects"));
        Files.writeString(tempDir.resolve(".git/config"), "");
        Files.writeString(tempDir.resolve("README.md"), "# Hello");

        var context = scanner.scan(tempDir);
        assertTrue(context.fileTree().stream().noneMatch(f -> f.contains(".git")),
                "File tree should not contain any .git entries");
        assertTrue(context.fileTree().contains("README.md"));
    }

    // ── node_modules filtering ───────────────────────────────────────

    @Test
    @DisplayName("ignores node_modules directory contents")
    void ignoresNodeModules() throws IOException {
        Files.createDirectories(tempDir.resolve("node_modules/express"));
        Files.writeString(tempDir.resolve("node_modules/express/index.js"), "");
        Files.writeString(tempDir.resolve("index.js"), "");

        var context = scanner.scan(tempDir);
        assertTrue(context.fileTree().stream().noneMatch(f -> f.contains("node_modules")),
                "File tree should not contain node_modules entries");
        assertTrue(context.fileTree().contains("index.js"));
    }

    // ── Python detection ─────────────────────────────────────────────

    @Test
    @DisplayName("detects Python project from requirements.txt")
    void detectsPythonProject() throws IOException {
        Files.writeString(tempDir.resolve("requirements.txt"), "flask");

        var context = scanner.scan(tempDir);
        assertEquals("python", context.language());
        assertEquals("pip", context.framework());
    }

    @Test
    @DisplayName("detects Python project from pyproject.toml")
    void detectsPythonProjectFromPyproject() throws IOException {
        Files.writeString(tempDir.resolve("pyproject.toml"), "[project]");

        var context = scanner.scan(tempDir);
        assertEquals("python", context.language());
        assertEquals("pip", context.framework());
    }

    // ── JavaScript / Node detection ──────────────────────────────────

    @Test
    @DisplayName("detects JavaScript/Node project from package.json")
    void detectsNodeProject() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), "{}");

        var context = scanner.scan(tempDir);
        assertEquals("javascript", context.language());
        assertEquals("node", context.framework());
    }

    // ── Go detection ─────────────────────────────────────────────────

    @Test
    @DisplayName("detects Go project from go.mod")
    void detectsGoProject() throws IOException {
        Files.writeString(tempDir.resolve("go.mod"), "module example.com/foo");

        var context = scanner.scan(tempDir);
        assertEquals("go", context.language());
        assertEquals("go-modules", context.framework());
    }

    // ── Rust detection ───────────────────────────────────────────────

    @Test
    @DisplayName("detects Rust project from Cargo.toml")
    void detectsRustProject() throws IOException {
        Files.writeString(tempDir.resolve("Cargo.toml"), "[package]");

        var context = scanner.scan(tempDir);
        assertEquals("rust", context.language());
        assertEquals("cargo", context.framework());
    }

    // ── .DS_Store filtering ──────────────────────────────────────────

    @Test
    @DisplayName("ignores .DS_Store files")
    void ignoresDSStore() throws IOException {
        Files.writeString(tempDir.resolve(".DS_Store"), "");
        Files.writeString(tempDir.resolve("main.py"), "");

        var context = scanner.scan(tempDir);
        assertTrue(context.fileTree().stream().noneMatch(f -> f.contains(".DS_Store")),
                "File tree should not contain .DS_Store");
        assertTrue(context.fileTree().contains("main.py"));
    }

    // ── target directory filtering ───────────────────────────────────

    @Test
    @DisplayName("ignores target directory contents")
    void ignoresTargetDirectory() throws IOException {
        Files.createDirectories(tempDir.resolve("target/classes"));
        Files.writeString(tempDir.resolve("target/classes/App.class"), "");
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        var context = scanner.scan(tempDir);
        assertTrue(context.fileTree().stream().noneMatch(f -> f.contains("target")),
                "File tree should not contain target entries");
    }

    // ── Summary format ───────────────────────────────────────────────

    @Test
    @DisplayName("summary includes language and file count")
    void summaryIncludesLanguageAndCount() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("App.java"), "class App {}");

        var context = scanner.scan(tempDir);
        assertTrue(context.summary().contains("java"),
                "Summary should contain the detected language");
        assertTrue(context.summary().contains("2"),
                "Summary should contain the file count");
    }

    // ── File count consistency ───────────────────────────────────────

    @Test
    @DisplayName("fileCount matches fileTree size")
    void fileCountMatchesFileTree() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "a");
        Files.writeString(tempDir.resolve("b.txt"), "b");
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/c.txt"), "c");

        var context = scanner.scan(tempDir);
        assertEquals(context.fileTree().size(), context.fileCount());
        // directories + files minus root itself:
        //   sub (dir), a.txt, b.txt, sub/c.txt = 4
        assertEquals(4, context.fileCount());
    }
}
