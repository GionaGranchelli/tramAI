package dev.tramai.spring.sovereign

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Import

/**
 * Selects the sovereign TramAI runtime when `tramai.profile=sovereign`.
 *
 * Sovereign mode is deliberately explicit — there is no starter-specific
 * default profile. With no `tramai.profile` configured, the standard runtime
 * remains the default.
 */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "tramai",
    name = ["profile"],
    havingValue = "sovereign",
)
@Import(SovereignTramaiAutoConfiguration::class)
internal class SovereignTramaiProfileAutoConfiguration
