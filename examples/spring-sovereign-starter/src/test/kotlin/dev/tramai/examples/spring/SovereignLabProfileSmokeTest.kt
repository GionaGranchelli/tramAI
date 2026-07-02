package dev.tramai.examples.spring

import com.zaxxer.hikari.HikariDataSource
import dev.tramai.openai.OpenAiCompatibleProvider
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
 * points to a non-routable address so CI can verify wiring without inference.
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
    fun `local provider config is present and points to localhost`() {
        assertThat(env.getProperty("tramai.providers.local-lab-provider.type"))
            .describedAs("Local provider type must be openai")
            .isEqualTo("openai")
        assertThat(env.getProperty("tramai.providers.local-lab-provider.base-url"))
            .describedAs("Local provider must point to localhost (not a cloud endpoint)")
            .contains("localhost")
        assertThat(env.getProperty("tramai.providers.local-lab-provider.model"))
            .describedAs("Local provider model must be set")
            .isEqualTo("test-local-model")
    }
}

/**
 * Top-level ApplicationContextInitializer that:
 * 1. Starts embedded PostgreSQL
 * 2. Creates the encryption key file and sets key-file property
 * 3. Registers a [HikariDataSource] bean pointing at the embedded PG
 * 4. Registers a [dev.tramai.core.provider.ModelProvider] bean for `local-lab-provider`
 *
 * We register DataSource and ModelProvider as singletons in the BeanFactory
 * because:
 * - [SovereignJdbcPersistenceAutoConfiguration] evaluates
 *   @ConditionalOnMissingBean(DataSource::class) before DataSourceAutoConfiguration
 *   creates the DataSource from properties, so we must provide the bean directly.
 * - The sovereign-lab YAML's `tramai.providers.local-lab-provider` is a free-form
 *   property not mapped by [dev.tramai.spring.TramaiProperties.Providers], so no
 *   ModelProvider bean is auto-created. We register one here explicitly so the
 *   sovereign runtime's @ConditionalOnBean(ModelProvider::class) condition passes.
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

        // Register a ModelProvider bean for local-lab-provider so the
        // sovereign runtime's @ConditionalOnBean(ModelProvider::class) passes
        // and all allowed providers are registered.
        val localProvider = OpenAiCompatibleProvider.bearerToken(
            bearerToken = "test-local-key",
            baseUrl = "http://localhost:9999/v1",
            providerName = "local-lab-provider",
        )
        ctx.beanFactory.registerSingleton("localLabModelProvider", localProvider)

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
