package io.aurora.testing

import io.aurora.core.annotations.AiService
import io.aurora.core.annotations.Operation
import io.aurora.standalone.Aurora
import io.aurora.standalone.create
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TestingModuleTest {

    @Test
    fun `mock provider returns configured method responses and records requests`() {
        val provider = MockAiProvider {
            onMethod("analyze") respondWith """{"status":"ok"}"""
        }
        val observer = RecordingOperationObserver()
        val aurora = Aurora {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            observer(observer)
        }
        val service = aurora.create<TestStructuredService>()

        val result = runBlocking { service.analyze("invoice-1") }

        assertEquals(Status("ok"), result)
        AuroraAssertions.assertThat(provider, observer)
            .whenCalled("analyze")
            .wasCalledTimes(1)
            .andRetried(0)
            .andParsedSuccessfully()
            .emittedProvider("mock")
    }

    @Test
    fun `mock provider can drive retry scenarios with sequenced responses`() {
        val provider = MockAiProvider {
            onMethod("analyze") respondWith "not json"
            onMethod("analyze") respondWith """{"status":"ok"}"""
        }
        val observer = RecordingOperationObserver()
        val aurora = Aurora {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            observer(observer)
        }
        val service = aurora.create<TestStructuredService>()

        val result = runBlocking { service.analyze("invoice-1") }

        assertEquals(Status("ok"), result)
        AuroraAssertions.assertThat(provider, observer)
            .whenCalled("analyze")
            .wasCalledTimes(2)
            .andRetried(1)
            .andParsedSuccessfully()
    }
}

@AiService
private interface TestStructuredService {
    @Operation(
        prompt = "Analyze the invoice",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun analyze(invoiceId: String): Status
}

private data class Status(
    val status: String,
)
