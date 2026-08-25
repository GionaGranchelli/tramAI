package dev.tramai.spring.sovereign

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.standalone.Tramai
import kotlin.test.Test
import kotlin.test.assertEquals
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class SpringRuntimeProfileSelectionTest {

    private val standardProfileAutoConfiguration: Class<*> =
        Class.forName("dev.tramai.spring.StandardTramaiProfileAutoConfiguration")

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                standardProfileAutoConfiguration,
                SovereignTramaiProfileAutoConfiguration::class.java,
            ),
        )
        .withBean(ModelProvider::class.java, { ProfileTestProvider() })

    @Test
    fun `sovereign profile activates only sovereign runtime`() {
        runner
            .withPropertyValues(
                "tramai.profile=sovereign",
                "tramai.sovereign.allowed-models[0]=local-model",
                "tramai.sovereign.allowed-providers[0]=local-provider",
                "tramai.sovereign.provider-zones.local-provider=LOCAL",
                "tramai.sovereign.models.local-model=local-provider",
            )
            .run { context ->
                assertThat(context).hasSingleBean(SovereignTramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
                assertThat(context).doesNotHaveBean(Tramai::class.java)
            }
    }

    @Test
    fun `standard profile activates only standard runtime even with sovereign starter present`() {
        runner
            .withPropertyValues(
                "tramai.profile=standard",
                "tramai.models.local-model=local-provider",
                "tramai.default-provider=local-provider",
            )
            .run { context ->
                assertThat(context).hasSingleBean(Tramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramaiRuntime::class.java)
            }
    }

    @Test
    fun `sovereign starter defaults profile to sovereign when application did not choose one`() {
        val environment = StandardEnvironment()
        SovereignDefaultProfileEnvironmentPostProcessor()
            .postProcessEnvironment(environment, SpringApplication())

        assertEquals("sovereign", environment.getProperty("tramai.profile"))
    }

    @Test
    fun `explicit application profile wins over sovereign starter default`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "test",
                mapOf("tramai.profile" to "standard"),
            ),
        )

        SovereignDefaultProfileEnvironmentPostProcessor()
            .postProcessEnvironment(environment, SpringApplication())

        assertEquals("standard", environment.getProperty("tramai.profile"))
    }

    private class ProfileTestProvider : ModelProvider {
        override fun providerId(): String = "local-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "stub response")
    }
}
