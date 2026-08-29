package dev.tramai.build.sovereign

import java.io.File

/** The six evidence-bundle shell scripts the scenario drives (absolute paths). */
data class EvidenceScripts(
    val create: File,
    val verifier: File,
    val finalizer: File,
    val packager: File,
    val archiveVerifier: File,
    val signatureVerifier: File,
)

/**
 * Owns the frozen verifySovereignLabEvidenceBundle scenario sequence
 * (Epic 9.2d-a3b2b): create -> verify(clean) -> inject runtime-evidence ->
 * verify(pre-finalize, must fail) -> finalize -> verify(post-finalize) ->
 * tamper cycles -> negative fixtures -> archive -> determinism -> signature.
 * Pure Kotlin: no Gradle/Project at execution time; every process launch
 * routes through [EvidenceBundleProcessAdapter]. All require() diagnostics
 * and the invocation order are byte-identical to the historical root
 * doLast closure.
 */
class EvidenceBundleScenarioRunner(
    private val scripts: EvidenceScripts,
    private val workDir: File,
    private val adapter: EvidenceBundleProcessAdapter,
    private val log: (String) -> Unit = {},
) {

    /** Adapter-backed scenario fixtures (manifest mutations, expect-fail runners). */
    private val fixture = EvidenceBundleFixtureBuilder(scripts, adapter)

    /** All external processes route through the adapter; default cwd is [workDir]. */
    private fun runProcess(
        executable: String,
        arguments: List<String>,
        environment: Map<String, String> = emptyMap(),
        workingDirectory: File = workDir,
    ): ProcessResult = adapter.run(File(executable), arguments, environment, workingDirectory)

    fun run() {
        val script = scripts.create
        require(script.exists()) {
            "Missing evidence bundle script at ${script.absolutePath}"
        }

        // The historical closure implicitly created the build dir via file()
        // resolution; ProcessBuilder.directory() requires it to exist, so
        // ensure it up front (master's launches inherited the Gradle cwd,
        // which always existed — the scripts resolve their own paths).
        workDir.mkdirs()

        val bundleRoot = workDir.resolve("evidence-bundles")
        val bundle = bundleRoot.resolve("test-bundle")
        if (bundle.exists()) {
            bundle.deleteRecursively()
        }

        val pb = runProcess(
            "bash",
            listOf(scripts.create.absolutePath),
            environment = mapOf("TRAMAI_EVIDENCE_BUNDLE_TIMESTAMP" to "test-bundle"),
        )
        val exitCode = pb.exitCode
        require(exitCode == 0) {
            "Evidence bundle script exited with code $exitCode"
        }

        require(bundle.exists()) {
            "Evidence bundle was not created at ${bundle.absolutePath}"
        }

        BundleAssertions.assertRequiredFiles(bundle)

        BundleAssertions.assertReadmeClaims(bundle)

        BundleAssertions.assertManifestClaims(bundle)

        BundleAssertions.assertManifestDigests(bundle)

        // ── standalone verifier ──

        val verifier = scripts.verifier
        require(verifier.exists()) {
            "Missing evidence bundle verifier at ${verifier.absolutePath}"
        }

        val finalizer = scripts.finalizer
        require(finalizer.exists()) {
            "Missing evidence bundle finalizer at ${finalizer.absolutePath}"
        }

        // Clean generated bundle should pass
        val cleanProcess = runProcess("bash", listOf(verifier.absolutePath, bundle.absolutePath))
        val cleanExitCode = cleanProcess.exitCode
        require(cleanExitCode == 0) {
            "Evidence bundle verifier rejected a clean generated bundle (exit $cleanExitCode)."
        }

        // ── Positive runtime-evidence: add valid records before finalization ──

        val rtEvidenceDir = bundle.resolve("runtime-evidence")
        rtEvidenceDir.mkdirs()

        fun writeRtLine(filename: String, vararg lines: String) {
            rtEvidenceDir.resolve(filename).writeText(lines.joinToString("\n") + "\n")
        }

        // Valid policy decision record
        writeRtLine("policy-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-policy-001","eventType":"policy.decision","workflowRunId":"wf-lc","correlationId":"corr-lc","actor":"policy-engine","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine","module":"v1"},"decision":{"kind":"ALLOW","reasonCode":"policy_allowed"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"providerName":"ollama","classification":"low"}}"""
        )

        // Valid approval decision record
        writeRtLine("approval-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-approval-001","eventType":"approval.decision","workflowRunId":"wf-lc","correlationId":"corr-lc2","actor":"human-approver","createdAt":"2026-07-13T10:00:10Z","source":{"component":"approval-control-plane","module":"approval"},"decision":{"kind":"APPROVED","reasonCode":"approval-approved"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000003","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000004"},"metadata":{"approvalVersion":"1","reasonDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","reasonLength":"29"}}"""
        )

        // Valid provider routing record
        writeRtLine("provider-routing.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-routing-001","eventType":"provider.route","workflowRunId":"wf-lc","correlationId":"corr-lc3","actor":"provider-router","createdAt":"2026-07-13T10:00:20Z","source":{"component":"provider-router","module":"tramai-engine"},"decision":{"kind":"SELECTED","reasonCode":"provider-selected"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000005","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000006"},"metadata":{"requestedModelDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","routeIndex":"0","attempt":"1"}}"""
        )

        // Valid tool permission record
        writeRtLine("tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-tool-001","eventType":"tool.permission","workflowRunId":"wf-lc","correlationId":"corr-lc4","actor":"policy-engine","createdAt":"2026-07-13T10:00:30Z","source":{"component":"policy-engine","module":"v1"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000007","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000008"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )

        log("verifySovereignLabEvidenceBundle: added positive runtime-evidence to ${bundle.absolutePath}")

        // ── lifecycle: edit → fail → finalize → pass → tamper → fail ──

        val evidenceFile = bundle.resolve("command-log.md")
        evidenceFile.appendText("\nOperator captured command output.\n")

        // Verify before finalization must fail (manifest is stale or missing files)
        val preFinalizeProcess = runProcess("bash", listOf(verifier.absolutePath, bundle.absolutePath))
        val preFinalizeOutput = preFinalizeProcess.output
        val preFinalizeExitCode = preFinalizeProcess.exitCode
        require(preFinalizeExitCode != 0) {
            "Evidence bundle verifier must fail after evidence is filled but before finalization."
        }
        require(
            preFinalizeOutput.contains("sha256 mismatch") ||
            preFinalizeOutput.contains("sizeBytes mismatch") ||
            preFinalizeOutput.contains("missing from manifest") ||
            preFinalizeOutput.contains("files missing from manifest")
        ) {
            "Evidence bundle verifier failure before finalization should explain digest or size mismatch or missing files. Output: $preFinalizeOutput"
        }

        // Finalize to refresh manifest digests
        val finalizeProcess = runProcess("bash", listOf(finalizer.absolutePath, bundle.absolutePath))
        val finalizeExitCode = finalizeProcess.exitCode
        require(finalizeExitCode == 0) {
            "Evidence bundle finalizer exited with code $finalizeExitCode"
        }

        // Finalized bundle must pass
        val postFinalizeProcess = runProcess("bash", listOf(verifier.absolutePath, bundle.absolutePath))
        val postFinalizeExitCode = postFinalizeProcess.exitCode
        require(postFinalizeExitCode == 0) {
            "Evidence bundle verifier rejected a finalized bundle (exit $postFinalizeExitCode)."
        }

        // ── Positive runtime-evidence manifest checks ──

        BundleAssertions.assertRuntimeEvidenceStructure(bundle)
        log(
            "verifySovereignLabEvidenceBundle: positive runtime-evidence finalized " +
                "and verified with 4 files in manifest.json"
        )

        // ── Positive runtime-evidence tamper test ──
        // Tamper WITHOUT re-finalizing so verifier catches stale manifest

        val tamperedRtFile = bundle.resolve("runtime-evidence/policy-decisions.jsonl")
        val originalRtContent = tamperedRtFile.readText()
        tamperedRtFile.appendText("\n{\"tampered\":true}\n")
        val tamperVerifyProc = runProcess("bash", listOf(verifier.absolutePath, bundle.absolutePath))
        val tamperVerifyOutput = tamperVerifyProc.output
        val tamperVerifyExit = tamperVerifyProc.exitCode
        require(tamperVerifyExit != 0) {
            "Verifier must reject a tampered runtime-evidence file, but exit was $tamperVerifyExit. Output: $tamperVerifyOutput"
        }
        require(
            tamperVerifyOutput.contains("sha256 mismatch") ||
            tamperVerifyOutput.contains("sizeBytes mismatch") ||
            tamperVerifyOutput.contains("unknown root field")
        ) {
            "Verifier failure after runtime-evidence tamper should explain digest, size, or unknown field. Output: $tamperVerifyOutput"
        }
        log(
            "verifySovereignLabEvidenceBundle: tampered runtime-evidence correctly rejected"
        )

        // Restore original content and re-finalize for subsequent tests
        tamperedRtFile.writeText(originalRtContent)
        val restoreFinalizeProc = runProcess("bash", listOf(finalizer.absolutePath, bundle.absolutePath))
        require(restoreFinalizeProc.exitCode == 0) { "Failed to re-finalize after tamper recovery" }

        // ── tool-permissions.jsonl tamper test ──
        val tamperedToolFile = bundle.resolve("runtime-evidence/tool-permissions.jsonl")
        val originalToolContent = tamperedToolFile.readText()
        tamperedToolFile.appendText("\n{\"tampered\":true}\n")
        val tamperToolProc = runProcess("bash", listOf(verifier.absolutePath, bundle.absolutePath))
        val tamperToolOutput = tamperToolProc.output
        val tamperToolExit = tamperToolProc.exitCode
        require(tamperToolExit != 0) {
            "Verifier must reject tampered tool-permissions.jsonl, but exit was $tamperToolExit. Output: $tamperToolOutput"
        }
        require(
            tamperToolOutput.contains("sha256 mismatch") ||
            tamperToolOutput.contains("sizeBytes mismatch") ||
            tamperToolOutput.contains("unknown root field")
        ) {
            "Verifier failure after tool-permissions.jsonl tamper should explain digest, size, or unknown field. Output: $tamperToolOutput"
        }
        log(
            "verifySovereignLabEvidenceBundle: tampered tool-permissions.jsonl correctly rejected"
        )
        // Restore tool content and re-finalize
        tamperedToolFile.writeText(originalToolContent)
        val restoreToolProc = runProcess("bash", listOf(finalizer.absolutePath, bundle.absolutePath))
        require(restoreToolProc.exitCode == 0) { "Failed to re-finalize after tool-permissions tamper recovery" }

        // Post-finalization tamper must fail
        evidenceFile.appendText("\nTampered after finalization.\n")
        val tamperedAfterProcess = runProcess("bash", listOf(verifier.absolutePath, bundle.absolutePath))
        val tamperedAfterOutput = tamperedAfterProcess.output
        val tamperedAfterExit = tamperedAfterProcess.exitCode
        require(tamperedAfterExit != 0) {
            "Evidence bundle verifier must fail after a finalized bundle is tampered with."
        }
        require(
            tamperedAfterOutput.contains("sha256 mismatch") ||
            tamperedAfterOutput.contains("sizeBytes mismatch") ||
            tamperedAfterOutput.contains("unknown root field") ||
            tamperedAfterOutput.contains("unsupported schemaVersion")
        ) {
            "Evidence bundle verifier failure after tampering should explain digest, size, or structural mismatch. Output: $tamperedAfterOutput"
        }

        // ── copied reports regression ──

        val reportFile = bundle.resolve("reports/generated-report.txt")
        reportFile.parentFile.mkdirs()
        reportFile.writeText("Generated report content\n")

        // Re-finalize with new report
        val reFinalizeProcess = runProcess("bash", listOf(finalizer.absolutePath, bundle.absolutePath))
        val reFinalizeExitCode = reFinalizeProcess.exitCode
        require(reFinalizeExitCode == 0) {
            "Evidence bundle finalizer exited with code $reFinalizeExitCode after adding report."
        }

        // Finalized bundle with copied report must pass
        val withReportProcess = runProcess("bash", listOf(verifier.absolutePath, bundle.absolutePath))
        val withReportExitCode = withReportProcess.exitCode
        require(withReportExitCode == 0) {
            "Evidence bundle verifier rejected a finalized bundle with a copied report."
        }

        // Tampering the copied report must fail
        reportFile.appendText("tampered report\n")
        val tamperedReportProcess = runProcess("bash", listOf(verifier.absolutePath, bundle.absolutePath))
        val tamperedReportOutput = tamperedReportProcess.output
        val tamperedReportExitCode = tamperedReportProcess.exitCode
        require(tamperedReportExitCode != 0) {
            "Evidence bundle verifier must fail after a copied report is tampered with."
        }
        require(
            tamperedReportOutput.contains("sha256 mismatch") ||
            tamperedReportOutput.contains("sizeBytes mismatch")
        ) {
            "Evidence bundle verifier failure for copied report should explain digest or size mismatch. Output: $tamperedReportOutput"
        }

        // ── Negative fixture tests ──

        // Re-create a clean finalized bundle for negative fixture copies
        if (bundle.exists()) bundle.deleteRecursively()
        val cleanPb = runProcess(
            "bash",
            listOf(scripts.create.absolutePath),
            environment = mapOf("TRAMAI_EVIDENCE_BUNDLE_TIMESTAMP" to "test-bundle"),
        )
        require(cleanPb.exitCode == 0) { "Failed to re-create clean bundle" }

        val finalizeCleanPb = runProcess("bash", listOf(scripts.finalizer.absolutePath, bundle.absolutePath))
        require(finalizeCleanPb.exitCode == 0) { "Failed to finalize clean bundle" }

        val negDir = bundleRoot.resolve("negative-fixtures")
        if (negDir.exists()) negDir.deleteRecursively()
        negDir.mkdirs()

        fun negCopy(name: String): File {
            val target = negDir.resolve(name)
            if (target.exists()) target.deleteRecursively()
            bundle.copyRecursively(target, overwrite = true)
            return target
        }

        // Case 1: Path traversal in requiredFiles
        val traversalDir = negCopy("required-path-traversal")
        fixture.mutateManifest(traversalDir, """m["requiredFiles"].append("../evil.md")""")
        fixture.negRunVerifier(traversalDir, "safe relative path")
        fixture.negRunFinalizer(traversalDir, "safe relative path")

        // Case 2: Absolute path in requiredFiles
        val absDir = negCopy("required-absolute-path")
        fixture.mutateManifest(absDir, """m["requiredFiles"].append("/tmp/evil.md")""")
        fixture.negRunVerifier(absDir, "safe relative path")
        fixture.negRunFinalizer(absDir, "safe relative path")

        // Case 3: Duplicate files[].path
        val dupDir = negCopy("duplicate-file-path")
        fixture.mutateManifest(dupDir, """m["files"].append(m["files"][0])""")
        fixture.negRunVerifier(dupDir, "duplicate files metadata entry")

        // Case 4: manifest.json self-digest
        // The verifier checks SHA-256 before the self-digest check, so the reject
        // message will be "sha256 mismatch for manifest.json" — which still proves
        // the bundle is rejected because of the manifest.json files[] entry.
        val selfDigestDir = negCopy("manifest-self-digest")
        fixture.mutateManifest(selfDigestDir, """m["files"].insert(0, {"path": "manifest.json", "sha256": "0" * 64, "sizeBytes": 0})""")
        fixture.negRunVerifier(selfDigestDir, "sha256 mismatch for manifest.json")

        // Case 5: Weakened claim boundary
        val weakenDir = negCopy("weakened-claims")
        fixture.mutateManifest(weakenDir, """m["claimBoundary"]["certifiesProductionReadiness"] = True""")
        fixture.negRunVerifier(weakenDir, "claimBoundary.certifiesProductionReadiness")
        fixture.negRunFinalizer(weakenDir, "claimBoundary.certifiesProductionReadiness")

        // Case 6: Invalid SHA-256
        val badShaDir = negCopy("malformed-sha")
        fixture.mutateManifest(badShaDir, """m["files"][0]["sha256"] = "not-a-sha" """)
        fixture.negRunVerifier(badShaDir, "sha256")

        // Case 7: Negative sizeBytes
        val negSizeDir = negCopy("negative-size")
        fixture.mutateManifest(negSizeDir, """m["files"][0]["sizeBytes"] = -1""")
        fixture.negRunVerifier(negSizeDir, "sizeBytes")

        // Case 8: Missing required file
        val missingDir = negCopy("missing-file")
        missingDir.resolve("command-log.md").delete()
        fixture.negRunVerifier(missingDir, "required file missing")
        fixture.negRunFinalizer(missingDir, "required file missing")

        // ── Symlink negative fixtures ──

        fun createSymlinkOrSkip(link: File, target: File): Boolean {
            return try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
                true
            } catch (ex: UnsupportedOperationException) {
                log("Skipping symlink fixture: unsupported - ${ex.message}")
                false
            } catch (ex: java.nio.file.FileSystemException) {
                log("Skipping symlink fixture: creation failed - ${ex.message}")
                false
            }
        }

        // Case 9: Required file symlink
        val requiredSymlinkDir = negCopy("required-file-symlink")
        val originalLog = requiredSymlinkDir.resolve("command-log.md")
        val realLog = requiredSymlinkDir.resolve("real-command-log.md")
        originalLog.copyTo(realLog, overwrite = true)
        originalLog.delete()
        if (createSymlinkOrSkip(originalLog, realLog)) {
            fixture.negRunVerifier(requiredSymlinkDir, "symlink")
            fixture.negRunFinalizer(requiredSymlinkDir, "symlink")
        }

        // Case 10: Copied report symlink
        val reportSymlinkDir = negCopy("report-file-symlink")
        val reportDir = reportSymlinkDir.resolve("reports")
        reportDir.mkdirs()
        val realReportFile = reportDir.resolve("generated-report-real.txt")
        realReportFile.writeText("generated report content\n")
        val symlinkReportFile = reportDir.resolve("generated-report.txt")
        if (createSymlinkOrSkip(symlinkReportFile, realReportFile)) {
            fixture.negRunVerifier(reportSymlinkDir, "symlink")
            fixture.negRunFinalizer(reportSymlinkDir, "symlink")
        }

        // Case 11: Unlisted symlink inside bundle
        val unlistedSymlinkDir = negCopy("unlisted-symlink")
        val hiddenLink = unlistedSymlinkDir.resolve("reports/unlisted-link.txt")
        val hiddenTarget = unlistedSymlinkDir.resolve("reports/generated-report.txt")
        if (createSymlinkOrSkip(hiddenLink, hiddenTarget)) {
            fixture.negRunVerifier(unlistedSymlinkDir, "symlink")
            fixture.negRunFinalizer(unlistedSymlinkDir, "symlink")
        }

        // Case 12: Manifest symlink
        val manifestSymlinkDir = negCopy("manifest-symlink")
        val realManifest = manifestSymlinkDir.resolve("real-manifest.json")
        val manifestFile = manifestSymlinkDir.resolve("manifest.json")
        manifestFile.copyTo(realManifest, overwrite = true)
        manifestFile.delete()
        if (createSymlinkOrSkip(manifestFile, realManifest)) {
            fixture.negRunVerifier(manifestSymlinkDir, "symlink")
            fixture.negRunFinalizer(manifestSymlinkDir, "symlink")
        }

        // ── Runtime evidence negative fixtures ──

        val rtDir = negDir.resolve("runtime-evidence-fixtures")
        if (rtDir.exists()) rtDir.deleteRecursively()
        rtDir.mkdirs()

        fun writeRtEvidence(bundle: File, filename: String, vararg lines: String) {
            val dir = bundle.resolve("runtime-evidence")
            dir.mkdirs()
            val file = dir.resolve(filename)
            file.writeText(lines.joinToString("\n") + "\n")
        }

        fun createRtNegFixture(name: String): File {
            val target = rtDir.resolve(name)
            if (target.exists()) target.deleteRecursively()
            bundle.copyRecursively(target, overwrite = true)
            return target
        }

        val validJsonlLine = """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-001","eventType":"policy.decision","workflowRunId":null,"correlationId":null,"actor":null,"createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine","module":"v1"},"decision":{"kind":"ALLOW","reasonCode":"policy_allowed"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"providerName":"ollama"}}"""

        // Case: Malformed JSON line
        val malformedDir = createRtNegFixture("malformed-json-line")
        writeRtEvidence(malformedDir, "policy-decisions.jsonl", "this is not json")
        fixture.negFinalizeRt(malformedDir)
        fixture.negRunVerifier(malformedDir, "invalid JSON")

        // Case: Blank file (must contain at least one record)
        val blankDir = createRtNegFixture("blank-jsonl-file")
        val blankFile = blankDir.resolve("runtime-evidence/policy-decisions.jsonl")
        blankFile.parentFile.mkdirs()
        blankFile.writeText("")
        fixture.negFinalizeRt(blankDir)
        fixture.negRunVerifier(blankDir, "must contain at least one record")

        // Case: Wrong schema version
        val badSchemaDir = createRtNegFixture("wrong-schema-version")
        writeRtEvidence(badSchemaDir, "policy-decisions.jsonl",
            """{"schemaVersion":"evidences.v2","eventId":"evt-002","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"ALLOW"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"}}"""
        )
        fixture.negFinalizeRt(badSchemaDir)
        fixture.negRunVerifier(badSchemaDir, "unsupported schemaVersion")

        // Case: Event/file mismatch
        val mismatchDir = createRtNegFixture("event-file-mismatch")
        writeRtEvidence(mismatchDir, "approval-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-003","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"ALLOW"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"}}"""
        )
        fixture.negFinalizeRt(mismatchDir)
        fixture.negRunVerifier(mismatchDir, "does not match expected")

        // Case: Invalid decision kind
        val badKindDir = createRtNegFixture("invalid-decision-kind")
        writeRtEvidence(badKindDir, "policy-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-004","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"INVALID_KIND"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"}}"""
        )
        fixture.negFinalizeRt(badKindDir)
        fixture.negRunVerifier(badKindDir, "unsupported decision.kind")

        // Case: Unknown metadata key
        val badMetaDir = createRtNegFixture("unknown-metadata-key")
        writeRtEvidence(badMetaDir, "policy-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-005","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"ALLOW"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"rawPrompt":"this should be rejected"}}"""
        )
        fixture.negFinalizeRt(badMetaDir)
        fixture.negRunVerifier(badMetaDir, "not allowlisted")

        // Case: Runtime file removed from files[] in manifest
        val missingManifestDir = createRtNegFixture("runtime-file-missing-from-manifest")
        writeRtEvidence(missingManifestDir, "policy-decisions.jsonl", validJsonlLine)
        // Re-finalize (will include the file), then remove it from manifest
        val reFinalProcess = runProcess("bash", listOf(finalizer.absolutePath, missingManifestDir.absolutePath))
        require(reFinalProcess.exitCode == 0) { "Finalization failed for runtime-file-missing-from-manifest" }
        fixture.mutateManifest(missingManifestDir,
            """m["files"] = [f for f in m["files"] if f["path"] != "runtime-evidence/policy-decisions.jsonl"]"""
        )
        fixture.negRunVerifier(missingManifestDir, "manifest")

        // Case: Unknown JSONL filename
        val unknownFileDir = createRtNegFixture("unknown-runtime-jsonl")
        writeRtEvidence(unknownFileDir, "secret-events.jsonl", validJsonlLine)
        fixture.negFinalizeRt(unknownFileDir)
        fixture.negRunVerifier(unknownFileDir, "unknown file")

        // ── Tool permission negative fixtures ──

        // Case: tool-permissions.jsonl with invalid decision kind
        val badToolKindDir = createRtNegFixture("tool-permission-invalid-decision")
        writeRtEvidence(badToolKindDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-001","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"REDACT_RESULT","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )
        fixture.negFinalizeRt(badToolKindDir)
        fixture.negRunVerifier(badToolKindDir, "unsupported decision.kind")

        // Case: tool-permissions.jsonl with missing toolName
        val missingToolNameDir = createRtNegFixture("tool-permission-missing-toolname")
        writeRtEvidence(missingToolNameDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-002","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )
        fixture.negFinalizeRt(missingToolNameDir)
        fixture.negRunVerifier(missingToolNameDir, "toolName")

        // Case: tool-permissions.jsonl with invalid enforcementPoint
        val badEpDir = createRtNegFixture("tool-permission-bad-enforcementpoint")
        writeRtEvidence(badEpDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-003","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_SOMETHING","riskLevel":"HIGH"}}"""
        )
        fixture.negFinalizeRt(badEpDir)
        fixture.negRunVerifier(badEpDir, "enforcementPoint")

        // Case: tool-permissions.jsonl with invalid riskLevel
        val badRiskDir = createRtNegFixture("tool-permission-bad-risklevel")
        writeRtEvidence(badRiskDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-004","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"ULTRA_HIGH"}}"""
        )
        fixture.negFinalizeRt(badRiskDir)
        fixture.negRunVerifier(badRiskDir, "riskLevel")

        // Case: tool-permissions.jsonl with wrong source.component
        val badToolSrcDir = createRtNegFixture("tool-permission-wrong-source")
        writeRtEvidence(badToolSrcDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-005","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"provider-router"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )
        fixture.negFinalizeRt(badToolSrcDir)
        fixture.negRunVerifier(badToolSrcDir, "source.component")

        // Clean up negative fixture directories
        negDir.deleteRecursively()

        // ── Archive export verification ──

        val packager = scripts.packager
        require(packager.exists()) {
            "Missing evidence bundle packager at ${packager.absolutePath}"
        }

        // Package the finalized bundle
        val packageProcess = runProcess("bash", listOf(packager.absolutePath, bundle.absolutePath))
        val packageOutput = packageProcess.output
        val packageExitCode = packageProcess.exitCode
        require(packageExitCode == 0) {
            "Evidence bundle packager failed with code $packageExitCode. Output: $packageOutput"
        }

        val archiveRoot = workDir.resolve("evidence-archives")
        val archive = archiveRoot.resolve("test-bundle.tar.gz")
        val checksum = archiveRoot.resolve("test-bundle.tar.gz.sha256")

        require(archive.isFile) {
            "Expected evidence bundle archive at ${archive.absolutePath}"
        }
        require(checksum.isFile) {
            "Expected evidence bundle archive checksum at ${checksum.absolutePath}"
        }

        // Verify checksum
        val checksumProcess = runProcess(
            "sha256sum",
            listOf("-c", checksum.name),
            workingDirectory = archiveRoot,
        )
        val checksumOutput = checksumProcess.output
        val checksumExitCode = checksumProcess.exitCode
        require(checksumExitCode == 0) {
            "Evidence bundle archive checksum validation failed. Output: $checksumOutput"
        }

        // Extract and re-verify
        val extractRoot = workDir.resolve("evidence-archives/extracted")
        if (extractRoot.exists()) extractRoot.deleteRecursively()
        extractRoot.mkdirs()

        val extractProcess = runProcess(
            "tar",
            listOf(
                "-xzf",
                archive.absolutePath,
                "-C",
                extractRoot.absolutePath,
            ),
        )
        val extractOutput = extractProcess.output
        val extractExitCode = extractProcess.exitCode
        require(extractExitCode == 0) {
            "Evidence bundle archive extraction failed with code $extractExitCode. Output: $extractOutput"
        }

        val extractedBundle = extractRoot.resolve("test-bundle")
        require(extractedBundle.isDirectory) {
            "Extracted evidence bundle directory not found at ${extractedBundle.absolutePath}"
        }

        val extractedVerify = runProcess("bash", listOf(verifier.absolutePath, extractedBundle.absolutePath))
        val extractedVerifyOutput = extractedVerify.output
        val extractedVerifyExitCode = extractedVerify.exitCode
        require(extractedVerifyExitCode == 0) {
            "Verifier rejected extracted evidence bundle. Output: $extractedVerifyOutput"
        }

        // ── Deterministic archive export regression ──

        val determinismRoot = archiveRoot.resolve("determinism")
        if (determinismRoot.exists()) determinismRoot.deleteRecursively()
        determinismRoot.mkdirs()

        // First packaging
        val firstPackage = runProcess("bash", listOf(packager.absolutePath, bundle.absolutePath))
        val firstPackageOutput = firstPackage.output
        val firstPackageExitCode = firstPackage.exitCode
        require(firstPackageExitCode == 0) {
            "First deterministic archive packaging failed. Output: $firstPackageOutput"
        }

        val firstArchive = determinismRoot.resolve("test-bundle-first.tar.gz")
        val firstChecksum = determinismRoot.resolve("test-bundle-first.tar.gz.sha256")
        archive.copyTo(firstArchive, overwrite = true)
        checksum.copyTo(firstChecksum, overwrite = true)

        val firstArchiveSha = BundleAssertions.sha256(firstArchive)
        val firstChecksumText = firstChecksum.readText()

        // Second packaging
        val secondPackage = runProcess("bash", listOf(packager.absolutePath, bundle.absolutePath))
        val secondPackageOutput = secondPackage.output
        val secondPackageExitCode = secondPackage.exitCode
        require(secondPackageExitCode == 0) {
            "Second deterministic archive packaging failed. Output: $secondPackageOutput"
        }

        val secondArchive = determinismRoot.resolve("test-bundle-second.tar.gz")
        val secondChecksum = determinismRoot.resolve("test-bundle-second.tar.gz.sha256")
        archive.copyTo(secondArchive, overwrite = true)
        checksum.copyTo(secondChecksum, overwrite = true)

        val secondArchiveSha = BundleAssertions.sha256(secondArchive)
        val secondChecksumText = secondChecksum.readText()

        require(firstArchiveSha == secondArchiveSha) {
            "Evidence archive export is not deterministic. First SHA-256=$firstArchiveSha, second SHA-256=$secondArchiveSha"
        }

        require(firstChecksumText == secondChecksumText) {
            "Evidence archive checksum sidecar is not deterministic. First=$firstChecksumText Second=$secondChecksumText"
        }

        require(secondChecksumText.startsWith(secondArchiveSha)) {
            "Checksum sidecar does not match archive SHA-256. Sidecar=$secondChecksumText Archive=$secondArchiveSha"
        }

        // ── PR #156: Archive verifier positive test ──

        val archiveVerifier = scripts.archiveVerifier
        require(archiveVerifier.exists()) {
            "Missing evidence archive verifier at ${archiveVerifier.absolutePath}"
        }

        val archiveVerifyProcess = runProcess("bash", listOf(archiveVerifier.absolutePath, archive.absolutePath))
        val archiveVerifyOutput = archiveVerifyProcess.output
        val archiveVerifyExitCode = archiveVerifyProcess.exitCode

        require(archiveVerifyExitCode == 0) {
            "Evidence archive verifier failed with code $archiveVerifyExitCode. Output: $archiveVerifyOutput"
        }
        require(archiveVerifyOutput.contains("Evidence archive verified:")) {
            "Archive verifier success output missing. Got: $archiveVerifyOutput"
        }

        // ── PR #156: Negative archive fixtures ──

        val archiveNegRoot = archiveRoot.resolve("negative-archives")
        if (archiveNegRoot.exists()) archiveNegRoot.deleteRecursively()
        archiveNegRoot.mkdirs()

        // Negative 1: Missing checksum sidecar
        val missingChecksumArchive = archiveNegRoot.resolve("missing-checksum.tar.gz")
        archive.copyTo(missingChecksumArchive, overwrite = true)

        fixture.runArchiveVerifierExpectFail(missingChecksumArchive, "checksum")

        // Negative 2: Tampered archive
        val tamperedArchive = archiveNegRoot.resolve("tampered.tar.gz")
        val tamperedChecksum = archiveNegRoot.resolve("tampered.tar.gz.sha256")

        archive.copyTo(tamperedArchive, overwrite = true)
        checksum.copyTo(tamperedChecksum, overwrite = true)
        tamperedChecksum.writeText(tamperedChecksum.readText().replace("test-bundle.tar.gz", "tampered.tar.gz"))
        tamperedArchive.appendBytes("tamper".toByteArray())

        fixture.runArchiveVerifierExpectFail(tamperedArchive, "checksum mismatch")

        // Negative 3: Unsafe tar entry (path traversal)
        val unsafeArchive = archiveNegRoot.resolve("unsafe-entry.tar.gz")
        val unsafeDir = archiveNegRoot.resolve("unsafe-src")
        if (unsafeDir.exists()) unsafeDir.deleteRecursively()
        unsafeDir.mkdirs()
        unsafeDir.resolve("evil.txt").writeText("evil\n")

        val unsafeCreateProcess = runProcess(
            "python3",
            listOf(
                "-c",
                """
import tarfile, pathlib
archive = pathlib.Path("${unsafeArchive.absolutePath}")
payload = pathlib.Path("${unsafeDir.resolve("evil.txt").absolutePath}")
with tarfile.open(archive, "w:gz") as tar:
    tar.add(payload, arcname="../evil.txt")
"""
            ),
        )
        val unsafeCreateOutput = unsafeCreateProcess.output
        val unsafeCreateExit = unsafeCreateProcess.exitCode
        require(unsafeCreateExit == 0) {
            "Failed to create unsafe archive fixture. Output: $unsafeCreateOutput"
        }

        val unsafeSha = BundleAssertions.sha256(unsafeArchive)
        unsafeArchive.resolveSibling("${unsafeArchive.name}.sha256")
            .writeText("$unsafeSha  ${unsafeArchive.name}\n")

        fixture.runArchiveVerifierExpectFail(unsafeArchive, "safe relative path")

        // Negative 4: Symlink tar entry
        val symlinkArchive = archiveNegRoot.resolve("symlink-entry.tar.gz")
        val symlinkCreateProcess = runProcess(
            "python3",
            listOf(
                "-c",
                """
import tarfile, pathlib
archive = pathlib.Path("${symlinkArchive.absolutePath}")
info = tarfile.TarInfo("test-bundle/link.txt")
info.type = tarfile.SYMTYPE
info.linkname = "target.txt"
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
            ),
        )
        val symlinkCreateOutput = symlinkCreateProcess.output
        val symlinkCreateExit = symlinkCreateProcess.exitCode
        require(symlinkCreateExit == 0) {
            "Failed to create symlink archive fixture. Output: $symlinkCreateOutput"
        }

        val symlinkSha = BundleAssertions.sha256(symlinkArchive)
        symlinkArchive.resolveSibling("${symlinkArchive.name}.sha256")
            .writeText("$symlinkSha  ${symlinkArchive.name}\n")

        fixture.runArchiveVerifierExpectFail(symlinkArchive, "symlink")

        // Negative 5: Sidecar references wrong filename
        val wrongSidecarArchive = archiveNegRoot.resolve("wrong-sidecar-name.tar.gz")
        val wrongSidecar = archiveNegRoot.resolve("wrong-sidecar-name.tar.gz.sha256")
        archive.copyTo(wrongSidecarArchive, overwrite = true)
        val wrongSha = BundleAssertions.sha256(wrongSidecarArchive)
        wrongSidecar.writeText("$wrongSha  /dev/zero\n")

        fixture.runArchiveVerifierExpectFail(wrongSidecarArchive, "must reference")

        // ── PR #157: Expanded negative archive fixtures ──

        fun writeArchiveSidecar(archiveFile: File) {
            archiveFile.resolveSibling("${archiveFile.name}.sha256")
                .writeText("${BundleAssertions.sha256(archiveFile)}  ${archiveFile.name}\n")
        }

        // Negative 6: Absolute path tar entry
        val absoluteEntryArchive = archiveNegRoot.resolve("absolute-entry.tar.gz")

        val absoluteCreateProcess = runProcess(
            "python3",
            listOf(
                "-c",
                """
import tarfile, pathlib
archive = pathlib.Path("${absoluteEntryArchive.absolutePath}")

# Must use TarInfo directly because tar.add() strips leading slashes
info = tarfile.TarInfo("/evil.txt")
info.type = tarfile.REGTYPE
info.size = 0

with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
            ),
        )
        val absoluteCreateOutput = absoluteCreateProcess.output
        require(absoluteCreateProcess.exitCode == 0) {
            "Failed to create absolute-entry archive fixture. Output: $absoluteCreateOutput"
        }

        writeArchiveSidecar(absoluteEntryArchive)
        fixture.runArchiveVerifierExpectFail(absoluteEntryArchive, "must not be absolute")

        // Negative 7: Hardlink tar entry
        val hardlinkArchive = archiveNegRoot.resolve("hardlink-entry.tar.gz")

        val hardlinkCreateProcess = runProcess(
            "python3",
            listOf(
                "-c",
                """
import tarfile, pathlib
archive = pathlib.Path("${hardlinkArchive.absolutePath}")
info = tarfile.TarInfo("test-bundle/hardlink.txt")
info.type = tarfile.LNKTYPE
info.linkname = "target.txt"
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
            ),
        )
        val hardlinkCreateOutput = hardlinkCreateProcess.output
        require(hardlinkCreateProcess.exitCode == 0) {
            "Failed to create hardlink archive fixture. Output: $hardlinkCreateOutput"
        }

        writeArchiveSidecar(hardlinkArchive)
        fixture.runArchiveVerifierExpectFail(hardlinkArchive, "hardlink")

        // Negative 8: Special file / FIFO tar entry
        val specialFileArchive = archiveNegRoot.resolve("special-file-entry.tar.gz")

        val specialCreateProcess = runProcess(
            "python3",
            listOf(
                "-c",
                """
import tarfile, pathlib
archive = pathlib.Path("${specialFileArchive.absolutePath}")
info = tarfile.TarInfo("test-bundle/fifo")
info.type = tarfile.FIFOTYPE
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
            ),
        )
        val specialCreateOutput = specialCreateProcess.output
        require(specialCreateProcess.exitCode == 0) {
            "Failed to create special-file archive fixture. Output: $specialCreateOutput"
        }

        writeArchiveSidecar(specialFileArchive)
        fixture.runArchiveVerifierExpectFail(specialFileArchive, "regular file or directory")

        // Negative 9: Empty archive
        val emptyArchive = archiveNegRoot.resolve("empty-archive.tar.gz")

        val emptyCreateProcess = runProcess(
            "python3",
            listOf(
                "-c",
                """
import tarfile, pathlib
archive = pathlib.Path("${emptyArchive.absolutePath}")
with tarfile.open(archive, "w:gz"):
    pass
"""
            ),
        )
        val emptyCreateOutput = emptyCreateProcess.output
        require(emptyCreateProcess.exitCode == 0) {
            "Failed to create empty archive fixture. Output: $emptyCreateOutput"
        }

        writeArchiveSidecar(emptyArchive)
        fixture.runArchiveVerifierExpectFail(emptyArchive, "archive is empty")

        // Negative 10: Multiple top-level directories
        val multiTopArchive = archiveNegRoot.resolve("multi-top-level.tar.gz")
        val multiTopRoot = archiveNegRoot.resolve("multi-top-src")
        if (multiTopRoot.exists()) multiTopRoot.deleteRecursively()
        multiTopRoot.mkdirs()

        val fileA = multiTopRoot.resolve("a.txt")
        val fileB = multiTopRoot.resolve("b.txt")
        fileA.writeText("a\n")
        fileB.writeText("b\n")

        val multiTopCreateProcess = runProcess(
            "python3",
            listOf(
                "-c",
                """
import tarfile, pathlib
archive = pathlib.Path("${multiTopArchive.absolutePath}")
file_a = pathlib.Path("${fileA.absolutePath}")
file_b = pathlib.Path("${fileB.absolutePath}")
with tarfile.open(archive, "w:gz") as tar:
    tar.add(file_a, arcname="bundle-a/a.txt")
    tar.add(file_b, arcname="bundle-b/b.txt")
"""
            ),
        )
        val multiTopCreateOutput = multiTopCreateProcess.output
        require(multiTopCreateProcess.exitCode == 0) {
            "Failed to create multi-top-level archive fixture. Output: $multiTopCreateOutput"
        }

        writeArchiveSidecar(multiTopArchive)
        fixture.runArchiveVerifierExpectFail(multiTopArchive, "exactly one top-level")

        // Negative 11: Invalid sidecar SHA format
        val invalidShaArchive = archiveNegRoot.resolve("invalid-sidecar-sha.tar.gz")
        archive.copyTo(invalidShaArchive, overwrite = true)
        invalidShaArchive.resolveSibling("${invalidShaArchive.name}.sha256")
            .writeText("not-a-sha  ${invalidShaArchive.name}\n")

        fixture.runArchiveVerifierExpectFail(invalidShaArchive, "valid SHA-256")

        // Negative 12: Multi-line sidecar
        val multilineSidecarArchive = archiveNegRoot.resolve("multiline-sidecar.tar.gz")
        archive.copyTo(multilineSidecarArchive, overwrite = true)

        val multilineSha = BundleAssertions.sha256(multilineSidecarArchive)
        multilineSidecarArchive.resolveSibling("${multilineSidecarArchive.name}.sha256")
            .writeText(
                """
                $multilineSha  ${multilineSidecarArchive.name}
                $multilineSha  other.tar.gz
                """.trimIndent() + "\n"
            )

        fixture.runArchiveVerifierExpectFail(multilineSidecarArchive, "exactly one line")

        // ── PR #158: Sidecar parser fixtures ──

        fun writeCustomSidecar(archiveFile: File, text: String) {
            archiveFile.resolveSibling("${archiveFile.name}.sha256")
                .writeText(text)
        }

        // Positive: binary-mode sidecar (sha256sum -b)
        val binarySidecarArchive = archiveNegRoot.resolve("binary-sidecar.tar.gz")
        archive.copyTo(binarySidecarArchive, overwrite = true)
        val binarySha = BundleAssertions.sha256(binarySidecarArchive)
        writeCustomSidecar(binarySidecarArchive, "$binarySha *${binarySidecarArchive.name}\n")

        val binaryProcess = runProcess("bash", listOf(archiveVerifier.absolutePath, binarySidecarArchive.absolutePath))
        val binaryOutput = binaryProcess.output
        val binaryExit = binaryProcess.exitCode
        require(binaryExit == 0) {
            "Expected binary-mode sidecar to verify, but it failed. Output: $binaryOutput"
        }

        // Negative: extra sidecar field
        val extraFieldSidecarArchive = archiveNegRoot.resolve("extra-field-sidecar.tar.gz")
        archive.copyTo(extraFieldSidecarArchive, overwrite = true)
        val extraFieldSha = BundleAssertions.sha256(extraFieldSidecarArchive)
        writeCustomSidecar(
            extraFieldSidecarArchive,
            "$extraFieldSha  ${extraFieldSidecarArchive.name} unexpected\n"
        )
        fixture.runArchiveVerifierExpectFail(extraFieldSidecarArchive, "exactly a SHA-256 digest and archive filename")

        // Negative: missing filename
        val missingNameSidecarArchive = archiveNegRoot.resolve("missing-name-sidecar.tar.gz")
        archive.copyTo(missingNameSidecarArchive, overwrite = true)
        val missingNameSha = BundleAssertions.sha256(missingNameSidecarArchive)
        writeCustomSidecar(missingNameSidecarArchive, "$missingNameSha\n")
        fixture.runArchiveVerifierExpectFail(missingNameSidecarArchive, "digest and archive filename")

        // Negative: whitespace-only sidecar
        val blankSidecarArchive = archiveNegRoot.resolve("blank-sidecar.tar.gz")
        archive.copyTo(blankSidecarArchive, overwrite = true)
        writeCustomSidecar(blankSidecarArchive, "   \n")
        fixture.runArchiveVerifierExpectFail(blankSidecarArchive, "digest and archive filename")

        // Positive: sidecar without trailing newline
        val noTrailingNewlineArchive = archiveNegRoot.resolve("no-trailing-newline-sidecar.tar.gz")
        archive.copyTo(noTrailingNewlineArchive, overwrite = true)
        val noTrailingNewlineSha = BundleAssertions.sha256(noTrailingNewlineArchive)
        writeCustomSidecar(
            noTrailingNewlineArchive,
            "$noTrailingNewlineSha  ${noTrailingNewlineArchive.name}"
        )
        val noTrailingNewlineProcess = runProcess("bash", listOf(archiveVerifier.absolutePath, noTrailingNewlineArchive.absolutePath))
        val noTrailingNewlineOutput = noTrailingNewlineProcess.output
        val noTrailingNewlineExit = noTrailingNewlineProcess.exitCode
        require(noTrailingNewlineExit == 0) {
            "Expected sidecar without trailing newline to verify, but it failed. Output: $noTrailingNewlineOutput"
        }

        // Negative: two lines, second line has no trailing newline
        val multilineNoFinalNewlineArchive = archiveNegRoot.resolve("multiline-no-final-newline-sidecar.tar.gz")
        archive.copyTo(multilineNoFinalNewlineArchive, overwrite = true)
        val multilineNoFinalNewlineSha = BundleAssertions.sha256(multilineNoFinalNewlineArchive)
        writeCustomSidecar(
            multilineNoFinalNewlineArchive,
            "$multilineNoFinalNewlineSha  ${multilineNoFinalNewlineArchive.name}\n$multilineNoFinalNewlineSha  other.tar.gz"
        )
        fixture.runArchiveVerifierExpectFail(multilineNoFinalNewlineArchive, "exactly one line")

        // ── PR #159: Top-level file rejection ──

        val topLevelFileArchive = archiveNegRoot.resolve("top-level-file.tar.gz")

        val topLevelFileCreateProcess = runProcess(
            "python3",
            listOf(
                "-c",
                """
import tarfile, pathlib, io
archive = pathlib.Path("${topLevelFileArchive.absolutePath}")
payload = b"not a bundle directory\\n"
info = tarfile.TarInfo("bundle.txt")
info.type = tarfile.REGTYPE
info.size = len(payload)
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info, io.BytesIO(payload))
"""
            ),
        )
        val topLevelFileCreateOutput = topLevelFileCreateProcess.output
        require(topLevelFileCreateProcess.exitCode == 0) {
            "Failed to create top-level-file archive fixture. Output: $topLevelFileCreateOutput"
        }
        writeArchiveSidecar(topLevelFileArchive)
        fixture.runArchiveVerifierExpectFail(topLevelFileArchive, "top-level entry must be a directory")

        // ── PR #161: Optional archive signature verifier ──

        val signatureVerifier = scripts.signatureVerifier
        require(signatureVerifier.exists()) {
            "Missing evidence archive signature verifier at ${signatureVerifier.absolutePath}"
        }

        val sigArchiveRoot = archiveRoot.resolve("signature-tests")
        if (sigArchiveRoot.exists()) sigArchiveRoot.deleteRecursively()
        sigArchiveRoot.mkdirs()

        // Helper: generate ephemeral RSA keypair for fixture testing

        // Helper: sign a checksum sidecar

        // Re-package the finalized bundle into a fresh archive for signature tests
        val sigPackageProcess = runProcess("bash", listOf(packager.absolutePath, bundle.absolutePath))
        val sigPackageOutput = sigPackageProcess.output
        require(sigPackageProcess.exitCode == 0) {
            "Repackaging for signature tests failed. Output: $sigPackageOutput"
        }

        val sigArchive = archiveRoot.resolve("test-bundle.tar.gz")
        val sigChecksum = archiveRoot.resolve("test-bundle.tar.gz.sha256")
        require(sigArchive.isFile && sigChecksum.isFile) {
            "Re-packaged archive or checksum missing for signature tests."
        }

        // Copy archive + checksum to fixture dir so we don't mutate the originals
        val sigArchiveCopy = sigArchiveRoot.resolve("test-bundle.tar.gz")
        val sigChecksumCopy = sigArchiveRoot.resolve("test-bundle.tar.gz.sha256")
        sigArchive.copyTo(sigArchiveCopy, overwrite = true)
        sigChecksum.copyTo(sigChecksumCopy, overwrite = true)

        // Generate ephemeral keypair
        val (sigPrivateKey, sigPublicKey) = fixture.generateKeypair(sigArchiveRoot)

        // Sign the checksum sidecar
        val sigSigFile = sigArchiveRoot.resolve("test-bundle.tar.gz.sha256.sig")
        fixture.signChecksum(sigChecksumCopy, sigPrivateKey, sigSigFile)

        // Positive: valid signature + archive verification
        val positiveSigProcess = runProcess(
            "bash",
            listOf(
                signatureVerifier.absolutePath,
                sigArchiveCopy.absolutePath, sigPublicKey.absolutePath,
            ),
        )
        val positiveSigOutput = positiveSigProcess.output
        val positiveSigExitCode = positiveSigProcess.exitCode
        require(positiveSigExitCode == 0) {
            "Expected signature verifier to pass for valid signature. Output: $positiveSigOutput"
        }
        require(positiveSigOutput.contains("Evidence archive signature verified:")) {
            "Signature verifier success output missing. Got: $positiveSigOutput"
        }

        // Negative 1: Missing .sha256.sig
        val noSigArchive = sigArchiveRoot.resolve("no-sig.tar.gz")
        val noSigChecksum = sigArchiveRoot.resolve("no-sig.tar.gz.sha256")
        sigArchiveCopy.copyTo(noSigArchive, overwrite = true)
        sigChecksumCopy.copyTo(noSigChecksum, overwrite = true)
        fixture.runSignatureVerifierExpectFail(noSigArchive, sigPublicKey, "missing")

        // Negative 2: Tampered checksum sidecar after signing
        val tamperedSigArchive = sigArchiveRoot.resolve("tampered-sidecar.tar.gz")
        val tamperedSigChecksum = sigArchiveRoot.resolve("tampered-sidecar.tar.gz.sha256")
        val tamperedSigSig = sigArchiveRoot.resolve("tampered-sidecar.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(tamperedSigArchive, overwrite = true)
        sigChecksumCopy.copyTo(tamperedSigChecksum, overwrite = true)
        fixture.signChecksum(tamperedSigChecksum, sigPrivateKey, tamperedSigSig)
        // Tamper the sidecar after signing
        tamperedSigChecksum.appendText("tamper\n")
        fixture.runSignatureVerifierExpectFail(tamperedSigArchive, sigPublicKey, "FAILED")

        // Negative 3: Wrong public key
        val wrongKeyArchive = sigArchiveRoot.resolve("wrong-key.tar.gz")
        val wrongKeyChecksum = sigArchiveRoot.resolve("wrong-key.tar.gz.sha256")
        val wrongKeySig = sigArchiveRoot.resolve("wrong-key.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(wrongKeyArchive, overwrite = true)
        sigChecksumCopy.copyTo(wrongKeyChecksum, overwrite = true)
        fixture.signChecksum(wrongKeyChecksum, sigPrivateKey, wrongKeySig)
        val (_, wrongPublicKey) = fixture.generateKeypair(sigArchiveRoot.resolve("wrong-key-keys"))
        fixture.runSignatureVerifierExpectFail(wrongKeyArchive, wrongPublicKey, "FAILED")

        // Negative 4: Tampered archive after valid signature
        // Proves the script chains into verify-evidence-archive.sh after signature verification
        val tamperedArchiveSig = sigArchiveRoot.resolve("tampered-archive.tar.gz")
        val tamperedArchiveChecksum = sigArchiveRoot.resolve("tampered-archive.tar.gz.sha256")
        val tamperedArchiveSigFile = sigArchiveRoot.resolve("tampered-archive.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(tamperedArchiveSig, overwrite = true)
        // Write a proper sidecar referencing the tampered archive filename
        val tamperedSha = BundleAssertions.sha256(tamperedArchiveSig)
        tamperedArchiveChecksum.writeText("$tamperedSha  tampered-archive.tar.gz\n")
        fixture.signChecksum(tamperedArchiveChecksum, sigPrivateKey, tamperedArchiveSigFile)
        // Tamper the archive content after signature creation
        tamperedArchiveSig.appendBytes("tamper".toByteArray())
        // Signature was over the original checksum; archive is now different.
        // openssl verifies the signature (valid for the signed checksum),
        // then archive verifier rejects because the archive doesn't match the checksum.
        fixture.runSignatureVerifierExpectFail(tamperedArchiveSig, sigPublicKey, "checksum mismatch")

        // Negative 5: Missing public key (non-existent file)
        val missingKeyArchive = sigArchiveRoot.resolve("missing-key.tar.gz")
        val missingKeyChecksum = sigArchiveRoot.resolve("missing-key.tar.gz.sha256")
        val missingKeySig = sigArchiveRoot.resolve("missing-key.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(missingKeyArchive, overwrite = true)
        sigChecksumCopy.copyTo(missingKeyChecksum, overwrite = true)
        fixture.signChecksum(missingKeyChecksum, sigPrivateKey, missingKeySig)
        val nonexistentKey = sigArchiveRoot.resolve("nonexistent.pem")
        val missingKeyProcess = runProcess(
            "bash",
            listOf(
                signatureVerifier.absolutePath,
                missingKeyArchive.absolutePath, nonexistentKey.absolutePath,
            ),
        )
        val missingKeyOutput = missingKeyProcess.output
        val missingKeyExitCode = missingKeyProcess.exitCode
        require(missingKeyExitCode != 0) {
            "Expected signature verifier to fail for missing public key. Output: $missingKeyOutput"
        }
        require(missingKeyOutput.contains("Public key must be a readable regular file", ignoreCase = true)) {
            "Expected missing public key error, but got: $missingKeyOutput"
        }

        log("verifySovereignLabEvidenceBundle: generated bundle verified at ${bundle.absolutePath}")
    }
}
