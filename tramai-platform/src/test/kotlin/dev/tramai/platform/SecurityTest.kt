package dev.tramai.platform

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class SecurityTest {
    private val objectMapper = jacksonObjectMapper()
    private val passwordEncoder = BCryptPasswordEncoder()

    @Test
    fun `api key service stores bcrypt hashes and authenticator validates the raw key`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val dataSource = platformDataSource("security-hash")
        val teamRepository = TeamRepository(dataSource)
        val projectRepository = ProjectRepository(dataSource)
        val apiKeyRepository = ApiKeyRepository(dataSource)
        val auditLogService = AuditLogService(AuditLogRepository(dataSource, objectMapper), clock)

        teamRepository.create(Team(id = "team-security", name = "Security Team"))
        projectRepository.create(Project(id = "project-security", teamId = "team-security", name = "Security Project"))

        val created = ApiKeyService(
            teamRepository = teamRepository,
            projectRepository = projectRepository,
            repository = apiKeyRepository,
            auditLogService = auditLogService,
            clock = clock,
            passwordEncoder = passwordEncoder,
        ).create(
            request = CreateApiKeyRequest(
                teamId = "team-security",
                projectId = "project-security",
                name = "admin",
                scopes = setOf("admin"),
            ),
            actorId = "bootstrap",
        )

        val stored = apiKeyRepository.findActiveByPrefix(created.record.prefix)

        assertThat(stored).isNotNull
        assertThat(stored!!.hashedKey)
            .startsWith("\$2")
            .isNotEqualTo(created.key)
        val authenticated = ApiKeyAuthenticator(
            repository = apiKeyRepository,
            clock = clock,
            passwordEncoder = passwordEncoder,
        ).authenticate(created.key)
        assertThat(authenticated.record.id).isEqualTo(created.record.id)
        assertThat(authenticated.record.lastUsedAt).isNull()
    }

    @Test
    fun `rate limiter refills tokens over elapsed monotonic time`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        var currentNanos = 0L
        val limiter = ApiKeyRateLimiter(
            clock = clock,
            nanoTimeSource = { currentNanos },
        )
        val record = ApiKeyRecord(
            id = "key-1",
            teamId = "team-security",
            projectId = "project-security",
            prefix = "tmr_test_prefix",
            name = "limited",
            scopes = setOf(ApiKeyScope.RUN),
            burstCapacity = 2,
            refillTokensPerSecond = 1.0,
            createdAt = clock.instant(),
            revokedAt = null,
            lastUsedAt = null,
        )

        val first = limiter.check(record)
        val second = limiter.check(record)
        val denied = limiter.check(record)

        currentNanos += 1_000_000_000L
        clock.instant = clock.instant.plusSeconds(1)

        val refilled = limiter.check(record)

        assertThat(first.allowed).isTrue()
        assertThat(first.remaining).isEqualTo(1)
        assertThat(second.allowed).isTrue()
        assertThat(second.remaining).isEqualTo(0)
        assertThat(denied.allowed).isFalse()
        assertThat(denied.retryAfterSeconds).isEqualTo(1)
        assertThat(refilled.allowed).isTrue()
        assertThat(refilled.remaining).isEqualTo(0)
    }
}

internal fun platformDataSource(name: String): org.h2.jdbcx.JdbcDataSource = org.h2.jdbcx.JdbcDataSource().apply {
    setURL("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1")
    user = "sa"
    password = ""
    Flyway.configure()
        .dataSource(this)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}

private class MutableClock(
    var instant: Instant,
    private val zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = instant

    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
}
