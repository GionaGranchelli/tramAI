package dev.tramai.spring.sovereign

import dev.tramai.sovereign.SovereignTramai
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

/**
 * Symmetric half of the one-authority invariant.
 *
 * [SovereignTramaiAutoConfiguration] rejects a plain `Tramai` bean when the
 * sovereign profile is selected. This guard covers the reverse: when the
 * standard profile is selected (explicit `standard` or missing), a manual
 * `SovereignTramai` bean would coexist with the standard auto-configured
 * `Tramai` — two runtime authorities. That must fail loudly, and it must not
 * depend on which bean Spring happens to instantiate first, so the guard runs
 * against bean definitions (no eager initialization) at startup.
 */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "tramai",
    name = ["profile"],
    havingValue = "standard",
    matchIfMissing = true,
)
internal class StandardProfileSovereignAuthorityGuardAutoConfiguration {

    @Bean
    fun sovereignAuthorityGuard(applicationContext: ApplicationContext): InitializingBean =
        InitializingBean {
            // includeNonSingletons = true: a prototype-scoped SovereignTramai
            // bean is still an authority; only eager initialization is avoided.
            val manualSovereignBeans = applicationContext.getBeanNamesForType(
                SovereignTramai::class.java,
                true,
                false,
            )
            check(manualSovereignBeans.isEmpty()) {
                "tramai.profile=standard (or missing) is incompatible with a manual SovereignTramai bean " +
                    "(found: ${manualSovereignBeans.joinToString()}). tramai.profile is the sole runtime " +
                    "selector and exactly one runtime authority is allowed."
            }
        }
}
