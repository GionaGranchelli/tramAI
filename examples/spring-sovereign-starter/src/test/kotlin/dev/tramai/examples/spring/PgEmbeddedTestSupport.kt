package dev.tramai.examples.spring

import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource

/**
 * Test-only support for managing a shared embedded PostgreSQL instance.
 *
 * Starts a single native PostgreSQL process per test run (no Docker required),
 * applies TramAI JDBC schema migrations once, and reuses the same database
 * across all E2E tests in the class.
 *
 * Usage:
 * ```
 * @BeforeAll fun startPg() { PgEmbeddedTestSupport.start() }
 * @AfterAll fun stopPg() { PgEmbeddedTestSupport.stop() }
 * ```
 */
object PgEmbeddedTestSupport {

    /** Lazily initialized and shared across all E2E test classes. */
    @Volatile
    private var holder: EmbeddedPgHolder? = null

    fun start() {
        if (holder != null) return
        synchronized(this) {
            if (holder != null) return
            val pg = EmbeddedPostgres.start()
            val ds = HikariDataSource().apply {
                jdbcUrl = pg.getJdbcUrl("postgres", "postgres")
                username = "postgres"
                password = "postgres"
                maximumPoolSize = 10
            }
            JdbcSchemaTestSupport.applyMigrations(ds)
            holder = EmbeddedPgHolder(pg, ds)
        }
    }

    val jdbcUrl: String get() = holder!!.pg.getJdbcUrl("postgres", "postgres")
    val username: String get() = "postgres"
    val password: String get() = "postgres"

    fun newDataSource(): DataSource = HikariDataSource().apply {
        this.jdbcUrl = jdbcUrl
        this.username = username
        this.password = password
        this.maximumPoolSize = 5
    }

    fun stop() {
        synchronized(this) {
            holder?.let {
                (it.ds as? AutoCloseable)?.close()
                it.pg.close()
            }
            holder = null
        }
    }

    private data class EmbeddedPgHolder(
        val pg: EmbeddedPostgres,
        val ds: DataSource,
    )
}
