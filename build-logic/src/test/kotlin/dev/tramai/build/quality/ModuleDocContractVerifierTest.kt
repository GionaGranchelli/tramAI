package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * RED discriminator tests for ModuleDocContractVerifier.
 * Each fixture deliberately violates ONE contract rule and must be caught.
 */
class ModuleDocContractVerifierTest {

    private val HEADINGS = """
### Responsibility
### Public entry points
### Internal extension points
### Significant dependencies
### Lifecycle ownership
### Thread-safety and concurrency
### Failure semantics
### Contract tests / TCKs
### Do not
### Related architecture
""".trimIndent()

    private val MANIFEST = """
schemaVersion: "2"
dependencyPolicies:
  framework:
    allowedLayers: [framework-integrations]
  core:
    allowedLayers: [core-contracts]
modules:
  - path: ":tramai-core"
    layer: core-contracts
    maturity: stable
    visibility: public
    owner: core
    dependencyPolicy: core
    releaseInclusion: included
    rationale: "core"
    publishability: published
    apiStability: stable
  - path: ":tramai-server"
    layer: operations-observability
    maturity: internal
    visibility: internal
    owner: runtime
    dependencyPolicy: framework
    releaseInclusion: internal_only
    rationale: "server"
    publishability: internal
    apiStability: internal
""".trimIndent()

    private val CARD_TEMPLATE = """
# Module: `%s`

> **One-liner:** test card.
> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

$HEADINGS
""".trimIndent()

    private fun fixture(modules: List<String> = listOf("tramai-core", "tramai-server"), cards: Map<String, String> = emptyMap()): File {
        val dir = File.createTempFile("mdoc", "").parentFile.resolve("mdoc-${System.nanoTime()}")
        dir.mkdirs()
        File(dir, "config/quality").mkdirs()
        File(dir, "config/quality/module-catalog.yml").writeText(MANIFEST)
        File(dir, "docs/modules").mkdirs()
        File(dir, "docs/reference").mkdirs()
        File(dir, "docs/reference/module-matrix.md").writeText("# Module Matrix\n")
        File(dir, "docs/architecture").mkdirs()
        File(dir, "docs/architecture/modules.md").writeText("# Modules\n")
        File(dir, "ARCHITECTURE.md").writeText("# Architecture\n")
        for (m in modules) {
            File(dir, "docs/modules/$m.md").writeText(String.format(CARD_TEMPLATE, m))
        }
        for ((name, content) in cards) {
            File(dir, "docs/modules/$name.md").writeText(content)
        }
        File(dir, "docs/modules/README.md").writeText("""
| Metric | Count |
|--------|-------|
| Manifest modules | 2 |
| Module cards | ${modules.size} |
| Conforming cards | ${modules.size} |
| Existing non-conforming | 0 |
| Missing cards | 0 |
| Orphans | 0 |
""".trimIndent())
        return dir
    }

    private fun verify(dir: File) = ModuleDocContractVerifier.verify(dir)

    @Test
    fun `clean fixture passes`() {
        val dir = fixture()
        assertTrue(verify(dir).isEmpty(), "clean fixture must pass: ${verify(dir)}")
    }

    @Test
    fun `missing card is caught`() {
        val dir = fixture(modules = listOf("tramai-core"))
        val diags = verify(dir)
        assertTrue(diags.any { it.code == DiagnosticCode.MODULE_CARD_MISSING }, "missing card must be caught: $diags")
    }

    @Test
    fun `orphan card is caught`() {
        val dir = fixture()
        File(dir, "docs/modules/tramai-orphan.md").writeText(String.format(CARD_TEMPLATE, "tramai-orphan"))
        val diags = verify(dir)
        assertTrue(diags.any { it.code == DiagnosticCode.MODULE_CARD_ORPHAN }, "orphan card must be caught: $diags")
    }

    @Test
    fun `missing heading is caught`() {
        val dir = fixture(cards = mapOf("tramai-core" to String.format(CARD_TEMPLATE, "tramai-core").replace("### Do not", "### Removed")))
        val diags = verify(dir)
        assertTrue(diags.any { it.code == DiagnosticCode.MODULE_CARD_HEADING_MISSING }, "missing heading must be caught: $diags")
    }

    @Test
    fun `broken markdown link is caught`() {
        val broken = String.format(CARD_TEMPLATE, "tramai-core") + "\nSee [missing](../architecture/nope.md).\n"
        val dir = fixture(cards = mapOf("tramai-core" to broken))
        val diags = verify(dir)
        assertTrue(diags.any { it.code == DiagnosticCode.MODULE_CARD_LINK_BROKEN }, "broken link must be caught: $diags")
    }

    @Test
    fun `broken inline repo path is caught`() {
        val broken = String.format(CARD_TEMPLATE, "tramai-core") + "\nSee `docs/architecture/nope.md`.\n"
        val dir = fixture(cards = mapOf("tramai-core" to broken))
        val diags = verify(dir)
        assertTrue(diags.any { it.code == DiagnosticCode.MODULE_CARD_INLINE_PATH_BROKEN }, "broken inline path must be caught: $diags")
    }

    @Test
    fun `legacy classification metadata is caught`() {
        val legacy = String.format(CARD_TEMPLATE, "tramai-core").replace(
            "> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)",
            "> **Module type:** `core`\n> **Status:** Stable"
        )
        val dir = fixture(cards = mapOf("tramai-core" to legacy))
        val diags = verify(dir)
        assertTrue(diags.any { it.code == DiagnosticCode.MODULE_CARD_LEGACY_CLASSIFICATION }, "legacy classification must be caught: $diags")
    }

    @Test
    fun `versionless dependency without BOM is caught`() {
        val bad = String.format(CARD_TEMPLATE, "tramai-core") + """
```kotlin
dependencies {
    implementation("dev.tramai:tramai-core")
}
```
"""
        val dir = fixture(cards = mapOf("tramai-core" to bad))
        val diags = verify(dir)
        assertTrue(diags.any { it.code == DiagnosticCode.MODULE_CARD_VERSIONLESS_DEPENDENCY }, "versionless dep must be caught: $diags")
    }

    @Test
    fun `self-contained BOM snippet passes`() {
        val good = String.format(CARD_TEMPLATE, "tramai-core") + """
```kotlin
val tramaiVersion: String by project

dependencies {
    implementation(platform("dev.tramai:tramai-bom:${'$'}tramaiVersion"))
    implementation("dev.tramai:tramai-core")
}
```
"""
        val dir = fixture(cards = mapOf("tramai-core" to good))
        val diags = verify(dir)
        assertTrue(diags.none { it.code == DiagnosticCode.MODULE_CARD_VERSIONLESS_DEPENDENCY }, "self-contained BOM snippet must pass: $diags")
    }

    @Test
    fun `internal module advertising Maven coordinate is caught`() {
        val bad = String.format(CARD_TEMPLATE, "tramai-server") + """
```kotlin
dependencies {
    implementation("dev.tramai:tramai-server")
}
```
"""
        val dir = fixture(cards = mapOf("tramai-server" to bad))
        val diags = verify(dir)
        assertTrue(diags.any { it.code == DiagnosticCode.MODULE_CARD_INTERNAL_MAVEN_ADVERTISEMENT }, "internal Maven ad must be caught: $diags")
    }

    @Test
    fun `internal module with project dependency passes`() {
        val good = String.format(CARD_TEMPLATE, "tramai-server") + """
```kotlin
dependencies {
    implementation(project(":tramai-server"))
}
```
"""
        val dir = fixture(cards = mapOf("tramai-server" to good))
        val diags = verify(dir)
        assertTrue(diags.none { it.code == DiagnosticCode.MODULE_CARD_INTERNAL_MAVEN_ADVERTISEMENT }, "project() composition must pass: $diags")
    }

    @Test
    fun `internal module mentioning a published dependency passes`() {
        // tramai-server is internal; tramai-orchestration is published.
        // Mentioning the published coordinate is legitimate — the rule only
        // rejects advertising the module's OWN artifact.
        val good = String.format(CARD_TEMPLATE, "tramai-server") + """
```kotlin
dependencies {
    implementation(project(":tramai-server"))
    // depends on the published orchestration artifact:
    // dev.tramai:tramai-orchestration
}
```
"""
        val dir = fixture(cards = mapOf("tramai-server" to good))
        val diags = verify(dir)
        assertTrue(diags.none { it.code == DiagnosticCode.MODULE_CARD_INTERNAL_MAVEN_ADVERTISEMENT }, "mentioning a published dep must pass: $diags")
    }

    @Test
    fun `prose with Status does not fail legacy classification`() {
        val prose = String.format(CARD_TEMPLATE, "tramai-core") + """
| Item | Status |
|------|--------|
| Build | Passing |

Runtime status: active.
"""
        val dir = fixture(cards = mapOf("tramai-core" to prose))
        val diags = verify(dir)
        assertTrue(diags.none { it.code == DiagnosticCode.MODULE_CARD_LEGACY_CLASSIFICATION }, "prose Status: must not be rejected: $diags")
    }

    @Test
    fun `prose with Role does not fail legacy classification`() {
        val prose = String.format(CARD_TEMPLATE, "tramai-core") + """
| Role | Responsibility |
|------|----------------|
| Owner | core team |

The Role of the runtime is execution.
"""
        val dir = fixture(cards = mapOf("tramai-core" to prose))
        val diags = verify(dir)
        assertTrue(diags.none { it.code == DiagnosticCode.MODULE_CARD_LEGACY_CLASSIFICATION }, "prose Role: must not be rejected: $diags")
    }

    @Test
    fun `published module with explicit version passes without BOM`() {
        val good = String.format(CARD_TEMPLATE, "tramai-core") + """
```kotlin
val tramaiVersion: String by project

dependencies {
    implementation("dev.tramai:tramai-core:${'$'}tramaiVersion")
}
```
"""
        val dir = fixture(cards = mapOf("tramai-core" to good))
        val diags = verify(dir)
        assertTrue(diags.none { it.code == DiagnosticCode.MODULE_CARD_VERSIONLESS_DEPENDENCY }, "explicit version without BOM must pass: $diags")
    }

    @Test
    fun `README coverage mismatch is caught`() {
        val dir = fixture()
        File(dir, "docs/modules/README.md").writeText("| Manifest modules | 99 |\n")
        val diags = verify(dir)
        assertTrue(diags.any { it.code == DiagnosticCode.MODULE_CARD_COVERAGE_MISMATCH }, "coverage mismatch must be caught: $diags")
    }
}
