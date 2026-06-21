package dev.tramai.engine

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Computes a deterministic digest over the [SensitiveReplayEnvelope] content.
 *
 * Canonicalises the messages deterministically using [CanonicalMessageEncoder]
 * so the digest can be compared at resume time.
 *
 * Used in [SuspendedInvocationMetadata.replayEnvelopeDigest] to detect
 * replay-envelope tampering after claim.
 */
object ReplayEnvelopeDigestHelper {

    fun compute(
        operationReference: ResumeOperationReference,
        messages: List<Message>,
    ): Sha256Digest {
        val canonical = buildString {
            appendField("service_interface", operationReference.serviceInterface)
            append("method=").append(operationReference.methodName).append('\n')
            append("jvm_descriptor=").append(operationReference.jvmMethodDescriptor).append('\n')
            append("digest=").append(operationReference.resumeDefinitionDigest.value).append('\n')

            // Messages are the primary content — canonicalise deterministically
            append(CanonicalMessageEncoder.encode(messages))
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }
}
