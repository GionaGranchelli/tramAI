package dev.tramai.testing

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.standalone.Tramai
import dev.tramai.standalone.create
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test
import kotlin.test.assertEquals

class TestingModuleTest {

    @Test
    fun `mock provider returns configured method responses and records requests`() {
        val provider = MockAiProvider {
            onMethod("analyze") respondWith """{"status":"ok"}"""
        }
        val observer = RecordingOperationObserver()
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            observer(observer)
        }
        val service = tramai.create<TestStructuredService>()

        val result = runBlocking { service.analyze("invoice-1") }

        assertEquals(Status("ok"), result)
        TramaiAssertions.assertThat(provider, observer)
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
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            observer(observer)
        }
        val service = tramai.create<TestStructuredService>()

        val result = runBlocking { service.analyze("invoice-1") }

        assertEquals(Status("ok"), result)
        TramaiAssertions.assertThat(provider, observer)
            .whenCalled("analyze")
            .wasCalledTimes(2)
            .andRetried(1)
            .andParsedSuccessfully()
    }

    @Test
    fun `simulated failure provider can drive retryable provider recovery`() {
        val provider = SimulatedFailureProvider {
            onMethod("summarize").retryableFailure("rate limited", statusCode = 429)
            onMethod("summarize") respondWith "recovered summary"
        }
        val observer = RecordingOperationObserver()
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "simulated-failure")
            observer(observer)
        }
        val service = tramai.create<TestRawService>()

        val result = runBlocking { service.summarize("invoice-1") }

        assertEquals("recovered summary", result)
        TramaiAssertions.assertThat(provider, observer)
            .whenCalled("summarize")
            .wasCalledTimes(2)
            .andRetried(1)
            .andObservedFailure(ProviderException::class)
            .emittedProvider("simulated-failure")
    }

    @Test
    fun `simulated failure provider surfaces non retryable failures immediately`() {
        val provider = SimulatedFailureProvider {
            onMethod("summarize").nonRetryableFailure("unauthorized", statusCode = 401)
        }
        val observer = RecordingOperationObserver()
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "simulated-failure")
            observer(observer)
        }
        val service = tramai.create<TestRawService>()

        assertThatThrownBy { runBlocking { service.summarize("invoice-1") } }
            .isInstanceOfSatisfying(ProviderException::class.java) { error ->
                kotlin.test.assertEquals(401, error.statusCode)
                kotlin.test.assertFalse(error.retryable)
            }

        TramaiAssertions.assertThat(provider, observer)
            .whenCalled("summarize")
            .wasCalledTimes(1)
            .andRetried(0)
            .andFailedWith(ProviderException::class)
    }

    @Test
    fun `mock provider captures exhausted structured parse failures`() {
        val provider = MockAiProvider {
            onMethod("analyze") respondWith "not json"
            onMethod("analyze") respondWith "still not json"
            onMethod("analyze") respondWith "still broken"
        }
        val observer = RecordingOperationObserver()
        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "mock")
            observer(observer)
        }
        val service = tramai.create<TestStructuredService>()

        assertThatThrownBy { runBlocking { service.analyze("invoice-1") } }
            .isInstanceOfSatisfying(StructuredOutputException::class.java) { error ->
                kotlin.test.assertEquals(3, error.attemptCount)
                kotlin.test.assertEquals("Analyze the invoice", error.originalPrompt)
                kotlin.test.assertEquals("still broken", error.lastRawResponse)
            }

        TramaiAssertions.assertThat(provider, observer)
            .whenCalled("analyze")
            .wasCalledTimes(3)
            .andRetried(2)
            .andObservedParseFailure()
            .emittedProvider("mock")
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

@AiService
private interface TestRawService {
    @Operation(
        prompt = "Summarize the invoice",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun summarize(invoiceId: String): String
}
