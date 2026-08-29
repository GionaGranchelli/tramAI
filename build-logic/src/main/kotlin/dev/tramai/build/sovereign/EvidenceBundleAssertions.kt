package dev.tramai.build.sovereign

import java.io.File

/**
 * Pure bundle assertions for the sovereign-lab evidence-bundle scenario
 * (Epic 9.2d-a3b2b). No processes, no Gradle — every function takes only
 * [File] parameters and preserves the historical require() messages
 * byte-for-byte so tests can assert the exact diagnostics.
 */
object BundleAssertions {

    /** The same 12 required files the historical closure required. */
    val requiredFiles: List<String> = listOf(
        "README.md",
        "manifest.json",
        "MANIFEST.md",
        "command-log.md",
        "environment.md",
        "run-log.md",
        "approval-flow.md",
        "restart-proof.md",
        "jdbc-persistence.md",
        "no-cloud-proof.md",
        "benchmark.md",
        "reports/.gitkeep",
    )

    /** SHA-256 hex digest of [file] (moved verbatim from the closure helper). */
    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Every required file must exist in the bundle. */
    fun assertRequiredFiles(bundle: File) {
        requiredFiles.forEach { relativePath ->
            val candidate = bundle.resolve(relativePath)
            require(candidate.exists()) {
                "Generated evidence bundle is missing $relativePath at ${candidate.absolutePath}"
            }
        }
    }

    /** README.md claim-boundary checks. */
    fun assertReadmeClaims(bundle: File) {
        val readmeText = bundle.resolve("README.md").readText()
        require(readmeText.contains("Sovereign Lab Evidence Bundle")) {
            "Generated README.md must be the bundle README, not the template README."
        }
        require(!readmeText.contains("Copy this entire folder", ignoreCase = true)) {
            "Generated README.md must not be copied from evidence-template/README.md."
        }
        require(readmeText.contains("does not certify", ignoreCase = true)) {
            "Generated README.md must avoid certification claims."
        }
        require(readmeText.contains("performance guarantees", ignoreCase = true)) {
            "Generated README.md must avoid production performance guarantee claims."
        }
    }

    /** MANIFEST.md + manifest.json structural claim checks. */
    fun assertManifestClaims(bundle: File) {
        val manifestText = bundle.resolve("MANIFEST.md").readText()
        require(manifestText.contains("This bundle does not certify", ignoreCase = true)) {
            "MANIFEST.md must retain non-certification language."
        }

        // ── manifest.json checks ──

        val jsonManifestText = bundle.resolve("manifest.json").readText()
        require(jsonManifestText.contains("\"schemaVersion\": 1")) {
            "manifest.json must declare schemaVersion 1."
        }
        require(jsonManifestText.contains("\"bundleType\": \"sovereign-lab-evidence-bundle\"")) {
            "manifest.json must declare the sovereign lab evidence bundle type."
        }
        require(jsonManifestText.contains("\"localEvidenceScaffold\": true")) {
            "manifest.json must declare this as a local evidence scaffold."
        }
        require(jsonManifestText.contains("\"certifiesProductionReadiness\": false")) {
            "manifest.json must not imply production certification."
        }
        require(jsonManifestText.contains("\"definesPerformanceGuarantees\": false")) {
            "manifest.json must not imply performance guarantees."
        }
        require(jsonManifestText.contains("\"runsLocalModel\": false")) {
            "manifest.json must state that bundle verification does not run a local model."
        }
        require(jsonManifestText.contains("\"runsBenchmark\": false")) {
            "manifest.json must state that bundle verification does not run benchmarks."
        }
        require(jsonManifestText.contains("\"validatesEvidenceTruth\": false")) {
            "manifest.json must state that it does not validate evidence truth."
        }
        requiredFiles
            .filterNot { it == "manifest.json" }
            .forEach { required ->
                require(jsonManifestText.contains("\"$required\"")) {
                    "manifest.json must list required file $required."
                }
            }
    }

    /** manifest.json SHA-256 + sizeBytes digests must match the generated files. */
    fun assertManifestDigests(bundle: File) {
        val jsonManifestText = bundle.resolve("manifest.json").readText()

        // ── manifest.json file digests ──

        require(jsonManifestText.contains("\"files\": [")) {
            "manifest.json must include file integrity metadata."
        }
        require(jsonManifestText.contains("\"sha256\"")) {
            "manifest.json must include SHA-256 digests."
        }
        require(jsonManifestText.contains("\"sizeBytes\"")) {
            "manifest.json must include file sizes."
        }

        // Recompute SHA-256 digests and verify they match
        requiredFiles
            .filterNot { it == "manifest.json" }
            .forEach { required ->
                val candidate = bundle.resolve(required)
                require(candidate.exists()) {
                    "Cannot recompute digest for missing file $required."
                }
                val digest = sha256(candidate)
                require(jsonManifestText.contains("\"sha256\": \"$digest\"")) {
                    "manifest.json SHA-256 for $required does not match generated file."
                }
                require(jsonManifestText.contains("\"sizeBytes\": ${candidate.length()}")) {
                    "manifest.json sizeBytes for $required does not match generated file."
                }
            }
    }

    /** After finalization the manifest must list the 4 runtime-evidence paths. */
    fun assertRuntimeEvidenceStructure(bundle: File) {
        val manifestText = bundle.resolve("manifest.json").readText()
        for (rtFile in listOf(
            "runtime-evidence/policy-decisions.jsonl",
            "runtime-evidence/approval-decisions.jsonl",
            "runtime-evidence/provider-routing.jsonl",
            "runtime-evidence/tool-permissions.jsonl",
        )) {
            require(manifestText.contains(rtFile)) {
                "manifest.json must contain runtime-evidence path '$rtFile' after finalization. " +
                    "Manifest: $manifestText"
            }
        }
    }
}
