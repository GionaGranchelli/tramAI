package dev.tramai.spring.compatibility

import dev.tramai.spring.compatibilityfixture.TckAiService
import dev.tramai.spring.compatibilityfixture.TckCompatibilityFixture
import dev.tramai.spring.compatibilityfixture.TckConsumer
import dev.tramai.spring.compatibilityfixture.TckEnableTramaiFixture
import dev.tramai.spring.AiToolScanner
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext

/**
 * One runtime profile's view of the compatibility contract: which authority
 * beans must/must not exist and which runtime configuration to apply.
 */
internal data class RuntimeProfileHarness(
    val displayName: String,
    val profileProperty: String,
    val runtimeType: Class<*>,
    val forbiddenRuntimeType: Class<*>,
    val sovereignRuntimeType: Class<*>?,
    val properties: Array<String>,
)

/**
 * Reusable S7 compatibility contract: proves that the standard and sovereign
 * runtime profiles expose the SAME application-facing Spring programming
 * model.
 *
 * One application fixture ([TckCompatibilityFixture]) runs under each profile
 * harness; only `tramai.profile` and runtime configuration change.
 *
 * The contract is profile-NEUTRAL. It does NOT assert equal security
 * outcomes — sovereign intentionally enforces stricter guarantees. Profile-
 * specific governance enforcement stays in the sovereign module tests
 * (e.g. SovereignAiToolScanningTest).
 *
 * Follows the repository TCK convention (StructuredOutputContractTck):
 * an internal contract class executed by a thin JUnit driver per
 * implementation — here, per runtime profile.
 */
internal class SpringRuntimeProfileCompatibilityContract(
    private val harness: RuntimeProfileHarness,
) {

    private val unifiedAutoConfigurations = AutoConfigurations.of(
        Class.forName("dev.tramai.spring.StandardTramaiProfileAutoConfiguration"),
        Class.forName("dev.tramai.spring.AiServiceProxyAutoConfiguration"),
        Class.forName("dev.tramai.spring.sovereign.SovereignTramaiProfileAutoConfiguration"),
        Class.forName("dev.tramai.spring.sovereign.SovereignAiServiceProxyAutoConfiguration"),
    )

    fun verify() {
        verifyPlainBootFixture()
        verifyExplicitEnableTramaiFixture()
    }

    /** Canonical Boot experience: @SpringBootApplication-equivalent fixture, no @EnableTramai. */
    private fun verifyPlainBootFixture() {
        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(TckCompatibilityFixture::class.java)
            .withInitializer(::registerFixturePackage)
            .withPropertyValues(harness.profileProperty, *harness.properties)
            .run { context ->
                assertThat(context).hasNotFailed()

                // Runtime authority: exactly one, never both.
                verifyRuntimeAuthority(context)

                // @AiService discovery + injection + invocation.
                assertThat(context).hasSingleBean(TckAiService::class.java)
                val service = context.getBean(TckAiService::class.java)
                assertThat(runBlocking { service.analyze("invoice") }).isEqualTo("TCK_OK")

                // Named AiService creator: present and functional.
                assertThat(context.getBeanFactory().containsBean("tramaiAiServiceCreator")).isTrue()
                assertThat(context.getBean("tramaiAiServiceCreator")).isNotNull()

                // Constructor injection into application code under both profiles.
                val consumer = context.getBean(TckConsumer::class.java)
                assertThat(consumer.invokeAi("invoice")).isEqualTo("TCK_OK")

                // @AiTool discovery parity at the programming-model level.
                verifyToolDiscovery(context)
            }
    }

    /** Explicit @EnableTramai must coexist with Boot auto-config without duplicates. */
    private fun verifyExplicitEnableTramaiFixture() {
        ApplicationContextRunner()
            .withConfiguration(unifiedAutoConfigurations)
            .withUserConfiguration(TckEnableTramaiFixture::class.java)
            .withInitializer(::registerFixturePackage)
            .withPropertyValues(harness.profileProperty, *harness.properties)
            .run { context ->
                assertThat(context).hasNotFailed()
                verifyRuntimeAuthority(context)
                assertThat(context).hasSingleBean(TckAiService::class.java)
                // A duplicate creator bean name would fail context startup;
                // the named creator contract therefore holds iff startup succeeds
                // and the bean is resolvable.
                assertThat(context.getBeanFactory().containsBean("tramaiAiServiceCreator")).isTrue()
                assertThat(context.getBean("tramaiAiServiceCreator")).isNotNull()
            }
    }

    private fun verifyRuntimeAuthority(context: AssertableApplicationContext) {
        assertThat(context).hasSingleBean(harness.runtimeType)
        assertThat(context).doesNotHaveBean(harness.forbiddenRuntimeType)
        harness.sovereignRuntimeType?.let { sovereignRuntimeType ->
            assertThat(context).hasSingleBean(sovereignRuntimeType)
        }
    }

    /**
     * Both runtimes wire the SAME shared [AiToolScanner] at startup; asserting
     * scanner output against the started context pins identical discovery and
     * identical governed-metadata mapping under both profiles.
     */
    private fun verifyToolDiscovery(context: AssertableApplicationContext) {
        val tools = AiToolScanner.fromApplicationContext(context)

        val governed = tools.single { it.name == "tck-schedule-payment" }
        val security = governed.security
        assertThat(security).isNotNull()
        security!!.let {
            assertThat(it.permission).isEqualTo("payment.schedule")
            assertThat(it.risk).isEqualTo(dev.tramai.core.policy.RiskLevel.LOW)
            assertThat(it.approval).isEqualTo(dev.tramai.core.policy.ApprovalMode.AUTO)
            assertThat(it.managedNetworkEgress).isEqualTo(dev.tramai.core.policy.ManagedNetworkEgress.DENY)
            assertThat(it.audit).isEqualTo(dev.tramai.core.policy.AuditDetail.FULL)
        }

        val legacy = tools.single { it.name == "tck-legacy-payment" }
        assertThat(legacy.security).isNull()
    }

    private fun registerFixturePackage(context: ConfigurableApplicationContext) {
        AutoConfigurationPackages.register(
            context.beanFactory as BeanDefinitionRegistry,
            "dev.tramai.spring.compatibilityfixture",
        )
    }
}
