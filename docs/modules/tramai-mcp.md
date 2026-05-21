# Module: `tramai-mcp`

> **One-liner:** Exposes registered Tramai workflows as MCP (Model Context Protocol) tools — `list_workflows`, `run_workflow`, `resume_workflow`, `get_workflow_status` — over stdio or SSE transport, so AI agents can discover and invoke workflows through the standard MCP contract.
> **Module type:** `adapter`
> **Source files:** 3 — `TramaiMcpServer.kt`, `McpToolHandlers.kt`, `TramaiMcpAutoConfiguration.kt`
> **Test files:** 1 — `TramaiMcpServerTest.kt`
> **Build:** `dev.tramai:tramai-mcp:0.3.0`
> **Depends on:** `tramai-server` (required), `tramai-structured` (required), `spring-boot-autoconfigure`, `spring-context`, MCP Kotlin SDK, Ktor CIO

---

## L1: Quick Start (30-second read)

### What

`tramai-mcp` is a Spring Boot auto-configured **MCP server adapter** that reads the `WorkflowRegistry` and `WorkflowController` already present from `tramai-server` and exposes four MCP tools:

| Tool | Purpose |
|------|---------|
| `list_workflows` | Returns registered workflows with JSON Schema for input and output payloads |
| `run_workflow` | Starts a workflow run from a JSON state object, returns workflow ID |
| `resume_workflow` | Resumes a suspended workflow run by workflow ID |
| `get_workflow_status` | Fetches current status, active step, history, result, and error state |

It does **not** reimplement workflow execution — it delegates every call through the existing `WorkflowController` and `WorkflowRunStore` from `tramai-server`. The module is an adapter layer, not a second orchestration runtime.

### Why

AI coding tools, agent frameworks, and local CLIs speak MCP to discover capabilities and call tools. Without `tramai-mcp`, workflow invocation requires raw REST calls or direct Java API usage. With it:

- **Agent-native discovery** — any MCP client can call `tools/list` and learn about all registered workflows, including their input/output JSON schemas
- **Uniform tool contract** — workflow invocation looks like any other MCP tool call; agent frameworks don't need special-case REST client code
- **Transport flexibility** — stdio for local co-process agents (e.g., Claude Desktop, VS Code extensions), SSE for network-accessible MCP clients
- **Error normalization** — server-side exceptions are mapped to MCP error types (`not_found`, `conflict`, `invalid_request`, `internal_error`) so agents see consistent failure shapes

### When to use

```
Use this module when:
- A local coding tool or AI agent should discover workflows automatically
- Workflow invocation should look like normal MCP tool usage to the client
- You want workflow input/output contracts exposed as JSON Schema through MCP
- You already have tramai-server in your application

Don't use this module when:
- No MCP client will consume it (the REST API from tramai-server is sufficient)
- You don't need agent-native tool discovery
- You want to avoid the MCP SDK dependency
```

### How to add

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.3.0"))
    implementation("dev.tramai:tramai-server")  // required dependency
    implementation("dev.tramai:tramai-mcp")
}
```

```xml
<!-- pom.xml -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.tramai</groupId>
      <artifactId>tramai-bom</artifactId>
      <version>0.3.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-server</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-mcp</artifactId>
  </dependency>
</dependencies>
```

### Configuration

The adapter activates automatically via Spring Boot auto-configuration when the workflow server beans (`WorkflowRegistry`, `WorkflowRunStore`, `WorkflowController`) are present. It binds under the `tramai.mcp.*` namespace:

```yaml
tramai:
  mcp:
    stdio:
      enabled: false     # starts an MCP stdio session in-process
    sse:
      enabled: false     # starts an embedded Ktor Server-Sent Events server
      host: 127.0.0.1
      port: 8091
      path: /mcp
```

At least one transport must be enabled for the server to start. Auto-configuration uses a `SmartLifecycle` bean that checks these flags and calls `startStdio()` and/or `startSse()` accordingly.

### Where to go next

- [MCP Integration Guide](../guides/mcp.md) — Setup walkthrough and transport options
- [Workflow Server Guide](../guides/server.md) — Using tramai-server REST API
- [Configuration Reference](../reference/configuration.md) — All `tramai.mcp.*` properties
- [tramai-server](./tramai-server.md) — Server module this adapter depends on
- [tramai-orchestration](./tramai-orchestration.md) — Workflow definitions that get exposed as tools

---

## L2: Usage Guide (5-minute read)

### Activation model

`tramai-mcp` is a **conditional** auto-configuration. It activates only when all three server beans are in the context:

- `WorkflowRegistry` — holds registered workflow definitions
- `WorkflowRunStore` — persists workflow run state
- `WorkflowController` — orchestrates run/resume/status operations

This means `tramai-server` must already be in the application. The MCP adapter layers on top of the existing server infrastructure without replacing or duplicating it.

### Tool definitions

Each tool is defined as an MCP SDK `Tool` with a `name`, `description`, `inputSchema`, and `outputSchema`:

**`list_workflows`** — No input arguments. Returns an array of workflow descriptors, each with:
- `name` — workflow name
- `definitionVersion` — semantic version string
- `inputSchema` — JSON Schema for the workflow's initial state type, generated by `JacksonStructuredOutputHandler`
- `outputSchema` — JSON Schema for the workflow's result type

**`run_workflow`** — Required arguments:
- `workflowName` (string) — registered workflow name
- `state` (object) — initial JSON state for the workflow run
- Optional `idempotencyKey` (string) — deduplication key

Returns `workflowId`, `status`, `definitionVersion`, and optional `result`.

**`resume_workflow`** — Required argument:
- `workflowId` (string) — ID of a suspended workflow run
- Optional `workflowName` (string) — if omitted, resolved by scanning all registered workflows

Returns the same response shape as `run_workflow`.

**`get_workflow_status`** — Required argument:
- `workflowId` (string) — ID of an existing workflow run
- Optional `workflowName` (string) — if omitted, resolved by scanning all registered workflows

Returns `workflowId`, `status`, `definitionVersion`, `currentStep`, `history` (array of step entries), optional `result`, and optional `error`.

### Error mapping

The adapter normalizes server-side exceptions into MCP tool failures through `McpToolHandlers.toolFailure()`:

| Server exception | MCP errorType | When |
|-----------------|---------------|------|
| `WorkflowNotRegisteredException` | `not_found` | Unknown workflow name |
| `WorkflowRunNotFoundException` | `not_found` | Unknown workflow run ID |
| `WorkflowConflictException` | `conflict` | Resume/state conflict |
| `BadWorkflowRequestException` | `invalid_request` | Invalid input or request shape |
| `IllegalArgumentException` | `invalid_request` | Invalid arguments |
| Any other `RuntimeException` | `internal_error` | Unexpected failure |

Each failure returns a structured error object with `error` (message) and `errorType` fields, keeping agent clients from seeing Spring-specific or Ktor-specific exception shapes.

### Transport: Stdio

Stdio transport starts an MCP session on the process's standard input/output streams. This is the mode used by local agent tools (Claude Desktop, VS Code MCP extensions, CLI agents):

```yaml
tramai:
  mcp:
    stdio:
      enabled: true
```

The server creates a `StdioServerTransport` from `System.in` and `System.out` and starts a session on the application's `CoroutineScope`. Useful when the same JVM process is both the workflow host and the agent's subprocess target.

### Transport: SSE

SSE transport starts an embedded Ktor CIO server on a separate port. This is the mode for network-accessible MCP clients:

```yaml
tramai:
  mcp:
    sse:
      enabled: true
      host: 127.0.0.1
      port: 8091
      path: /mcp
```

The SSE server is independent from the Spring Boot HTTP port. The Ktor `routing` block mounts the MCP server at the configured path, and the MCP Kotlin SDK's `mcp(path) { ... }` routing extension handles the SSE and JSON-RPC message protocol.

### Relationship to REST API

Every MCP tool call ultimately flows through the same code path as a REST API request. For example:

```
MCP run_workflow("invoice", { "invoiceId": "abc" })
  → McpToolHandlers.runWorkflow()
    → WorkflowController.runWorkflow(name, body, idempotencyKey)
      → WorkflowEngine.run(...)
```

This means:
- Workflow validation is identical
- Idempotency behavior is identical
- Conflict rules are identical
- Run status semantics are identical

The adapter does not introduce a separate execution path.

---

## L3: Architecture & Mechanics (15-minute read)

### Module dependency graph

```
tramai-mcp
  │
  ├── api: tramai-server
  │     └── WorkflowRegistry, WorkflowRunStore, WorkflowController
  │
  ├── api: tramai-structured
  │     └── JacksonStructuredOutputHandler (schema generation)
  │
  ├── impl: MCP Kotlin SDK (io.modelcontextprotocol:kotlin-sdk-server:0.11.1)
  │     └── Server, StdioServerTransport, Tool, ToolSchema, CallToolResult
  │
  ├── impl: Ktor Server CIO (io.ktor:ktor-server-cio-jvm:3.3.3)
  │     └── EmbeddedServer, routing, mcp(path) extension
  │
  ├── impl: Spring Boot autoconfigure
  │     └── SmartLifecycle, @AutoConfiguration, @ConfigurationProperties
  │
  └── impl: kotlinx-serialization-json, Jackson, Okio
```

### Class responsibilities

Three production source files, one test file.

#### `TramaiMcpServer.kt`

The core MCP server adapter. Constructs an MCP SDK `Server` instance with four registered tools, then provides two lifecycle methods for transport:

```kotlin
class TramaiMcpServer(
    private val handlers: McpToolHandlers,
) : AutoCloseable
```

**Server construction (init block):** Creates an MCP SDK `Server` with:
- `serverInfo` — name `"tramai-mcp"`, version `"0.3.0"`
- `capabilities` — tools support (`listChanged = false`)
- Four `addTool(...)` registrations, each wiring a tool definition to its handler via `handleCall { ... }`

Each tool is defined by a private `Tool(...)` factory method that provides:
- `name` — the MCP tool name string
- `description` — human-readable description
- `inputSchema` — parsed from inline JSON via `schema()` helper, which deserializes a raw JSON string into a `ToolSchema` with `properties`, `required`, and optional `defs`
- `outputSchema` — same `schema()` helper pattern

**Tool argument extraction:** Three private extension functions on `CallToolRequest`:
- `requiredString(name)` — extracts and returns a string, throws `ToolExecutionException` with `"invalid_request"` if missing
- `optionalString(name)` — extracts a nullable string, returning `null` for absent or `JsonNull` values
- `requiredObject(name)` — extracts a `JsonElement` argument, throws `ToolExecutionException` if missing

**`handleCall` dispatcher:** A private function wrapping every tool handler:
```kotlin
private fun handleCall(action: () -> JsonObject): CallToolResult = try {
    val payload = action()
    CallToolResult(
        content = listOf(TextContent(Json.encodeToString(...))),
        structuredContent = payload,
    )
} catch (error: ToolExecutionException) {
    CallToolResult(
        content = listOf(TextContent(error.message)),
        isError = true,
        structuredContent = error.asResult(),
    )
}
```

Success returns both a `TextContent` string and `structuredContent` JSON object. Failure returns the error message as `TextContent` and the structured `{ error, errorType }` object.

**Transport methods:**

- `startStdio()` — Launches a coroutine on the module's `CoroutineScope(IO)` that creates a `StdioServerTransport` from `System.in`/`System.out` (via Okio `asSource()`/`asSink()`), then calls `mcpServer.createSession(transport)`. This is a one-shot session — useful when the process is spawned specifically for MCP.

- `startSse(host, port, path)` — Creates an `embeddedServer(CIO)` with a routing block that mounts the MCP server via the Ktor `mcp(path) { mcpServer }` extension. Starts non-blocking (`wait = false`). The embedded server is stored so it can be stopped later.

- `stopSse()` — Stops the embedded SSE server with 1-second grace and timeout periods.

- `close()` — Implements `AutoCloseable`. Stops SSE, cancels the coroutine scope, and closes the MCP server session.

**The `sdkServer()` accessor** returns the raw MCP SDK `Server` instance, enabling programmatic session creation (used in tests for in-process stdio client testing via piped streams).

#### `McpToolHandlers.kt`

The business-logic adapter that translates MCP tool calls into `tramai-server` operations. Constructed with:

```kotlin
class McpToolHandlers(
    private val registry: WorkflowRegistry,
    private val runStore: WorkflowRunStore,
    private val workflowController: WorkflowController,
    private val objectMapper: ObjectMapper,
    private val structuredOutputHandler: JacksonStructuredOutputHandler,
)
```

**`listWorkflows()`:** Iterates `registry.list()`, building a JSON array where each entry includes the workflow name, definition version, and JSON schemas for the input state type and result type. Schema generation delegates to `structuredOutputHandler.generateSchema(type)`, which uses the same structured-output infrastructure that validates LLM responses — guaranteeing that the schema exposed via MCP matches what the workflow engine actually expects.

**`runWorkflow(workflowName, state, idempotencyKey)`:** Serializes the incoming `JsonElement` state to a Jackson `JsonNode` via `objectMapper.writeValueAsString()`, then calls `workflowController.runWorkflow()`. Catches `RuntimeException` subtypes and translates them via `toolFailure()`.

**`resumeWorkflow(workflowId, workflowName)`:** If `workflowName` is provided and valid, uses it directly. Otherwise, calls `resolveWorkflowName()` which scans all registered workflows and queries each one's checkpoint store to find which workflow owns the given ID. This allows callers to resume runs without knowing which workflow they belong to.

**`getWorkflowStatus(workflowId, workflowName)`:** Same name resolution logic as `resumeWorkflow`, then calls `workflowController.getRun()` to fetch the full `WorkflowRunDetail` including current step, history array, result, and error state. Returns the serialized detail as a JSON object.

**`resolveWorkflowName(workflowId, workflowName)`:** Two-phase resolution:
1. If `workflowName` is non-blank, validates it exists in the registry and returns it
2. Otherwise, iterates every registered workflow and queries `runStore.get(workflow.name, workflowId)` until a match is found; throws `ToolExecutionException("not_found")` if no match

**`toolFailure(error)`:** Pattern-matches `RuntimeException` subtypes to MCP error types:
- `WorkflowNotRegisteredException` → `"not_found"`
- `WorkflowRunNotFoundException` → `"not_found"`
- `WorkflowConflictException` → `"conflict"`
- `BadWorkflowRequestException` / `IllegalArgumentException` → `"invalid_request"`
- Everything else → `"internal_error"` (with the original exception as cause)

**`ToolExecutionException`:** A custom `RuntimeException` subclass that carries an `errorType` string and produces a structured `{ error, errorType }` JSON object via `asResult()`. This is the contract between the handlers and the `handleCall` wrapper in `TramaiMcpServer`.

#### `TramaiMcpAutoConfiguration.kt`

Spring Boot auto-configuration that wires the MCP adapter into the application context. Annotated with `@AutoConfiguration` and `@ConditionalOnBean(WorkflowRegistry, WorkflowRunStore, WorkflowController)`.

**Beans registered:**

| Bean | Type | Purpose |
|------|------|---------|
| `tramaiMcpStructuredOutputHandler` | `JacksonStructuredOutputHandler` | Schema generation for workflow input/output types |
| `mcpToolHandlers` | `McpToolHandlers` | Bridges MCP calls to server controllers |
| `tramaiMcpServer` | `TramaiMcpServer` | MCP SDK server with tool registrations |
| `tramaiMcpLifecycle` | `SmartLifecycle` | Auto-starts transports at application startup |

**`TramaiMcpProperties`** — Binds `tramai.mcp.*` configuration:
- `stdio.enabled` — default `false`
- `sse.enabled` — default `false`
- `sse.host` — default `"127.0.0.1"`
- `sse.port` — default `8091`
- `sse.path` — default `"/mcp"`

**`TramaiMcpLifecycle`** — A `SmartLifecycle` bean that:
- In `start()`: checks the property flags and calls the appropriate transport start methods on the server
- In `stop()`: calls `server.close()` to tear down transports and coroutine scope
- `isAutoStartup()` returns `true` so transports start automatically
- Only sets `running = true` if at least one transport was enabled

### Handler dispatch chain

The complete flow from MCP client to workflow engine:

```
MCP Client
  │ tools/list → Server.listTools()
  │   └─ returns all 4 Tool definitions
  │
  │ tools/call → {
  │     name: "run_workflow",
  │     arguments: { workflowName: "invoice", state: { ... } }
  │   }
  │   └─ MCP SDK dispatches to registered Tool handler
  │       └─ TramaiMcpServer.addTool(runWorkflowTool()).handleCall
  │           └─ handlers.runWorkflow(workflowName, state, idempotencyKey)
  │               └─ workflowController.runWorkflow(name, body, idempotencyKey)
  │                   └─ WorkflowEngine.run(...)
  │                       └─ returns WorkflowRunDetail
  │
  │ CallToolResult {
  │     content: [TextContent(json)],
  │     structuredContent: { workflowId, status, ... }
  │   }
```

### Schema generation

Tool schemas are generated from Kotlin types using `JacksonStructuredOutputHandler.generateSchema(type)`, the same component that generates JSON Schema for structured LLM output parsing. This guarantees consistency between:
- What the MCP client sees as the workflow's input/output contract
- What the workflow engine actually deserializes at runtime
- What the REST API documentation describes

The `schema()` helper in `TramaiMcpServer` parses a raw JSON string into the MCP SDK's `ToolSchema` type, splitting `properties`, `required`, and `$defs` from the parsed object:

```kotlin
private fun schema(raw: String): ToolSchema {
    val schema = Json.parseToJsonElement(raw).jsonObject
    val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    return ToolSchema(
        properties = schema["properties"]?.jsonObject ?: buildJsonObject {},
        required = required,
        defs = schema["\$defs"]?.jsonObject,
    )
}
```

### Design decisions

1. **Adapter, not runtime** — The module is deliberately thin. All execution logic lives in `tramai-server` and `tramai-orchestration`. The MCP adapter is only responsible for protocol translation.

2. **Two transports, one server** — The same `TramaiMcpServer` instance can serve both stdio and SSE simultaneously. The `SmartLifecycle` checks each flag independently and starts the relevant transports.

3. **Explicit tool schemas** — Each tool's input and output schemas are defined as inline JSON strings in `TramaiMcpServer.kt`, not auto-generated from Kotlin types at runtime. This keeps the tool definitions predictable and easy to audit. The schemas for workflow-specific state types (input/output) *are* auto-generated via `JacksonStructuredOutputHandler` at `listWorkflows` time.

4. **Workflow name resolution** — Both `resume_workflow` and `get_workflow_status` accept an optional `workflowName`. When omitted, the handler scans all registered workflows to find the one that owns the given run ID. This simplifies the client contract — an agent can stash a workflow ID and call status/resume without tracking which workflow it belongs to.

5. **Structured error payloads** — Error responses include both a human-readable `TextContent` message and a structured JSON payload with `error` and `errorType` fields, enabling both agent-facing error messages and programmatic error classification.

### Current limits

- No `cancellation` tool — workflow runs cannot be cancelled via MCP (use the REST API)
- No `tools/notifications` — `listChanged` is `false`; clients must re-list to discover new workflows
- Single MCP session — the stdio transport creates exactly one session; for multi-connection scenarios, use SSE
- SSE server is a separate Ktor instance — does not reuse the Spring Boot HTTP port or its TLS configuration
