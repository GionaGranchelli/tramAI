package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.core.retry.DefaultRetryJitterSource
import dev.tramai.core.retry.RetryJitterSource

internal sealed interface ProviderRetryDecision {
    data class Retry(val delayMillis: Long, val delaySource: String) : ProviderRetryDecision
    data object Stop : ProviderRetryDecision
}

internal open class ProviderRetryPolicy(private val retryDelayPolicy: ProviderRetryDelayPolicy) {
    open fun decide(error: Throwable, retryIndex: Int, maxAttempts: Int): ProviderRetryDecision {
        if (retryIndex >= maxAttempts - 1 || !isRetryable(error)) return ProviderRetryDecision.Stop
        val fallbackDelayMillis = minOf(50L shl retryIndex, 1_000L)
        return ProviderRetryDecision.Retry(
            delayMillis = retryDelayPolicy.delayMillis(error, fallbackDelayMillis),
            delaySource = if (error is ProviderException && error.retryAfterMillis != null) "retry_after" else "backoff",
        )
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is TimeoutException -> true
        is ProviderException -> error.retryable
        else -> false
    }
}

internal class ProviderRetryDelayPolicy(
    private val settings: RetryPolicySettings,
    private val retryJitterSource: RetryJitterSource = DefaultRetryJitterSource,
) {
    fun delayMillis(error: Throwable, fallbackDelayMillis: Long): Long {
        val cappedBaseDelay = if (error is ProviderException && error.retryAfterMillis != null) {
            minOf(requireNotNull(error.retryAfterMillis), settings.maxRetryAfterMillis)
        } else fallbackDelayMillis
        val jitterSample = retryJitterSource.nextDouble()
        require(jitterSample >= 0.0 && jitterSample < 1.0) {
            "Retry jitter sample must be in [0.0, 1.0), got $jitterSample"
        }
        return cappedBaseDelay + (cappedBaseDelay * settings.jitterRatio * jitterSample).toLong()
    }
}
