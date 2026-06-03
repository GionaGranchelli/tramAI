package dev.tramai.core.security

import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification

/**
 * Content type for DLP scanning, distinguishing model outputs from tool results.
 */
enum class DlpContentType {
    MODEL_OUTPUT,
    TOOL_RESULT,
}

/**
 * Execution context for a DLP inspection, providing metadata about the operation
 * that produced the text being scanned.
 */
data class DlpContext(
    val contentType: DlpContentType,
    val operationInterface: String,
    val operationMethod: String,
    val providerId: String? = null,
    val modelName: String? = null,
    val toolName: String? = null,
    val correlationId: String,
    val dataClassification: DataClassification? = null,
    val classificationSource: ClassificationSource? = null,
)

/**
 * Summary of a single DLP redaction. Contains the rule identifier and replacement
 * count but deliberately excludes any raw matched values or input text to prevent
 * accidental sensitive data leakage through audit or exception paths.
 */
data class DlpRedaction(
    val ruleId: String,
    val replacementCount: Int,
)

/**
 * Result of a DLP inspection.
 *
 * [sanitizedText] is authoritative. [redactions] optionally provides
 * rule-level evidence when available. [hasRedactions] reports whether
 * such evidence entries are present.
 */
data class DlpResult(
    val sanitizedText: String,
    val redactions: List<DlpRedaction> = emptyList(),
) {
    val hasRedactions: Boolean get() = redactions.isNotEmpty()
}

/**
 * Exception thrown when DLP inspection fails. This is distinct from provider
 * failures — a DLP failure does not count toward provider circuit breakers
 * and does not trigger fallback or retry.
 */
class DlpInspectionException(
    message: String = "DLP inspection failed",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Service-provider interface for DLP (Data Loss Prevention) interceptors.
 *
 * Implementations inspect and optionally sanitize model outputs and tool results
 * before they reach downstream consumers, structured parsers, or cache storage.
 */
fun interface DlpInterceptor {
    /**
     * Inspect and optionally sanitize [text] within the given [context].
     *
     * @return a [DlpResult] describing any redactions that were applied.
     */
    fun inspect(context: DlpContext, text: String): DlpResult
}

/**
 * No-op DLP interceptor that passes all text through unmodified.
 */
object NoOpDlpInterceptor : DlpInterceptor {
    override fun inspect(context: DlpContext, text: String): DlpResult = DlpResult(text)
}
