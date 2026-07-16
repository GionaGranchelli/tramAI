# Tool Calling

TramAI supports engine-owned tool calling.

That means:

- tools are declared explicitly on an operation
- tools are registered explicitly in the runtime
- the engine owns the tool loop and final response completion
- applications do not hand-roll the model -> tool -> model orchestration path

This is not an autonomous agent framework. It is a bounded execution loop attached to a normal typed service method.

---

## Operation Contract

Enable tools on an operation through `@Operation(tools = [...])`:

```kotlin
@AiService
interface TenantAssistant {
    @Operation(
        prompt = "Use the lookup tool when you need tenant data",
        model = "claude-sonnet-4-20250514",
        tools = ["lookup"],
    )
    suspend fun answer(question: String): String
}
```

The tool names must match registered tools.

---

## Standalone Registration

In standalone usage, register tools through the builder:

```kotlin
data class LookupInput(
    val query: String,
)

data class LookupResult(
    val value: String,
)

class LookupTool : TramaiTool<LookupInput, LookupResult> {
    override val name: String = "lookup"
    override val description: String = "Looks up tenant data"
    override val inputType = LookupInput::class
    override val idempotent: Boolean = true
    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY

    override suspend fun execute(
        input: LookupInput,
        context: ToolExecutionContext,
    ): LookupResult = LookupResult(
        value = tenantDirectory.lookup(input.query),
    )
}

val tramai = Tramai {
    provider(AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")), name = "anthropic", default = true)
    model("claude-sonnet-4-20250514", "anthropic")
    tools(LookupTool())
}
```

---

## Spring Boot Registration

Spring can discover tools from methods annotated with `@AiTool`.

```kotlin
data class LookupInput(
    val query: String,
)

@Component
class TenantTools {
    @AiTool(
        name = "lookup",
        description = "Looks up tenant data",
        idempotent = true,
        sideEffectLevel = SideEffectLevel.READ_ONLY,
    )
    fun lookup(input: LookupInput): LookupResult = LookupResult(
        value = tenantDirectory.lookup(input.query),
    )
}
```

Current Spring rule:

- `@AiTool` methods must take exactly one parameter
- that input type must be a data class
- the Spring adapter converts those methods into `TramaiTool` registrations automatically

---

## Execution Semantics

The tool loop is engine-owned:

1. the model returns one or more tool calls
2. TramAI validates and deserializes the tool arguments
3. TramAI executes the matching registered tool
4. TramAI returns the tool result to the model
5. the model continues toward a final answer

Applications stay inside the normal service method contract.

---

## Tool Failure Semantics

TramAI distinguishes several tool outcomes:

- successful execution
- invalid input
- transient failure
- permanent failure

This matters because retry behavior should depend on tool semantics, not just on provider behavior.

Practical guidance:

- mark read-only tools idempotent when retries are safe
- do not mark write tools idempotent unless you are sure duplicate execution is acceptable
- keep tool input/output contracts small and typed

---

## Caching Boundary

Tool-enabled operations are not cached automatically even if `@Operation(cacheable = true)` is present.

That is deliberate. Once tools participate, the call may depend on external state or side effects that make naive response caching unsafe.

---

## Cost And Retry Interaction

Tool calling still runs inside the engine's existing controls:

- token budgets apply across the whole logical operation
- provider retries remain engine-owned
- tool loops are part of the same operation budget
- observability remains attached to the same operation identity

That boundary is important. Tool calling extends the execution engine; it does not bypass it.

---

## Current Boundaries

Tool calling is intentionally constrained:

- tools are explicit, not magically discovered in standalone usage
- the public contract is portable across providers
- provider-native tool calling may be used under the hood, but the user-facing model stays TramAI-owned
- streaming-plus-tool-calling is not the primary public contract today

> **Next:** see the [Governed Tool Use Guide](governed-tool-use.md) for how tool permission decisions (ALLOW, DENY, REQUIRE_APPROVAL) are enforced and evidenced through the tool.permission runtime evidence family.

---

## Design Intent

Tool calling in TramAI is for deterministic application actions inside a bounded AI operation.

It is not:

- a planner framework
- an autonomous agent loop
- a peer-to-peer multi-agent runtime

That constraint is what keeps the feature compatible with TramAI's backend-first identity.
