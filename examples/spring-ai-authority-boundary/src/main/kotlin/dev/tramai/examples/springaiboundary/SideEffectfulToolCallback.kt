package dev.tramai.examples.springaiboundary

import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The deterministic, side-effect-observable tool the boundary governs.
 *
 * The observed effect is the invocation ledger: an action that was prevented leaves **no** entry, so a
 * test can distinguish "the framework called it and governance allowed it" from "the framework called it
 * and nothing happened". That distinction is the whole point of the proof — an implementation that
 * merely observed the call would still fill this ledger.
 */
class SideEffectfulToolCallback(
    private val name: String = TOOL_NAME,
    private val description: String = "Appends one record to the side-effect ledger",
) : ToolCallback {
    private val invocations = CopyOnWriteArrayList<String>()

    /** The observable side effect. Empty means the operation never ran. */
    fun invocations(): List<String> = invocations.toList()

    override fun getToolDefinition(): ToolDefinition =
        ToolDefinition
            .builder()
            .name(name)
            .description(description)
            .inputSchema(INPUT_SCHEMA)
            .build()

    override fun call(toolInput: String): String {
        invocations += toolInput
        return """{"applied":true,"ledgerSize":${invocations.size}}"""
    }

    companion object {
        const val TOOL_NAME = "ledger_append"

        private const val INPUT_SCHEMA =
            """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}"""
    }
}
