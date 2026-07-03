package dev.tramai.examples.spring

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.test.context.ContextConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.sql.DataSource

/**
 * Smoke test for the sovereign-lab Spring profile.
 *
 * Proves that the profile boots correctly with:
 * - PostgreSQL persistence (embedded, no Docker required)
 * - Local provider configuration (no cloud endpoint needed)
 * - JDBC encryption key setup
 *
 * Does NOT require a real local model endpoint — the local provider URL
 * points to an unused localhost endpoint so CI can verify wiring without inference.
 *
 * The [dev.tramai.openai.OpenAiCompatibleProvider] bean for `local-lab-provider`
 * is auto-configured from `tramai.providers.*` YAML by
 * [dev.tramai.spring.provider.openai.OpenAiCompatibleProviderAutoConfiguration].
 */
@Tag("e2e")
@SpringBootTest(
    classes = [SpringSovereignStarterApplication::class],
    properties = [
        "spring.profiles.active=sovereign-lab",
        "TRAMAI_LOCAL_BASE_URL=http://localhost:9999/v1",
        "TRAMAI_LOCAL_MODEL=test-local-model",
        "TRAMAI_LOCAL_API_KEY=test-local-key",
        "tramai.sovereign.ops.mutations-enabled=true",
        "tramai.sovereign.ops.resume-enabled=true",
    ],
)
@ContextConfiguration(initializers = [LabProfileInitializer::class])
class SovereignLabProfileSmokeTest {

    companion object {
        /** Path to the temp encryption key file, set by [LabProfileInitializer]. */
        @JvmField
        var tempKeyFile: Path? = null

        @JvmStatic
        @AfterAll
        fun tearDown() {
            PgEmbeddedTestSupport.stop()
            tempKeyFile?.toFile()?.delete()
        }
    }

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var env: Environment

    @Test
    fun `sovereign lab profile boots with JDBC persistence and local provider`() {
        assertThat(context).isNotNull
        // Sovereign runtime bean exists (proves sovereign wiring is active)
        assertThat(context.getBean(dev.tramai.sovereign.SovereignTramaiRuntime::class.java)).isNotNull
    }

    @Test
    fun `datasource points to PostgreSQL`() {
        val ds = context.getBean(DataSource::class.java)
        ds.connection.use { conn ->
            assertThat(conn.metaData.databaseProductName)
                .describedAs("Database product name should be PostgreSQL")
                .isEqualTo("PostgreSQL")
        }
    }

    @Test
    fun `sovereign persistence type is JDBC`() {
        assertThat(env.getProperty("tramai.sovereign.persistence.type"))
            .describedAs("Persistence type must be jdbc for the lab profile")
            .isEqualTo("jdbc")
    }

    @Test
    fun `local provider config is present and auto-configured as Spring bean`() {
        // Property binding
        assertThat(env.getProperty("tramai.providers.local-lab-provider.type"))
            .describedAs("Local provider type must be openai")
            .isEqualTo("openai")
        assertThat(env.getProperty("tramai.providers.local-lab-provider.base-url"))
            .describedAs("Local provider must point to localhost (not a cloud endpoint)")
            .contains("localhost")
        assertThat(env.getProperty("tramai.providers.local-lab-provider.model"))
            .describedAs("Local provider model must be set")
            .isEqualTo("test-local-model")

        // Provider bean created by OpenAiCompatibleProviderAutoConfiguration
        val provider = context.getBean(dev.tramai.openai.OpenAiCompatibleProvider::class.java)
        assertThat(provider)
            .describedAs("Local OpenAI provider must be auto-configured as a Spring bean")
            .isNotNull
        assertThat(provider.providerId())
            .describedAs("Provider name must match the YAML key")
            .isEqualTo("local-lab-provider")
    }
}

/**
 * Top-level ApplicationContextInitializer that:
 * 1. Starts embedded PostgreSQL
 * 2. Creates the encryption key file and sets key-file property
 * 3. Registers a [HikariDataSource] bean pointing at the embedded PG
 *
 * We register DataSource as a singleton in the BeanFactory because
 * [SovereignJdbcPersistenceAutoConfiguration] evaluates
 * @ConditionalOnMissingBean(DataSource::class) before DataSourceAutoConfiguration
 * creates the DataSource from properties, so we must provide the bean directly.
 *
 * The [dev.tramai.openai.OpenAiCompatibleProvider] for `local-lab-provider`
 * is now auto-configured from YAML by
 * [dev.tramai.spring.provider.openai.OpenAiCompatibleProviderAutoConfiguration]
 * — no manual registration needed.
 */
class LabProfileInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(ctx: ConfigurableApplicationContext) {
        // Start embedded PostgreSQL (idempotent — singleton guard inside)
        PgEmbeddedTestSupport.start()

        // Create a temporary encryption key file for AES-256
        val keyFile = Files.createTempFile("sovereign-lab-key", ".key")
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        keyFile.toFile().writeText(key)

        // Register the key file path so the test companion object can clean it up
        SovereignLabProfileSmokeTest.tempKeyFile = keyFile

        // Register DataSource bean directly to avoid ordering issues with
        // SovereignJdbcPersistenceAutoConfiguration vs DataSourceAutoConfiguration.
        val ds = HikariDataSource().apply {
            jdbcUrl = PgEmbeddedTestSupport.jdbcUrl
            username = PgEmbeddedTestSupport.username
            password = PgEmbeddedTestSupport.password
            maximumPoolSize = 3
        }
        ctx.beanFactory.registerSingleton("dataSource", ds)
        // Register for proper cleanup — Spring may not close singletons
        // registered directly via registerSingleton the same way it closes
        // bean-defined DataSources.
        (ctx.beanFactory as? org.springframework.beans.factory.support.DefaultSingletonBeanRegistry)
            ?.registerDisposableBean("dataSource",
                org.springframework.beans.factory.DisposableBean { ds.close() })

        // Set the key-file property so SovereignJdbcPersistenceAutoConfiguration
        // can load the encryption key.
        val props = java.util.Properties()
        props.setProperty(
            "tramai.sovereign.persistence.encryption.key-file",
            keyFile.toAbsolutePath().toString(),
        )
        ctx.environment.propertySources.addFirst(
            org.springframework.core.env.PropertiesPropertySource("labProfileProps", props),
        )
    }
}
