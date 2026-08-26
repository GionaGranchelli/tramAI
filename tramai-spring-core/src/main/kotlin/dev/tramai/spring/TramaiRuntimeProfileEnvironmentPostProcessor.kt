package dev.tramai.spring

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Fails Spring Boot startup early when `tramai.profile` contains an unsupported
 * value. Missing profile remains valid because profile modules own their
 * compatibility defaults.
 */
internal class TramaiRuntimeProfileEnvironmentPostProcessor :
    EnvironmentPostProcessor,
    Ordered {

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        TramaiRuntimeProfileSupport.validate(
            environment.getProperty(TramaiRuntimeProfileSupport.PROPERTY_NAME),
        )
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE
}
