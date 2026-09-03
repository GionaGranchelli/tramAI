package dev.tramai.core.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.util.ResourceBundle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProviderFailuresTest {
    private val secretFixture = "sk-secret-222 /path/customer/alice SELECT * FROM users bearer-token-xyz"

    @Test
    fun `applyTramaiTimeout copies request timeout onto http request`() {
        val request =
            ModelRequest(
                model = "gpt-5.1-chat-latest",
                messages = listOf(Message(MessageRole.USER, "hello")),
                timeoutMillis = 1_500,
            )

        val httpRequest =
            HttpRequest
                .newBuilder(URI("https://example.com"))
                .applyTramaiTimeout(request)
                .build()

        assertThat(httpRequest.timeout()).hasValueSatisfying { timeout ->
            assertThat(timeout.toMillis()).isEqualTo(1_500)
        }
    }

    @Test
    fun `provider http failure preserves legacy shape and safe metadata`() {
        val error = providerHttpFailure("openai", 429, "secret body", "2")

        assertThat(error.statusCode).isEqualTo(429)
        assertThat(error.retryable).isTrue()
        assertThat(error.retryAfterMillis).isEqualTo(2_000)
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
        assertThat(error.message).isEqualTo("Provider request failed with HTTP 429")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `provider http failure marks transient statuses as retryable`() {
        assertThat(providerHttpFailure("openai", 429, "body").retryable).isTrue()
    }

    @Test
    fun `provider http failure captures retry after hints`() {
        assertThat(providerHttpFailure("openai", 429, "body", "2").retryAfterMillis).isEqualTo(2_000)
    }

    @Test
    fun `provider http failure marks permanent statuses as non retryable`() {
        assertThat(providerHttpFailure("openai", 401, "body").retryable).isFalse()
    }

    @Test
    fun `provider http failure never exposes the response body in the public exception`() {
        val error = providerHttpFailure("openai", 500, "body $secretFixture")

        assertThat(error.message).isEqualTo("Provider request failed with HTTP 500")
        assertThat(error.message).doesNotContain(secretFixture)
        assertThat(error.cause).isNull()
    }

    @Test
    fun `observed http failure delivers bounded preview and alias`() {
        runBlocking {
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()
            val oversized = "x".repeat(PROVIDER_ERROR_BODY_LIMIT_BYTES + 100)

            val error =
                providerHttpFailureObserved(
                    providerId = "openai",
                    providerAlias = "customer-openai",
                    statusCode = 500,
                    body = oversized,
                    observer = ProviderFailureDiagnosticObserver(events::add),
                )

            assertThat(error.message).isEqualTo("Provider request failed with HTTP 500")
            val event = events.single()
            assertThat(event.providerId).isEqualTo("openai")
            assertThat(event.providerAlias).isEqualTo("customer-openai")
            assertThat(event.statusCode).isEqualTo(500)
            assertThat(event.retryable).isTrue()
            assertThat(event.httpBodyPreview!!.toByteArray()).hasSizeLessThanOrEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES)
            assertThat(event.httpBodyPreviewTruncated).isTrue()
            assertThat(event.failure).isNull()
        }
    }

    @Test
    fun `provider http failure delivers a bounded body preview to the diagnostic observer`() {
        runBlocking {
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()

            val error =
                providerHttpFailureObserved(
                    providerId = "openai",
                    statusCode = 429,
                    body = "x".repeat(PROVIDER_ERROR_BODY_LIMIT_BYTES + 100),
                    observer = ProviderFailureDiagnosticObserver(events::add),
                )

            assertThat(error.statusCode).isEqualTo(429)
            assertThat(error.retryable).isTrue()
            assertThat(events.single().httpBodyPreview!!.toByteArray())
                .hasSizeLessThanOrEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES)
            assertThat(events.single().httpBodyPreviewTruncated).isTrue()
        }
    }

    @Test
    fun `observed http failure preserves caller truncation flag`() {
        runBlocking {
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()

            providerHttpFailureObserved(
                providerId = "openai",
                statusCode = 429,
                body = "small preview",
                bodyTruncated = true,
                observer = ProviderFailureDiagnosticObserver(events::add),
            )

            assertThat(events.single().httpBodyPreviewTruncated).isTrue()
        }
    }

    @Test
    fun `read error body preview bounds a large single line and retains its prefix`() {
        val input = "useful-prefix:" + "x".repeat(100 * 1024)

        val preview = readErrorBodyPreview(ByteArrayInputStream(input.toByteArray()))

        assertThat(preview.truncated).isTrue()
        assertThat(preview.text).startsWith("useful-prefix:")
        assertThat(preview.text.toByteArray()).hasSizeLessThanOrEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES)
    }

    @Test
    fun `read error body preview keeps multibyte utf8 within the byte cap`() {
        val input = "€".repeat(PROVIDER_ERROR_BODY_LIMIT_BYTES + 1)

        val preview = readErrorBodyPreview(ByteArrayInputStream(input.toByteArray()))

        assertThat(preview.truncated).isTrue()
        assertThat(preview.text.toByteArray()).hasSizeLessThanOrEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES)
    }

    @Test
    fun `read error body preview distinguishes exact limit from one byte over`() {
        val exact = readErrorBodyPreview(ByteArrayInputStream(ByteArray(PROVIDER_ERROR_BODY_LIMIT_BYTES) { 'a'.code.toByte() }))
        val over = readErrorBodyPreview(ByteArrayInputStream(ByteArray(PROVIDER_ERROR_BODY_LIMIT_BYTES + 1) { 'b'.code.toByte() }))

        assertThat(exact.truncated).isFalse()
        assertThat(exact.text.toByteArray()).hasSize(PROVIDER_ERROR_BODY_LIMIT_BYTES)
        assertThat(over.truncated).isTrue()
        assertThat(over.text.toByteArray()).hasSize(PROVIDER_ERROR_BODY_LIMIT_BYTES)
    }

    @Test
    fun `read error body preview consumes no more than limit plus sentinel byte`() {
        val input = CountingInputStream(ByteArray(100 * 1024) { 'z'.code.toByte() })

        val preview = readErrorBodyPreview(input)

        assertThat(preview.truncated).isTrue()
        assertThat(input.bytesRead).isEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES + 1)
        assertThat(input.closed).isTrue()
    }

    @Test
    fun `transport classifications use fixed messages without causes`() {
        val cases =
            listOf(
                HttpTimeoutException("timeout $secretFixture") to
                    Triple("Provider request timed out", ProviderFailureCode.TIMEOUT, true),
                ConnectException("connect $secretFixture") to
                    Triple("Provider connection failed", ProviderFailureCode.CONNECTION_FAILED, true),
                IOException("io $secretFixture") to Triple("Provider transport failed", ProviderFailureCode.TRANSPORT_FAILED, true),
                IllegalStateException("unexpected $secretFixture") to
                    Triple("Provider request failed", ProviderFailureCode.UNEXPECTED_FAILURE, false),
            )

        cases.forEach { (original, expected) ->
            val error = providerTransportFailure("openai", original)
            assertThat(error.message).isEqualTo(expected.first)
            assertThat(error.failureCode).isEqualTo(expected.second)
            assertThat(error.retryable).isEqualTo(expected.third)
            assertThat(error.message).doesNotContain(secretFixture)
            assertThat(error.cause).isNull()
        }
    }

    @Test
    fun `provider transport failure marks timeout as retryable with fixed message and no cause`() {
        val error = providerTransportFailure("ollama", HttpTimeoutException("timeout $secretFixture"))
        assertThat(error.retryable).isTrue()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.TIMEOUT)
        assertThat(error.message).isEqualTo("Provider request timed out")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `provider transport failure marks connection failures as retryable`() {
        val error = providerTransportFailure("anthropic", ConnectException("refused"))
        assertThat(error.retryable).isTrue()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.CONNECTION_FAILED)
        assertThat(error.message).isEqualTo("Provider connection failed")
    }

    @Test
    fun `provider transport failure marks io failures as retryable`() {
        val error = providerTransportFailure("openai", IOException("socket closed"))
        assertThat(error.retryable).isTrue()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.TRANSPORT_FAILED)
        assertThat(error.message).isEqualTo("Provider transport failed")
    }

    @Test
    fun `unchecked io failure is classified as retryable transport failure and observed`() {
        runBlocking {
            val original = UncheckedIOException(IOException("socket closed"))
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()

            val error =
                providerTransportFailureObserved(
                    "openai",
                    original,
                    ProviderFailureDiagnosticObserver(events::add),
                )

            assertThat(error.failureCode).isEqualTo(ProviderFailureCode.TRANSPORT_FAILED)
            assertThat(error.retryable).isTrue()
            assertThat(error.message).isEqualTo("Provider transport failed")
            assertThat(error.cause).isNull()
            assertThat(events.single().failure).isSameAs(original)
        }
    }

    @Test
    fun `provider transport failure leaves unexpected failures non retryable`() {
        val error = providerTransportFailure("openai", IllegalStateException("boom"))
        assertThat(error.retryable).isFalse()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.UNEXPECTED_FAILURE)
        assertThat(error.message).isEqualTo("Provider request failed")
    }

    @Test
    fun `provider transport failure never exposes throwable message and retains no cause`() {
        val error = providerTransportFailure("openai", IOException("connection to $secretFixture refused"))
        assertThat(error.message).doesNotContain(secretFixture)
        assertThat(error.cause).isNull()
    }

    @Test
    fun `untrusted provider exception is sanitized and delivered only to observer`() {
        runBlocking {
            val originalCause = IllegalStateException("cause $secretFixture")
            val original =
                ProviderException(
                    message = "message $secretFixture",
                    cause = originalCause,
                    statusCode = 429,
                    retryable = true,
                    retryAfterMillis = 2_000,
                )
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()

            val result =
                providerTransportFailureObserved(
                    "openai",
                    original,
                    ProviderFailureDiagnosticObserver(events::add),
                )

            assertThat(result).isNotSameAs(original)
            assertThat(result.message).isEqualTo("Provider request failed with HTTP 429")
            assertThat(result.cause).isNull()
            assertThat(result.statusCode).isEqualTo(429)
            assertThat(result.retryable).isTrue()
            assertThat(result.retryAfterMillis).isEqualTo(2_000)
            assertThat(result.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
            assertThat(events.single().failure).isSameAs(original)
        }
    }

    @Test
    fun `safe provider failure passes through transport boundary unchanged`() {
        runBlocking {
            val trusted = safeProviderFailure("trusted caller text", ProviderFailureCode.UNEXPECTED_FAILURE)
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()

            val result =
                providerTransportFailureObserved(
                    "openai",
                    trusted,
                    ProviderFailureDiagnosticObserver(events::add),
                )

            assertThat(result).isSameAs(trusted)
            assertThat(events).isEmpty()
        }
    }

    @Test
    fun `original transport throwable reaches only the observer`() {
        runBlocking {
            val original = IOException("raw $secretFixture")
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()

            val result =
                providerTransportFailureObserved(
                    "openai",
                    original,
                    ProviderFailureDiagnosticObserver(events::add),
                )

            assertThat(result.cause).isNull()
            assertThat(events.single().failure).isSameAs(original)
            assertThat(events.single().code).isEqualTo(ProviderFailureCode.TRANSPORT_FAILED)
        }
    }

    @Test
    fun `cancellation input is rethrown without diagnostics`() {
        runBlocking {
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()
            val cancellation = CancellationException("parent cancelled")

            val thrown =
                assertFailsWith<CancellationException> {
                    providerTransportFailureObserved(
                        "openai",
                        cancellation,
                        ProviderFailureDiagnosticObserver(events::add),
                    )
                }

            assertThat(thrown).isSameAs(cancellation)
            assertThat(events).isEmpty()
        }
    }

    @Test
    fun `observer cancellation while job is active leaves provider failure primary`() {
        runBlocking {
            val result =
                async {
                    providerTransportFailureObserved(
                        "openai",
                        IOException("boom"),
                        ProviderFailureDiagnosticObserver { throw CancellationException("observer bug") },
                    )
                }.await()

            assertThat(result.message).isEqualTo("Provider transport failed")
            assertThat(result.cause).isNull()
        }
    }

    @Test
    fun `parent cancellation during observer delivery remains primary`() {
        runBlocking {
            val continued = AtomicBoolean(false)
            val task =
                async {
                    val job = coroutineContext.job
                    providerTransportFailureObserved(
                        "openai",
                        IOException("boom"),
                        ProviderFailureDiagnosticObserver {
                            job.cancel(CancellationException("parent cancelled"))
                            throw CancellationException("observer observed cancellation")
                        },
                    )
                    continued.set(true)
                }

            assertFailsWith<CancellationException> { task.await() }
            assertThat(continued).isFalse()
        }
    }

    @Test
    fun `ordinary observer failure is fail open`() {
        runBlocking {
            val result =
                providerTransportFailureObserved(
                    "openai",
                    IOException("boom"),
                    ProviderFailureDiagnosticObserver { throw IllegalStateException("observer bug") },
                )

            assertThat(result.message).isEqualTo("Provider transport failed")
        }
    }

    @Test
    fun `legacy transport failure rethrows cancellation input unchanged`() {
        val cancellation = CancellationException("parent cancelled")

        val thrown =
            assertFailsWith<CancellationException> {
                providerTransportFailure("openai", cancellation)
            }

        assertThat(thrown).isSameAs(cancellation)
    }

    @Test
    fun `legacy transport failure passes safe factory failures through unchanged`() {
        val trusted = safeProviderFailure("trusted caller text", ProviderFailureCode.UNEXPECTED_FAILURE)

        val result = providerTransportFailure("openai", trusted)

        assertThat(result).isSameAs(trusted)
    }

    @Test
    fun `http failure debug log emits only when debug is loggable`() {
        val logged = mutableListOf<String>()
        val logger =
            object : System.Logger {
                override fun getName(): String = "test"

                override fun isLoggable(level: System.Logger.Level): Boolean = level == System.Logger.Level.DEBUG

                override fun log(
                    level: System.Logger.Level,
                    resourceBundle: ResourceBundle?,
                    msg: String,
                    params: Array<Any?>?,
                ) {
                    logged += msg
                }

                override fun log(
                    level: System.Logger.Level,
                    resourceBundle: ResourceBundle?,
                    msg: String,
                    thrown: Throwable?,
                ) {
                    logged += msg
                }
            }

        logProviderHttpFailureDebug(logger, "openai", 503, "overloaded")
        assertThat(logged).hasSize(1)
        assertThat(logged.single()).contains("HTTP_REJECTED").contains("503").contains("retryable=true")

        // Non-debug-capable logger stays silent.
        val silent =
            object : System.Logger {
                override fun getName(): String = "test"

                override fun isLoggable(level: System.Logger.Level): Boolean = false

                override fun log(
                    level: System.Logger.Level,
                    resourceBundle: ResourceBundle?,
                    msg: String,
                    params: Array<Any?>?,
                ) {
                    // no-op: the DEBUG-capable logger records through its own override
                }

                override fun log(
                    level: System.Logger.Level,
                    resourceBundle: ResourceBundle?,
                    msg: String,
                    thrown: Throwable?,
                ) {
                    // no-op: overload never invoked by logProviderHttpFailureDebug
                }
            }
        logged.clear()
        logProviderHttpFailureDebug(silent, "openai", 503, "overloaded")
        assertThat(logged).isEmpty()

        // Null logger (the legacy default) stays silent too.
        logProviderHttpFailureDebug(null, "openai", 503, "overloaded")
        assertThat(logged).isEmpty()
    }

    @Test
    fun `read error body preview handles a stream that reports zero-length bulk reads`() {
        // An InputStream whose bulk read temporarily reports 0 (count == 0 path)
        // must still drain via single-byte reads without losing or duplicating bytes.
        // Includes a NUL byte (0x00) so a wrong EOF comparison on the single-byte
        // fallback cannot treat real data as end-of-stream.
        val data = byteArrayOf('a'.code.toByte(), 0, 'c'.code.toByte(), 'd'.code.toByte(), 'e'.code.toByte())
        val stream =
            object : InputStream() {
                private var position = 0

                override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

                override fun read(
                    target: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = 0 // always report zero bulk reads
            }

        val preview = readErrorBodyPreview(stream, limitBytes = 10)

        assertThat(preview.text).isEqualTo("a\u0000cde")
        assertThat(preview.truncated).isFalse()
    }

    @Test
    fun `read error body preview accepts a zero byte limit`() {
        val preview = readErrorBodyPreview(ByteArrayInputStream("abc".toByteArray()), limitBytes = 0)

        assertThat(preview.text).isEmpty()
        assertThat(preview.truncated).isTrue()
    }

    @Test
    fun `read error body preview trims a multibyte character split by the byte cap`() {
        // (LIMIT-1) ASCII bytes then one euro sign: the cap cuts inside the
        // multibyte character, so naive UTF-8 decoding would overshoot the byte
        // limit and the trim loop must pull it back under.
        val input = "a".repeat(PROVIDER_ERROR_BODY_LIMIT_BYTES - 1) + "€"

        val preview = readErrorBodyPreview(ByteArrayInputStream(input.toByteArray()))

        assertThat(preview.truncated).isTrue()
        assertThat(preview.text.toByteArray()).hasSizeLessThanOrEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES)
    }

    private class CountingInputStream(
        bytes: ByteArray,
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var bytesRead: Int = 0
            private set
        var closed: Boolean = false
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

        override fun read(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int = delegate.read(target, offset, length).also { if (it > 0) bytesRead += it }

        override fun close() {
            closed = true
            delegate.close()
        }
    }
}
