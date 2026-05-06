package dev.tramai.examples.supportagent

import dev.tramai.examples.supportagent.lookupOrderTool
import dev.tramai.examples.supportagent.getCurrentTimeTool
import dev.tramai.standalone.Tramai
import dev.tramai.standalone.create
import dev.tramai.testing.MockAiProvider
import dev.tramai.testing.RecordingOperationObserver
import dev.tramai.testing.TramaiAssertions
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SupportAgentTest {

    @Test
    fun `happy path — mock returns valid structured response`() {
        val provider = MockAiProvider {
            onMethod("handle") respondWith """
                {
                    "answer": "Your order ORD-42 was shipped and is expected by 2026-04-18.",
                    "action": "looked up order",
                    "eta": "2026-04-18",
                    "resolved": true
                }
            """.trimIndent()
        }
        val observer = RecordingOperationObserver()
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            observer(observer)
            tools(lookupOrderTool, getCurrentTimeTool)
        }
        val agent = tramai.create<SupportAgent>()

        val result = runBlocking { agent.handle("Where is my order ORD-42?") }

        assertEquals("Your order ORD-42 was shipped and is expected by 2026-04-18.", result.answer)
        assertEquals("looked up order", result.action)
        assertEquals("2026-04-18", result.eta)
        assertEquals(true, result.resolved)

        TramaiAssertions.assertThat(provider, observer)
            .whenCalled("handle")
            .wasCalledTimes(1)
            .andRetried(0)
            .andParsedSuccessfully()
            .emittedProvider("mock")
    }

    @Test
    fun `retry — first response is invalid JSON, second is valid`() {
        val provider = MockAiProvider {
            onMethod("handle") respondWith "not valid json at all"
            onMethod("handle") respondWith """
                {
                    "answer": "Your order ORD-42 is being processed.",
                    "action": "escalated",
                    "eta": null,
                    "resolved": false
                }
            """.trimIndent()
        }
        val observer = RecordingOperationObserver()
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            observer(observer)
            tools(lookupOrderTool, getCurrentTimeTool)
        }
        val agent = tramai.create<SupportAgent>()

        val result = runBlocking { agent.handle("Where is my order ORD-42?") }

        assertEquals("Your order ORD-42 is being processed.", result.answer)
        assertEquals(false, result.resolved)

        TramaiAssertions.assertThat(provider, observer)
            .whenCalled("handle")
            .wasCalledTimes(2)
            .andRetried(1)
            .andParsedSuccessfully()
            .andObservedParseFailure()
    }

    @Test
    fun `exhausted retries — all responses are invalid JSON`() {
        val provider = MockAiProvider {
            onMethod("handle") respondWith "bad json 1"
            onMethod("handle") respondWith "bad json 2"
            onMethod("handle") respondWith "bad json 3"
        }
        val observer = RecordingOperationObserver()
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            observer(observer)
            tools(lookupOrderTool, getCurrentTimeTool)
        }
        val agent = tramai.create<SupportAgent>()

        val exception = runCatching { runBlocking { agent.handle("Where is my order?") } }
            .exceptionOrNull()

        assertEquals(true, exception != null, "Expected an exception when all retries are exhausted")
        TramaiAssertions.assertThat(provider, observer)
            .whenCalled("handle")
            .wasCalledTimes(3)
            .andRetried(2)
            .andObservedParseFailure()
    }
}
