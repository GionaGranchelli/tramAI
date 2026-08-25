package dev.tramai.spring.sovereign

import dev.tramai.spring.sovereign.enablefixture.SovereignEnableTramaiFixture
import dev.tramai.spring.sovereign.enablefixture.SovereignEnableTramaiService
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.standalone.Tramai
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils

class EnableTramaiSovereignProfileTest {

    private val sovereignProperties = arrayOf(
        "tramai.profile=sovereign",
        "tramai.sovereign.allowed-models[0]=local-model",
        "tramai.sovereign.allowed-providers[0]=local-provider",
        "tramai.sovereign.provider-zones.local-provider=LOCAL",
        "tramai.sovereign.models.local-model=local-provider",
    )

    @Test
    fun `annotation driven context activates sovereign profile and discovers AiService`() {
        AnnotationConfigApplicationContext().use { context ->
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context,
                *sovereignProperties,
            )
            context.register(SovereignEnableTramaiFixture::class.java)
            context.refresh()

            assertNotNull(context.getBean(SovereignTramai::class.java))
            assertNull(context.getBeanProvider(Tramai::class.java).ifAvailable)
            val service = context.getBean(SovereignEnableTramaiService::class.java)
            assertEquals("SOVEREIGN_ENABLE_OK", runBlocking { service.analyze("invoice") })
        }
    }

    @Test
    fun `explicit annotation coexists with mixed classpath boot auto configuration`() {
        val standardProfileAutoConfiguration =
            Class.forName("dev.tramai.spring.StandardTramaiProfileAutoConfiguration")
        val standardProxyAutoConfiguration =
            Class.forName("dev.tramai.spring.AiServiceProxyAutoConfiguration")

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    standardProfileAutoConfiguration,
                    standardProxyAutoConfiguration,
                    SovereignTramaiProfileAutoConfiguration::class.java,
                    SovereignAiServiceProxyAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(SovereignEnableTramaiFixture::class.java)
            .withPropertyValues(*sovereignProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(SovereignTramai::class.java)
                assertThat(context).doesNotHaveBean(Tramai::class.java)
                assertThat(context).hasSingleBean(SovereignEnableTramaiService::class.java)
            }
    }
}
