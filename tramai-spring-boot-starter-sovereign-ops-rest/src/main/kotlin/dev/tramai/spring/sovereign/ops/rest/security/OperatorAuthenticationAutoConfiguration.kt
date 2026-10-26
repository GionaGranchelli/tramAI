package dev.tramai.spring.sovereign.ops.rest.security

import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.spring.sovereign.ops.OperatorApprovalPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.util.AntPathMatcher
import org.springframework.util.ClassUtils
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * Identity-provider-neutral operator authentication boundary for the sovereign-ops REST control plane
 * (0.7.6c).
 *
 * What this establishes: a verified JWT becomes an immutable
 * [dev.tramai.core.operator.OperatorPrincipal], with capabilities resolved from server-side
 * configuration, or the request is rejected. What it does **not** establish: authorization. The
 * existing approval endpoints are not more authorized because this bean exists — enforcement is
 * 0.7.6e, and the approval concurrency defect recorded by 0.7.6a (F2) is still open until 0.7.6d.
 *
 * Off by default ([OperatorSecurityProperties.enabled]), and fail-closed when on: a missing issuer,
 * audience or JWKS URI raises at startup instead of degrading to anonymous access. There is no
 * trusted-header mode and no principal-from-request path — a caller cannot establish its own identity.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["org.springframework.security.oauth2.jwt.JwtDecoder"])
@ConditionalOnProperty(prefix = "tramai.sovereign.ops.security", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(OperatorSecurityProperties::class)
class OperatorAuthenticationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun operatorCapabilityMapping(properties: OperatorSecurityProperties): OperatorCapabilityMapping =
        OperatorCapabilityMapping(properties.roleClaim, properties.roleMappings)

    @Bean
    @ConditionalOnMissingBean
    fun operatorApproverRoleMapping(properties: OperatorSecurityProperties): OperatorApproverRoleMapping =
        OperatorApproverRoleMapping(properties.roleClaim, properties.approverRoleMappings)

    /**
     * The per-approval approver-role requirement (0.7.6e), keyed by an approval's persisted `policyVersion`.
     *
     * Deliberately **not** `@ConditionalOnMissingBean`: an alternative bean of this type is an
     * ambiguous-injection startup failure rather than a quiet override, which is what keeps a host from
     * installing an allow-all policy behind a green pipeline.
     *
     * Fails startup when authentication is enabled but no `approval-policies` entry exists, because an
     * authorization boundary that cannot express a single requirement would authorize nothing while looking
     * configured.
     */
    @Bean
    fun operatorApprovalPolicy(properties: OperatorSecurityProperties): OperatorApprovalPolicy =
        OperatorApprovalPolicy(
            requireNotNull(properties.approvalPolicies.takeIf { it.isNotEmpty() }) {
                "tramai-approval-policies-missing: tramai.sovereign.ops.security.enabled is true but no " +
                    "approval-policies entry is configured, so no approval decision could be authorized"
            }.mapValues { (_, roles) -> roles.map { ApproverRole(it) }.toSet() },
        )

    @Bean
    @ConditionalOnMissingBean
    fun operatorClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean
    fun operatorPrincipalFactory(
        properties: OperatorSecurityProperties,
        capabilityMapping: OperatorCapabilityMapping,
        approverRoleMapping: OperatorApproverRoleMapping,
        clock: Clock,
    ): OperatorPrincipalFactory = OperatorPrincipalFactory(properties, capabilityMapping, approverRoleMapping, clock)

    @Bean
    @ConditionalOnMissingBean
    fun operatorPrincipalResolver(principalFactory: OperatorPrincipalFactory): OperatorPrincipalResolver =
        OperatorPrincipalResolver(principalFactory)

    /**
     * Operator boundary for the sovereign-ops routes only, ordered ahead of any host chain so a
     * consumer configuration cannot accidentally leave them outside protection. Requests to those
     * paths must be authenticated; everything else is left to the host application's own chains.
     */
    @Bean
    @Order(1)
    fun operatorSecurityFilterChain(
        http: HttpSecurity,
        properties: OperatorSecurityProperties,
        @Value("\${tramai.sovereign.ops.rest.base-path:/tramai/sovereign/approvals}") approvalBasePath: String,
    ): SecurityFilterChain {
        requireOperatorConfiguration(properties, approvalBasePath)
        val decoder = operatorJwtDecoder(properties)
        // Widening cast keeps the element type exact without constructing a mutable collection: the
        // architecture gate treats mutable collection construction in production code as global state.
        val matchers: List<RequestMatcher> =
            properties.protectedPaths
                .map { path -> AntPathRequestMatcher(path) as RequestMatcher }
        return http
            .securityMatcher(OrRequestMatcher(matchers))
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { authorize -> authorize.anyRequest().authenticated() }
            .oauth2ResourceServer { server ->
                // The chain's own decoder, constructed two lines above from configuration. It is not a
                // bean, so no host definition - whatever its name or qualifier - can replace it.
                server.jwt { jwt ->
                    jwt
                        .decoder(decoder)
                        .jwtAuthenticationConverter(JwtAuthenticationConverter())
                }
            }.build()
    }

    @Bean
    fun operatorPrincipalWebMvcConfigurer(principalResolver: OperatorPrincipalResolver): WebMvcConfigurer =
        OperatorPrincipalWebMvcConfigurer(principalResolver)
}

/**
 * Fails startup when authentication is enabled but the Spring Security resource server is not on the
 * classpath.
 *
 * This exists because Spring Security is an **optional** dependency of this starter (a consumer adds
 * `spring-boot-starter-oauth2-resource-server` to opt in). Without this check, `enabled=true` on a
 * classpath without the classes would make [OperatorAuthenticationAutoConfiguration] quietly inactive —
 * leaving the control-plane routes open while the operator believed they were protected. Fail-open is
 * not an acceptable default for an authentication boundary.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tramai.sovereign.ops.security", name = ["enabled"], havingValue = "true")
class OperatorAuthenticationDependencyCheckAutoConfiguration {
    @Bean
    fun operatorAuthenticationDependencyCheck() = OperatorAuthenticationDependencyCheck()
}

/** Startup guard; see [OperatorAuthenticationDependencyCheckAutoConfiguration]. */
class OperatorAuthenticationDependencyCheck {
    init {
        check(ClassUtils.isPresent("org.springframework.security.oauth2.jwt.JwtDecoder", javaClass.classLoader)) {
            "tramai-operator-authentication-missing-dependency: add " +
                "'org.springframework.boot:spring-boot-starter-oauth2-resource-server' " +
                "to enable operator authentication"
        }
    }
}

/** Registers [OperatorPrincipalArgumentResolver] so handlers can declare an operator parameter. */
@Configuration(proxyBeanMethods = false)
class OperatorPrincipalWebMvcConfigurer(
    private val principalResolver: OperatorPrincipalResolver,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(OperatorPrincipalArgumentResolver(principalResolver))
    }
}

/**
 * The operator boundary's own verifying decoder.
 *
 * Deliberately **not** a Spring bean. A bean - under any name, with any qualifier - can be replaced by a
 * host definition, and `@ConditionalOnMissingBean(name = ...)` merely moves the collision rather than
 * removing it: a host that declares a bean with this exact name wins and decides which signatures the
 * operator boundary trusts. Building it here from configuration means ownership cannot be transferred, at
 * the cost of not being overridable - which is the point.
 *
 * Signature verification, expiry and not-before come from Spring's Nimbus decoder; no custom cryptography
 * lives here. The issuer is pinned, the audience and `exp` are required, and the trust material comes from
 * configuration rather than from token discovery.
 */
internal fun operatorJwtDecoder(properties: OperatorSecurityProperties): JwtDecoder {
    val decoder =
        if (properties.publicKey.isNotBlank()) {
            NimbusJwtDecoder
                .withPublicKey(rsaPublicKey(properties.publicKey))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
        } else {
            NimbusJwtDecoder
                .withJwkSetUri(properties.jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build()
        }
    decoder.setJwtValidator(operatorJwtValidators(properties.issuer, properties.audience))
    return decoder
}

private fun rsaPublicKey(encoded: String): RSAPublicKey {
    val der = Base64.getMimeDecoder().decode(encoded)
    return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
}

/**
 * The claim validation the operator boundary applies to every token: default timestamp validation
 * (expiry, not-before) plus a **strict issuer** and a **required audience member**.
 *
 * Exposed to tests inside the module so the HTTP tests can wire a local-key decoder around the *same*
 * validator set the production decoder uses — a test that substituted its own validators would prove
 * nothing about this code.
 */
internal fun operatorJwtValidators(
    issuer: String,
    audience: String,
): OAuth2TokenValidator<Jwt> =
    DelegatingOAuth2TokenValidator(
        JwtValidators.createDefaultWithIssuer(issuer),
        JwtClaimValidator<List<String>>("aud") { claim -> claim != null && audience in claim },
        // Presence, not just timestamp comparison: Spring's default timestamp validator compares a
        // claimed expiry but a token that simply omits `exp` would otherwise be accepted forever.
        JwtClaimValidator<Instant>("exp") { claim -> claim != null },
    )

/**
 * Fail-closed configuration check: turning authentication on without an issuer, audience or JWKS URI is a
 * startup failure, never a degraded mode. Internal so the failure behaviour is directly testable.
 */
internal fun requireOperatorConfiguration(
    properties: OperatorSecurityProperties,
    approvalBasePath: String,
) {
    val missing =
        buildList {
            if (properties.issuer.isBlank()) add("issuer")
            if (properties.audience.isBlank()) add("audience")
            if (properties.jwkSetUri.isBlank() && properties.publicKey.isBlank()) {
                add("jwk-set-uri or public-key")
            }
        }
    check(missing.isEmpty()) {
        "tramai-operator-authentication-incomplete-configuration: missing ${missing.joinToString(", ")}"
    }
    requireProtectedControlPlane(properties.protectedPaths, approvalBasePath)
}

/**
 * Fail-closed route coverage. The approval base path is configurable, so protecting a fixed pattern would
 * silently leave the control plane outside the operator chain whenever a consumer moved it. Both the base
 * path and a synthetic child of it must match some protected pattern, and an empty pattern list is a
 * failure rather than an unprotected-but-authenticated boundary.
 */
internal fun requireProtectedControlPlane(
    protectedPaths: List<String>,
    controlPlaneBasePath: String,
) {
    val matcher = AntPathMatcher()
    val basePath = controlPlaneBasePath.trimEnd('/')
    val covered =
        protectedPaths.any { pattern ->
            matcher.match(pattern, basePath) && matcher.match(pattern, "$basePath/probe")
        }
    check(covered) {
        "tramai-operator-authentication-unprotected-control-plane: control plane at '$basePath' is not " +
            "covered by protected-paths ${protectedPaths.ifEmpty { listOf("<empty>") }}"
    }
}
