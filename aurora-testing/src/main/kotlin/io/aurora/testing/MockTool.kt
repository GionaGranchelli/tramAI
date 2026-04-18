package io.aurora.testing

import io.aurora.core.model.AuroraTool
import io.aurora.core.model.ToolExecutionContext
import kotlin.reflect.KClass

/**
 * Mock tool for testing model-tool interactions.
 */
class MockTool<I : Any, O : Any>(
    override val name: String,
    override val description: String,
    override val inputType: KClass<I>,
    private val responder: suspend (I) -> O
) : AuroraTool<I, O> {
    
    val calls = mutableListOf<I>()

    override suspend fun execute(input: I, context: ToolExecutionContext): O {
        calls.add(input)
        return responder(input)
    }

    companion object {
        /**
         * Creates a tool that always returns the same [response].
         */
        inline fun <reified I : Any, O : Any> fixed(
            name: String,
            description: String,
            response: O
        ): MockTool<I, O> = MockTool(name, description, I::class) { response }
    }
}
