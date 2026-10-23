package dev.tramai.spring.sovereign.ops.rest.security

import dev.tramai.core.operator.OperatorPrincipal
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * Resolves the authenticated [OperatorPrincipal] at the Spring boundary (0.7.6c).
 *
 * This is the only place in the adapter that reads [SecurityContextHolder], and it reads it to produce
 * an **explicit value**: [OperatorPrincipalArgumentResolver] hands that value to a controller as a
 * method argument, so nothing downstream needs a thread-local to learn who is calling.
 *
 * Three properties that matter for the epic:
 *
 * - the source of truth is the verified security context — specifically a [JwtAuthenticationToken],
 *   which only exists after Spring's decoder accepted the token;
 * - an unauthenticated request cannot obtain a principal: missing or non-JWT authentication throws
 *   [InsufficientAuthenticationException] (HTTP 401), it never returns null or an anonymous operator;
 * - a manually constructed [OperatorPrincipal] is not obtainable here at all. Constructing one proves
 *   nothing; this resolver ignores anything the caller supplies.
 */
class OperatorPrincipalResolver(
    private val principalFactory: OperatorPrincipalFactory,
) {
    /** Resolves a principal from an [authentication] already established by the filter chain. */
    fun resolve(authentication: Authentication?): OperatorPrincipal {
        val jwtAuthentication =
            authentication as? JwtAuthenticationToken
                ?: throw InsufficientAuthenticationException("tramai-operator-not-authenticated")
        return try {
            principalFactory.fromVerified(jwtAuthentication.token)
        } catch (unsupported: UnsupportedOperatorSubjectException) {
            // A verified token whose subject cannot be recorded is an unusable credential, not a server
            // fault: fail the authentication (401) rather than surfacing a 500 to the caller.
            throw InsufficientAuthenticationException(
                "tramai-operator-subject-unsupported",
                unsupported,
            )
        }
    }

    /** Resolves the principal of the current request's verified authentication. */
    fun resolveCurrent(): OperatorPrincipal = resolve(SecurityContextHolder.getContext().authentication)
}

/**
 * Delivers the authenticated operator to Spring MVC handlers as an explicit method argument:
 *
 * ```kotlin
 * @PostMapping("/{approvalId}/approve")
 * fun approve(operator: OperatorPrincipal, ...) = ...
 * ```
 *
 * A handler that declares an [OperatorPrincipal] parameter therefore cannot be reached unauthenticated:
 * the resolver throws before the handler body runs.
 */
class OperatorPrincipalArgumentResolver(
    private val principalResolver: OperatorPrincipalResolver,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(param: MethodParameter) = param.parameterType == OperatorPrincipal::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any = principalResolver.resolveCurrent()
}
