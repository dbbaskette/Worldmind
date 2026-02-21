package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.ClarifyingQuestions;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.state.WorldmindState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GenerateClarifyingQuestionsNode}.
 */
class GenerateClarifyingQuestionsNodeTest {

    private static final Classification DEFAULT_CLASSIFICATION =
            new Classification("feature", 3, List.of("api"), "sequential", "java");
    private static final ProjectContext DEFAULT_PROJECT_CONTEXT =
            new ProjectContext(".", List.of(), "java", "spring-boot", Map.of(), 10, "test");

    @Test
    @DisplayName("injects CF service binding question when createCfDeployment is true")
    void injectsCfServiceBindingQuestion() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(
                List.of(new ClarifyingQuestions.Question(
                        "q1", "What scope?", "scope", "To clarify scope",
                        List.of("Option A", "Option B"), true, null)),
                "Building an app"
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a simple web app",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");

        assertEquals(2, questions.questions().size());
        assertEquals("q1", questions.questions().get(0).id());
        assertEquals("cf_service_bindings", questions.questions().get(1).id());
    }

    @Test
    @DisplayName("does not inject CF question when createCfDeployment is false")
    void doesNotInjectCfQuestionWhenDisabled() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(
                List.of(new ClarifyingQuestions.Question(
                        "q1", "What scope?", "scope", "To clarify scope",
                        List.of("Option A"), true, null)),
                "Building an app"
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a todo app with PostgreSQL",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", false
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");

        assertEquals(1, questions.questions().size());
        assertEquals("q1", questions.questions().get(0).id());
    }

    @Test
    @DisplayName("auto-detects PostgreSQL from request text and includes in question")
    void autoDetectsPostgresqlFromRequest() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(List.of(), "Building a todo app");
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a todo app with PostgreSQL database",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");

        assertEquals(1, questions.questions().size());
        var cfQuestion = questions.questions().get(0);
        assertEquals("cf_service_bindings", cfQuestion.id());
        assertTrue(cfQuestion.question().contains("PostgreSQL Database"));
        assertTrue(cfQuestion.question().contains("detected from:"));
        assertTrue(cfQuestion.suggestedOptions().stream()
                .anyMatch(opt -> opt.startsWith("postgresql:")));
        assertTrue(cfQuestion.defaultAnswer().contains("postgresql: <instance-name>"));
    }

    @Test
    @DisplayName("auto-detects Redis from request text")
    void autoDetectsRedisFromRequest() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(List.of(), "Building a web service");
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a REST API with Redis caching",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");
        var cfQuestion = questions.questions().get(0);

        assertTrue(cfQuestion.question().contains("Redis Cache"));
        assertTrue(cfQuestion.suggestedOptions().stream()
                .anyMatch(opt -> opt.startsWith("redis:")));
    }

    @Test
    @DisplayName("auto-detects multiple services from request")
    void autoDetectsMultipleServices() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(List.of(), "Building a complex app");
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a Spring Boot app with PostgreSQL and Redis cache",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");
        var cfQuestion = questions.questions().get(0);

        assertTrue(cfQuestion.question().contains("PostgreSQL Database"));
        assertTrue(cfQuestion.question().contains("Redis Cache"));
        // Should have service-type suggestions plus "No services needed" and "Other"
        assertTrue(cfQuestion.suggestedOptions().size() >= 4);
    }

    @Test
    @DisplayName("auto-detects services from PRD document")
    void autoDetectsServicesFromPrd() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(List.of(), "Building from PRD");
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build the app described in the PRD",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true,
                "prdDocument", """
                        # Todo App PRD
                        ## Technical Requirements
                        - Use PostgreSQL for persistent storage
                        - Use RabbitMQ for async event processing
                        """
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");
        var cfQuestion = questions.questions().get(0);

        assertTrue(cfQuestion.question().contains("PostgreSQL Database"));
        assertTrue(cfQuestion.question().contains("RabbitMQ Message Queue"));
    }

    @Test
    @DisplayName("falls back to generic question when no services detected")
    void fallsBackToGenericQuestionWhenNoServicesDetected() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(List.of(), "Building a simple app");
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a calculator app with HTML and JavaScript",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");
        var cfQuestion = questions.questions().get(0);

        assertEquals("cf_service_bindings", cfQuestion.id());
        assertTrue(cfQuestion.question().contains("Does your application need to bind"));
        assertEquals("No services needed", cfQuestion.defaultAnswer());
        assertTrue(cfQuestion.suggestedOptions().contains("No services needed"));
    }

    @Test
    @DisplayName("detected question includes format instructions")
    void detectedQuestionIncludesFormatInstructions() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(List.of(), "Building an app");
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a todo app with PostgreSQL",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");
        var cfQuestion = questions.questions().get(0);

        assertTrue(cfQuestion.question().contains("Format: service-type: instance-name"));
        assertTrue(cfQuestion.question().contains("Example: postgresql: my-todo-db"));
    }

    @Test
    @DisplayName("skips to spec generation when answers already provided")
    void skipsWhenAnswersAlreadyProvided() {
        var mockLlm = mock(LlmService.class);
        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a todo app",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "clarifyingAnswers", "{\"q1\": \"answer1\"}"
        ));

        var result = node.apply(state);
        assertEquals(MissionStatus.SPECIFYING.name(), result.get("status"));
        verifyNoInteractions(mockLlm);
    }

    @Test
    @DisplayName("returns CLARIFYING status when questions already generated")
    void returnsClarifyingWhenQuestionsExist() {
        var mockLlm = mock(LlmService.class);
        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var existingQuestions = new ClarifyingQuestions(
                List.of(new ClarifyingQuestions.Question(
                        "q1", "What?", "scope", "why", List.of(), true, null)),
                "summary"
        );
        var state = new WorldmindState(Map.of(
                "request", "Build a todo app",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "clarifyingQuestions", existingQuestions
        ));

        var result = node.apply(state);
        assertEquals(MissionStatus.CLARIFYING.name(), result.get("status"));
        verifyNoInteractions(mockLlm);
    }

    @Test
    @DisplayName("generic question also includes format instructions")
    void genericQuestionIncludesFormatInstructions() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(List.of(), "Simple app");
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a basic web page",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");
        var cfQuestion = questions.questions().get(0);

        assertTrue(cfQuestion.question().contains("Format: service-type: instance-name"));
        assertTrue(cfQuestion.question().contains("Example: postgresql: my-todo-db"));
    }

    @Test
    @DisplayName("CF question is appended after LLM-generated questions")
    void cfQuestionAppendedAfterLlmQuestions() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(
                List.of(
                        new ClarifyingQuestions.Question("q1", "Scope?", "scope", "why", List.of(), true, null),
                        new ClarifyingQuestions.Question("q2", "Tech?", "technical", "why", List.of(), false, null)
                ),
                "Building with database"
        );
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a todo app with PostgreSQL",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");

        assertEquals(3, questions.questions().size());
        assertEquals("q1", questions.questions().get(0).id());
        assertEquals("q2", questions.questions().get(1).id());
        assertEquals("cf_service_bindings", questions.questions().get(2).id());
    }

    @Test
    @DisplayName("handles null questions list from LLM when CF deployment enabled")
    void handlesNullQuestionsFromLlm() {
        var mockLlm = mock(LlmService.class);
        var llmQuestions = new ClarifyingQuestions(null, "Building an app");
        when(mockLlm.structuredCall(anyString(), anyString(), eq(ClarifyingQuestions.class)))
                .thenReturn(llmQuestions);

        var node = new GenerateClarifyingQuestionsNode(mockLlm);
        var state = new WorldmindState(Map.of(
                "request", "Build a todo app with PostgreSQL",
                "classification", DEFAULT_CLASSIFICATION,
                "projectContext", DEFAULT_PROJECT_CONTEXT,
                "createCfDeployment", true
        ));

        var result = node.apply(state);
        var questions = (ClarifyingQuestions) result.get("clarifyingQuestions");

        assertNotNull(questions.questions());
        assertEquals(1, questions.questions().size());
        assertEquals("cf_service_bindings", questions.questions().get(0).id());
        assertTrue(questions.questions().get(0).defaultAnswer().contains("postgresql: <instance-name>"));
    }
}
