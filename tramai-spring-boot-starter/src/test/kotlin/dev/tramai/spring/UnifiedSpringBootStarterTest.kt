package dev.tramai.spring

import dev.tramai.spring.unifiedfixture.UnifiedStarterAiService
import dev.tramai.spring.unifiedfixture.UnifiedStarterEnableTramaiFixture
import dev.tramai.spring.unifiedfixture.UnifiedStarterFixture
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.standalone.Tramai
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.test.context.support.TestPropertySourceUtils

/**
 * Proves the canonical S6 architecture on the unified starter artifact:
 * one starter, one programming model, one runtime authority per profile.
 *
 * The same application fixture ([UnifiedStarterFixture]) runs under both
 * profiles — only `tramai.profile` and the sovereign configuration change.
 */
class UnifiedSpringBootStarterTest {

    private val standardProfileAutoConfiguration: Class<*> =
        Class.forName("dev.tramai.spring.StandardTramaiProfileAutoConfiguration")

    private val standardAiServiceProxyAutoConfiguration: Class<*> =
        Class.forName("dev.tramai.spring.AiServiceProxyAutoConfiguration")

    private val sovereignProfileAutoConfiguration: Class<*> =
        Class.forName("dev.tramai.spring.sovereign.SovereignTramaiProfileAutoConfiguration")

    private val sovereignAiServiceProxyAutoConfiguration: Class<*> =
        Class.forName("dev.tramai.spring.sovereign.SovereignAiServiceProxyAutoConfiguration")

    private val unifiedAutoConfigurations = AutoConfigurations.of(
        standardProfileAutoConfiguration,
        standardAiServiceProxyAutoConfiguration,
        sovereignProfileAutoConfiguration,
        sovereignAiServiceProxyAutoConfiguration,
    )

    private val standardProperties = arrayOf(
        "tramai.models.local-model=local-provider",
        "tramai.default-provider=local-provider",
    )

    private val sovereignRuntimeProperties = arrayOf(
        "tramai.sovereign.allowed-models[0]=local-model",
        "tramai.sovereign.allowed-providers[0]=local-provider",
        "tramai.sovereign.provider-zones.local-provider=LOCAL",
        "tramai.sovereign.models.local-model=local-provider",
    )

    @Test
    fun `unified starter without profile defaults to standard runtime only`() {
        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(UnifiedStarterFixture::class.java)
            .withInitializer(::registerFixturePackage)
            .withPropertyValues(*standardProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(Tramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramaiRuntime::class.java)
                assertThat(context).hasSingleBean(UnifiedStarterAiService::class.java)
                assertServiceInvocation(context)
            }
    }

    @Test
    fun `standard profile selects standard runtime only`() {
        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(UnifiedStarterFixture::class.java)
            .withInitializer(::registerFixturePackage)
            .withPropertyValues("tramai.profile=standard", *standardProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(Tramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramaiRuntime::class.java)
                assertThat(context).hasSingleBean(UnifiedStarterAiService::class.java)
                assertServiceInvocation(context)
            }
    }

    @Test
    fun `sovereign profile selects sovereign runtime only`() {
        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(UnifiedStarterFixture::class.java)
            .withInitializer(::registerFixturePackage)
            .withPropertyValues("tramai.profile=sovereign", *sovereignRuntimeProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(Tramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
                assertThat(context).hasSingleBean(UnifiedStarterAiService::class.java)
                assertServiceInvocation(context)
            }
    }

    @Test
    fun `uppercase profile values behave identically`() {
        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(UnifiedStarterFixture::class.java)
            .withInitializer(::registerFixturePackage)
            .withPropertyValues("tramai.profile=STANDARD", *standardProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(Tramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
            }

        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(UnifiedStarterFixture::class.java)
            .withInitializer(::registerFixturePackage)
            .withPropertyValues("tramai.profile=SOVEREIGN", *sovereignRuntimeProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(Tramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
            }
    }

    @Test
    fun `invalid profile fails loudly instead of silently selecting a runtime`() {
        assertThatThrownBy {
            AnnotationConfigApplicationContext().use { context ->
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "tramai.profile=soveriegn",
                )
                context.register(UnifiedStarterEnableTramaiFixture::class.java)
                context.refresh()
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Unsupported tramai.profile 'soveriegn'. Supported values: standard, sovereign.",
            )
    }

    @Test
    fun `same application fixture switches runtime per profile`() {
        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(UnifiedStarterFixture::class.java)
            .withInitializer(::registerFixturePackage)
            .withPropertyValues("tramai.profile=standard", *standardProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(Tramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
            }

        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(UnifiedStarterFixture::class.java)
            .withInitializer(::registerFixturePackage)
            .withPropertyValues("tramai.profile=sovereign", *sovereignRuntimeProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(Tramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
            }
    }

    @Test
    fun `explicit EnableTramai coexists with unified starter boot auto configuration in standard`() {
        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(UnifiedStarterEnableTramaiFixture::class.java)
            .withPropertyValues(*standardProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(Tramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
                assertThat(context).hasSingleBean(UnifiedStarterAiService::class.java)
            }
    }

    @Test
    fun `explicit EnableTramai coexists with unified starter boot auto configuration in sovereign`() {
        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(UnifiedStarterEnableTramaiFixture::class.java)
            .withPropertyValues("tramai.profile=sovereign", *sovereignRuntimeProperties)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(Tramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
                assertThat(context).hasSingleBean(UnifiedStarterAiService::class.java)
            }
    }

    private fun assertServiceInvocation(context: org.springframework.context.ConfigurableApplicationContext) {
        val service = context.getBean(UnifiedStarterAiService::class.java)
        assertThat(runBlocking { service.analyze("invoice") }).isEqualTo("UNIFIED_STARTER_OK")
    }

    private fun registerFixturePackage(context: ConfigurableApplicationContext) {
        AutoConfigurationPackages.register(
            context.beanFactory as BeanDefinitionRegistry,
            "dev.tramai.spring.unifiedfixture",
        )
    }
}
