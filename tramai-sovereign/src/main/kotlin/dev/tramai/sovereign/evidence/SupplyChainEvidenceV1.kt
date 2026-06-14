package dev.tramai.sovereign.evidence

/**
 * Supply-chain evidence capturing the CycloneDX SBOM identity for a
 * sovereign TramAI deployment.
 *
 * This DTO links the build-time SBOM artifact into the evidence pack so
 * that auditors can trace the exact set of third-party dependencies
 * shipped with the deployment.
 *
 * @property schemaVersion Schema version (currently 1).
 * @property sbomFormat The SBOM format standard (e.g. "CycloneDX").
 * @property sbomSpecVersion The SBOM specification version (e.g. "1.6").
 * @property sbomFileName The SBOM file name (no path components).
 * @property sbomSha256 The SHA-256 digest of the SBOM file in "sha256:<hex>" format.
 * @property generatedBy The tool that produced the SBOM (e.g. "CycloneDX Gradle Plugin 3.2.4").
 */
data class SupplyChainEvidenceV1(
    val schemaVersion: Int = 1,
    val sbomFormat: String,
    val sbomSpecVersion: String,
    val sbomFileName: String,
    val sbomSha256: String,
    val generatedBy: String,
)
