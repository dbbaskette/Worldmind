# Spring AI Research Report - 2026
## Comprehensive Analysis for Agentic Orchestration Systems

**Research Date:** February 5, 2026
**Focus:** Building multi-step agentic workflows with Spring AI

---

## Executive Summary

Spring AI 1.1 GA (released November 2025) provides a comprehensive framework for building enterprise-grade AI applications in Java. It supports 20+ AI models, includes sophisticated tool calling, structured outputs, streaming responses, and agent orchestration patterns. Key features include:

- **ChatClient API**: Fluent, portable interface for AI interactions
- **Tool Calling**: Declarative `@Tool` annotation for Java method exposure
- **Structured Output**: Automatic mapping to Java records/classes via `BeanOutputConverter`
- **Agent Orchestration**: Multiple patterns including Chain, Orchestrator-Workers, Routing, and Parallelization
- **MCP Support**: Full Model Context Protocol client and server implementations
- **State Management**: Advisors API with memory persistence (JDBC, Cassandra, Neo4j, In-Memory)
- **Streaming**: Native SSE support with reactive Flux responses
- **A2A Protocol**: Agent-to-Agent communication for multi-agent systems

---

## 1. Spring AI Agents/Workflows

### 1.1 Agent Orchestration Patterns

Spring AI implements **five fundamental orchestration patterns**:

#### 1. **Chain Workflow**
Breaking complex tasks into sequential, manageable steps where each step's output becomes the next step's input.

**Use Case:** Document processing pipeline (extract → analyze → summarize → format)

#### 2. **Orchestrator-Workers Pattern**
A manager LLM analyzes requests, decomposes them into subtasks, delegates to specialized worker LLMs, and synthesizes results.

**Use Case:** Travel itinerary planner (one LLM coordinates hotels, restaurants, activities workers)

**Implementation:**
```java
// 1. Orchestrator analyzes and creates task plan
List<Task> tasks = orchestratorLLM.generateTasks(userRequest);

// 2. Execute workers in parallel
List<CompletableFuture<String>> futures = tasks.stream()
    .map(task -> CompletableFuture.supplyAsync(() ->
        workerLLM.call(task)))
    .toList();

// 3. Synthesize results
String finalResult = synthesizerLLM.call(
    CompletableFuture.allOf(futures).join()
);
```

#### 3. **Routing Workflow**
Intelligent routing of inputs to specialized handlers based on content classification.

**Use Case:** Customer support routing (technical/billing/general inquiries)

#### 4. **Parallelization Workflow**
Concurrent execution of multiple LLM operations with programmatic aggregation.

**Use Case:** Batch processing, multi-perspective analysis

#### 5. **Evaluator-Optimizer Workflow**
Dual-LLM iterative refinement where one generates and another evaluates/improves.

**Use Case:** Content quality assurance, self-correcting systems

### 1.2 Agent Skills

**Agent Skills** are modular folders of instructions, scripts, and resources that agents discover and load on demand. Anthropic Claude models support generating files like Excel, PowerPoint, Word, and PDFs through Skills.

**Key Advantage:** Extends agent capabilities without hardcoding knowledge or creating specialized tools.

### 1.3 State Machines and Workflow Graphs

Spring AI doesn't have built-in state machines like LangGraph's `StateGraph`. Instead, it uses:

- **Advisors** for stateful conversational flows
- **CompletableFuture** for parallel execution graphs
- **Spring Statemachine** (separate project) for reactive state machines
- **LangGraph4j** integration for graph-based workflows

**LangGraph4j Integration:**
```java
// LangGraph4j works seamlessly with Spring AI
StateGraph<AgentState> graph = StateGraph.builder(AgentState.class)
    .addNode("agent", agentNode)
    .addNode("tools", toolNode)
    .addConditionalEdges("agent", routeFunction)
    .build();
```

---

## 2. Tool Calling / Function Calling

### 2.1 Declarative Tool Definition

Spring AI's `@Tool` annotation (introduced in recent milestones) replaces the deprecated `FunctionCallback` approach:

```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

class DateTimeTools {

    @Tool(description = "Get the current date and time in the user's timezone")
    String getCurrentDateTime() {
        return LocalDateTime.now()
            .atZone(LocaleContextHolder.getTimeZone().toZoneId())
            .toString();
    }

    @Tool(description = "Set a user alarm for the given time")
    void setAlarm(
        @ToolParam(description = "Time in ISO-8601 format") String time
    ) {
        LocalDateTime alarmTime = LocalDateTime.parse(time);
        System.out.println("Alarm set for " + alarmTime);
    }
}
```

### 2.2 Tool Registration Methods

#### Method 1: Inline Tools (Per Request)
```java
String response = ChatClient.create(chatModel)
    .prompt("What day is tomorrow?")
    .tools(new DateTimeTools())  // Per-request tools
    .call()
    .content();
```

#### Method 2: Default Tools (Shared)
```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultTools(new DateTimeTools())  // Available to all requests
    .build();
```

#### Method 3: Tool Names (Spring Beans)
```java
@Bean
@Description("Get the weather in location")
Function<WeatherRequest, WeatherResponse> currentWeather() {
    return new WeatherService();
}

// Usage
ChatClient.create(chatModel)
    .prompt("What's the weather in Copenhagen?")
    .toolNames("currentWeather")  // Resolved from Spring context
    .call()
    .content();
```

#### Method 4: Programmatic ToolCallbacks
```java
ToolCallback toolCallback = FunctionToolCallback
    .builder("currentWeather", new WeatherService())
    .description("Get the weather in location")
    .inputType(WeatherRequest.class)
    .build();

ChatClient.create(chatModel)
    .toolCallbacks(toolCallback)
    .call()
    .content();
```

### 2.3 Tool Context for Dependency Injection

```java
class CustomerTools {

    @Tool(description = "Retrieve customer information")
    Customer getCustomerInfo(Long id, ToolContext toolContext) {
        String tenantId = (String) toolContext.getContext().get("tenantId");
        return customerRepository.findById(id, tenantId);
    }
}

// Usage
ChatClient.create(chatModel)
    .prompt("Tell me about customer 42")
    .tools(new CustomerTools())
    .toolContext(Map.of("tenantId", "acme"))
    .call()
    .content();
```

### 2.4 Parallel Tool Execution

Spring AI supports **concurrent tool execution** when models request multiple tools:

```java
ChatOptions chatOptions = ToolCallingChatOptions.builder()
    .toolCallbacks(ToolCallbacks.from(tools))
    .parallelToolCalls(true)  // Enable parallel tool calls
    .toolExecutionMode(ToolExecutionMode.CONCURRENT)  // Execute concurrently
    .internalToolExecutionEnabled(true)  // Framework handles execution
    .build();
```

**Implementation:** Uses `CompletableFuture.supplyAsync()` with indexed responses to maintain order consistency.

**Error Handling:** "Collect-all" strategy where all tools execute and errors are aggregated (no fail-fast).

---

## 3. Structured Output

### 3.1 BeanOutputConverter

Automatically maps LLM responses to Java classes/records using JSON schema generation:

```java
public record ActorsFilms(String actor, List<String> movies) {}

BeanOutputConverter<ActorsFilms> converter =
    new BeanOutputConverter<>(ActorsFilms.class);

String response = chatClient.prompt()
    .user("Generate the filmography for Tom Hanks")
    .call()
    .entity(ActorsFilms.class);  // Automatic conversion
```

### 3.2 Available Converters

| Converter | Purpose |
|-----------|---------|
| `BeanOutputConverter` | Convert to Java class/record |
| `MapOutputConverter` | Convert to `Map<String, Object>` |
| `ListOutputConverter` | Convert to `List<T>` |
| `AbstractConversionServiceOutputConverter` | Custom conversion logic |

### 3.3 Property Ordering

Use `@JsonPropertyOrder` for deterministic schema generation:

```java
@JsonPropertyOrder({"name", "age", "email"})
public record Person(String name, int age, String email) {}
```

### 3.4 How It Works

1. **Before LLM Call:** Converter appends JSON schema to prompt
2. **LLM Response:** Model generates JSON matching schema
3. **After LLM Call:** Framework parses JSON to Java object

```java
// Fluent API with structured output
List<Article> articles = chatClient.prompt()
    .user("Find articles about Spring AI")
    .call()
    .entity(new ParameterizedTypeReference<List<Article>>() {});
```

---

## 4. Chat Models - Anthropic Claude Configuration

### 4.1 Maven Dependency

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

### 4.2 Gradle Dependency

```gradle
dependencies {
    implementation 'org.springframework.ai:spring-ai-starter-model-anthropic'
}
```

**Note:** Add Spring AI BOM to your build for version management.

### 4.3 Configuration Properties

```properties
# API Key
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}

# Model Selection
spring.ai.anthropic.chat.options.model=claude-sonnet-4-5
spring.ai.anthropic.chat.options.temperature=0.7
spring.ai.anthropic.chat.options.max-tokens=8192

# Connection
spring.ai.anthropic.base-url=api.anthropic.com
spring.ai.anthropic.version=2023-06-01
```

### 4.4 Available Models (2026)

| Model | Best For |
|-------|----------|
| `claude-opus-4-5` | Complex reasoning, coding |
| `claude-sonnet-4-5` | Balanced performance (default) |
| `claude-haiku-4-5` | Fast, cost-effective |
| `claude-3-7-sonnet-latest` | Extended thinking (explicit) |
| `claude-3-5-sonnet-latest` | Previous generation |

### 4.5 Extended Thinking Configuration

**Claude 3.7 Sonnet (Explicit Configuration):**
```java
ChatResponse response = chatClient.prompt()
    .options(AnthropicChatOptions.builder()
        .model("claude-3-7-sonnet-latest")
        .temperature(1.0)
        .maxTokens(8192)
        .thinking(AnthropicApi.ThinkingType.ENABLED, 2048)  // Budget ≥1024
        .build())
    .user("Are there infinite primes where n mod 4 == 3?")
    .call()
    .chatResponse();
```

**Claude 4 Models (Default Thinking):**
```java
// Thinking enabled by default, no configuration needed
ChatResponse response = chatClient.prompt()
    .options(AnthropicChatOptions.builder()
        .model("claude-opus-4-0")
        .build())
    .user(complexReasoningQuery)
    .call()
    .chatResponse();
```

**Key Differences:**
- **Claude 3.7:** Returns full thinking content
- **Claude 4:** Returns summarized reasoning (reduced latency)

### 4.6 Runtime Options Override

```java
ChatResponse response = chatModel.call(
    new Prompt(
        "Generate 5 famous pirates",
        AnthropicChatOptions.builder()
            .model("claude-3-7-sonnet-latest")
            .temperature(0.4)
            .build()
    )
);
```

---

## 5. State Persistence / Checkpointing

### 5.1 Spring AI Approach

Spring AI uses **Advisors** for state management, not explicit checkpointing like LangGraph:

#### Available Memory Stores

| Store | Implementation | Use Case |
|-------|---------------|----------|
| **In-Memory** | `InMemoryChatMemoryRepository` | Development, testing |
| **JDBC** | `JdbcChatMemoryRepository` | PostgreSQL, Oracle, MySQL |
| **Cassandra** | `CassandraChatMemoryRepository` | Distributed systems |
| **Neo4j** | `Neo4jChatMemoryRepository` | Graph-based relationships |

### 5.2 Chat Memory Advisors

```java
// Initialize chat memory
ChatMemory chatMemory = new JdbcChatMemory(dataSource);

// Configure advisor
var chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory)
            .conversationIdKey("userId")
            .historySize(10)
            .build()
    )
    .build();

// Use with conversation ID
String response = chatClient.prompt()
    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, "user-123"))
    .user("What did we discuss earlier?")
    .call()
    .content();
```

### 5.3 Three Memory Advisor Types

1. **MessageChatMemoryAdvisor**: Retrieves history as message collection
2. **PromptChatMemoryAdvisor**: Incorporates memory into system text
3. **VectorStoreChatMemoryAdvisor**: Uses semantic search for relevant history

### 5.4 LangGraph4j Integration for Checkpointing

For LangGraph-style checkpointing, use **LangGraph4j** with Spring AI:

```java
// LangGraph4j supports checkpointing with Oracle DB
MemorySaver checkpointer = new OracleMemorySaver(dataSource);

StateGraph<AgentState> graph = StateGraph.builder(AgentState.class)
    .addNode("agent", agentNode)
    .setCheckpointer(checkpointer)  // Enable state persistence
    .build();

// Resume from checkpoint
graph.invoke(input, config.withCheckpointId(checkpointId));
```

---

## 6. Streaming / SSE (Server-Sent Events)

### 6.1 Basic Streaming

```java
@GetMapping("/ai/stream")
public Flux<ChatResponse> streamResponse(@RequestParam String message) {
    Prompt prompt = new Prompt(new UserMessage(message));
    return chatModel.stream(prompt);
}
```

### 6.2 ChatClient Streaming API

```java
ChatClient chatClient = ChatClient.create(chatModel);

// Stream content only
Flux<String> contentStream = chatClient.prompt()
    .user("Tell me about Spring AI")
    .stream()
    .content();

contentStream.subscribe(System.out::print);
```

### 6.3 SSE Endpoint

```java
@GetMapping(value = "/ai/stream-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> streamSSE(@RequestParam String message) {
    return chatClient.prompt()
        .user(message)
        .stream()
        .content()
        .map(content -> ServerSentEvent.<String>builder()
            .data(content)
            .build());
}
```

### 6.4 Streaming with Advisors

```java
@Override
public Flux<ChatClientResponse> adviseStream(
        ChatClientRequest request,
        StreamAdvisorChain chain) {

    return Mono.just(request)
        .publishOn(Schedulers.boundedElastic())
        .map(req -> {
            // Pre-process request
            return req;
        })
        .flatMapMany(req -> chain.nextStream(req))
        .map(response -> {
            // Post-process response
            return response;
        });
}
```

### 6.5 Message Aggregation

```java
new ChatClientMessageAggregator()
    .aggregateChatClientResponse(responseFlux, this::processComplete);
```

---

## 7. MCP (Model Context Protocol)

### 7.1 Overview

Spring AI provides **comprehensive MCP support** as both client and server through Boot Starters.

**Architecture:**
```
┌─────────────────────────────────┐
│  Client/Server Layer            │
│  (McpClient, McpServer)         │
└─────────────────────────────────┘
           ↓
┌─────────────────────────────────┐
│  Session Layer                  │
│  (McpSession, Protocol Mgmt)    │
└─────────────────────────────────┘
           ↓
┌─────────────────────────────────┐
│  Transport Layer                │
│  (STDIO, HTTP/SSE, Streamable)  │
└─────────────────────────────────┘
```

### 7.2 MCP Client Dependencies

**Core Client (Servlet + STDIO):**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

**WebFlux Client (Reactive):**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client-webflux</artifactId>
</dependency>
```

### 7.3 MCP Server Dependencies

**STDIO Server:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
```

```properties
spring.ai.mcp.server.stdio=true
```

**WebMVC Server (SSE/Streamable-HTTP):**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

```properties
# Options: SSE (default), STREAMABLE, STATELESS
spring.ai.mcp.server.protocol=SSE
```

**WebFlux Server (Reactive):**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

### 7.4 MCP Annotation-Based Programming

**Server-Side Annotations:**
```java
import org.springframework.ai.mcp.server.annotation.*;

@McpTool(description = "Get current weather")
public WeatherResponse getCurrentWeather(
        @McpParam(description = "City name") String city) {
    return weatherService.getWeather(city);
}

@McpResource(uri = "file://documents/{docId}", name = "Document Retrieval")
public String getDocument(@McpParam String docId) {
    return documentStore.load(docId);
}

@McpPrompt(name = "summarize", description = "Summarize content")
public String summarizePrompt(String content) {
    return "Summarize the following: " + content;
}
```

**Client-Side Annotations:**
```java
@McpLogging
public void handleLog(LogEntry log) {
    logger.info("MCP Log: {}", log);
}

@McpProgress
public void handleProgress(ProgressNotification progress) {
    progressTracker.update(progress);
}
```

### 7.5 MCP Transport Options

| Transport | Client Support | Server Support | Use Case |
|-----------|---------------|---------------|----------|
| **STDIO** | ✓ | ✓ | Local processes |
| **SSE** | ✓ | ✓ | HTTP streaming |
| **Streamable-HTTP** | ✓ | ✓ | Multiple messages |
| **Stateless Streamable** | ✓ | ✓ | Serverless |

### 7.6 MCP Client Example

```java
@Configuration
public class McpClientConfig {

    @Bean
    McpClient weatherMcpClient() {
        return McpClient.builder()
            .transport(McpTransport.stdio("weather-server"))
            .build();
    }
}

// Usage
List<Tool> tools = mcpClient.listTools();
String result = mcpClient.callTool("getCurrentWeather",
    Map.of("city", "San Francisco"));
```

---

## 8. Concurrency / Parallel Execution

### 8.1 CompletableFuture for Orchestration

```java
List<CompletableFuture<String>> futures = tasks.stream()
    .map(task -> CompletableFuture.supplyAsync(() ->
        chatModel.call(task), executorService))
    .toList();

// Wait for all
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .join();

// Collect results
List<String> results = futures.stream()
    .map(CompletableFuture::join)
    .toList();
```

### 8.2 Virtual Threads (Java 21+)

```java
@Configuration
public class AsyncConfig {

    @Bean
    public TaskExecutor taskExecutor() {
        return new SimpleAsyncTaskExecutor("ai-task-");
        // Backed by virtual threads in Java 21+
    }
}

@Async
public CompletableFuture<String> processAsync(String input) {
    return CompletableFuture.completedFuture(
        chatModel.call(input)
    );
}
```

**Benefits of Virtual Threads:**
- Simplifies I/O-bound concurrency
- No need for reactive chains
- Platform threads not blocked

### 8.3 Reactive Flux for Streaming Parallelization

```java
Flux.fromIterable(tasks)
    .parallel()
    .runOn(Schedulers.boundedElastic())
    .flatMap(task -> chatModel.stream(task))
    .sequential()
    .collectList()
    .subscribe(results -> processResults(results));
```

### 8.4 Parallel Tool Execution

Spring AI's `ToolExecutionMode.CONCURRENT`:

```java
ChatOptions options = ToolCallingChatOptions.builder()
    .parallelToolCalls(true)  // LLM can request multiple tools
    .toolExecutionMode(ToolExecutionMode.CONCURRENT)  // Execute in parallel
    .build();

// Framework executes tools concurrently using CompletableFuture
```

### 8.5 Custom Executors

```java
@Configuration
public class ConcurrencyConfig {

    @Bean(name = "aiExecutor")
    public ExecutorService aiExecutorService() {
        return Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
        );
    }
}

@Service
public class ParallelAgentService {

    @Autowired
    @Qualifier("aiExecutor")
    private ExecutorService executor;

    public List<String> processParallel(List<String> queries) {
        return queries.stream()
            .map(query -> CompletableFuture.supplyAsync(() ->
                chatClient.prompt().user(query).call().content(),
                executor))
            .map(CompletableFuture::join)
            .toList();
    }
}
```

---

## 9. Key Dependencies

### 9.1 Maven BOM

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.1.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 9.2 Core Dependencies

```xml
<!-- Anthropic Claude -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>

<!-- Vector Store (Choose one) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store-spring-boot-starter</artifactId>
</dependency>

<!-- MCP Client -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>

<!-- MCP Server -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

### 9.3 Gradle Configuration

```gradle
plugins {
    id 'org.springframework.boot' version '3.4.1'
    id 'io.spring.dependency-management' version '1.1.7'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.ai:spring-ai-bom:1.1.2"
    }
}

dependencies {
    implementation 'org.springframework.ai:spring-ai-starter-model-anthropic'
    implementation 'org.springframework.ai:spring-ai-starter-mcp-client'
    implementation 'org.springframework.ai:spring-ai-pgvector-store-spring-boot-starter'
}
```

### 9.4 Spring Boot Version Requirements

| Spring AI | Spring Boot | Java |
|-----------|------------|------|
| 1.1.2 | 3.4.x | 17+ (21+ for build) |
| 1.0.0 GA | 3.3.x | 17+ |

### 9.5 Optional Dependencies

```xml
<!-- LangGraph4j Integration -->
<dependency>
    <groupId>org.langgraph4j</groupId>
    <artifactId>langgraph4j-spring-ai</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- State Machine (if needed) -->
<dependency>
    <groupId>org.springframework.statemachine</groupId>
    <artifactId>spring-statemachine-core</artifactId>
    <version>3.0.0</version>
</dependency>

<!-- Agent Skills (Anthropic) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-anthropic-agent-skills</artifactId>
</dependency>
```

---

## 10. Comparison to LangGraph

### 10.1 Feature Mapping

| LangGraph Feature | Spring AI Equivalent | Notes |
|-------------------|---------------------|-------|
| **StateGraph** | LangGraph4j + Spring AI | Use LangGraph4j for graph-based workflows |
| **Send API (Fan-Out)** | CompletableFuture + Parallel Streams | Use Java concurrency primitives |
| **Conditional Edges** | Java conditional logic in nodes | No built-in routing, use Java code |
| **Checkpointing** | Advisors + Memory Stores | Different approach: Advisors for state |
| **Persistence** | JDBC/Cassandra/Neo4j | Multiple storage backends |
| **Tool Calling** | @Tool annotation | More declarative than LangGraph |
| **Structured Output** | BeanOutputConverter | Automatic Java class mapping |
| **Human-in-the-Loop** | Spring Web endpoints | Build custom approval flows |

### 10.2 StateGraph vs Spring AI Workflows

**LangGraph StateGraph:**
```python
# Python
graph = StateGraph(AgentState)
graph.add_node("agent", agent_node)
graph.add_node("tools", tool_node)
graph.add_conditional_edges("agent", route_function)
graph.set_entry_point("agent")
```

**Spring AI Equivalent:**
```java
// Option 1: LangGraph4j
StateGraph<AgentState> graph = StateGraph.builder(AgentState.class)
    .addNode("agent", agentNode)
    .addNode("tools", toolNode)
    .addConditionalEdges("agent", routeFunction)
    .build();

// Option 2: Native Spring AI (Advisors + CompletableFuture)
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(memoryAdvisor)
    .defaultTools(tools)
    .build();

String result = chatClient.prompt()
    .user(input)
    .call()
    .content();
```

### 10.3 Send API (Fan-Out) Comparison

**LangGraph Send API:**
```python
def route(state):
    return [Send("worker", {"task": t}) for t in state["tasks"]]
```

**Spring AI Parallel Execution:**
```java
List<CompletableFuture<String>> futures = tasks.stream()
    .map(task -> CompletableFuture.supplyAsync(() ->
        workerAgent.process(task)))
    .toList();

List<String> results = futures.stream()
    .map(CompletableFuture::join)
    .toList();
```

### 10.4 Per-Step Checkpointing

**LangGraph:**
```python
checkpointer = SqliteSaver("checkpoints.db")
graph = StateGraph(AgentState, checkpointer=checkpointer)
```

**Spring AI (via LangGraph4j):**
```java
MemorySaver checkpointer = new OracleMemorySaver(dataSource);
StateGraph<AgentState> graph = StateGraph.builder(AgentState.class)
    .setCheckpointer(checkpointer)
    .build();
```

**Spring AI Native (Advisors):**
```java
// State managed through Advisors
var chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build()
    )
    .build();

// Conversation state persisted to DB automatically
```

### 10.5 Conditional Edges

**LangGraph:**
```python
graph.add_conditional_edges(
    "agent",
    lambda state: "tools" if state["needs_tools"] else "end"
)
```

**Spring AI Approach:**
```java
public String routeAgent(AgentState state) {
    if (state.needsTools()) {
        return toolAgent.call(state);
    } else {
        return finalizeAgent.call(state);
    }
}
```

### 10.6 Key Differences

| Aspect | LangGraph | Spring AI |
|--------|-----------|-----------|
| **Philosophy** | Explicit graph-based | Implicit flow via Advisors |
| **State Management** | Built-in StateGraph | Advisors + Memory |
| **Concurrency** | Send API | Java CompletableFuture |
| **Persistence** | Checkpointing per step | Per-conversation memory |
| **Language** | Python-native | Java-native |
| **Integration** | LangChain ecosystem | Spring ecosystem |

### 10.7 When to Use LangGraph4j with Spring AI

Use **LangGraph4j** when you need:
- Complex multi-step workflows with branching
- Explicit state graph visualization
- Per-step checkpointing
- Human-in-the-loop at specific nodes
- Dynamic graph construction

Use **Native Spring AI** when you need:
- Simple linear or parallel workflows
- Enterprise Java integration
- Strong type safety
- Spring Boot ecosystem benefits
- RAG patterns with Advisors

---

## 11. Advanced Features

### 11.1 A2A (Agent-to-Agent Protocol)

Spring AI supports the **A2A Protocol** for multi-agent communication:

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-a2a</artifactId>
</dependency>
```

**Key Features:**
- Automatic A2A server exposure
- Agent capability discovery
- Cross-platform agent communication
- Full @Tool support

**Configuration:**
```java
@Bean
public AgentCard agentCard() {
    return AgentCard.builder()
        .name("MyAgent")
        .description("Handles customer queries")
        .capabilities(List.of("search", "summarize"))
        .build();
}
```

### 11.2 Vector Database Integration

Spring AI supports multiple vector stores:

```xml
<!-- PostgreSQL with pgvector -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store-spring-boot-starter</artifactId>
</dependency>

<!-- Redis -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-redis-store-spring-boot-starter</artifactId>
</dependency>

<!-- Milvus -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-milvus-store-spring-boot-starter</artifactId>
</dependency>
```

### 11.3 RAG with QuestionAnswerAdvisor

```java
VectorStore vectorStore = ...; // Initialize vector store

var chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        QuestionAnswerAdvisor.builder(vectorStore)
            .searchRequest(SearchRequest.defaults()
                .withTopK(5)
                .withSimilarityThreshold(0.7))
            .build()
    )
    .build();

String answer = chatClient.prompt()
    .user("What is Spring AI?")
    .call()
    .content();
```

### 11.4 Observability

Spring AI integrates with **Spring Boot Actuator**:

```properties
management.endpoints.web.exposure.include=health,metrics,traces
management.tracing.sampling.probability=1.0
```

**Available Metrics:**
- LLM call duration
- Token usage
- Tool invocation counts
- Cache hit rates

### 11.5 Prompt Caching

Reduce costs with Anthropic's prompt caching:

```java
ChatOptions options = AnthropicChatOptions.builder()
    .cacheStrategy(CacheStrategy.SYSTEM_AND_TOOLS)
    .build();
```

**Cache Strategies:**
- `SYSTEM_ONLY`: Cache system messages
- `TOOLS_ONLY`: Cache tool definitions
- `SYSTEM_AND_TOOLS`: Cache both
- `CONVERSATION_HISTORY`: Cache entire history

---

## 12. Code Examples Repository

### 12.1 Complete Working Examples

**GitHub Repositories:**
- [Spring AI Official](https://github.com/spring-projects/spring-ai)
- [Spring AI MCP](https://github.com/spring-projects-experimental/spring-ai-mcp)
- [LangGraph4j](https://github.com/langgraph4j/langgraph4j)
- [Orchestrator-Workers Example](https://github.com/BootcampToProd/spring-ai-orchestrator-workers-workflow)

### 12.2 Sample Application Structure

```
src/main/java/
├── config/
│   ├── ChatClientConfig.java
│   ├── McpClientConfig.java
│   └── VectorStoreConfig.java
├── service/
│   ├── OrchestratorService.java
│   ├── WorkerService.java
│   └── ToolService.java
├── model/
│   ├── AgentState.java
│   └── TaskRequest.java
├── advisor/
│   ├── CustomMemoryAdvisor.java
│   └── LoggingAdvisor.java
└── controller/
    └── AgentController.java
```

---

## 13. Best Practices

### 13.1 Tool Design

1. **Keep tools focused**: One tool = one capability
2. **Provide clear descriptions**: LLM needs to understand when to use each tool
3. **Use @ToolParam descriptions**: Helps LLM provide correct inputs
4. **Return structured data**: Use records/classes for consistency

### 13.2 State Management

1. **Use conversation IDs**: Always pass conversation ID for memory
2. **Choose appropriate storage**: In-memory for dev, JDBC for prod
3. **Set history limits**: Prevent token overflow with `.historySize(10)`
4. **Consider vector memory**: For large conversation histories

### 13.3 Concurrency

1. **Use virtual threads (Java 21+)**: Simplifies I/O-bound workloads
2. **Configure executors**: Set appropriate pool sizes
3. **Handle errors gracefully**: Use `exceptionally()` with CompletableFuture
4. **Monitor performance**: Track parallel execution metrics

### 13.4 Workflow Orchestration

1. **Choose the right pattern**: Chain for sequential, Orchestrator-Workers for dynamic
2. **Consider LangGraph4j**: For complex graph-based workflows
3. **Use Advisors**: For cross-cutting concerns (logging, memory, RAG)
4. **Implement observability**: Metrics, tracing, logging

---

## 14. Resources

### Official Documentation
- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/)
- [Spring AI GitHub](https://github.com/spring-projects/spring-ai)
- [MCP Documentation](https://docs.spring.io/spring-ai/reference/api/mcp/)
- [LangGraph4j](https://langgraph4j.github.io/langgraph4j/)

### Blog Posts & Tutorials
- [Building Effective Agents with Spring AI](https://spring.io/blog/2025/01/21/spring-ai-agentic-patterns/)
- [Spring AI Agentic Patterns: Agent Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/)
- [Spring AI MCP Introduction](https://spring.io/blog/2025/09/16/spring-ai-mcp-intro-blog/)
- [Spring AI A2A Integration](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration)

### Community Resources
- [Baeldung Spring AI Tutorials](https://www.baeldung.com/spring-ai)
- [BootcampToProd Spring AI Guides](https://bootcamptoprod.com/)
- [Piotr's TechBlog](https://piotrminkowski.com/)

---

## 15. Conclusion

Spring AI 1.1 GA provides a **production-ready framework** for building agentic AI systems in Java. Key strengths:

1. **Mature Tooling**: Declarative `@Tool` annotations, fluent ChatClient API
2. **Enterprise Integration**: Spring Boot ecosystem, multiple databases, observability
3. **Flexible Orchestration**: Advisors for simple flows, LangGraph4j for complex graphs
4. **MCP & A2A Support**: Full support for emerging agent protocols
5. **Type Safety**: Strong Java types, structured outputs via records
6. **Streaming**: Native SSE support, reactive Flux integration

**Compared to LangGraph:**
- Spring AI is more **enterprise Java-focused** with strong Spring Boot integration
- LangGraph provides more **explicit graph-based orchestration**
- Spring AI uses **Advisors** for state; LangGraph uses **StateGraph**
- Both support parallel execution, tool calling, and state persistence
- LangGraph4j bridges the gap, enabling graph workflows in Spring AI

**Recommendation for Worldmind:**
- Use **Spring AI + LangGraph4j** for complex multi-step workflows
- Leverage **Advisors** for RAG, memory, and cross-cutting concerns
- Use **CompletableFuture + Virtual Threads** for parallelization
- Implement **MCP servers** to expose Worldmind capabilities
- Use **A2A protocol** for inter-agent communication

---

## Sources

- [Spring AI Agentic Patterns (Part 1): Agent Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/)
- [Building Effective Agents with Spring AI](https://spring.io/blog/2025/01/21/spring-ai-agentic-patterns/)
- [Anthropic Chat :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html)
- [Tool Calling :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Structured Output Converter :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)
- [Advisors API :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Model Context Protocol (MCP) :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [Spring AI 1.0 GA Released](https://spring.io/blog/2025/05/20/spring-ai-1-0-GA-released/)
- [Spring AI 1.1 GA Released](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/)
- [Spring AI Orchestrator-Workers Workflow Guide](https://bootcamptoprod.com/spring-ai-orchestrator-workers-workflow-guide/)
- [LangGraph4j GitHub](https://github.com/langgraph4j/langgraph4j)
- [Spring AI A2A Integration](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration)
- [Using Anthropic's Claude Models With Spring AI](https://www.baeldung.com/spring-ai-anthropics-claude-models)
- [ChatClient Fluent API in Spring AI](https://www.baeldung.com/spring-ai-chatclient)
- [Getting Started with Spring AI Function Calling](https://piotrminkowski.com/2025/01/30/getting-started-with-spring-ai-function-calling/)
- [Spring AI Streaming Response Guide](https://bootcamptoprod.com/spring-ai-streaming-response-guide/)
- [Chat Memory in Spring AI](https://www.baeldung.com/spring-ai-chat-memory)
