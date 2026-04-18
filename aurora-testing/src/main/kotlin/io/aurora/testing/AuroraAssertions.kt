package io.aurora.testing

import org.assertj.core.api.Assertions.assertThat

object AuroraAssertions {
    fun assertThat(
        provider: MockAiProvider,
        observer: RecordingOperationObserver,
    ): AuroraAssertion = AuroraAssertion(provider, observer)
}

class AuroraAssertion internal constructor(
    private val provider: MockAiProvider,
    private val observer: RecordingOperationObserver,
) {
    fun whenCalled(methodName: String): MethodAssertion = MethodAssertion(
        methodName = methodName,
        provider = provider,
        observer = observer,
    )
}

class MethodAssertion internal constructor(
    private val methodName: String,
    private val provider: MockAiProvider,
    private val observer: RecordingOperationObserver,
) {
    fun wasCalledTimes(expectedCalls: Int): MethodAssertion = apply {
        assertThat(provider.requests.count { it.operationMethod == methodName }).isEqualTo(expectedCalls)
    }

    fun andRetried(expectedRetries: Int): MethodAssertion = apply {
        val attempts = observer.callRecords.count { it.context.methodName == methodName }
        assertThat(attempts - 1).isEqualTo(expectedRetries)
    }

    fun andParsedSuccessfully(): MethodAssertion = apply {
        val finalRecord = observer.callRecords.last { it.context.methodName == methodName }
        assertThat(finalRecord.parseSuccess).isTrue()
    }

    fun emittedProvider(providerId: String): MethodAssertion = apply {
        assertThat(observer.callRecords.filter { it.context.methodName == methodName })
            .allSatisfy { record -> assertThat(record.context.providerId).isEqualTo(providerId) }
    }
}
