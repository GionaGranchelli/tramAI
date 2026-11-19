package dev.tramai.security.governance

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolDefinition
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException

/**
 * Deterministic, fail-closed projector that derives a deployment-bound [ModelRequest]
 * from [canonicalInput] according to a P0-A1 [ProviderInputReleaseDecision].
 */
class ProviderInputProjector(
    minimizers: Map<MinimizationObligation, ObligationMinimizer> = emptyMap(),
) {
    private val minimizers: Map<MinimizationObligation, ObligationMinimizer> =
        Collections.unmodifiableMap(minimizers.toMap())

    private val jsonMapper =
        ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun project(
        canonicalInput: ModelRequest,
        selectedDeployment: ProviderDeployment,
        decision: ProviderInputReleaseDecision,
    ): ProviderInputProjectionResult =
        when (decision) {
            is ProviderInputReleaseDecision.Deny -> {
                ProviderInputProjectionResult.Refused(
                    deploymentId = selectedDeployment.deploymentId,
                    reason = ProjectionRefusal.RELEASE_DENIED,
                )
            }

            is ProviderInputReleaseDecision.AllowRaw -> {
                if (decision.deploymentId != selectedDeployment.deploymentId) {
                    ProviderInputProjectionResult.Refused(
                        deploymentId = selectedDeployment.deploymentId,
                        reason = ProjectionRefusal.DEPLOYMENT_MISMATCH,
                    )
                } else {
                    ProviderInputProjectionResult.Projected(
                        deploymentId = selectedDeployment.deploymentId,
                        providerBoundRequest = isolateRequest(canonicalInput),
                        metadata = ProjectionMetadata(appliedObligations = emptySet(), redactionCount = 0),
                    )
                }
            }

            is ProviderInputReleaseDecision.AllowWithMinimization -> {
                projectWithMinimization(canonicalInput, selectedDeployment, decision)
            }
        }

    private fun projectWithMinimization(
        canonicalInput: ModelRequest,
        selectedDeployment: ProviderDeployment,
        decision: ProviderInputReleaseDecision.AllowWithMinimization,
    ): ProviderInputProjectionResult {
        val orderedObligations = decision.obligations.sortedBy { it.ordinal }
        val activeMinimizers = orderedObligations.mapNotNull { obligation -> minimizers[obligation] }
        return when {
            decision.deploymentId != selectedDeployment.deploymentId -> {
                ProviderInputProjectionResult.Refused(
                    deploymentId = selectedDeployment.deploymentId,
                    reason = ProjectionRefusal.DEPLOYMENT_MISMATCH,
                )
            }

            activeMinimizers.size != orderedObligations.size -> {
                ProviderInputProjectionResult.Refused(
                    deploymentId = selectedDeployment.deploymentId,
                    reason = ProjectionRefusal.MISSING_OBLIGATION_HANDLER,
                )
            }

            hasUnsupportedContentPart(canonicalInput) -> {
                ProviderInputProjectionResult.Refused(
                    deploymentId = selectedDeployment.deploymentId,
                    reason = ProjectionRefusal.UNSUPPORTED_CONTENT_PART,
                )
            }

            else -> {
                runMinimization(canonicalInput, selectedDeployment, decision, activeMinimizers)
            }
        }
    }

    private fun hasUnsupportedContentPart(request: ModelRequest): Boolean =
        request.messages.any { message ->
            message.contentParts?.any { part ->
                part is ContentPart.ImagePart || part is ContentPart.ImageUrlContent
            } == true
        }

    private fun runMinimization(
        canonicalInput: ModelRequest,
        selectedDeployment: ProviderDeployment,
        decision: ProviderInputReleaseDecision.AllowWithMinimization,
        activeMinimizers: List<ObligationMinimizer>,
    ): ProviderInputProjectionResult =
        try {
            var totalRedactions = 0
            val projectedMessages =
                canonicalInput.messages.map { message ->
                    val (projectedMessage, count) = projectMessage(message, activeMinimizers)
                    totalRedactions = Math.addExact(totalRedactions, count)
                    projectedMessage
                }
            val projectedTools =
                canonicalInput.tools?.map { tool ->
                    val (projectedTool, count) = projectToolDefinition(tool, activeMinimizers)
                    totalRedactions = Math.addExact(totalRedactions, count)
                    projectedTool
                }
            val isolated =
                canonicalInput.copy(
                    messages = Collections.unmodifiableList(projectedMessages),
                    tools = projectedTools?.let { Collections.unmodifiableList(it) },
                )
            ProviderInputProjectionResult.Projected(
                deploymentId = selectedDeployment.deploymentId,
                providerBoundRequest = isolated,
                metadata =
                    ProjectionMetadata(
                        appliedObligations = decision.obligations,
                        redactionCount = totalRedactions,
                    ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProviderInputProjectionResult.Refused(
                deploymentId = selectedDeployment.deploymentId,
                reason = ProjectionRefusal.TRANSFORMATION_FAILED,
            )
        }

    private fun projectMessage(
        message: Message,
        minimizers: List<ObligationMinimizer>,
    ): Pair<Message, Int> {
        var count = 0
        val projectedContent =
            if (message.content.isNotEmpty()) {
                val outcome = applyMinimizers(message.content, minimizers)
                count = Math.addExact(count, outcome.redactionCount)
                outcome.minimizedText
            } else {
                message.content
            }
        val projectedParts =
            message.contentParts?.map { part ->
                when (part) {
                    is ContentPart.TextPart -> {
                        val outcome = applyMinimizers(part.text, minimizers)
                        count = Math.addExact(count, outcome.redactionCount)
                        ContentPart.TextPart(outcome.minimizedText)
                    }

                    is ContentPart.ImagePart,
                    is ContentPart.ImageUrlContent,
                    -> {
                        error("Unreachable: unsupported content parts are rejected before projection")
                    }
                }
            }
        val projectedToolCallId =
            message.toolCallId?.also { ensureIdentifierSafe(it, minimizers) }
        val projectedToolCalls =
            message.toolCalls?.map { call ->
                ensureIdentifierSafe(call.id, minimizers)
                ensureIdentifierSafe(call.name, minimizers)
                val outcome = applyMinimizers(call.argumentsJson, minimizers)
                validateJson(call.argumentsJson, outcome.minimizedText)
                count = Math.addExact(count, outcome.redactionCount)
                ToolCall(id = call.id, name = call.name, argumentsJson = outcome.minimizedText)
            }
        return message.copy(
            content = projectedContent,
            contentParts = projectedParts?.let { Collections.unmodifiableList(it) },
            toolCallId = projectedToolCallId,
            toolCalls = projectedToolCalls?.let { Collections.unmodifiableList(it) },
        ) to count
    }

    private fun projectToolDefinition(
        tool: ToolDefinition,
        minimizers: List<ObligationMinimizer>,
    ): Pair<ToolDefinition, Int> {
        ensureIdentifierSafe(tool.name, minimizers)
        val descOutcome = applyMinimizers(tool.description, minimizers)
        val schemaOutcome = applyMinimizers(tool.inputSchemaJson, minimizers)
        validateJson(tool.inputSchemaJson, schemaOutcome.minimizedText)
        return tool.copy(
            description = descOutcome.minimizedText,
            inputSchemaJson = schemaOutcome.minimizedText,
        ) to Math.addExact(descOutcome.redactionCount, schemaOutcome.redactionCount)
    }

    private fun ensureIdentifierSafe(
        identifier: String,
        minimizers: List<ObligationMinimizer>,
    ) {
        if (identifier.isNotBlank()) {
            val outcome = applyMinimizers(identifier, minimizers)
            check(outcome.redactionCount == 0 && outcome.minimizedText == identifier) {
                "Provider-bound identifier cannot be safely preserved under minimization"
            }
        }
    }

    private fun validateJson(
        original: String,
        transformed: String,
    ) {
        if (original.isBlank()) {
            require(transformed.isBlank()) { "Blank JSON field must remain blank" }
            return
        }
        require(transformed.isNotBlank()) { "Transformed JSON must not be blank" }
        val originalTree = jsonMapper.readTree(original)
        val transformedTree = jsonMapper.readTree(transformed)
        assertJsonStructurePreserved(originalTree, transformedTree)
    }

    private fun applyMinimizers(
        text: String,
        minimizers: List<ObligationMinimizer>,
    ): MinimizationOutcome {
        var current = text
        var count = 0
        for (minimizer in minimizers) {
            val outcome = minimizer.minimize(current)
            check(outcome.inspectionCompleted) { "Minimizer did not complete inspection" }
            current = outcome.minimizedText
            count = Math.addExact(count, outcome.redactionCount)
        }
        return MinimizationOutcome(minimizedText = current, redactionCount = count, inspectionCompleted = true)
    }

    private fun isolateRequest(request: ModelRequest): ModelRequest {
        val isolatedMessages =
            request.messages.map { message ->
                val isolatedParts =
                    message.contentParts?.map { part ->
                        when (part) {
                            is ContentPart.TextPart -> ContentPart.TextPart(part.text)
                            is ContentPart.ImagePart -> ContentPart.ImagePart(part.mimeType, part.data.copyOf())
                            is ContentPart.ImageUrlContent -> ContentPart.ImageUrlContent(part.url, part.mimeType)
                        }
                    }
                val isolatedCalls =
                    message.toolCalls?.map { call ->
                        ToolCall(id = call.id, name = call.name, argumentsJson = call.argumentsJson)
                    }
                message.copy(
                    contentParts = isolatedParts?.let { Collections.unmodifiableList(it) },
                    toolCalls = isolatedCalls?.let { Collections.unmodifiableList(it) },
                )
            }
        val isolatedTools =
            request.tools?.map { tool ->
                ToolDefinition(
                    name = tool.name,
                    description = tool.description,
                    inputSchemaJson = tool.inputSchemaJson,
                )
            }
        return request.copy(
            messages = Collections.unmodifiableList(isolatedMessages),
            tools = isolatedTools?.let { Collections.unmodifiableList(it) },
        )
    }
}

private fun assertJsonStructurePreserved(
    original: JsonNode,
    transformed: JsonNode,
) {
    require(transformed.nodeType == original.nodeType) {
        "Transformed JSON node type must be preserved"
    }
    when {
        original.isObject -> {
            val originalKeys = original.fieldNames().asSequence().toSet()
            val transformedKeys = transformed.fieldNames().asSequence().toSet()
            require(transformedKeys == originalKeys) { "Transformed JSON object keys must be preserved" }
            for (key in originalKeys) {
                assertJsonStructurePreserved(original.get(key), transformed.get(key))
            }
        }

        original.isArray -> {
            require(transformed.size() == original.size()) { "Transformed JSON array size must be preserved" }
            for (index in 0 until original.size()) {
                assertJsonStructurePreserved(original.get(index), transformed.get(index))
            }
        }
    }
}

/**
 * Deterministic text minimizer for a single [MinimizationObligation].
 *
 * Trusted-computing-base contract: only policy-backed, verified minimizer
 * implementations should be registered.
 */
fun interface ObligationMinimizer {
    fun minimize(text: String): MinimizationOutcome
}

/**
 * Result of applying a single [ObligationMinimizer].
 */
data class MinimizationOutcome(
    val minimizedText: String,
    val redactionCount: Int,
    val inspectionCompleted: Boolean,
) {
    init {
        require(redactionCount >= 0) { "redactionCount must be non-negative" }
    }

    override fun toString(): String =
        "MinimizationOutcome(" +
            "redactionCount=$redactionCount, " +
            "inspectionCompleted=$inspectionCompleted)"
}

/**
 * Safe, non-sensitive metadata describing a completed projection.
 */
class ProjectionMetadata(
    appliedObligations: Set<MinimizationObligation>,
    val redactionCount: Int,
) {
    init {
        require(redactionCount >= 0) { "redactionCount must be non-negative" }
    }

    val appliedObligations: Set<MinimizationObligation> =
        Collections.unmodifiableSet(appliedObligations.toSet())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ProjectionMetadata &&
            appliedObligations == other.appliedObligations &&
            redactionCount == other.redactionCount
    }

    override fun hashCode(): Int = 31 * appliedObligations.hashCode() + redactionCount

    override fun toString(): String =
        "ProjectionMetadata(appliedObligations=$appliedObligations, " +
            "redactionCount=$redactionCount)"
}

/**
 * Result of [ProviderInputProjector.project].
 */
sealed interface ProviderInputProjectionResult {
    val deploymentId: String?

    /** Projection succeeded and produced an isolated [providerBoundRequest]. */
    data class Projected(
        override val deploymentId: String,
        val providerBoundRequest: ModelRequest,
        val metadata: ProjectionMetadata,
    ) : ProviderInputProjectionResult {
        override fun toString(): String = "Projected(deploymentId=$deploymentId, metadata=$metadata)"
    }

    /** Projection was refused; no provider-bound request is produced. */
    data class Refused(
        override val deploymentId: String?,
        val reason: ProjectionRefusal,
    ) : ProviderInputProjectionResult
}

/**
 * Stable, non-sensitive reason why provider-input projection was refused.
 */
enum class ProjectionRefusal {
    RELEASE_DENIED,
    DEPLOYMENT_MISMATCH,
    MISSING_OBLIGATION_HANDLER,
    UNSUPPORTED_CONTENT_PART,
    TRANSFORMATION_FAILED,
}
