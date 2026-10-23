package dev.tramai.spring.sovereign.ops.rest.security

import org.springframework.security.authentication.InsufficientAuthenticationException

/**
 * Raised when a verified token carries a subject that cannot be recorded as an approval actor id.
 *
 * Fail-closed on purpose (audit limitation A4): the operator subject becomes the actor recorded in
 * approval evidence, so an identity that the approval path could not store must not silently become an
 * operator under some rewritten form. It extends [InsufficientAuthenticationException] so a token the
 * boundary cannot turn into an identity is rejected as an authentication failure (401), never surfaced as
 * a 500 from a downstream illegal-state error.
 */
class UnsupportedOperatorSubjectException(
    message: String,
    cause: Throwable? = null,
) : InsufficientAuthenticationException(message, cause)
