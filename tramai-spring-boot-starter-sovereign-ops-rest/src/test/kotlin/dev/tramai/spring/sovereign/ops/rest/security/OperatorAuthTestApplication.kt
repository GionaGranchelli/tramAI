package dev.tramai.spring.sovereign.ops.rest.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.tramai.core.operator.OperatorAuthorizationResult
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test host application for the operator authentication boundary (0.7.6c).
 *
 * The decoder is built from a **locally generated** RSA key so the suite makes no network request, and
 * it is wired with the production validator set ([operatorJwtValidators]) so issuer, audience and
 * expiry are the rules under test — not a test-local reimplementation of them.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
open class OperatorAuthTestApplication {
    /** Local signing material: one trusted key pair, plus a foreign key to prove signature checks. */
    @Bean
    open fun operatorTestKeys(): OperatorTestKeys = SharedTestKeys.keys

    @Bean
    open fun sideEffectRecorder(): SideEffectRecorder = SideEffectRecorder()

    @Bean
    open fun operatorTestController(sideEffects: SideEffectRecorder) = OperatorTestController(sideEffects)

    /**
     * A deliberately permissive decoder of the kind a host application might define: it trusts the
     * *foreign* key and applies no validators.
     */
    @Bean
    open fun permissiveHostJwtDecoder(keys: OperatorTestKeys): JwtDecoder =
        NimbusJwtDecoder.withPublicKey(keys.foreignPair.public as RSAPublicKey).build()

    /**
     * The same permissive decoder under the name an earlier revision reserved for the operator decoder.
     * The operator boundary must ignore it too: its own decoder is constructed from configuration and is
     * not a bean, so there is no name to claim.
     */
    @Bean("operatorJwtDecoder")
    open fun permissiveHostJwtDecoderWithOperatorName(keys: OperatorTestKeys): JwtDecoder =
        NimbusJwtDecoder.withPublicKey(keys.foreignPair.public as RSAPublicKey).build()
}

/**
 * Process-wide key material for the suite, because `@DynamicPropertySource` runs before the application
 * context exists and therefore cannot read the [OperatorTestKeys] bean. Generated locally, never on a
 * network, and test-only.
 */
class SharedTestKeys private constructor() {
    companion object {
        val keys: OperatorTestKeys by lazy { OperatorTestKeys.generate() }
    }
}

/** Locally generated key pairs and token signing helpers; no network, fully deterministic. */
class OperatorTestKeys private constructor(
    val trustedPair: KeyPair,
    val foreignPair: KeyPair,
) {
    /** Valid-by-default token recipe; call sites use `copy(...)` to express only what they change. */
    data class TokenSpec(
        /** Null means "omit the sub claim entirely", which is its own rejection case. */
        val subject: String? = "alice",
        val issuer: String = ISSUER,
        val audience: String = AUDIENCE,
        val roles: List<String> = listOf("approval-reviewer"),
        val issuedAt: Instant = Instant.parse("2026-02-28T00:00:00Z"),
        /** Null means "omit the exp claim entirely", which is its own rejection case. */
        val expiresAt: Instant? = Instant.now().plusSeconds(600),
        val signedWith: KeyPair? = null,
        val extraClaims: Map<String, Any> = emptyMap(),
    )

    fun token(spec: TokenSpec = TokenSpec()): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(spec.issuer)
                .apply { spec.subject?.let { subject(it) } }
                .audience(spec.audience)
                .issueTime(Date.from(spec.issuedAt))
                .apply { spec.expiresAt?.let { expirationTime(Date.from(it)) } }
                .claim("roles", spec.roles)
                .apply { spec.extraClaims.forEach { (name, value) -> claim(name, value) } }
                .build()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build()
        val signer = spec.signedWith ?: trustedPair
        return SignedJWT(header, claims)
            .apply { sign(RSASSASigner(signer.private as RSAPrivateKey)) }
            .serialize()
    }

    companion object {
        const val ISSUER = "https://idp.example/"
        const val AUDIENCE = "tramai-control-plane"

        fun generate(): OperatorTestKeys {
            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            return OperatorTestKeys(generator.generateKeyPair(), generator.generateKeyPair())
        }
    }
}

/** Counts handler invocations, so tests can assert a denied request never produced an effect. */
class SideEffectRecorder {
    private val invocations = AtomicInteger()
    private val mutations = AtomicInteger()

    fun recordInvocation() {
        invocations.incrementAndGet()
    }

    fun recordMutation() {
        mutations.incrementAndGet()
    }

    fun invocations(): Int = invocations.get()

    fun mutations(): Int = mutations.get()

    fun reset() {
        invocations.set(0)
        mutations.set(0)
    }
}

/**
 * Test-only control surface. It stands in for the 0.7.6e control boundary: the endpoints declare an
 * [OperatorPrincipal] parameter, which the operator argument resolver can only satisfy from a verified
 * JWT, and one endpoint performs a capability check so the 401/403 distinction can be observed without
 * pretending the real approval endpoints are authorized.
 */
@RestController
class OperatorTestController(
    private val sideEffects: SideEffectRecorder,
) {
    @GetMapping("/tramai/sovereign/test/whoami")
    fun whoAmI(operator: OperatorPrincipal): Map<String, Any> {
        sideEffects.recordInvocation()
        return mapOf(
            "subject" to operator.subject,
            "issuer" to operator.issuer,
            "authenticatedAt" to operator.authenticatedAt.toString(),
            "capabilities" to operator.capabilities.map { it.wireName }.sorted(),
        )
    }

    @PostMapping("/tramai/sovereign/test/gated")
    fun gated(operator: OperatorPrincipal): Map<String, Any> {
        sideEffects.recordInvocation()
        if (operator.authorize(OperatorCapability.DECIDE_APPROVAL) != OperatorAuthorizationResult.Authorized) {
            throw AccessDeniedException("tramai-operator-capability-missing")
        }
        sideEffects.recordMutation()
        return mapOf("mutated" to true)
    }

    @GetMapping("/public/ping")
    fun ping(): Map<String, Any> = mapOf("pong" to true)
}
