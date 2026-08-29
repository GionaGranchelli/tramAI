package dev.tramai.build.sovereign

import java.io.File

/**
 * Adapter-backed scenario fixtures for the sovereign-lab evidence-bundle
 * scenario (Epic 9.2d-a3b2b): manifest mutations, expect-fail verifier /
 * finalizer runs, archive/signature negative runners, ephemeral openssl
 * keypairs and sidecar signing. All require() diagnostics are preserved
 * byte-for-byte from the historical root closure.
 */
class EvidenceBundleFixtureBuilder(
    private val scripts: EvidenceScripts,
    private val adapter: EvidenceBundleProcessAdapter,
) {

    private fun run(executable: String, arguments: List<String>): ProcessResult =
        adapter.run(File(executable), arguments, emptyMap(), File("."))

    fun runExpectFail(
        runner: File,
        bundleDir: File,
        expectMessage: String,
        runnerName: String,
    ) {
        val p = run("bash", listOf(runner.absolutePath, bundleDir.absolutePath))
        val out = p.output
        val code = p.exitCode
        require(code != 0) {
            "Expected $runnerName to fail for ${bundleDir.name}, but exited 0. Output: $out"
        }
        require(out.contains(expectMessage, ignoreCase = true)) {
            "Expected $runnerName failure for ${bundleDir.name} to contain '$expectMessage'. Output: $out"
        }
    }

    fun negRunVerifier(dir: File, msg: String) =
        runExpectFail(scripts.verifier, dir, msg, "verifier")

    fun negRunFinalizer(dir: File, msg: String) =
        runExpectFail(scripts.finalizer, dir, msg, "finalizer")

    fun mutateManifest(dir: File, pythonCode: String) {
        val fullCode = """
import json, pathlib, sys
bp = pathlib.Path(sys.argv[1])
m = json.loads((bp / "manifest.json").read_text())
$pythonCode
(bp / "manifest.json").write_text(json.dumps(m, indent=2) + "\n")
"""
        val p = run("python3", listOf("-c", fullCode, dir.absolutePath))
        val out = p.output
        val exitCode = p.exitCode
        require(exitCode == 0) { "manifest mutation failed: $out" }
    }

    fun negFinalizeRt(bundleDir: File) {
        val p = run("bash", listOf(scripts.finalizer.absolutePath, bundleDir.absolutePath))
        require(p.exitCode == 0) { "Finalization failed for ${bundleDir.name}" }
    }

    fun runArchiveVerifierExpectFail(archiveFile: File, expected: String) {
        val process = run("bash", listOf(scripts.archiveVerifier.absolutePath, archiveFile.absolutePath))
        val output = process.output
        val exitCode = process.exitCode

        require(exitCode != 0) {
            "Expected archive verifier to fail for ${archiveFile.name}, but it passed. Output: $output"
        }
        require(output.contains(expected, ignoreCase = true)) {
            "Expected archive verifier failure to contain '$expected', but output was: $output"
        }
    }

    fun generateKeypair(dir: File): Pair<File, File> {
        dir.mkdirs()
        val privateKey = dir.resolve("fixture-key.pem")
        val publicKey = dir.resolve("fixture-key.pub")
        val genProcess = run(
            "openssl",
            listOf(
                "genpkey",
                "-algorithm", "RSA",
                "-pkeyopt", "rsa_keygen_bits:2048",
                "-outform", "PEM",
                "-out", privateKey.absolutePath,
            ),
        )
        val genOutput = genProcess.output
        require(genProcess.exitCode == 0) {
            "Failed to generate ephemeral signing key. Output: $genOutput"
        }

        val pubProcess = run(
            "openssl",
            listOf(
                "rsa",
                "-pubout",
                "-in", privateKey.absolutePath,
                "-outform", "PEM",
                "-out", publicKey.absolutePath,
            ),
        )
        val pubOutput = pubProcess.output
        require(pubProcess.exitCode == 0) {
            "Failed to extract public key. Output: $pubOutput"
        }

        return Pair(privateKey, publicKey)
    }

    fun signChecksum(checksumFile: File, privateKey: File, signatureFile: File) {
        val signProcess = run(
            "openssl",
            listOf(
                "dgst", "-sha256",
                "-sign", privateKey.absolutePath,
                "-out", signatureFile.absolutePath,
                checksumFile.absolutePath,
            ),
        )
        val signOutput = signProcess.output
        require(signProcess.exitCode == 0) {
            "Failed to sign checksum sidecar. Output: $signOutput"
        }
    }

    fun runSignatureVerifierExpectFail(
        archiveFile: File,
        publicKey: File,
        expected: String,
    ) {
        val process = run(
            "bash",
            listOf(
                scripts.signatureVerifier.absolutePath,
                archiveFile.absolutePath, publicKey.absolutePath,
            ),
        )
        val output = process.output
        val exitCode = process.exitCode
        require(exitCode != 0) {
            "Expected signature verifier to fail for ${archiveFile.name}, but it passed. Output: $output"
        }
        require(output.contains(expected, ignoreCase = true)) {
            "Expected signature verifier failure to contain '$expected', but output was: $output"
        }
    }
}
