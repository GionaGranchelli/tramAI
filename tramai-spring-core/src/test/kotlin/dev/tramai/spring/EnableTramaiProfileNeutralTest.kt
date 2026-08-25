package dev.tramai.spring

import dev.tramai.spring.enablefixture.StandardEnableTramaiFixture
import dev.tramai.spring.enablefixture.StandardEnableTramaiService
import dev.tramai.standalone.Tramai
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils

class EnableTramaiProfileNeutralTest {

    @Test
    fun `annotation driven context defaults to standard and discovers AiService`() {
        AnnotationConfigApplicationContext().use { context ->
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context,
                "tramai.models.local-model=local-provider",
                "tramai.default-provider=local-provider",
            )
            context.register(StandardEnableTramaiFixture::class.java)
            context.refresh()

            assertNotNull(context.getBean(Tramai::class.java))
            val service = context.getBean(StandardEnableTramaiService::class.java)
            assertEquals("STANDARD_ENABLE_OK", runBlocking { service.analyze("invoice") })
        }
    }

    @Test
    fun `explicit annotation coexists with boot auto configuration`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    StandardTramaiProfileAutoConfiguration::class.java,
                    AiServiceProxyAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(StandardEnableTramaiFixture::class.java)
            .withPropertyValues(
                "tramai.models.local-model=local-provider",
                "tramai.default-provider=local-provider",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(Tramai::class.java)
                assertThat(context).hasSingleBean(StandardEnableTramaiService::class.java)
            }
    }

    @Test
    fun `annotation driven context rejects unsupported runtime profile`() {
        assertThatThrownBy {
            AnnotationConfigApplicationContext().use { context ->
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "tramai.profile=soveriegn",
                )
                context.register(StandardEnableTramaiFixture::class.java)
                context.refresh()
            }
        }
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            .hasRootCauseMessage(
                "Unsupported tramai.profile 'soveriegn'. Supported values: standard, sovereign.",
            )
    }
}
