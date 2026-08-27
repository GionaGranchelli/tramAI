# Module: `tramai-bom`

> **One-liner:** Bill of materials — a single-import Maven BOM (Gradle `java-platform`) that aligns versions of all Tramai publishable modules so consumers never worry about cross-module version mismatches.

---

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Bill of materials — a `java-platform`/Maven BOM aligning versions of all Tramai publishable modules so consumers never worry about cross-module version mismatches.

### Public entry points

- BOM artifact only (`dev.tramai:tramai-bom`) — no code API

### Internal extension points

- None — publication-only module

### Significant dependencies

- `api()` of every publishable project (platform constraints) — see [module-catalog.yml](../../config/quality/module-catalog.yml) and [module-matrix.md](../../docs/reference/module-matrix.md)

### Lifecycle ownership

- No runtime resource lifecycle; publication artifact only

### Thread-safety and concurrency

- N/A — no runtime code

### Failure semantics

- N/A — no runtime code; BOM drift is caught by the module-manifest verifier

### Contract tests / TCKs

- BOM consistency verified by build-time platform checks (dependencyManagement completeness)

### Do not

- Do not add implementation code here — constraints only

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — core-contracts layer
- [module-matrix.md](../../docs/reference/module-matrix.md)
## L1: Quick Start (30-second read)

### What

`tramai-bom` is a **Gradle `java-platform`** (published as a Maven BOM with `packaging=pom`) that declares version constraints for every Tramai module that ships a JAR. It contains no source code — only a constraints block that pins each module to the current project version.

When a consumer imports the BOM, all Tramai dependencies resolve to the same version without the consumer needing to specify individual versions.

### Membership is manifest-derived

BOM membership is **not hand-maintained**. It is computed from the authoritative manifest by `ModuleManifest.bomModulePaths(...)` in `build-logic/src/main/kotlin/dev/tramai/build/quality/ModuleManifest.kt`:

```kotlin
fun bomModulePaths(entries: Collection<ModuleCatalog.ModuleEntry>): List<String> =
    entries.filter {
        it.path != ":tramai-bom" &&
            it.publishability == ModulePublishability.PUBLISHED &&
            it.releaseInclusion == ReleaseInclusion.INCLUDED
    }.map { it.path }.sorted()
```

Every module whose manifest entry is `publishability: published` and `releaseInclusion: included` (excluding `tramai-bom` itself) is a BOM member — the same filter that drives the generated [module matrix](../../docs/reference/module-matrix.md). Adding or removing a module from the BOM is done by editing `module-catalog.yml`, never by editing this card or the constraints block by hand.

### Why

Tramai modules have deep internal dependencies. Without a BOM, a consumer who mixes versions risks `NoSuchMethodError`, binary-incompatible SPI types, or broken annotation processing at runtime. The BOM eliminates this category of error entirely.

### When to use the BOM

- **Always** — in any multi-module Tramai project that consumes two or more Tramai modules.
- **Often** — even in single-module usage, because adopting another module later requires no version changes.
- **Never** — if you only use exactly one Tramai module and are comfortable managing its version directly (not recommended for future-proofing).

---

## L2: Usage — Maven BOM Import

### Gradle (Kotlin DSL)

```kotlin
// tramaiVersion is the canonical version property (see gradle.properties)
val tramaiVersion: String by project

dependencies {
    // 1. Import the BOM
    implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))

    // 2. Declare Tramai modules without versions
    implementation("dev.tramai:tramai-orchestration")
    implementation("dev.tramai:tramai-openai")
    implementation("dev.tramai:tramai-testing")
}
```

Gradle's `platform()` notation activates the version constraints from the BOM. All declared modules resolve to the version declared in the BOM.

To **override** a single module version (e.g., to test a snapshot), declare an explicit version on the module coordinate — the explicit version wins over the BOM constraint:

```kotlin
implementation("dev.tramai:tramai-openai:0.5.0")  // explicit version wins
```

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.tramai</groupId>
            <artifactId>tramai-bom</artifactId>
            <version>${tramai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>dev.tramai</groupId>
        <artifactId>tramai-orchestration</artifactId>
        <!-- version inherited from BOM -->
    </dependency>
</dependencies>
```

The BOM's `packaging=pom` is verified at publication time — the CI pipeline checks that no accidental JAR is emitted for `tramai-bom`.

---

## L3: Version Alignment

### How it works

`tramai-bom` uses Gradle's **`java-platform`** plugin, which produces a Maven BOM POM with a `<dependencyManagement>` section. The constraints block is generated from the manifest-derived membership (see above), not copied by hand.

When published, Gradle resolves each `project()` reference to the **current project version** (`tramaiVersion`, see `gradle.properties`). The resulting POM contains a `<dependencyManagement>` block that lists every module with its resolved version.

### What it pins

The BOM pins every manifest entry with `publishability: published` and `releaseInclusion: included`, except `tramai-bom` itself. For the authoritative current list, see the generated [module matrix](../../docs/reference/module-matrix.md) — do not maintain a module list in this card.

External (third-party) dependencies like OkHttp, Jackson, or OpenTelemetry SDK are **not** pinned by `tramai-bom` — those are managed by each module's own dependency declarations and the consumer's own platform constraints.

### Verification

Build-time checks enforce:

- The BOM POM has `packaging=pom`
- The `<dependencyManagement>` section exists and contains exactly one `<dependency>` entry for each manifest-derived BOM member
- The set of managed artifact IDs matches the publishable, release-included module set (minus `tramai-bom`)

This prevents accidental omissions or extra entries during development. The BOM is also excluded from JAR/sources/javadoc publication — no `-sources.jar` or `-javadoc.jar` is emitted for it.
