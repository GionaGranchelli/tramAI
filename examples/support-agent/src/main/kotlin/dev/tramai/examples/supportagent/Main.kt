package dev.tramai.examples.supportagent

import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.TramaiTool
import dev.tramai.ollama.OllamaProvider
import dev.tramai.standalone.Tramai
import dev.tramai.standalone.create
import kotlinx.coroutines.runBlocking

val lookupOrderTool = object : TramaiTool<String, String> {
    override val name = "lookupOrder"
    override val description = "Look up an order by ID"
    override val inputType = String::class
    override suspend fun execute(input: String, ctx: ToolExecutionContext): String =
        "Order $input: shipped on 2026-04-15"
}

fun main() = runBlocking {
    val agent = Tramai.builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma4:e2b", "ollama")
        .tools(lookupOrderTool)
        .build()
        .create<SupportAgent>()

    val result = agent.handle("Where is my order #ORD-42?")
    println("Answer: ${result.answer}")
    result.action?.let { println("Action: $it") }
}
