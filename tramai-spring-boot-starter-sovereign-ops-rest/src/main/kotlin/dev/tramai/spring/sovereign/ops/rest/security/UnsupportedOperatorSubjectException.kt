package dev.tramai.spring.sovereign.ops.rest.security

/**
 * Raised when a verified token carries a subject that cannot be recorded as an approval actor id.
 *
 * Fail-closed on purpose (audit limitation A4): the operator subject becomes the actor recorded in
 * approval evidence, so an identity that the approval path could not store must not silently become an
 * operator under some rewritten form.
 */
class UnsupportedOperatorSubjectException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
