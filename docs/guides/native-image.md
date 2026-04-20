# Native Image

TramAI still uses JDK dynamic proxies for `@AiService` interfaces.

That means GraalVM native-image builds need proxy metadata for the service interfaces you want to instantiate at runtime.

## What TramAI Provides

TramAI ships a small metadata generator:

- `NativeImageProxyConfig.json(...)`
- `NativeImageProxyConfig.write(...)`

These generate a GraalVM `proxy-config.json` for your `@AiService` interfaces.

## Example

```kotlin
import dev.tramai.core.nativeimage.NativeImageProxyConfig
import java.nio.file.Path

fun main() {
    NativeImageProxyConfig.write(
        outputPath = Path.of("src/main/resources/META-INF/native-image/com.example/app/proxy-config.json"),
        InvoiceAnalyzer::class,
        IncidentSummarizer::class,
    )
}
```

The generated JSON contains one proxy entry per interface:

```json
[
  { "interfaces": [ "com.example.InvoiceAnalyzer" ] },
  { "interfaces": [ "com.example.IncidentSummarizer" ] }
]
```

## What To Register

Register every `@AiService` interface that TramAI will instantiate in the native binary.

That usually means:

- application service interfaces used through `Tramai.create(...)`
- Spring-discovered `@AiService` interfaces if you use `tramai-spring`

## Minimal Workflow

The current native-image path is explicit:

1. identify every `@AiService` interface that will be instantiated at runtime
2. generate `proxy-config.json`
3. place it under `META-INF/native-image/<group>/<artifact>/proxy-config.json`
4. build your native image with that metadata on the classpath

For a standalone app, a minimal metadata generator can live in `src/main/kotlin` or in a small build helper source set:

```kotlin
import dev.tramai.core.nativeimage.NativeImageProxyConfig
import java.nio.file.Path

fun main() {
    NativeImageProxyConfig.write(
        outputPath = Path.of(
            "src/main/resources/META-INF/native-image/com.example/app/proxy-config.json",
        ),
        InvoiceAnalyzer::class,
        IncidentSummarizer::class,
    )
}
```

Run that generator before producing the native binary.

## What This Solves

This metadata covers TramAI's use of JDK dynamic proxies for `@AiService` interfaces.

It does not attempt to solve every native-image concern in an application. Your app may still need to handle:

- HTTP client native-image needs
- framework-specific reflection hints
- provider-library native-image constraints

## Spring Note

If you use `tramai-spring`, register the same `@AiService` interfaces that Spring will ask TramAI to instantiate.

The important rule is the same in standalone and Spring usage:

- if TramAI will create a dynamic proxy for the interface at runtime, include it in `proxy-config.json`

## Smoke Path

A reasonable smoke path for native-image support is:

1. generate proxy metadata
2. run the normal JVM tests
3. build one minimal native sample or smoke binary that instantiates at least one `@AiService`

The repository currently provides the metadata generator and tests for it. Applications are still responsible for wiring the final native build for their runtime and deployment environment.

## Current Boundary

The current native-image story is intentionally explicit:

- TramAI keeps runtime proxying in v1
- applications generate proxy metadata for their own service interfaces
- TramAI does not yet ship KSP-generated implementations or automatic full native-image AOT integration

This gives you a documented path without pretending the native-image problem is solved by hidden magic.
