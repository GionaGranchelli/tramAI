# Module: `tramai-bom`

> **One-liner:** Bill of materials — a single-import Maven BOM (Gradle `java-platform`) that aligns versions of all Tramai publishable modules so consumers never worry about cross-module version mismatches.
> **Module type:** `platform`
> **Group:** `dev.tramai`, **Version:** `0.3.1`
> **Module file:** `build.gradle.kts` (23 lines)

---

## L1: Quick Start (30-second read)

### What

`tramai-bom` is a **Gradle `java-platform`** (published as a Maven BOM with `packaging=pom`) that declares version constraints for every Tramai module that ships a JAR. It contains no source code — only a single `dependencies.constraints` block that pins each module to the current project version.

When a consumer imports the BOM, all Tramai dependencies resolve to the same version without the consumer needing to specify individual versions.

### Why

Tramai has **11 publishable modules** (`tramai-core`, `tramai-engine`, `tramai-structured`, `tramai-anthropic`, `tramai-openai`, `tramai-ollama`, `tramai-observability`, `tramai-orchestration`, `tramai-standalone`, `tramai-spring`, `tramai-testing`). These modules have deep internal dependencies:

- `tramai-orchestration` depends on `tramai-engine` and `tramai-structured`
- `tramai-spring` depends on several modules
- `tramai-testing` depends on `tramai-core` and `tramai-engine`

Without a BOM, a consumer who mixes versions (e.g., `tramai-core:0.3.1` with `tramai-engine:0.3.1`) risks `NoSuchMethodError`, binary-incompatible SPI types, or broken annotation processing at runtime. The BOM eliminates this category of error entirely.

### When to use the BOM

- **Always** — in any multi-module Tramai project that consumes two or more Tramai modules.
- **Often** — even in single-module usage, because adopting another module later requires no version changes.
- **Never** — if you only use exactly one Tramai module and are comfortable managing its version directly (not recommended for future-proofing).

---

## L2: Usage — Maven BOM Import

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    // 1. Import the BOM
    implementation(platform("dev.tramai:tramai-bom:0.3.1"))

    // 2. Declare Tramai modules without versions
    implementation("dev.tramai:tramai-orchestration")
    implementation("dev.tramai:tramai-openai")
    implementation("dev.tramai:tramai-testing")
}
```

Gradle's `platform()` notation activates the version constraints from the BOM. All three modules resolve to `0.3.1` — the version declared in the BOM for each.

To **override** a single module version (e.g., to test a snapshot):

```kotlin
implementation("dev.tramai:tramai-openai:0.3.1-SNAPSHOT") // explicit version wins
```

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.tramai</groupId>
            <artifactId>tramai-bom</artifactId>
            <version>0.3.1</version>
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

`tramai-bom` uses Gradle's **`java-platform`** plugin, which produces a Maven BOM POM with a `<dependencyManagement>` section.

The `build.gradle.kts` is straightforward:

```kotlin
plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()  // permits the platform itself to have dependencies if needed
}

dependencies {
    constraints {
        api(project(":tramai-core"))
        api(project(":tramai-engine"))
        api(project(":tramai-structured"))
        api(project(":tramai-anthropic"))
        api(project(":tramai-openai"))
        api(project(":tramai-ollama"))
        api(project(":tramai-observability"))
        api(project(":tramai-orchestration"))
        api(project(":tramai-standalone"))
        api(project(":tramai-spring"))
        api(project(":tramai-testing"))
    }
}
```

When published, Gradle resolves each `project()` reference to the **current project version** (`tramaiVersion`, default `0.3.1`). The resulting POM contains a `<dependencyManagement>` block that lists every module with its resolved version.

### Versions it pins

The BOM pins **all JAR-publishing Tramai modules** — that is, every publishable project except `tramai-bom` itself:

- **`tramai-core`** — core: annotations, SPI, data models, exceptions
- **`tramai-engine`** — orchestration: proxy dispatch, retry, provider resolution
- **`tramai-structured`** — structured output: schema gen, extraction, deserialization, failure analysis
- **`tramai-anthropic`** — provider: Anthropic Claude model provider
- **`tramai-openai`** — provider: OpenAI / Azure OpenAI model provider
- **`tramai-ollama`** — provider: Ollama local model provider
- **`tramai-observability`** — observability: OpenTelemetry hooks (optional plugin)
- **`tramai-orchestration`** — workflow: `@AiService` / `@Operation` / `@AiTool` DSL
- **`tramai-standalone`** — runtime: minimal framework-free entry point
- **`tramai-spring`** — adapter: Spring Boot auto-configuration
- **`tramai-testing`** — testing: deterministic test utilities, mock providers

External (third-party) dependencies like OkHttp, Jackson, or OpenTelemetry SDK are **not** pinned by `tramai-bom` — those are managed by each module's own dependency declarations and the consumer's own platform constraints.

### Verification

The root `build.gradle.kts` includes a **`verifyPublicationMetadata`** task that enforces:

- The BOM POM has `packaging=pom`
- The `<dependencyManagement>` section exists and contains exactly one `<dependency>` entry for each publishable JAR module
- The set of managed artifact IDs matches `jarPublishingProjectNames` (all publishable modules minus `tramai-bom`)

This prevents accidental omissions or extra entries during development. The BOM is also excluded from JAR/sources/javadoc publication — no `-sources.jar` or `-javadoc.jar` is emitted for it.
