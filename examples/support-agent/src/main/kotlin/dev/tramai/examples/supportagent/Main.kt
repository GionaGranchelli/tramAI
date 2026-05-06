package dev.tramai.examples.supportagent

import dev.tramai.ollama.OllamaProvider
import dev.tramai.standalone.Tramai
import dev.tramai.standalone.create
import kotlinx.coroutines.runBlocking

/**
 * TramAI Support Agent — standalone example.
 *
 * Demonstrates:
 * - @AiService with @System/@User annotations
 * - Structured output via a typed data class
 * - Tool calling (lookupOrder, getCurrentTime)
 * - Deterministic testing with tramai-testing
 *
 * Prerequisites:
 * - Ollama running on localhost:11434
 * - Model "gemma4:e2b" pulled
 *
 * Run: ./gradlew run
 * Test: ./gradlew test
 */
fun main() = runBlocking {
    val agent = Tramai.builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma4:e2b", "ollama")
        .tools(lookupOrderTool, getCurrentTimeTool)
        .build()
        .create<SupportAgent>()

    println("═══ Support Agent Demo ═══")
    println()

    val result = agent.handle("Where is my order #ORD-42?")

    println("Answer: ${result.answer}")
    result.action?.let { println("Action: $it") }
    result.eta?.let { println("ETA: $it") }
    println("Resolved: ${if (result.resolved) "✅" else "❌"}")
}
