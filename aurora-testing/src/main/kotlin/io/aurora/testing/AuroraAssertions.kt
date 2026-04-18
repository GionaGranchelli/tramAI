package io.aurora.testing

import org.assertj.core.api.Assertions.assertThat
import kotlin.reflect.KClass

/**
 * Assertion entry point for Aurora integration-style tests.
 */
object AuroraAssertions {
    /**
     * Creates a fluent assertion scope backed by the mock provider and recording observer.
     */
    fun assertThat(
        provider: RecordedRequestProvider,
        observer: RecordingOperationObserver,
    ): AuroraAssertion = AuroraAssertion(provider, observer)
}

/**
 * Fluent assertion scope for one provider and observer pair.
 */
class AuroraAssertion internal constructor(
    private val provider: RecordedRequestProvider,
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
    private val provider: RecordedRequestProvider,
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
     * Asserts that at least one attempt for the method observed a structured parse failure.
     */
    fun andObservedParseFailure(): MethodAssertion = apply {
        assertThat(observer.callRecords.filter { it.context.methodName == methodName })
            .anySatisfy { record -> assertThat(record.parseFailureSummary).isNotBlank() }
    }

    /**
     * Asserts that at least one observed provider failure matches the expected type.
     */
    fun andObservedFailure(errorType: KClass<out Throwable>): MethodAssertion = apply {
        assertThat(observer.callRecords.filter { it.context.methodName == methodName })
            .anySatisfy { record -> assertThat(record.providerFailure).isInstanceOf(errorType.java) }
    }

    /**
     * Asserts that the final observed provider failure matches the expected type.
     */
    fun andFailedWith(errorType: KClass<out Throwable>): MethodAssertion = apply {
        val finalRecord = observer.callRecords.last { it.context.methodName == methodName }
        assertThat(finalRecord.providerFailure).isInstanceOf(errorType.java)
    }

    /**
     * Asserts that every attempt for the method used the given provider id.
     */
    fun emittedProvider(providerId: String): MethodAssertion = apply {
        assertThat(observer.callRecords.filter { it.context.methodName == methodName })
            .allSatisfy { record -> assertThat(record.context.providerId).isEqualTo(providerId) }
    }

    /**
     * Asserts that at least one tool call was emitted with the given [toolName].
     */
    fun andCalledTool(toolName: String): MethodAssertion = apply {
        val toolCalls = provider.requests
            .filter { it.operationMethod == methodName }
            .flatMap { it.messages }
            .flatMap { it.toolCalls.orEmpty() }
        
        assertThat(toolCalls).anySatisfy { tc -> assertThat(tc.name).isEqualTo(toolName) }
    }

    /**
     * Asserts how many tool calls were emitted with the given [toolName].
     */
    fun andCalledToolTimes(toolName: String, expectedTimes: Int): MethodAssertion = apply {
        val toolCalls = provider.requests
            .filter { it.operationMethod == methodName }
            .flatMap { it.messages }
            .flatMap { it.toolCalls.orEmpty() }
            .count { it.name == toolName }
            
        assertThat(toolCalls).isEqualTo(expectedTimes)
    }
}
