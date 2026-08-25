package dev.tramai.spring.sovereign

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Import

/**
 * Selects the sovereign TramAI runtime when `tramai.profile=sovereign`.
 *
 * The sovereign starter supplies that value as a low-precedence default for
 * backward compatibility. An explicit application property always wins.
 */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "tramai",
    name = ["profile"],
    havingValue = "sovereign",
)
@Import(SovereignTramaiAutoConfiguration::class)
internal class SovereignTramaiProfileAutoConfiguration
