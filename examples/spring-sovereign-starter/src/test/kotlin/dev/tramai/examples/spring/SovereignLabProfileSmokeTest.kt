package dev.tramai.examples.spring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
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
@Tag("lab-smoke")
@SpringBootTest(
    classes = [SpringSovereignStarterApplication::class],
    properties = [
        "spring.profiles.active=sovereign-lab",
        "TRAMAI_LOCAL_BASE_URL=http://localhost:9999/v1",
        "TRAMAI_LOCAL_MODEL=test-local-model",
        "TRAMAI_LOCAL_API_KEY=test-local-key",
    ],
)
class SovereignLabProfileSmokeTest {

    companion object {
        /** Temporary encryption key created before context loads. */
        private lateinit var tempKeyFile: Path

        @JvmStatic
        @BeforeAll
        fun setUp() {
            PgEmbeddedTestSupport.start()
            tempKeyFile = Files.createTempFile("sovereign-lab-key", ".key")
            tempKeyFile.toFile().writeText("A3vP8xK9mN2qR5tW7yB4eH1jL0sU6cFd")
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            PgEmbeddedTestSupport.stop()
            if (::tempKeyFile.isInitialized) {
                tempKeyFile.toFile().delete()
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun pgProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PgEmbeddedTestSupport.jdbcUrl }
            registry.add("spring.datasource.username") { PgEmbeddedTestSupport.username }
            registry.add("spring.datasource.password") { PgEmbeddedTestSupport.password }
            registry.add("TRAMAI_SOVEREIGN_KEY_FILE") { tempKeyFile.toAbsolutePath().toString() }
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
