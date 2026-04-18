package io.aurora.testing

import org.assertj.core.api.Assertions.assertThat

/**
 * Assertion entry point for Aurora integration-style tests.
 */
object AuroraAssertions {
    /**
     * Creates a fluent assertion scope backed by the mock provider and recording observer.
     */
    fun assertThat(
        provider: MockAiProvider,
        observer: RecordingOperationObserver,
    ): AuroraAssertion = AuroraAssertion(provider, observer)
}

/**
 * Fluent assertion scope for one provider and observer pair.
 */
class AuroraAssertion internal constructor(
    private val provider: MockAiProvider,
    private val observer: RecordingOperationObserver,
) {
    /**
     * Narrows the assertion scope to a single service method.
     */
    fun whenCalled(methodName: String): MethodAssertion = MethodAssertion(
        methodName = methodName,
        provider = provider,
        observer = observer,
    )
}

/**
 * Assertions over a single method's call records.
 */
class MethodAssertion internal constructor(
    private val methodName: String,
    private val provider: MockAiProvider,
    private val observer: RecordingOperationObserver,
) {
    /**
     * Asserts how many provider requests were emitted for the method.
     */
    fun wasCalledTimes(expectedCalls: Int): MethodAssertion = apply {
        assertThat(provider.requests.count { it.operationMethod == methodName }).isEqualTo(expectedCalls)
    }

    /**
     * Asserts how many retry attempts were observed after the initial call.
     */
    fun andRetried(expectedRetries: Int): MethodAssertion = apply {
        val attempts = observer.callRecords.count { it.context.methodName == methodName }
        assertThat(attempts - 1).isEqualTo(expectedRetries)
    }

    /**
     * Asserts that the final attempt completed with a successful structured parse.
     */
    fun andParsedSuccessfully(): MethodAssertion = apply {
        val finalRecord = observer.callRecords.last { it.context.methodName == methodName }
        assertThat(finalRecord.parseSuccess).isTrue()
    }

    /**
     * Asserts that every attempt for the method used the given provider id.
     */
    fun emittedProvider(providerId: String): MethodAssertion = apply {
        assertThat(observer.callRecords.filter { it.context.methodName == methodName })
            .allSatisfy { record -> assertThat(record.context.providerId).isEqualTo(providerId) }
    }
}
