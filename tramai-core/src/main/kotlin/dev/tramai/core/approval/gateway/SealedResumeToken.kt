package dev.tramai.core.approval.gateway

import dev.tramai.core.approval.gateway.ResumeToken

/**
 * A redacted wrapper around [ResumeToken] that prevents accidental leakage
 * of the sensitive credential through logs, exceptions, audit, or toString().
 *
 * The raw token is available only through the explicit [revealForInternalResume]
 * method, which should be called exclusively by the runtime-owned resume path.
 */
class SealedResumeToken private constructor(
    private val raw: ResumeToken,
) {
    /**
     * Reveal the underlying [ResumeToken] for internal runtime-owned resume.
     * Do not call from reviewer UI, inbox projections, audit, logs, or REST.
     */
    fun revealForInternalResume(): ResumeToken = raw

    override fun toString(): String = "[REDACTED]"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SealedResumeToken) return false
        return raw == other.raw
    }

    override fun hashCode(): Int = raw.hashCode()

    companion object {
        fun seal(token: ResumeToken): SealedResumeToken = SealedResumeToken(token)
    }
}
