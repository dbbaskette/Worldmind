package com.worldmind.core.llm;

import com.worldmind.core.model.Classification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LlmService}.
 * <p>
 * Mocks the entire {@link ChatClient} chain so no real LLM calls are made.
 */
class LlmServiceTest {

    private ChatClient mockChatClient;
    private ChatClientRequestSpec mockRequestSpec;
    private CallResponseSpec mockCallResponse;
    private LlmService llmService;

    @BeforeEach
    void setUp() {
        mockChatClient = mock(ChatClient.class);
        mockRequestSpec = mock(ChatClientRequestSpec.class);
        mockCallResponse = mock(CallResponseSpec.class);

        // Wire up the fluent API chain
        when(mockChatClient.prompt()).thenReturn(mockRequestSpec);
        when(mockRequestSpec.system(anyString())).thenReturn(mockRequestSpec);
        when(mockRequestSpec.user(anyString())).thenReturn(mockRequestSpec);
        when(mockRequestSpec.call()).thenReturn(mockCallResponse);

        // Build LlmService with a mocked Builder
        ChatClient.Builder mockBuilder = mock(ChatClient.Builder.class);
        when(mockBuilder.build()).thenReturn(mockChatClient);

        llmService = new LlmService(mockBuilder, "http://test:1234");
    }

    @Test
    @DisplayName("structuredCall sends system and user prompts to ChatClient")
    void structuredCallSendsPrompts() {
        String jsonResponse = """
                {"category":"feature","complexity":3,"affectedComponents":["api","service"],"planningStrategy":"parallel"}
                """;
        when(mockCallResponse.content()).thenReturn(jsonResponse);

        llmService.structuredCall("System prompt", "User prompt", Classification.class);

        verify(mockRequestSpec).system("System prompt");
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockRequestSpec).user(userCaptor.capture());
        assertTrue(userCaptor.getValue().startsWith("User prompt"));
    }

    @Test
    @DisplayName("structuredCall appends BeanOutputConverter format instructions to user prompt")
    void structuredCallAppendsFormatInstructions() {
        String jsonResponse = """
                {"category":"feature","complexity":3,"affectedComponents":["api"],"planningStrategy":"sequential"}
                """;
        when(mockCallResponse.content()).thenReturn(jsonResponse);

        llmService.structuredCall("System prompt", "User prompt", Classification.class);

        // Verify the user prompt contains both the original text and format instructions
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockRequestSpec).user(userCaptor.capture());
        String capturedUser = userCaptor.getValue();
        assertTrue(capturedUser.startsWith("User prompt\n\n"), "Should start with user prompt followed by separator");
        assertTrue(capturedUser.length() > "User prompt\n\n".length(), "Should contain format instructions after separator");
    }

    @Test
    @DisplayName("structuredCall deserializes LLM JSON response into target type")
    void structuredCallDeserializesResponse() {
        String jsonResponse = """
                {"category":"bugfix","complexity":2,"affectedComponents":["service","model"],"planningStrategy":"sequential"}
                """;
        when(mockCallResponse.content()).thenReturn(jsonResponse);

        Classification result = llmService.structuredCall(
                "Classify this", "Fix the login bug", Classification.class
        );

        assertNotNull(result);
        assertEquals("bugfix", result.category());
        assertEquals(2, result.complexity());
        assertEquals(List.of("service", "model"), result.affectedComponents());
        assertEquals("sequential", result.planningStrategy());
    }

    @Test
    @DisplayName("structuredCall works with different output types")
    void structuredCallWorksDifferentTypes() {
        // Verify it works with the Classification record type
        String jsonResponse = """
                {"category":"research","complexity":5,"affectedComponents":["api","model","service","ui","config"],"planningStrategy":"parallel"}
                """;
        when(mockCallResponse.content()).thenReturn(jsonResponse);

        Classification result = llmService.structuredCall(
                "Classify", "Investigate system-wide performance issues", Classification.class
        );

        assertEquals("research", result.category());
        assertEquals(5, result.complexity());
        assertEquals(5, result.affectedComponents().size());
        assertEquals("parallel", result.planningStrategy());
    }

    @Test
    @DisplayName("structuredCall invokes the full ChatClient fluent chain")
    void structuredCallInvokesFullChain() {
        String jsonResponse = """
                {"category":"test","complexity":1,"affectedComponents":["test"],"planningStrategy":"sequential"}
                """;
        when(mockCallResponse.content()).thenReturn(jsonResponse);

        llmService.structuredCall("sys", "usr", Classification.class);

        // Verify the full chain: prompt() -> system() -> user() -> call() -> content()
        verify(mockChatClient).prompt();
        verify(mockRequestSpec).system("sys");
        verify(mockRequestSpec).user(anyString());
        verify(mockRequestSpec).call();
        verify(mockCallResponse).content();
    }
}
