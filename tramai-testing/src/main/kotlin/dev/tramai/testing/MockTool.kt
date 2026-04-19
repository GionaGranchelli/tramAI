package dev.tramai.testing

import dev.tramai.core.model.TramaiTool
import dev.tramai.core.model.ToolExecutionContext
import kotlin.reflect.KClass

/**
 * Mock tool for testing model-tool interactions.
 */
class MockTool<I : Any, O : Any>(
    override val name: String,
    override val description: String,
    override val inputType: KClass<I>,
    private val responder: suspend (I) -> O
) : TramaiTool<I, O> {
    
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
