package dev.tramai.spring

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Import

/**
 * Selects the standard TramAI runtime for Spring Boot applications.
 *
 * `standard` remains the default when no explicit profile is provided, preserving
 * existing `tramai-spring` behavior. There is no implicit sovereign default:
 * the sovereign runtime activates only when `tramai.profile=sovereign` is set
 * explicitly, so the two runtime authorities can never both activate.
 */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "tramai",
    name = ["profile"],
    havingValue = "standard",
    matchIfMissing = true,
)
@Import(TramaiAutoConfiguration::class)
internal class StandardTramaiProfileAutoConfiguration
