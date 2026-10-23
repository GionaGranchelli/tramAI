package dev.tramai.spring.sovereign.ops.rest.security

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
import org.springframework.util.ClassUtils
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

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
    fun operatorClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean
    fun operatorPrincipalFactory(
        properties: OperatorSecurityProperties,
        capabilityMapping: OperatorCapabilityMapping,
        clock: Clock,
    ): OperatorPrincipalFactory = OperatorPrincipalFactory(properties, capabilityMapping, clock)

    @Bean
    @ConditionalOnMissingBean
    fun operatorPrincipalResolver(principalFactory: OperatorPrincipalFactory): OperatorPrincipalResolver =
        OperatorPrincipalResolver(principalFactory)

    /**
     * Verifying decoder. Signature verification, expiry and not-before come from Spring's Nimbus
     * decoder — no custom cryptography lives here. The issuer is pinned, the audience is required, and
     * the JWKS location is configured rather than discovered from the token.
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder::class)
    fun operatorJwtDecoder(properties: OperatorSecurityProperties): JwtDecoder {
        requireOperatorConfiguration(properties)
        val decoder =
            NimbusJwtDecoder
                .withJwkSetUri(properties.jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build()
        decoder.setJwtValidator(operatorJwtValidators(properties.issuer, properties.audience))
        return decoder
    }

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
    ): SecurityFilterChain {
        requireOperatorConfiguration(properties)
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
                server.jwt { jwt -> jwt.jwtAuthenticationConverter(JwtAuthenticationConverter()) }
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
    )

/**
 * Fail-closed configuration check: turning authentication on without an issuer, audience or JWKS URI is a
 * startup failure, never a degraded mode. Internal so the failure behaviour is directly testable.
 */
internal fun requireOperatorConfiguration(properties: OperatorSecurityProperties) {
    val missing =
        buildList {
            if (properties.issuer.isBlank()) add("issuer")
            if (properties.audience.isBlank()) add("audience")
            if (properties.jwkSetUri.isBlank()) add("jwk-set-uri")
        }
    check(missing.isEmpty()) {
        "tramai-operator-authentication-incomplete-configuration: missing ${missing.joinToString(", ")}"
    }
}
