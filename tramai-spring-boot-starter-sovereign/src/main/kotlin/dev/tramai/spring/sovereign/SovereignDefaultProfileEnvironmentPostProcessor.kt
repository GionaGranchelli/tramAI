package dev.tramai.spring.sovereign

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Preserves the sovereign starter's historical behavior by selecting the
 * sovereign runtime profile when the application has not selected one.
 *
 * The property source is deliberately lowest precedence and is added only when
 * `tramai.profile` is absent, so command-line, environment, YAML, tests, and
 * other application configuration always win.
 */
internal class SovereignDefaultProfileEnvironmentPostProcessor :
    EnvironmentPostProcessor,
    Ordered {

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (environment.containsProperty(TRAMAI_PROFILE_PROPERTY)) {
            return
        }
        environment.propertySources.addLast(
            MapPropertySource(
                PROPERTY_SOURCE_NAME,
                mapOf(TRAMAI_PROFILE_PROPERTY to SOVEREIGN_PROFILE),
            ),
        )
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    private companion object {
        const val TRAMAI_PROFILE_PROPERTY = "tramai.profile"
        const val SOVEREIGN_PROFILE = "sovereign"
        const val PROPERTY_SOURCE_NAME = "tramaiSovereignDefaultProfile"
    }
}
