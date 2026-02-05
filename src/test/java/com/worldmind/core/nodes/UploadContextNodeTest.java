package com.worldmind.core.nodes;

import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.scanner.ProjectScanner;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UploadContextNode}.
 * <p>
 * Uses Mockito to mock {@link ProjectScanner} so that no real filesystem
 * walk is performed during testing.
 */
class UploadContextNodeTest {

    @Test
    @DisplayName("scans project and returns context with PLANNING status")
    void scansProjectAndUpdatesStatus() throws IOException {
        var mockScanner = mock(ProjectScanner.class);
        var expectedContext = new ProjectContext(
                "/test", List.of("pom.xml", "src/App.java"), "java", "maven",
                Map.of(), 2, "java project with 2 files"
        );
        when(mockScanner.scan(any(Path.class))).thenReturn(expectedContext);

        var node = new UploadContextNode(mockScanner);
        var state = new WorldmindState(Map.of("request", "test"));
        var result = node.apply(state);

        assertEquals(expectedContext, result.get("projectContext"));
        assertEquals("PLANNING", result.get("status"));
    }

    @Test
    @DisplayName("result contains exactly projectContext and status on success")
    void resultContainsExpectedKeys() throws IOException {
        var mockScanner = mock(ProjectScanner.class);
        var context = new ProjectContext(
                "/test", List.of(), "unknown", "unknown",
                Map.of(), 0, "unknown project with 0 files"
        );
        when(mockScanner.scan(any(Path.class))).thenReturn(context);

        var node = new UploadContextNode(mockScanner);
        var state = new WorldmindState(Map.of("request", "test"));
        var result = node.apply(state);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("projectContext"));
        assertTrue(result.containsKey("status"));
    }

    @Test
    @DisplayName("handles IOException gracefully with error info")
    void handlesIOException() throws IOException {
        var mockScanner = mock(ProjectScanner.class);
        when(mockScanner.scan(any(Path.class))).thenThrow(new IOException("permission denied"));

        var node = new UploadContextNode(mockScanner);
        var state = new WorldmindState(Map.of("request", "test"));
        var result = node.apply(state);

        assertEquals("PLANNING", result.get("status"));

        // Should still contain a ProjectContext (fallback)
        assertNotNull(result.get("projectContext"));
        var ctx = (ProjectContext) result.get("projectContext");
        assertEquals("unknown", ctx.language());
        assertEquals(0, ctx.fileCount());
        assertTrue(ctx.summary().contains("permission denied"));

        // Should contain error list
        @SuppressWarnings("unchecked")
        var errors = (List<String>) result.get("errors");
        assertNotNull(errors);
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("permission denied"));
    }
}
