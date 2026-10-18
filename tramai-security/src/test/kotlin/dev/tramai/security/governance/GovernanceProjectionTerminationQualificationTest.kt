package dev.tramai.security.governance

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test

/**
 * 0.7.4g — R5, an interrupted rebuild: the two cases a same-process test cannot reach.
 *
 * (A) committed but not acknowledged. A publish commits and the caller never learns the outcome,
 * because the process dies between the commit and the return. The database must still hold the whole
 * new snapshot, a later reader must rebuild it through the store's own load path, and re-publishing
 * the same content must answer ALREADY_CURRENT instead of writing a second version or duplicating
 * child rows.
 *
 * (B) abrupt termination. A child JVM is SIGKILLed while its publish transaction is open, after the
 * header upsert has been executed on the server and before any child row is written. PostgreSQL can
 * only leave the previously committed snapshot (the open transaction rolls back) or the fully
 * committed new one; the parent asserts exactly that from outside the killed process, byte-comparing
 * the row contents of all three tables for the key, and requires header + facts + decisions to be
 * self-consistent through the digest check the store performs on load.
 *
 * Both cases need a real PostgreSQL that outlives the JVM that writes to it and a real kill, so this
 * is an opt-in harness rather than a unit test: `scripts/qualify-termination-recovery.sh` starts a
 * container it owns, then runs this class once, in one JVM, which spawns the children itself.
 *
 * Determinism, stated plainly, because a kill test that cannot say when it killed proves nothing:
 *   - case B holds the child inside the transaction at a marker printed *after* the header upsert
 *     statement returned rows (`executeQuery` on the UPSERT_HEADER text, intercepted by a JDBC proxy
 *     in the child, never by a change to the store). The parent waits for that marker, then
 *     `destroyForcibly()` — SIGKILL — and records the exit status as the proof a kill happened.
 *   - case A's child prints its marker only after `publish` has returned, i.e. after `commit()`
 *     returned, and never reports the outcome. The parent kills it there, so the commit exists and no
 *     caller received its result.
 *
 * When `GOV_TERM_URL` is absent the test is skipped, which is what happens in CI: an opt-in
 * qualification that says so rather than silently qualifying nothing.
 */
class GovernanceProjectionTerminationQualificationTest {
    @Test
    fun `a killed publish leaves the previous projection or the fully committed new one, never a mixture`() =
        runBlocking<Unit> {
            val source = dataSource()
            schemaApplied(source)
            val store = JdbcGovernanceProjectionStore(source)

            val first = snapshot(HEAD_FIRST, "hash-$HEAD_FIRST", "policy_allowed")
            assertThat(store.publish(first)).isEqualTo(MaterializationOutcome.PUBLISHED)
            val baselineDump = dump(source)
            assertThat(counts(source)).describedAs("the baseline projection").containsExactly(1, FACT_COUNT, 1)

            // ---- (B) SIGKILL inside the transaction: after the header upsert, before any child row.
            val interrupted = snapshot(HEAD_INTERRUPTED, "hash-$HEAD_INTERRUPTED", "policy_allowed")
            val child = launch("in-transaction", HEAD_INTERRUPTED)
            val marker = awaitMarker(child)
            assertThat(marker).describedAs("the kill point is the open transaction").contains("head=$HEAD_INTERRUPTED")
            // The child has executed the header upsert and is holding its transaction open. Unless the
            // write is still uncommitted, the kill proves nothing about atomicity: a reader must still
            // see the previous projection, header and children alike.
            assertThat(visibleDigest(source))
                .describedAs("the child's header write is uncommitted and invisible to a reader")
                .isEqualTo(first.semanticDigest)
            assertThat(counts(source)).containsExactly(1, FACT_COUNT, 1)
            assertThat(child.isAlive).isTrue()
            val interruptedExit = killProven(child)
            awaitNoOtherBackends(source)

            val recovered =
                store.load(identity()) ?: error("the interrupted rebuild left no readable projection at all")
            val afterKill = dump(source)
            assertThat(counts(source))
                .describedAs("exactly one version of the projection, with its facts and decisions")
                .containsExactly(1, FACT_COUNT, 1)
            assertThat(recovered.snapshot.semanticDigest)
                .describedAs("the row is one of the two committed snapshots, rebuilt and digest-checked")
                .isIn(first.semanticDigest, interrupted.semanticDigest)

            // Equality with the baseline dump is the byte-identical claim for the rollback branch; the
            // commit branch is proved by the store's own digest check plus exact child counts above, and
            // by the row contents differing from the baseline.
            if (recovered.snapshot.semanticDigest == first.semanticDigest) {
                assertThat(afterKill)
                    .describedAs("the killed transaction rolled back: the previous projection is byte-identical")
                    .isEqualTo(baselineDump)
                println(
                    "R5-B: kill landed inside the transaction (child exit $interruptedExit after SIGKILL); " +
                        "database holds the previously committed projection, byte-identical",
                )
            } else {
                assertThat(afterKill).describedAs("the new projection committed in full").isNotEqualTo(baselineDump)
                assertThat(recovered.materializedAt).isNotNull()
                println(
                    "R5-B: kill landed inside the transaction (child exit $interruptedExit after SIGKILL); " +
                        "database holds the fully committed new projection",
                )
            }

            // ---- (A) commit returns, the outcome is never delivered.
            val committed = snapshot(HEAD_COMMITTED, "hash-$HEAD_COMMITTED", "policy_allowed_after_restart")
            val afterCommit = launch("committed", HEAD_COMMITTED)
            val committedMarker = awaitMarker(afterCommit)
            assertThat(committedMarker)
                .describedAs("the child committed before it was killed")
                .contains("outcome=PUBLISHED")
                .contains(committed.semanticDigest)
            // Commit happened; the outcome went nowhere. The row is visible before the kill and must
            // still be there, unchanged, after it.
            assertThat(visibleDigest(source)).isEqualTo(committed.semanticDigest)
            assertThat(afterCommit.isAlive).isTrue()
            val committedExit = killProven(afterCommit)
            awaitNoOtherBackends(source)

            val unacknowledged =
                store.load(identity()) ?: error("a committed, unacknowledged publish left nothing to read")
            assertThat(unacknowledged.snapshot.semanticDigest)
                .describedAs("the commit survived the process that never reported it")
                .isEqualTo(committed.semanticDigest)
            assertThat(unacknowledged.materializedAt).isNotNull()
            assertThat(counts(source))
                .describedAs("no second version and no duplicated children")
                .containsExactly(1, FACT_COUNT, 1)
            val committedDump = dump(source)

            assertThat(store.publish(committed))
                .describedAs("a retry of the same publish is recognised, not applied")
                .isEqualTo(MaterializationOutcome.ALREADY_CURRENT)
            assertThat(dump(source))
                .describedAs("the retry wrote nothing")
                .isEqualTo(committedDump)
            assertThat(counts(source)).containsExactly(1, FACT_COUNT, 1)
            println(
                "R5-A: committed-but-unacknowledged projection is readable and a retry reports " +
                    "ALREADY_CURRENT (child exit $committedExit after SIGKILL, outcome delivered to nobody)",
            )

            // ---- Control: the load-based assertion above must reject the mixture a non-atomic writer
            // could leave. Without this, "never a partial mixture" would be a claim about a check that
            // was never shown to fire.
            tamper(source, "UPDATE governance_projection_snapshot SET semantic_digest = ? WHERE projection_key = ?", first.semanticDigest, identity().key)
            assertCorruption(source, "a header that disagrees with its children")
            tamper(source, "UPDATE governance_projection_snapshot SET semantic_digest = ? WHERE projection_key = ?", committed.semanticDigest, identity().key)
            tamper(source, "DELETE FROM governance_projection_fact WHERE projection_key = ? AND fact = ?", identity().key, FACT_TELEMETRY)
            assertCorruption(source, "a header whose children are missing")
            println("R5-CONTROL: a partial mixture is refused by load, so the assertions above discriminate")
        }

    private fun assertCorruption(
        source: DataSource,
        what: String,
    ) {
        val failure =
            runCatching {
                runBlocking { JdbcGovernanceProjectionStore(source).load(identity()) }
            }.exceptionOrNull()
        assertThat(failure)
            .describedAs("$what must be refused, not read back as a snapshot")
            .isInstanceOf(ProjectionCorruptionException::class.java)
    }

    private fun tamper(
        source: DataSource,
        sql: String,
        vararg arguments: String,
    ) {
        source.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                arguments.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    /** One row per table for the key, in a stable rendering: the byte comparison is of these strings. */
    private fun dump(source: DataSource): String =
        source.connection.use { connection ->
            TABLES.joinToString(separator = "\n--\n") { table ->
                connection
                    .prepareStatement("SELECT row_to_json(t)::text FROM $table t WHERE projection_key = ? ORDER BY 1")
                    .use { statement ->
                        statement.setString(1, identity().key)
                        statement.executeQuery().use { rows ->
                            buildList { while (rows.next()) add(rows.getString(1)) }.joinToString("\n")
                        }
                    }
            }
        }

    /** The digest a reader sees right now, without the store's rebuild: visibility, not validity. */
    private fun visibleDigest(source: DataSource): String? =
        source.connection.use { connection ->
            connection
                .prepareStatement("SELECT semantic_digest FROM governance_projection_snapshot WHERE projection_key = ?")
                .use { statement ->
                    statement.setString(1, identity().key)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
        }

    private fun counts(source: DataSource): List<Int> =
        source.connection.use { connection ->
            TABLES.map { table ->
                connection
                    .prepareStatement("SELECT count(*) FROM $table WHERE projection_key = ?")
                    .use { statement ->
                        statement.setString(1, identity().key)
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
            }
        }

    /**
     * Waits until the killed child's backend is gone. Reads are MVCC and would not block, but the
     * retry in case A writes, and a backend still holding the interrupted transaction's locks would
     * make that race the kill rather than the property under test.
     */
    private fun awaitNoOtherBackends(source: DataSource) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (true) {
            val others =
                source.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement
                            .executeQuery(
                                "SELECT count(*) FROM pg_stat_activity " +
                                    "WHERE datname = current_database() AND pid <> pg_backend_pid()",
                            ).use { rows ->
                                rows.next()
                                rows.getInt(1)
                            }
                    }
                }
            if (others == 0) return
            check(System.nanoTime() < deadline) { "a killed child's database backend is still present after 60s" }
            Thread.sleep(100)
        }
    }

    private fun launch(
        mode: String,
        head: Long,
    ): Process {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val builder =
            ProcessBuilder(java, "-cp", childClasspath(), javaClass.name, mode, head.toString())
                .redirectErrorStream(true)
        builder.environment()["GOV_TERM_URL"] = env("GOV_TERM_URL")
        builder.environment()["GOV_TERM_USER"] = env("GOV_TERM_USER")
        builder.environment()["GOV_TERM_PASSWORD"] = env("GOV_TERM_PASSWORD")
        return builder.start()
    }

    /**
     * The child needs this module's test runtime classpath, which the JVM running this test already
     * has. Under Gradle the worker's `java.class.path` is the worker jar, so the loader's own URLs are
     * the authoritative answer; the property is only a fallback.
     */
    private fun childClasspath(): String {
        val fromLoader =
            (javaClass.classLoader as? URLClassLoader)?.urLs?.joinToString(File.pathSeparator) { it.path }
        if (fromLoader != null && fromLoader.contains("tramai-security")) return fromLoader
        val property = System.getProperty("java.class.path").orEmpty()
        require(property.contains("tramai-security")) {
            "cannot build a child classpath: neither the loader nor java.class.path names tramai-security"
        }
        return property
    }

    /** The first line the child prints at its kill point, or a failure that says what went wrong. */
    private fun awaitMarker(process: Process): String {
        val lines = LinkedBlockingQueue<String>()
        Thread {
            process.inputStream.bufferedReader().forEachLine { lines.put(it) }
        }.apply {
            isDaemon = true
            start()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        val seen = StringBuilder()
        while (System.nanoTime() < deadline) {
            val line = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            seen.append(line).append('\n')
            if (line.startsWith(MARKER)) return line
            if (!process.isAlive) {
                error("the child exited (status ${process.exitValue()}) before reaching its marker:\n$seen")
            }
        }
        error("the child never reached its marker within 120s:\n$seen")
    }

    /**
     * SIGKILL, and the record that it happened: `destroyForcibly` on this platform is SIGKILL, and
     * the JVM reports a signal death as 128 + 9. A child that exited on its own would not produce
     * 137, so the assertion is about the kill rather than about the test merely finishing.
     */
    private fun killProven(process: Process): Int {
        assertThat(process.isAlive).describedAs("the child must be alive when the kill lands").isTrue()
        process.destroyForcibly()
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).describedAs("the child did not terminate").isTrue()
        assertThat(process.isAlive).isFalse()
        val exit = process.exitValue()
        assertThat(exit).describedAs("death by SIGKILL").isEqualTo(137)
        return exit
    }

    private fun dataSource(): DataSource {
        assumeTrue(
            System.getenv("GOV_TERM_URL")?.isNotBlank() == true,
            "the termination qualification needs the database the harness started (GOV_TERM_URL)",
        )
        return PGSimpleDataSource().apply {
            setUrl(env("GOV_TERM_URL"))
            user = env("GOV_TERM_USER")
            password = env("GOV_TERM_PASSWORD")
        }
    }

    private fun schemaApplied(source: DataSource): DataSource {
        val migration = "V1__governance_projection_read_model.sql"
        val sql =
            this::class.java.classLoader
                .getResource("tramai/security/governance/postgres/$migration")
                ?.readText()
                ?: error("Migration not found: $migration")
        source.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
        return source
    }

    private fun identity() = ProjectionIdentity(WORKLOAD, ENVIRONMENT, DEPLOYMENT, WORKFLOW_RUN_ID)

    private fun snapshot(
        head: Long,
        headHash: String,
        reasonCode: String,
    ): GovernanceProjectionSnapshot =
        GovernanceProjectionSnapshot(
            formatVersion = GOVERNANCE_READ_MODEL_FORMAT_VERSION,
            identity = identity(),
            registration = registrationFact(),
            declaredRunIdentity = declaredFact(),
            telemetry = telemetryFact(head),
            decisions =
                listOf(
                    SnapshotDecision(
                        kind = DecisionKind.POLICY_DECISION,
                        completeness = Completeness.GAPS_PRESENT,
                        coverage =
                            SnapshotCoverage(
                                verifiedRange = 1L..head,
                                accountedPositions = head,
                                unrecordedDecisionPositions = setOf(head - 1),
                                unclassifiedPositions = setOf(UNCLASSIFIED_POSITION),
                                observedHeadHash = headHash,
                                classificationRevision = GOVERNANCE_CLASSIFICATION_REVISION,
                            ),
                        latest = decisionFact(head),
                        latestDecision = "ALLOW",
                        latestReasonCode = reasonCode,
                        latestBoundaryId = "BEFORE_PROVIDER_INVOCATION",
                        latestBoundaryStrength = null,
                        duplicatesIgnored = 0,
                        staleIgnored = 0,
                    ),
                ),
            provenance =
                SourceProvenance(
                    registrationVersion = REGISTRATION_VERSION,
                    auditHeadPosition = head,
                    auditHeadHash = headHash,
                    classificationRevision = GOVERNANCE_CLASSIFICATION_REVISION,
                ),
        )

    private fun registrationFact() =
        SnapshotFact(
            authority = FactAuthority.AUTHORITATIVE_PERSISTED,
            freshness = FactFreshness.CURRENT_AT_VERSION,
            established = "the registration authority reports this workload at the version named",
            mustNotInfer = listOf("registration is not a governance decision"),
            valuePresent = true,
            observedVersion = REGISTRATION_VERSION,
        )

    private fun declaredFact() =
        SnapshotFact(
            authority = FactAuthority.PRODUCER_DECLARED,
            freshness = FactFreshness.HISTORICAL,
            established = "a producer declared this identity for the run; it is not authenticated",
            mustNotInfer = listOf("declared identity is not authenticated identity"),
            valuePresent = true,
            sourceVersion = REGISTRATION_VERSION,
        )

    private fun telemetryFact(head: Long) =
        SnapshotFact(
            authority = FactAuthority.TELEMETRY,
            freshness = FactFreshness.HISTORICAL,
            established = "an audit stream is evidence, not telemetry; the read reported no observations",
            mustNotInfer = listOf("a non-authoritative observation cannot establish a governance fact"),
            valuePresent = true,
            sourceVersion = head,
        )

    private fun decisionFact(head: Long) =
        SnapshotFact(
            authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
            freshness = FactFreshness.HISTORICAL,
            established = "a recorded policy decision at a source position",
            mustNotInfer = listOf("a policy ALLOW is authorization, not completion"),
            valuePresent = true,
            sourceVersion = head,
        )

    companion object {
        private const val MARKER = "CHILD-MARKER: "
        private const val WORKLOAD = "claims"
        private const val ENVIRONMENT = "production"
        private const val DEPLOYMENT = "eu-west-amsterdam-01"
        private const val WORKFLOW_RUN_ID = "run-1"
        private const val REGISTRATION_VERSION = 1L
        private const val UNCLASSIFIED_POSITION = 2L
        private const val HEAD_FIRST = 5L
        private const val HEAD_INTERRUPTED = 6L
        private const val HEAD_COMMITTED = 7L
        private const val FACT_COUNT = 3
        private const val FACT_TELEMETRY = "telemetry"
        private val TABLES =
            listOf(
                "governance_projection_snapshot",
                "governance_projection_fact",
                "governance_projection_decision",
            )

        /**
         * The child side: a second JVM that publishes and is killed by the parent. `in-transaction`
         * holds after the header upsert executes; `committed` publishes, then holds after the commit
         * returned and its outcome was reported to nobody. This is the same JVM entry point, not a
         * reimplementation of the publish path.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            val mode = args.getOrNull(0) ?: error("usage: <in-transaction|committed> <audit head position>")
            val head =
                args.getOrNull(1)?.toLongOrNull()
                    ?: error("usage: <in-transaction|committed> <audit head position>")
            val source =
                PGSimpleDataSource().apply {
                    setUrl(env("GOV_TERM_URL"))
                    user = env("GOV_TERM_USER")
                    password = env("GOV_TERM_PASSWORD")
                }
            val reasonCode = if (mode == "committed") "policy_allowed_after_restart" else "policy_allowed"
            val snapshot =
                GovernanceProjectionTerminationQualificationTest().snapshot(head, "hash-$head", reasonCode)
            runBlocking {
                when (mode) {
                    "in-transaction" -> {
                        // The proxy only pauses; the SQL, the transaction and the commit stay the store's.
                        val pausing =
                            JdbcGovernanceProjectionStore(
                                pausingDataSource(source) { hold("header-upserted head=$head") },
                            )
                        val outcome = pausing.publish(snapshot)
                        error("publish completed with $outcome: the kill did not land inside the transaction")
                    }

                    "committed" -> {
                        val outcome = JdbcGovernanceProjectionStore(source).publish(snapshot)
                        println("$MARKER publish-returned outcome=$outcome digest=${snapshot.semanticDigest}")
                        System.out.flush()
                        Thread.sleep(HOLD_MILLIS)
                        error("the parent did not terminate this process after the commit")
                    }

                    else -> error("unknown mode $mode")
                }
            }
        }

        private const val HOLD_MILLIS = 120_000L

        private fun env(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name is not set")

        /** Prints the kill point and waits to be killed. Reaching the timeout is a harness failure. */
        private fun hold(label: String) {
            println("$MARKER $label")
            System.out.flush()
            Thread.sleep(HOLD_MILLIS)
            error("the parent did not terminate this process at the marker")
        }

        /**
         * A DataSource that pauses once, when the store executes its header upsert. It observes the
         * statement text the store already sends; it changes no SQL and no transaction boundary.
         */
        private fun pausingDataSource(
            delegate: DataSource,
            pause: () -> Unit,
        ): DataSource =
            delegating(DataSource::class.java, delegate) { method, args ->
                if (method.name == "getConnection") pausingConnection(delegate.connection, pause) else call(delegate, method, args)
            } as DataSource

        private fun pausingConnection(
            delegate: Connection,
            pause: () -> Unit,
        ): Connection =
            delegating(Connection::class.java, delegate) { method, args ->
                val result = call(delegate, method, args)
                if (method.name == "prepareStatement" &&
                    (args.firstOrNull() as? String)?.contains("INSERT INTO governance_projection_snapshot") == true
                ) {
                    pausingStatement(result as PreparedStatement, pause)
                } else {
                    result
                }
            } as Connection

        private fun pausingStatement(
            delegate: PreparedStatement,
            pause: () -> Unit,
        ): PreparedStatement {
            var paused = false
            return delegating(PreparedStatement::class.java, delegate) { method, args ->
                val result = call(delegate, method, args)
                if (method.name == "executeQuery" && !paused) {
                    paused = true
                    pause()
                }
                result
            } as PreparedStatement
        }

        private fun delegating(
            type: Class<*>,
            delegate: Any,
            handler: (Method, Array<Any?>) -> Any?,
        ): Any =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                if (method.declaringClass == Any::class.java) {
                    call(delegate, method, args ?: emptyArray())
                } else {
                    handler(method, args ?: emptyArray())
                }
            }

        /** Direct-call semantics: an invocation failure surfaces as itself, not as a reflection wrapper. */
        private fun call(
            target: Any,
            method: Method,
            args: Array<Any?>,
        ): Any? =
            try {
                method.invoke(target, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }
}
