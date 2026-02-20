package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.ClarifyingQuestions;
import com.worldmind.core.model.Classification;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.state.WorldmindState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates clarifying questions to gather more context before creating the PRD.
 * These questions help prevent ambiguous specs that lead to incorrect implementations.
 */
@Component
public class GenerateClarifyingQuestionsNode {

    private static final Logger log = LoggerFactory.getLogger(GenerateClarifyingQuestionsNode.class);

    private static final String SYSTEM_PROMPT = """
            You are a senior technical analyst preparing to write a detailed PRD (Product Requirements Document).
            Before writing the spec, you need to ask clarifying questions to ensure you fully understand
            what the user wants to build.
            
            Your job is to identify GAPS and AMBIGUITIES in the request that could lead to incorrect
            implementations. Autonomous coding agents will implement this — they cannot ask questions,
            so YOU must ask them now.
            
            ## Question Categories
            
            1. **SCOPE** - What's in/out of scope?
               - "Should this include X feature or is that separate?"
               - "Should this work on mobile/tablet or desktop only?"
            
            2. **TECHNICAL** - Implementation choices
               - "Should this use framework X or vanilla JS?"
               - "What data persistence is needed (local storage, database, none)?"
               - "What's the expected scale (users, data volume)?"
            
            3. **UX** - User experience requirements
               - "What should happen when the user does X?"
               - "Are there specific visual styles or themes required?"
               - "What feedback should users see on success/failure?"
            
            4. **INTEGRATION** - External dependencies
               - "Does this need to integrate with existing APIs?"
               - "Are there authentication requirements?"
            
            5. **CONSTRAINTS** - Limitations and requirements
               - "Are there performance requirements?"
               - "Browser/platform compatibility requirements?"
               - "Accessibility requirements?"
            
            ## Rules
            
            1. Ask 3-7 questions maximum — focus on the most impactful unknowns
            2. Don't ask about things that are obvious from the request
            3. Don't ask basic questions if the user is clearly technical
            4. Provide suggested options where applicable (makes it easier to answer)
            5. Include a brief summary showing you understood the core request
            6. Mark questions as required=true if the answer significantly affects implementation
            7. For simple/clear requests, you may return just 1-2 questions or even zero
            
            ## Example
            
            Request: "Build a todo app"
            
            Good questions:
            - "Should tasks persist between browser sessions?" (category: technical)
            - "Do you need due dates, priorities, or categories for tasks?" (category: scope)
            - "Should completed tasks be hidden, shown dimmed, or moved to a separate section?" (category: ux)
            
            Bad questions (too obvious or too detailed for this stage):
            - "What programming language?" (obvious from context)
            - "What color should the add button be?" (too detailed)
            
            Respond with valid JSON matching the schema provided.
            """;

    private final LlmService llmService;

    public GenerateClarifyingQuestionsNode(LlmService llmService) {
        this.llmService = llmService;
    }

    public Map<String, Object> apply(WorldmindState state) {
        // Skip if questions already answered
        if (state.clarifyingAnswers() != null && !state.clarifyingAnswers().isEmpty()) {
            log.info("Clarifying questions already answered, proceeding to spec generation");
            return Map.of("status", MissionStatus.SPECIFYING.name());
        }
        
        // Skip if questions already generated and waiting for answers
        if (state.clarifyingQuestions().isPresent()) {
            log.info("Clarifying questions already generated, waiting for answers");
            return Map.of("status", MissionStatus.CLARIFYING.name());
        }

        String request = state.request();
        Classification classification = state.classification().orElseThrow(
                () -> new IllegalStateException("Classification must be present before generating questions")
        );
        ProjectContext projectContext = state.projectContext().orElse(null);

        String userPrompt = buildUserPrompt(request, classification, projectContext);
        ClarifyingQuestions questions = llmService.structuredCall(SYSTEM_PROMPT, userPrompt, ClarifyingQuestions.class);
        
        // If CF deployment is requested, inject service binding question
        if (state.createCfDeployment()) {
            questions = injectCfServiceBindingQuestion(questions);
            log.info("Injected CF service binding question (createCfDeployment=true)");
        }
        
        log.info("Generated {} clarifying questions for request: {}", 
                questions.questions() != null ? questions.questions().size() : 0,
                request.substring(0, Math.min(50, request.length())));

        // If no questions generated (clear request), skip to spec generation
        if (questions.questions() == null || questions.questions().isEmpty()) {
            log.info("No clarifying questions needed, proceeding directly to spec generation");
            return Map.of(
                    "clarifyingQuestions", questions,
                    "status", MissionStatus.SPECIFYING.name()
            );
        }

        return Map.of(
                "clarifyingQuestions", questions,
                "status", MissionStatus.CLARIFYING.name()
        );
    }

    /**
     * Injects a CF service binding question when CF deployment is requested.
     * This allows users to specify what services (databases, message queues, etc.)
     * should be bound to the deployed application.
     */
    private ClarifyingQuestions injectCfServiceBindingQuestion(ClarifyingQuestions original) {
        var cfQuestion = new ClarifyingQuestions.Question(
                "cf_service_bindings",
                "Does your application need to bind to any Cloud Foundry services? If yes, please specify the service name(s) and type(s).",
                "integration",
                "Cloud Foundry apps often need databases, caches, or message queues. Specifying these now ensures the manifest.yml includes the correct service bindings.",
                List.of(
                        "No services needed",
                        "PostgreSQL database (specify instance name)",
                        "MySQL database (specify instance name)",
                        "Redis cache (specify instance name)",
                        "RabbitMQ (specify instance name)",
                        "Other (please describe)"
                ),
                false,
                "No services needed"
        );
        
        List<ClarifyingQuestions.Question> allQuestions = new ArrayList<>();
        if (original.questions() != null) {
            allQuestions.addAll(original.questions());
        }
        allQuestions.add(cfQuestion);
        
        return new ClarifyingQuestions(allQuestions, original.summary());
    }

    private String buildUserPrompt(String request, Classification classification, ProjectContext projectContext) {
        var sb = new StringBuilder();
        sb.append(String.format("""
                User Request: %s
                
                Classification:
                - Category: %s
                - Complexity: %d (1=trivial, 5=complex)
                - Affected Components: %s
                """,
                request,
                classification.category(),
                classification.complexity(),
                String.join(", ", classification.affectedComponents())
        ));

        if (projectContext != null) {
            sb.append(String.format("""
                    
                    Project Context:
                    - Language: %s
                    - Framework: %s
                    - File Count: %d
                    """,
                    projectContext.language(),
                    projectContext.framework(),
                    projectContext.fileCount()
            ));
            
            if (projectContext.fileTree() != null && !projectContext.fileTree().isEmpty()) {
                sb.append("- Existing files: ");
                sb.append(String.join(", ", projectContext.fileTree().stream().limit(20).toList()));
                if (projectContext.fileTree().size() > 20) {
                    sb.append(" ... and ").append(projectContext.fileTree().size() - 20).append(" more");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
