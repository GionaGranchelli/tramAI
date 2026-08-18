package dev.tramai.engine.invocation

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.security.DlpContentLocation
import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInspectionException
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.planning.ServiceDefinition
import kotlinx.coroutines.CancellationException

/**
 * Sanitizes provider responses through the DLP interceptor.
 *
 * Owns the authoritative model-output DLP scan: consistency validation
 * (sanitized-without-evidence, evidence-without-sanitized), redaction-audit
 * emission, and failure mapping to [DlpInspectionException]. The top-level
 * invocation coordinator only sequences execution; DLP enforcement lives here.
 */
internal class ProviderResponseDlpSanitizer(
    private val dlpInterceptor: DlpInterceptor,
    private val dlpRedactionAuditEmitter: DlpRedactionAuditEmitter,
    private val serviceDefinition: ServiceDefinition,
) {

    /**
     * Performs the authoritative DLP scan for a single response boundary.
     *
     * `text` must be the raw pre-DLP text for the specific scan boundary being inspected.
     */
    private suspend fun inspectDlpAuthoritatively(
        context: DlpContext,
        text: String,
    ) = dlpInterceptor.inspect(context, text).also { result ->
        val sanitizedTextChanged = result.sanitizedText != text
        val hasRedactionEvidence = result.redactions.isNotEmpty()

        if (sanitizedTextChanged && !hasRedactionEvidence && dlpRedactionAuditEmitter !== NoOpDlpRedactionAuditEmitter) {
            throw DlpInspectionException("DLP modified output without redaction evidence")
        }
        if (!sanitizedTextChanged && hasRedactionEvidence) {
            throw DlpInspectionException("DLP redactions reported without modifying output")
        }
        if (result.redactions.isNotEmpty()) {
            try {
                dlpRedactionAuditEmitter.emit(context, result.redactions)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                throw DlpInspectionException(
                    message = "DLP redaction audit emission failed",
                    cause = error,
                )
            }
        }
    }


    /**
     * Applies authoritative DLP inspection to model output without marking failures as provider failures.
     */
    internal suspend fun sanitizeProviderResponse(
        interceptedResponse: ModelResponse,
        operation: OperationDefinition,
        providerId: String,
        modelName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        observation: OperationObservation,
    ): ModelResponse = try {
        if (dlpInterceptor === NoOpDlpInterceptor) {
            interceptedResponse
        } else {
            applyProviderOutputDlp(
                interceptedResponse = interceptedResponse,
                operation = operation,
                providerId = providerId,
                modelName = modelName,
                correlationId = correlationId,
                securityContext = securityContext,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DlpInspectionException) {
        throw e
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        observation.onEngineEvent(
            name = "tramai.dlp.inspection_failed",
            attributes = mapOf("providerId" to providerId, "correlationId" to correlationId),
        )
        throw DlpInspectionException(
            message = "DLP inspection failed for provider '$providerId'",
            cause = e,
        )
    }

    /**
     * Builds the model-output DLP context and returns the sanitized response.
     */
    private suspend fun applyProviderOutputDlp(
        interceptedResponse: ModelResponse,
        operation: OperationDefinition,
        providerId: String,
        modelName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): ModelResponse {
        val dlpContext = DlpContext(
            contentType = DlpContentType.MODEL_OUTPUT,
            contentLocation = DlpContentLocation.MODEL_RESPONSE_CONTENT,
            operationInterface = serviceDefinition.serviceType.qualifiedName
                ?: serviceDefinition.serviceType.simpleName.orEmpty(),
            operationMethod = operation.method.name,
            providerId = providerId,
            modelName = modelName,
            correlationId = correlationId,
            dataClassification = securityContext.dataClassification,
            classificationSource = securityContext.classificationSource,
        )
        val dlpResult = inspectDlpAuthoritatively(dlpContext, interceptedResponse.content)
        return if (dlpResult.sanitizedText != interceptedResponse.content) {
            interceptedResponse.copy(content = dlpResult.sanitizedText)
        } else {
            interceptedResponse
        }
    }
}
