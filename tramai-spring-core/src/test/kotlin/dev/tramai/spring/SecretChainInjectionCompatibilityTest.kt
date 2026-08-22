package dev.tramai.spring

import dev.tramai.core.secret.SecretValueResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.annotation.Order

/**
 * Regression tests for the secret-chain refactor (#263 review round 1).
 *
 * Pre-#263 the bootstrap and full chains were local variables inside tramai()
 * and were never Spring beans; the only SecretValueResolver beans visible to
 * application code were user-supplied resolvers. #263 must preserve that
 * injection universe exactly.
 */
class SecretChainInjectionCompatibilityTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                TramaiSecretResolutionAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `unqualified SecretValueResolver injection sees only the user resolver`() {
        runner
            .withUserConfiguration(UserResolverAndConsumer::class.java)
            .run { context ->
                // P1-2 regression: without the typed-chain fix this throws
                // NoUniqueBeanDefinitionException (user + bootstrap + full chain).
                val consumer = context.getBean(UnqualifiedSecretValueResolverConsumer::class.java)
                assertThat(consumer.resolver).isSameAs(context.getBean("customUserResolver"))
            }
    }

    @Test
    fun `user resolver ordering follows Spring @Order semantics`() {
        runner
            .withUserConfiguration(OrderedUserResolvers::class.java)
            .run { context ->
                val chain = context.getBean(SpringSecretChain::class.java)
                // Both resolvers can resolve the same reference; @Order(1) must win.
                assertThat(chain.resolver.resolve("shared:ref")).isEqualTo("low-order-value")
            }
    }

    @Test
    fun `Ordered instance fallback without annotations`() {
        runner
            .withUserConfiguration(OrderedInstanceResolvers::class.java)
            .run { context ->
                val chain = context.getBean(SpringSecretChain::class.java)
                // No @Order anywhere; Ordered#getOrder() on the instances must
                // decide (order 1 beats order 5) — the pre-#263
                // orderedStream() fallback on the bean object itself.
                assertThat(chain.resolver.resolve("shared:ref")).isEqualTo("low-value")
            }
    }

    @Test
    fun `PriorityOrdered group wins over Ordered regardless of numeric order`() {
        runner
            .withUserConfiguration(PriorityOrderedResolvers::class.java)
            .run { context ->
                val chain = context.getBean(SpringSecretChain::class.java)
                // PriorityOrdered(order=100) must win over Ordered(order=1):
                // Spring sorts PriorityOrdered beans ahead of plain Ordered
                // beans before any numeric comparison.
                assertThat(chain.resolver.resolve("shared:ref")).isEqualTo("priority-value")
            }
    }

    @Configuration
    open class UserResolverAndConsumer {
        @Bean
        open fun customUserResolver(): SecretValueResolver =
            object : SecretValueResolver {
                override fun resolve(secretRef: String): String? = "custom-user-value"
            }

        @Bean
        open fun unqualifiedConsumer(customUserResolver: SecretValueResolver): UnqualifiedSecretValueResolverConsumer =
            UnqualifiedSecretValueResolverConsumer(customUserResolver)
    }

    class UnqualifiedSecretValueResolverConsumer(
        val resolver: SecretValueResolver,
    )

    @Configuration
    open class OrderedUserResolvers {
        // Registered in reverse @Order order on purpose: bean-definition order
        // must not decide precedence — @Order must.
        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        open fun fallbackUserResolver(): SecretValueResolver =
            object : SecretValueResolver {
                override fun resolve(secretRef: String): String? = "high-order-value"
            }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        open fun firstUserResolver(): SecretValueResolver =
            object : SecretValueResolver {
                override fun resolve(secretRef: String): String? = "low-order-value"
            }
    }

    @Configuration
    open class OrderedInstanceResolvers {
        // No annotations: precedence comes from the instance's Ordered#getOrder().
        @Bean
        open fun slowerResolver(): SecretValueResolver =
            object : SecretValueResolver, Ordered {
                override fun getOrder(): Int = 5
                override fun resolve(secretRef: String): String? = "high-value"
            }

        @Bean
        open fun fasterResolver(): SecretValueResolver =
            object : SecretValueResolver, Ordered {
                override fun getOrder(): Int = 1
                override fun resolve(secretRef: String): String? = "low-value"
            }
    }

    @Configuration
    open class PriorityOrderedResolvers {
        // PriorityOrdered forms a higher group than plain Ordered; the numeric
        // orders (100 vs 1) must not decide.
        @Bean
        open fun plainOrderedResolver(): SecretValueResolver =
            object : SecretValueResolver, Ordered {
                override fun getOrder(): Int = 1
                override fun resolve(secretRef: String): String? = "plain-value"
            }

        @Bean
        open fun priorityResolver(): SecretValueResolver =
            object : SecretValueResolver, PriorityOrdered {
                override fun getOrder(): Int = 100
                override fun resolve(secretRef: String): String? = "priority-value"
            }
    }
}
