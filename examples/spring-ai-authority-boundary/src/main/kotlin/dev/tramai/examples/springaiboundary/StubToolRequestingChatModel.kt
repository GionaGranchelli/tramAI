package dev.tramai.examples.springaiboundary

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Spring AI application side of the proof.
 *
 * The application stays Spring AI-owned: it owns the conversation, the prompt and the tool registration.
 * Its provider is a deterministic local test provider, which the XR1 checkpoint explicitly permits. What
 * is *not* substitutable is the enforcement boundary — the tool callbacks handed to the framework are the
 * real governed ones, and the framework's own tool-calling machinery dispatches through them.
 *
 * This stub exists so the invocation is driven by Spring AI rather than by the test calling the callback
 * directly: it requests the tool once, then reports a final message after the framework returns the tool
 * result. It never invokes the tool itself, and it never decides anything.
 */
class StubToolRequestingChatModel(
    private val requestedToolName: String,
    private val requestedArguments: String,
) : ChatModel {
    private val toolResultHandedBack = CopyOnWriteArrayList<Boolean>()

    /** True once the framework has handed a tool result back for the model to continue from. */
    fun receivedToolResult(): Boolean = toolResultHandedBack.isNotEmpty()

    /** Prompts the model was asked to answer, in order. Used to assert the loop actually ran. */
    private val promptsSeen = CopyOnWriteArrayList<Prompt>()

    fun promptsSeen(): List<Prompt> = promptsSeen.toList()

    override fun call(prompt: Prompt): ChatResponse {
        promptsSeen += prompt
        val toolResultPresent = prompt.instructions.any { it is ToolResponseMessage }
        if (toolResultPresent) {
            toolResultHandedBack += true
            return ChatResponse(listOf(Generation(AssistantMessage("workflow complete"))))
        }
        // The model proposes the action. It does not perform it: the framework will dispatch it.
        val call = AssistantMessage.ToolCall(CALL_ID, TOOL_CALL_TYPE, requestedToolName, requestedArguments)
        return ChatResponse(listOf(Generation(AssistantMessage("", emptyMap(), listOf(call)))))
    }

    /**
     * A tool-capable model declares tool options. This is not a test convenience: Spring AI only runs its
     * tool-calling machinery when the request options implement `ToolCallingChatOptions` with internal
     * execution enabled, and a model that returns ordinary options is a model whose tools are never
     * dispatched. Measured here: without this override both dispatch proofs recorded zero decisions.
     */
    override fun getDefaultOptions(): ChatOptions = ToolCallingChatOptions.builder().internalToolExecutionEnabled(true).build()

    private companion object {
        const val CALL_ID = "call-xr1-1"

        /** Spring AI's tool-call type discriminator for a function that is invoked with a local callback. */
        const val TOOL_CALL_TYPE = "function"
    }
}
