package com.worldmind.starblaster;

import com.worldmind.core.model.FileRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
            "Create file", Map.of(),
            ""
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
            "Bad instruction", Map.of(),
            ""
        );

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("error"));
    }

    @Test
    void detectFileChangesFindsNewFiles(@TempDir Path tempDir) throws IOException {
        var before = StarblasterManager.snapshotFiles(tempDir);
        Files.writeString(tempDir.resolve("hello.py"), "print('hello')");
        var changes = StarblasterManager.detectChanges(before, tempDir);

        assertEquals(1, changes.size());
        assertEquals("hello.py", changes.get(0).path());
        assertEquals("created", changes.get(0).action());
    }

    @Test
    void detectFileChangesFindsModifiedFiles(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("existing.py");
        Files.writeString(file, "old");
        // Set last-modified to the past so the re-write produces a different timestamp
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));
        var before = StarblasterManager.snapshotFiles(tempDir);
        Files.writeString(file, "new content");
        var changes = StarblasterManager.detectChanges(before, tempDir);

        assertEquals(1, changes.size());
        assertEquals("existing.py", changes.get(0).path());
        assertEquals("modified", changes.get(0).action());
    }
}
