package dev.tramai.build.quality

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildResultException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * The authority-transport proof for population admission (0.7.1g1G4z2).
 *
 * The ceremony was already proved at verifier level (M30-M39) and the ledger wiring was proved at
 * task level, but neither proved the transport: that the REAL `verifyMutationRatchet` task, under
 * RECORDED_EVOLUTION, measures the candidate population ITSELF, derives the trusted projection hash
 * from that fresh measurement, consumes a previously minted base-side authorization with it, and
 * rejects retention of an authorization it just consumed (M38).
 *
 * Everything here is real: real git history, the real wrapper, the real nested Gradle + PIT
 * measurement through `runNestedGradle`, the real YAML ledgers. The digest the authorization binds
 * is never fabricated - it is the projection hash of a population this fixture actually measured.
 *
 * Fixture git history. The authority base is an ANCESTOR of both transitions under test, exactly as a
 * real PR consumes authority that its base already carries:
 *
 * - `B0` base source;
 * - `B1` + base population - the base the authorization is minted against;
 * - `S1` scratch candidate source on a side branch - measured, then abandoned, so it is never an
 *   ancestor of the authority base (the measurement is data, the ancestry would be fiction);
 * - `C`  authority base: base source + base population + the minted authorization;
 * - `C-consumed` candidate source + candidate population, the authorization consumed (removed);
 * - `C-retained` candidate source + candidate population, the authorization still in place.
 *
 * The two candidate states differ in exactly one thing - whether the authorization is still there -
 * so the cases isolate single use from every other rule. The ancestry of each transition from the
 * authority base is asserted below, so a later refactor cannot quietly weaken the proof.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("slow")
class MutationPopulationAdmissionIntegrationTest {
    private lateinit var fixture: File
    private lateinit var stashRoot: File
    private lateinit var baseSha: String

    /** The fixture's main branch, whose tip is the authority base. */
    private lateinit var mainBranch: String

    /** The consuming transition: a child of the authority base, with the authorization removed. */
    private lateinit var consumedCandidateSha: String

    /** The retaining transition: the same candidate state as a child of the authority base. */
    private lateinit var retainedCandidateSha: String
    private var appearingIdentities: List<String> = emptyList()

    @BeforeAll
    fun prepareAuthorityTransportFixture() {
        val tempRoot = Files.createTempDirectory("tramai-population-admission-").toFile()
        println("[admission-integration] fixture root: $tempRoot")
        stashRoot = File(tempRoot, "stash").apply { mkdirs() }
        fixture = File(tempRoot, "fixture").apply { mkdirs() }

        installWrapper(repositoryRoot)
        // The measurement's provenance gate reads this repository, so the fixture IS a repository.
        git("init")
        git("config", "user.email", "admission-integration@tramai.dev")
        git("config", "user.name", "PopulationAdmissionIntegration")
        mainBranch = git("symbolic-ref", "--short", "HEAD").trim()
        writeQualityConfig()
        writeSubjectModule()
        writeBuildScript(pluginClasspath())

        // B0/B1: the measured base. B1 is the base the authorization is minted against.
        writeSubject(BASE_SOURCE)
        commit("base source")

        val basePopulation = File(stashRoot, "base-population.json")
        measureInto(basePopulation, "base measurement")
        commit("base population")
        val mintingBaseSha = headSha()

        // S1: the future candidate measurement, taken on a side branch and then abandoned. It supplies
        // the population the authorization binds, but it must not become an ancestor of the authority
        // base below - that would model a transition no PR can have.
        writeSubject(CANDIDATE_SOURCE)
        commit("scratch candidate source")
        val candidatePopulation = File(stashRoot, "candidate-population.json")
        measureInto(candidatePopulation, "candidate measurement")
        git("branch", "scratch-candidate-measurement")
        git("checkout", "-f", "-B", mainBranch, mintingBaseSha)

        val base = readPopulation(basePopulation)
        val candidate = readPopulation(candidatePopulation)
        assertAuthorityTransportPreconditions(base, candidate)
        appearingIdentities = appearingNonKilled(base, candidate)

        // C: the authority base. It carries the base state plus the authorization a FUTURE transition
        // consumes: an authorization is merged into the base, never created by the transition that uses
        // it (M31). `fromBaseSha` records the base of the minting transition.
        writeAdmissionLedger(candidate, mintingBaseSha, authorityProjectionDigest(candidate))
        commit("authority base with minted population authorizations")
        baseSha = headSha()
        git("branch", "authority-base")

        // Both transitions under test are DESCENDANTS of the authority base, as a real PR is: one
        // consumes the authorization, the other retains it.
        consumedCandidateSha =
            commitCandidateTransition(
                branch = "consumed-candidate",
                population = candidatePopulation,
                retainAuthorization = false,
                message = "candidate consuming the authorization",
            )
        retainedCandidateSha =
            commitCandidateTransition(
                branch = "retained-candidate",
                population = candidatePopulation,
                retainAuthorization = true,
                message = "candidate retaining the consumed authorization",
            )

        assertAuthorityTopology(git("rev-parse", "scratch-candidate-measurement").trim())
    }

    /**
     * The topology this proof depends on. The authority base is an ancestor of both transitions under
     * test, and the scratch candidate measurement is NOT an ancestor of the authority base: a base that
     * already contained the candidate state would model a transition no PR can have.
     */
    private fun assertAuthorityTopology(scratchCandidateSha: String) {
        assertTrue(
            isAncestor(baseSha, consumedCandidateSha),
            "the authority base must be an ancestor of the consuming transition: $baseSha -> $consumedCandidateSha",
        )
        assertTrue(
            isAncestor(baseSha, retainedCandidateSha),
            "the authority base must be an ancestor of the retaining transition: $baseSha -> $retainedCandidateSha",
        )
        assertTrue(
            !isAncestor(scratchCandidateSha, baseSha),
            "the scratch candidate measurement must not be an ancestor of the authority base: " +
                "a base cannot already contain the candidate state it judges ($scratchCandidateSha -> $baseSha)",
        )
    }

    /**
     * The identities this transition makes appear as NON_KILLED: exactly what the minted
     * authorization has to cover, derived from two real measurements rather than assumed.
     */
    private fun appearingNonKilled(
        base: MutationPopulationBaseline,
        candidate: MutationPopulationBaseline,
    ): List<String> =
        candidate.mutants
            .filter { it.outcome == MutationRatchetVerifier.NON_KILLED }
            .map { it.identity }
            .filter { id -> base.mutants.none { it.identity == id } }
            .sorted()

    /**
     * A transition that starts from the authority base: the candidate state, with the minted
     * authorization either consumed (removed) or retained. Returns the new commit.
     */
    private fun commitCandidateTransition(
        branch: String,
        population: File,
        retainAuthorization: Boolean,
        message: String,
    ): String {
        git("checkout", "-B", branch, baseSha)
        writeSubject(CANDIDATE_SOURCE)
        writePopulation(population)
        if (!retainAuthorization) admissionLedgerFile().delete()
        commit(message)
        return headSha()
    }

    /**
     * The positive P2: the authorization the base minted is consumed by the exact row it authorized,
     * under the exact analyzer semantics and the exact population digest. The task itself produces
     * that digest from a fresh nested PIT measurement.
     */
    @Test
    fun `the real ratchet measures the candidate, consumes the base authorization and passes`() {
        // C3: the candidate state as a well-formed P2 - the consumed authorization is gone.
        checkout(consumedCandidateSha)
        assertTrue(
            !admissionLedgerFile().isFile,
            "the consumption candidate must not carry the authorization ledger: ${admissionLedgerFile()}",
        )

        val (ok, output) = verifyRatchet()
        println("[admission-integration] consumption transition:" + NL + decisiveLines(output))

        assertTrue(
            output.contains("canonicalMutationProbe"),
            "verifyMutationRatchet must run the real nested PIT measurement:\n$output",
        )
        assertTrue(
            output.contains("Critical mutation measurement:"),
            "the nested measurement must produce a population:\n$output",
        )
        assertTrue(ok, "the consumed authorization must be admitted:\n$output")
        assertTrue(
            output.contains("baseline verification PASSED"),
            "expected the ratchet to pass on this transition:\n$output",
        )
        // Any of these would mean the appearing rows were NOT admitted by the base authorization.
        listOf("M06", "M30", "M33", "M34", "M37", "M38").forEach { code ->
            assertTrue(!output.contains(code), "unexpected $code in a consumed-authorization pass:\n$output")
        }
    }

    /**
     * The negative: the authorization that admitted the row is still present. An authorization is
     * single-use, so the same transition that passes above must fail M38.
     */
    @Test
    fun `the real ratchet rejects an authorization retained after its own consumption`() {
        checkout(retainedCandidateSha)
        assertTrue(admissionLedgerFile().isFile, "the retained-authorization candidate must carry the ledger")

        val (ok, output) = verifyRatchet()
        println("[admission-integration] retained-authorization transition:" + NL + decisiveLines(output))

        assertTrue(!ok, "retaining a consumed authorization must fail the task:\n$output")
        assertTrue(output.contains("M38"), "expected the single-use failure M38:\n$output")
        assertTrue(
            output.contains(appearingIdentities.first().take(8)),
            "M38 must name the retained authorization's identity:\n$output",
        )
    }

    // ── fixture construction ──

    /**
     * The build-logic plugin classpath as TestKit itself resolves it, rendered literally into the
     * fixture's build script. `withPluginClasspath()` only ever reaches the OUTER invocation, so a
     * fixture whose production task spawns `runNestedGradle` must bootstrap plugin resolution in
     * the build script itself - otherwise the nested process cannot resolve the plugin at all.
     */
    private fun pluginClasspath(): List<String> =
        GradleRunner
            .create()
            .withPluginClasspath()
            .pluginClasspath
            .map { it.absolutePath }

    private fun writeBuildScript(classpath: List<String>) {
        val quote = '"'
        val entries = classpath.joinToString("," + System.lineSeparator()) { "$quote$it$quote" }
        write(
            "build.gradle.kts",
            """
            // Identical plugin resolution in the outer TestKit build and in the nested `gradlew`
            // process the mutation measurement spawns.
            buildscript {
                dependencies {
                    classpath(
                        files(
            $entries
                        ),
                    )
                }
            }

            println("[fixture] plugin classpath entries: " + buildscript.configurations.getByName("classpath").files.size)

            apply(plugin = "tramai.maintainability-baseline")
            """.trimIndent() + System.lineSeparator(),
        )
    }

    /**
     * The repository under test. Fails with an explicit diagnostic when the property is missing, the
     * same way the other build-logic integration tests do: without it the nested wrapper cannot be
     * located at all, and a null dereference would hide that.
     */
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("tramai.repositoryRoot")) {
                "tramai.repositoryRoot must be set (build-logic/build.gradle.kts test wiring); " +
                    "the fixture cannot install the real wrapper without it"
            },
        )

    private fun installWrapper(repositoryRoot: File) {
        check(repositoryRoot.isDirectory) { "tramai.repositoryRoot is not a directory: $repositoryRoot" }
        listOf("gradlew", "gradlew.bat").forEach { name ->
            File(repositoryRoot, name).copyTo(File(fixture, name), overwrite = true)
        }
        File(fixture, "gradlew").setExecutable(true)
        File(repositoryRoot, "gradle/wrapper").copyRecursively(File(fixture, "gradle/wrapper"), overwrite = true)
    }

    private fun writeQualityConfig() {
        write("settings.gradle.kts", "rootProject.name = \"population-admission\"" + NL + "include(\":subject\")" + NL)
        write(
            "config/quality/module-catalog.yml",
            """
            schemaVersion: "3"
            dependencyPolicies:
              testing:
                allowedLayers: [testing-support]
            entryDefaults:
              internal: &internal
                layer: "testing-support"
                maturity: "internal"
                publishability: "internal"
                apiStability: "excluded"
                visibility: "internal"
                owner: "testing"
                dependencyPolicy: "testing"
                releaseInclusion: "internal_only"
                rationale: "Provides a TestKit fixture module."
            modules:
              - path: ":subject"
                <<: *internal
            """.trimIndent() + NL,
        )
        write(
            "config/quality/test-quality.yml",
            """
            schemaVersion: "1"
            criticalModules: [":subject"]
            coverage:
              regressionTolerancePercentagePoints: 1.0
              exclusions: []
            mutation:
              regressionTolerancePercentagePoints: 1.0
              targetFamilies:
                admission:
                  modules: [":subject"]
                  targetClasses: ["dev.tramai.pit.*"]
                  targetTests: ["dev.tramai.pit.*Test"]
            """.trimIndent() + NL,
        )
        write("config/quality/mutation-classifications.yml", "schemaVersion: \"1\"" + NL + "classifications: []" + NL)
        write("config/quality/mutation-evolution.yml", "schemaVersion: \"1\"" + NL + "records: []" + NL)
        write(".gitignore", "build/" + NL + ".gradle/" + NL)
    }

    /** The `:subject` module the measurement actually mutates. */
    private fun writeSubjectModule() {
        write(
            "subject/build.gradle.kts",
            """
            plugins { `java-library` }

            repositories { mavenCentral() }

            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
            }

            tasks.test { useJUnitPlatform() }
            """.trimIndent() + NL,
        )
        write(
            "subject/src/test/java/dev/tramai/pit/SubjectTest.java",
            """
            package dev.tramai.pit;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class SubjectTest {
                private final Subject subject = new Subject();

                @Test
                void adds() {
                    assertEquals(3, subject.add(1, 2));
                }

                @Test
                void scalesAboveThreshold() {
                    assertEquals(24, subject.scale(12));
                }
            }
            """.trimIndent() + NL,
        )
    }

    private fun writeSubject(source: String) {
        write("subject/src/main/java/dev/tramai/pit/Subject.java", source)
    }

    /**
     * The authorization for every identity the candidate transition makes appear. Data-driven on
     * purpose: which rows appear is a property of the measurement, not something this fixture may
     * assume, and every appearing NON_KILLED identity needs its own exact authorization.
     */
    private fun writeAdmissionLedger(
        population: MutationPopulationBaseline,
        mintBaseSha: String,
        digest: String,
    ) {
        val analyzer = population.analyzer
        val byIdentity = population.mutants.associateBy { it.identity }
        val lines = mutableListOf("schemaVersion: \"1\"", "admissions:")
        for (id in appearingIdentities) {
            val row = byIdentity.getValue(id)
            lines += "  - identity: \"${row.identity}\""
            lines += "    status: \"${row.status}\""
            lines += "    outcome: \"${row.outcome}\""
            lines += "    family: \"${row.family}\""
            lines += "    module: \"${row.module}\""
            lines += "    analyzer:"
            lines += "      pluginVersion: \"${analyzer.pluginVersion}\""
            lines += "      engineVersion: \"${analyzer.engineVersion}\""
            lines += "      mutators:"
            analyzer.mutators.forEach { lines += "        - \"$it\"" }
            lines += "      timeoutConst: ${analyzer.timeoutConst}"
            lines += "      timeoutFactor: ${analyzer.timeoutFactor}"
            lines += "    fromBaseSha: \"$mintBaseSha\""
            lines += "    populationDigest: \"$digest\""
            lines += "    reason: \"adjudicated survivor admitted by the transition that consumes it\""
            lines += "    authorizedBy: \"fixture\""
            lines += "    authorizedAt: \"1970-01-01T00:00:00Z\""
        }
        write(MutationPopulationAdmissionLoader.FILE_NAME, lines.joinToString(NL) + NL)
    }

    /**
     * The verifier's own trusted **authority projection** digest computation, never a value this
     * test invents: the same call the task makes to derive the proof M34 compares an authorization
     * against. It must be the authority projection (identity, canonical outcome, family, module,
     * topology, analyzer) and NOT the raw-exact measurement proof: production M34 compares
     * `authorityProjectionHash`, so a fixture minting `projectionHash` would exercise the wrong
     * digest semantics and pass while the real transport is broken.
     */
    private fun authorityProjectionDigest(population: MutationPopulationBaseline): String =
        MutationPopulationEvolutionProof
            .exactComparison(population, population)
            .proof
            ?.authorityProjectionHash
            ?: error("the fixture population is not self-comparable, so no digest can be minted")

    private fun assertAuthorityTransportPreconditions(
        base: MutationPopulationBaseline,
        candidate: MutationPopulationBaseline,
    ) {
        val baseIds = base.mutants.map { it.identity }.toSet()
        val candidateIds = candidate.mutants.map { it.identity }.toSet()
        val disappeared = baseIds - candidateIds
        assertTrue(
            disappeared.isEmpty(),
            "the base population must survive the candidate measurement (M21 would reject the fixture " +
                "before it could exercise admission): $disappeared",
        )
        val appearingNonKilled =
            candidate.mutants
                .filter { it.outcome == MutationRatchetVerifier.NON_KILLED }
                .map { it.identity }
                .filter { it !in baseIds }
        assertTrue(
            appearingNonKilled.isNotEmpty(),
            "the candidate source must produce at least one appearing NON_KILLED identity, otherwise " +
                "the admission ceremony is never reached: base=$baseIds candidate=$candidateIds",
        )
        assertTrue(
            authorityProjectionDigest(base) != authorityProjectionDigest(candidate),
            "the two populations must differ",
        )
    }

    // ── task driving ──

    private fun measureInto(
        destination: File,
        label: String,
    ) {
        val (ok, output) = runTask("generateCriticalMutationBaseline")
        assertTrue(ok, "$label failed:\n$output")
        assertTrue(
            output.contains("Critical mutation measurement:"),
            "$label produced no population:\n$output",
        )
        val population = File(fixture, "config/quality/mutation-baseline.json")
        assertTrue(population.isFile, "$label wrote no ${population.name}")
        population.copyTo(destination, overwrite = true)
    }

    private fun verifyRatchet(): Pair<Boolean, String> =
        runTask(
            "verifyMutationRatchet",
            "-PtramaiMutationBaseSha=$baseSha",
            "-P${MutationPopulationEvolution.PROPERTY}=recorded-evolution",
        )

    /**
     * The decisive lines of a ratchet run: the ceremony's own diagnostics and the measurement's
     * population. Printed on success too, so a CI log shows what the transition actually decided
     * rather than only that it did not fail.
     */
    private fun decisiveLines(output: String): String =
        output
            .lines()
            .filter {
                it.contains("M3") ||
                    it.contains("baseline verification") ||
                    it.contains("Critical mutation measurement") ||
                    it.contains("Family ")
            }.joinToString(NL)

    private fun runTask(vararg arguments: String): Pair<Boolean, String> {
        val runner =
            GradleRunner
                .create()
                .withProjectDir(fixture)
                .withArguments(arguments.toList() + listOf("--stacktrace", "--console=plain"))
        return try {
            true to runner.build().output
        } catch (e: UnexpectedBuildResultException) {
            false to (e.buildResult?.output ?: e.message.orEmpty())
        }
    }

    // ── repository state ──

    private fun writePopulation(source: File) {
        source.copyTo(File(fixture, "config/quality/mutation-baseline.json"), overwrite = true)
    }

    private fun readPopulation(file: File): MutationPopulationBaseline =
        ReportNormalizer.readJson(file, MutationPopulationBaseline::class.java)

    private fun admissionLedgerFile(): File = File(fixture, MutationPopulationAdmissionLoader.FILE_NAME)

    private fun checkout(sha: String) {
        git("checkout", "--detach", sha)
        assertTrue(cleanTree(), "the fixture worktree must be clean at $sha or the measurement's provenance gate fails")
    }

    private fun cleanTree(): Boolean = git("status", "--porcelain").trim().isEmpty()

    private fun headSha(): String = git("rev-parse", "HEAD").trim()

    private fun commit(message: String) {
        git("add", "-A")
        git("commit", "-m", message)
    }

    /** `git merge-base --is-ancestor`: the topology this proof depends on. */
    private fun isAncestor(
        ancestor: String,
        descendant: String,
    ): Boolean = gitExit("merge-base", "--is-ancestor", ancestor, descendant) == 0

    private fun gitExit(vararg args: String): Int =
        ProcessBuilder(listOf("git") + args)
            .directory(fixture)
            .redirectErrorStream(true)
            .start()
            .let { process ->
                process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
            }

    private fun git(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(fixture)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    private fun write(
        path: String,
        content: String,
    ) {
        val file = File(fixture, path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private companion object {
        const val NL = "\n"

        val BASE_SOURCE =
            """
            package dev.tramai.pit;

            public final class Subject {
                public int add(int a, int b) {
                    return a + b;
                }

                public int scale(int value) {
                    if (value > 10) {
                        return value * 2;
                    }
                    return value + 1;
                }
            }
            """.trimIndent() + "\n"

        val CANDIDATE_SOURCE =
            """
            package dev.tramai.pit;

            public final class Subject {
                public int add(int a, int b) {
                    return a + b;
                }

                public int scale(int value) {
                    if (value > 10) {
                        return value * 2;
                    }
                    return value + 1;
                }

                // Adjudicated as an accepted survivor candidate: measured, yet not exercised by any
                // test, so it appears in the candidate population as NON_KILLED.
                public int adjudicated(int value) {
                    return value * 3;
                }
            }
            """.trimIndent() + "\n"
    }
}
