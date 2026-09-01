package dev.tramai.build.sovereign

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Discriminating TestKit tests for the 9.2d-a3b2a sovereign lab/evidence typed
 * tasks (verifySovereignLabProfile, prepareSovereignEvidenceBundle,
 * verifySovereignEvidenceBundleReleaseManifest,
 * verifySovereignEvidencePackContainsReleaseBundle,
 * verifySovereignDocumentIntelligenceEvidenceRun).
 *
 * Standing rule (user-mandated): NO empty fixture may serve as the successful
 * oracle when the verifier must discover real content. verifySovereignLabProfile
 * positives copy REAL repo files via git ls-files; the evidence-pack fixtures
 * use the REAL zero-egress report shape (build outputs are not git-tracked, so
 * the real structure is inlined); fail-closed negatives mutate exactly one
 * input and assert the exact diagnostic.
 *
 * Note: configuration-cache cold→warm reuse is intentionally NOT asserted here
 * (TestKit + CC is flaky); the C3-closure proof is run on the real repo by the
 * orchestrator (`--configuration-cache` cold, then warm, expecting "entry
 * reused").
 */
class SovereignLabVerifierTasksTest {
    @TempDir
    lateinit var tempDir: File

    private val repoRoot: File by lazy {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, ".git").isDirectory) {
            dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
        }
        dir
    }

    private fun copyFromRepo(
        dir: File,
        vararg relativePaths: String,
    ) {
        val git =
            ProcessBuilder("git", "-C", repoRoot.absolutePath, "ls-files", *relativePaths)
                .redirectErrorStream(true)
                .start()
        val listing = git.inputStream.bufferedReader().readText()
        check(git.waitFor() == 0) { "git ls-files failed: $listing" }
        listing.lineSequence().filter { it.isNotBlank() }.forEach { rel ->
            val src = File(repoRoot, rel)
            val dst = File(dir, rel)
            if (src.isFile) {
                dst.parentFile.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    private fun fixture(): File {
        val dir = File(tempDir, "fixture-${System.nanoTime()}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"sovereign-lab-fixture\"\n")
        writeFile(dir, "build.gradle.kts", "plugins { id(\"tramai.sovereign-lab-verification\") }\n")
        return dir
    }

    private fun runner(
        dir: File,
        vararg args: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--no-build-cache", "--stacktrace")
            .withPluginClasspath()

    private fun writeFile(
        base: File,
        relativePath: String,
        content: String,
    ) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    private fun runTask(
        dir: File,
        task: String,
    ): org.gradle.testkit.runner.BuildResult {
        val result = runner(dir, task).build()
        assertTrue(
            result.task(":$task")?.outcome == TaskOutcome.SUCCESS,
            "$task must succeed: ${result.output.take(1200)}",
        )
        return result
    }

    /** All files read by verifySovereignLabProfile — committed repo files (real oracles). */
    private val labProfileFiles =
        listOf(
            "examples/spring-sovereign-starter/src/main/resources/application-sovereign-lab.yml",
            "examples/sovereign-lab/README.md",
            "examples/sovereign-lab/EVIDENCE.md",
            "examples/sovereign-lab/evidence-template/benchmark.md",
            "examples/sovereign-lab/create-evidence-bundle.sh",
            "examples/sovereign-lab/evidence-template/MANIFEST.md",
            "examples/sovereign-lab/evidence-template/command-log.md",
            "examples/sovereign-lab/finalize-evidence-bundle.sh",
            "examples/sovereign-lab/RELEASE-READINESS.md",
            "examples/sovereign-lab/REVIEWER-GUIDE.md",
            "examples/sovereign-lab/EVIDENCE-CHAIN.md",
            "examples/sovereign-lab/package-evidence-bundle.sh",
            "examples/sovereign-lab/verify-evidence-archive.sh",
            "examples/sovereign-lab/ARCHIVE-SIGNING.md",
        )

    /**
     * The REAL sovereign-evidence-pack-v1.json produced by
     * scripts/verify-zero-egress.sh on this repo (releaseBundle null). Build
     * outputs are not git-tracked, so the real structure is inlined as the
     * oracle. [evidencePackWithReleaseBundle] is derived by populating the
     * releaseBundle key, exactly as the document-intelligence evidence run
     * attaches it.
     */
    private val evidencePackWithNullReleaseBundle =
        """
        {
            "schemaVersion": 1,
            "deploymentMode": "OFFLINE",
            "allowedModels": [
                "offline-test-model"
            ],
            "allowedProviders": [
                "loopback-local-provider"
            ],
            "providerZones": {
                "loopback-local-provider": "LOCAL"
            },
            "artifactVerificationSettings": {
                "enabled": true,
                "requireDigestForLocalModels": true
            },
            "artifacts": [
                {
                    "registryEntryId": "offline-entry",
                    "manifestDigest": "sha256:63cf2c537edb927b34c358df4d2f09dcdcfc53e358136fcb38ae65b6e855bd67",
                    "modelName": "offline-test-model",
                    "verifiedAt": "2026-08-25T19:06:28.315778100Z",
                    "artifactCount": 1,
                    "totalSizeBytes": 35
                }
            ],
            "zeroEgress": {
                "deploymentMode": "OFFLINE",
                "runtimeBuildSucceeded": true,
                "loopbackProviderInvocationSucceeded": true,
                "loopbackProviderInvocationCount": 1,
                "externalTcpProbeBlocked": true,
                "externalDnsProbeBlocked": true
            },
            "auditChain": {
                "isValid": true,
                "totalEvents": 3
            },
            "supplyChain": {
                "schemaVersion": 1,
                "sbomFormat": "CycloneDX",
                "sbomSpecVersion": "1.6",
                "sbomFileName": "tramai-cyclonedx-sbom.json",
                "sbomSha256": "sha256:4aea20c0c82afe32230ef9a8332f7a854ed399238fedc9674aebdbe923b6ab02",
                "generatedBy": "CycloneDX Gradle Plugin 3.2.4"
            },
            "releaseBundle": null,
            "attestation": null,
            "generatedAt": "2026-08-25T19:06:28.737090617Z"
        }
        """.trimIndent()

    private val evidencePackWithReleaseBundle: String =
        evidencePackWithNullReleaseBundle.replace(
            "\"releaseBundle\": null,",
            "\"releaseBundle\": { \"schemaVersion\": 1, \"releaseManifestFileName\": \"release-artifacts-v1.json\", \"releaseManifestSha256\": \"sha256:4aea20c0c82afe32230ef9a8332f7a854ed399238fedc9674aebdbe923b6ab02\", \"artifactCount\": 1 },",
        )

    private val zeroEgressReport =
        """
        {
            "schemaVersion": 1,
            "deploymentMode": "OFFLINE",
            "runtimeBuildSucceeded": true,
            "loopbackProviderInvocationSucceeded": true,
            "loopbackProviderInvocationCount": 1,
            "externalTcpProbeBlocked": true,
            "externalDnsProbeBlocked": true,
            "configuredProviderZones": {
                "loopback-local-provider": "LOCAL"
            },
            "artifactVerificationReceiptCount": 1,
            "auditChainValid": true
        }
        """.trimIndent()

    private val minimalSbom =
        """
        {
            "bomFormat": "CycloneDX",
            "specVersion": "1.6",
            "version": 1,
            "metadata": {
                "component": {
                    "type": "application",
                    "name": "tramai",
                    "version": "0.6.0"
                }
            },
            "components": []
        }
        """.trimIndent()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** release-artifacts-v1.json whose entries carry digest+size computed from the jar bytes. */
    private fun releaseManifestJson(vararg entries: Pair<String, ByteArray>): String {
        val artifacts =
            entries.map { (name, bytes) ->
                val sha = sha256Hex(bytes)
                """{"groupId": "dev.tramai", "artifactId": "tramai-core", "version": "0.1.0", "classifier": null, "extension": "jar", "fileName": "$name", "sha256": "sha256:$sha", "sizeBytes": ${bytes.size}}"""
            }
        return """{"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "21", "gradleVersion": "9.0.0", "artifacts": [${artifacts.joinToString(
            ",",
        )}]}"""
    }

    /**
     * Writes the full upstream-input set for prepareSovereignEvidenceBundle
     * (and, transitively, verifySovereignEvidenceBundleReleaseManifest) into
     * the fixture's build directory. The release manifest is derived from the
     * jar bytes so ReleaseManifestVerifier's digest/size checks pass.
     */
    private fun writeEvidenceBundleInputs(
        dir: File,
        jars: Map<String, ByteArray>,
        includeEvidencePack: Boolean = true,
        manifest: String = releaseManifestJson(*jars.entries.map { it.key to it.value }.toTypedArray()),
    ) {
        if (includeEvidencePack) {
            writeFile(dir, "build/zero-egress-report/sovereign-evidence-pack-v1.json", evidencePackWithNullReleaseBundle)
        }
        writeFile(dir, "build/zero-egress-report/zero-egress-report.json", zeroEgressReport)
        writeFile(dir, "build/supply-chain/sbom/tramai-cyclonedx-sbom.json", minimalSbom)
        writeFile(
            dir,
            "build/supply-chain/sbom/tramai-cyclonedx-sbom.sha256",
            "sha256:4aea20c0c82afe32230ef9a8332f7a854ed399238fedc9674aebdbe923b6ab02\n",
        )
        writeFile(dir, "build/sovereign-release/release-artifacts-v1.json", manifest)
        jars.forEach { (name, bytes) ->
            File(dir, "build/sovereign-release/artifacts/$name").apply {
                parentFile.mkdirs()
                writeBytes(bytes)
            }
        }
    }

    // ------------------------------------------------------------------
    // verifySovereignLabProfile
    // ------------------------------------------------------------------

    @Test
    fun `verifySovereignLabProfile passes on real repo lab files`() {
        val dir = fixture()
        copyFromRepo(dir, *labProfileFiles.toTypedArray())
        runTask(dir, "verifySovereignLabProfile")
    }

    @Test
    fun `verifySovereignLabProfile fails on duplicate tramai root key`() {
        val dir = fixture()
        copyFromRepo(dir, *labProfileFiles.toTypedArray())
        val yml = File(dir, "examples/spring-sovereign-starter/src/main/resources/application-sovereign-lab.yml")
        yml.writeText("tramai:\n  injected: true\n\n" + yml.readText())
        val result = runner(dir, "verifySovereignLabProfile").buildAndFail()
        assertContains(
            result.output,
            "Sovereign lab profile must define the 'tramai:' root key exactly once. Found 2. Duplicate root keys are not valid YAML.",
        )
    }

    // ------------------------------------------------------------------
    // prepareSovereignEvidenceBundle
    // ------------------------------------------------------------------

    @Test
    fun `prepareSovereignEvidenceBundle assembles the evidence bundle`() {
        val dir = fixture()
        val jarBytes = "fake jar bytes for bundle assembly".toByteArray()
        writeEvidenceBundleInputs(dir, jars = mapOf("tramai-core-0.1.0.jar" to jarBytes))
        runTask(dir, "prepareSovereignEvidenceBundle")

        val bundle = File(dir, "build/sovereign-evidence")
        assertTrue(File(bundle, "sovereign-evidence-pack-v1.json").isFile, "evidence pack must be copied")
        assertTrue(File(bundle, "zero-egress-report.json").isFile, "zero-egress report must be copied")
        assertTrue(File(bundle, "supply-chain/tramai-cyclonedx-sbom.json").isFile, "sbom must be copied")
        assertTrue(File(bundle, "supply-chain/tramai-cyclonedx-sbom.sha256").isFile, "sbom digest must be copied")
        assertTrue(File(bundle, "release/release-artifacts-v1.json").isFile, "release manifest must be copied")
        assertTrue(File(bundle, "release/artifacts/tramai-core-0.1.0.jar").isFile, "jar must be copied")
        assertEquals(
            String(jarBytes),
            File(bundle, "release/artifacts/tramai-core-0.1.0.jar").readText(),
            "jar bytes must match the source",
        )
    }

    @Test
    fun `prepareSovereignEvidenceBundle fails closed when evidence pack missing`() {
        val dir = fixture()
        writeEvidenceBundleInputs(dir, jars = mapOf("tramai-core-0.1.0.jar" to "fake jar bytes".toByteArray()), includeEvidencePack = false)
        val result = runner(dir, "prepareSovereignEvidenceBundle").buildAndFail()
        assertContains(result.output, "sovereign-evidence-missing-evidence-pack")
    }

    @Test
    fun `prepareSovereignEvidenceBundle copies ONLY the declared artifactsJars, not directory contents`() {
        // Discriminator for the declared-input authority rule: the source
        // directory contains A.jar AND B.jar, but the task's declared
        // artifactsJars is overridden to A.jar only. The old implementation
        // (directory enumeration) would copy both; the typed contract copies
        // only the declared authority.
        val dir = fixture()
        val aBytes = "jar-a".toByteArray()
        val bBytes = "jar-b".toByteArray()
        writeEvidenceBundleInputs(
            dir,
            jars =
                mapOf(
                    "a-0.1.0.jar" to aBytes,
                    "b-0.1.0.jar" to bBytes,
                ),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.sovereign-lab-verification") }
            tasks.named<dev.tramai.build.sovereign.PrepareSovereignEvidenceBundleTask>("prepareSovereignEvidenceBundle") {
                artifactsJars.setFrom(file("build/sovereign-release/artifacts/a-0.1.0.jar"))
            }
            """.trimIndent(),
        )
        runTask(dir, "prepareSovereignEvidenceBundle")

        val artifactsDir = File(dir, "build/sovereign-evidence/release/artifacts")
        assertTrue(File(artifactsDir, "a-0.1.0.jar").isFile, "declared jar must be copied")
        assertTrue(!File(artifactsDir, "b-0.1.0.jar").exists(), "undeclared directory jar must NOT be copied")
    }

    // ------------------------------------------------------------------
    // verifySovereignEvidenceBundleReleaseManifest
    // ------------------------------------------------------------------

    @Test
    fun `verifySovereignEvidenceBundleReleaseManifest passes on jars matching the manifest`() {
        val dir = fixture()
        val jarBytes = "real jar content for manifest verification".toByteArray()
        writeEvidenceBundleInputs(dir, jars = mapOf("tramai-core-0.1.0.jar" to jarBytes))
        runTask(dir, "verifySovereignEvidenceBundleReleaseManifest")
    }

    @Test
    fun `verifySovereignEvidenceBundleReleaseManifest fails when manifest lists a missing jar`() {
        val dir = fixture()
        val jarBytes = "present jar bytes".toByteArray()
        val manifest = releaseManifestJson("ghost-0.1.0.jar" to jarBytes)
        writeEvidenceBundleInputs(dir, jars = mapOf("present-0.1.0.jar" to jarBytes), manifest = manifest)
        val result = runner(dir, "verifySovereignEvidenceBundleReleaseManifest").buildAndFail()
        assertContains(result.output, "sovereign-release-artifact-missing: ghost-0.1.0.jar")
    }

    @Test
    fun `verifySovereignEvidenceBundleReleaseManifest ignores jars outside the declared artifactJars`() {
        // Discriminator for the declared-input authority rule: the artifacts
        // directory physically contains A.jar AND B.jar, but the task's
        // declared artifactJars is overridden to A.jar only and the manifest
        // lists A.jar only. The old implementation (directory enumeration)
        // would fail with artifact-unlisted: B.jar; the typed contract
        // verifies ONLY the declared inputs.
        val dir = fixture()
        val aBytes = "jar-a".toByteArray()
        val bBytes = "jar-b".toByteArray()
        val manifest = releaseManifestJson("a-0.1.0.jar" to aBytes)
        writeEvidenceBundleInputs(
            dir,
            jars =
                mapOf(
                    "a-0.1.0.jar" to aBytes,
                    "b-0.1.0.jar" to bBytes,
                ),
            manifest = manifest,
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.sovereign-lab-verification") }
            tasks.named<dev.tramai.build.sovereign.VerifySovereignEvidenceBundleReleaseManifestTask>("verifySovereignEvidenceBundleReleaseManifest") {
                artifactJars.setFrom(file("build/sovereign-release/artifacts/a-0.1.0.jar"))
            }
            """.trimIndent(),
        )
        runTask(dir, "verifySovereignEvidenceBundleReleaseManifest")
    }

    // ------------------------------------------------------------------
    // verifySovereignEvidencePackContainsReleaseBundle
    // ------------------------------------------------------------------

    @Test
    fun `verifySovereignEvidencePackContainsReleaseBundle passes when releaseBundle populated`() {
        val dir = fixture()
        writeFile(dir, "build/zero-egress-report/sovereign-evidence-pack-v1.json", evidencePackWithReleaseBundle)
        runTask(dir, "verifySovereignEvidencePackContainsReleaseBundle")
    }

    @Test
    fun `verifySovereignEvidencePackContainsReleaseBundle fails when releaseBundle null`() {
        val dir = fixture()
        writeFile(dir, "build/zero-egress-report/sovereign-evidence-pack-v1.json", evidencePackWithNullReleaseBundle)
        val result = runner(dir, "verifySovereignEvidencePackContainsReleaseBundle").buildAndFail()
        assertContains(result.output, "sovereign-evidence-pack-missing-release-bundle")
    }

    // ------------------------------------------------------------------
    // verifySovereignDocumentIntelligenceEvidenceRun (registration smoke)
    // ------------------------------------------------------------------

    @Test
    fun `verifySovereignDocumentIntelligenceEvidenceRun is registered as an Exec task`() {
        val dir = fixture()
        // The task executes a real doc-intelligence example whose upstream
        // producers live in the sovereign-verification plugin — not applied in
        // this fixture — so only existence/type/description are asserted here;
        // real-repo execution is verified by the orchestrator's CC probe.
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.sovereign-lab-verification") }
            tasks.register("execRegistrationSmoke") {
                doLast {
                    val t = tasks.named("verifySovereignDocumentIntelligenceEvidenceRun").get()
                    check(t is org.gradle.api.tasks.Exec) { "task must be an Exec" }
                    check(t.group == "verification") { "task group must be verification" }
                    check(t.description!!.contains("sovereign document intelligence")) { "task description must mention the doc-intelligence run" }
                }
            }
            """.trimIndent(),
        )
        runTask(dir, "execRegistrationSmoke")
    }

    // ------------------------------------------------------------------
    // 9.2d-b2 slice B: verifySovereignLabRuntimeSmoke / LocalModel / benchmark
    // moved from the root build script into this plugin.
    // ------------------------------------------------------------------

    @Test
    fun `verifySovereignLabRuntimeSmoke passes on a clean JUnit report`() {
        val dir = fixture()
        writeFile(
            dir,
            "examples/spring-sovereign-starter/build/test-results/e2eTest/TEST-dev.tramai.examples.spring.SovereignLabProfileSmokeTest.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="SovereignLabProfileSmokeTest" tests="1" failures="0" errors="0">
              <testcase name="smoke" classname="dev.tramai.examples.spring.SovereignLabProfileSmokeTest"/>
            </testsuite>
            """.trimIndent(),
        )
        // dependsOn :examples:spring-sovereign-starter:e2eTest must still
        // resolve: include the subproject and register a no-op e2eTest so the
        // fixture reaches the moved typed verification action.
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "sovereign-lab-fixture"
            include("examples:spring-sovereign-starter")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "examples/spring-sovereign-starter/build.gradle.kts",
            """
            tasks.register("e2eTest") { doLast { logger.lifecycle("fixture e2eTest ran") } }
            """.trimIndent(),
        )
        val result = runner(dir, "verifySovereignLabRuntimeSmoke").build()
        assertTrue(result.output.contains("sovereign lab runtime smoke tests passed"))
    }

    @Test
    fun `verifySovereignLabRuntimeSmoke fails closed on missing report`() {
        val dir = fixture()
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "sovereign-lab-fixture"
            include("examples:spring-sovereign-starter")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "examples/spring-sovereign-starter/build.gradle.kts",
            """
            tasks.register("e2eTest") { doLast { logger.lifecycle("fixture e2eTest ran") } }
            """.trimIndent(),
        )
        val result = runner(dir, "verifySovereignLabRuntimeSmoke").buildAndFail()
        assertContains(result.output, "SovereignLabProfileSmokeTest did not run")
    }

    @Test
    fun `verifySovereignLabRuntimeSmoke fails on non-clean report`() {
        val dir = fixture()
        writeFile(
            dir,
            "examples/spring-sovereign-starter/build/test-results/e2eTest/TEST-dev.tramai.examples.spring.SovereignLabProfileSmokeTest.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="SovereignLabProfileSmokeTest" tests="1" failures="1" errors="0">
              <testcase name="smoke" classname="dev.tramai.examples.spring.SovereignLabProfileSmokeTest">
                <failure message="boom"/>
              </testcase>
            </testsuite>
            """.trimIndent(),
        )
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "sovereign-lab-fixture"
            include("examples:spring-sovereign-starter")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "examples/spring-sovereign-starter/build.gradle.kts",
            """
            tasks.register("e2eTest") { doLast { logger.lifecycle("fixture e2eTest ran") } }
            """.trimIndent(),
        )
        val result = runner(dir, "verifySovereignLabRuntimeSmoke").buildAndFail()
        assertContains(result.output, "SovereignLabProfileSmokeTest did not pass cleanly")
    }

    @Test
    fun `sovereign lab local model tasks keep their names and env-gate semantics`() {
        val dir = fixture()
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.sovereign-lab-verification") }
            tasks.register("labRegistrationSmoke") {
                doLast {
                    val smoke = tasks.named("verifySovereignLabRuntimeSmoke").get()
                    check(smoke.group == "verification") { "smoke group" }
                    check(smoke.description!!.contains("embedded PostgreSQL")) { "smoke description" }
                    val local = tasks.named("verifySovereignLabLocalModel").get()
                    check(local.group == "verification") { "local group" }
                    check(local.description!!.contains("opt-in sovereign lab local-model")) { "local description" }
                    val bench = tasks.named("benchmarkSovereignLabLocalModel").get()
                    check(bench.group == "verification") { "bench group" }
                    check(bench.description!!.contains("benchmark diagnostics")) { "bench description" }
                }
            }
            """.trimIndent(),
        )
        runTask(dir, "labRegistrationSmoke")
    }

    @Test
    fun `sovereign lab local model env-gate fires when disabled`() {
        val dir = fixture()
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "sovereign-lab-fixture"
            include("examples:spring-sovereign-starter")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "examples/spring-sovereign-starter/build.gradle.kts",
            """
            tasks.register("localModelTest") { doLast { logger.lifecycle("fixture localModelTest ran") } }
            """.trimIndent(),
        )
        // No TRAMAI_ENABLE_LOCAL_MODEL_TEST: the moved doFirst env-gate must
        // fire and log the guidance message.
        val result = runner(dir, "verifySovereignLabLocalModel").build()
        assertTrue(result.output.contains("verifySovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_TEST=true"))
    }

    @Test
    fun `benchmark sovereign lab local model env-gate fires when disabled`() {
        val dir = fixture()
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "sovereign-lab-fixture"
            include("examples:spring-sovereign-starter")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "examples/spring-sovereign-starter/build.gradle.kts",
            """
            tasks.register("localModelBenchmark") { doLast { logger.lifecycle("fixture localModelBenchmark ran") } }
            """.trimIndent(),
        )
        val result = runner(dir, "benchmarkSovereignLabLocalModel").build()
        assertTrue(result.output.contains("benchmarkSovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK=true"))
    }

    @Test
    fun `root build script no longer carries sovereign lab execution implementation`() {
        val prop =
            System.getProperty("tramai.repositoryRoot")
                ?: error("tramai.repositoryRoot system property not set (wired by build-logic/build.gradle.kts)")
        val rootBuildScript = File(prop, "build.gradle.kts").readText()

        for (marker in listOf(
            "verifySovereignLabRuntimeSmoke must prove the lab smoke test executed",
            "SovereignLabProfileSmokeTest did not pass cleanly",
            "verifySovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_TEST",
            "benchmarkSovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK",
        )) {
            assertTrue(
                !rootBuildScript.contains(marker),
                "root build.gradle.kts must not contain sovereign-lab implementation marker: $marker",
            )
        }
    }
}
