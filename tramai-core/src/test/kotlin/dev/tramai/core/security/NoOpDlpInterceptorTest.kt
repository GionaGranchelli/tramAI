package dev.tramai.core.security

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class NoOpDlpInterceptorTest {

    private val interceptor = NoOpDlpInterceptor

    @Test
    fun `returns exact text`() {
        val result = interceptor.inspect(
            DlpContext(contentType = DlpContentType.MODEL_OUTPUT, operationInterface = "test", operationMethod = "m", correlationId = "c1"),
            "hello world"
        )
        assertThat(result.sanitizedText).isEqualTo("hello world")
    }

    @Test
    fun `has no redactions`() {
        val result = interceptor.inspect(
            DlpContext(contentType = DlpContentType.MODEL_OUTPUT, operationInterface = "test", operationMethod = "m", correlationId = "c2"),
            "hello"
        )
        assertThat(result.redactions).isEmpty()
    }

    @Test
    fun `modified is false`() {
        val result = interceptor.inspect(
            DlpContext(contentType = DlpContentType.MODEL_OUTPUT, operationInterface = "test", operationMethod = "m", correlationId = "c3"),
            "hello"
        )
        assertThat(result.modified).isFalse()
    }

    @Test
    fun `works with all content types`() {
        for (ct in DlpContentType.entries) {
            val result = interceptor.inspect(
                DlpContext(contentType = ct, operationInterface = "test", operationMethod = "m", correlationId = "c4"),
                "some text"
            )
            assertThat(result.sanitizedText).isEqualTo("some text")
            assertThat(result.modified).isFalse()
        }
    }
}
