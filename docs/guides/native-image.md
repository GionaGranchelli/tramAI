# Native Image

Tramai still uses JDK dynamic proxies for `@AiService` interfaces.

That means GraalVM native-image builds need proxy metadata for the service interfaces you want to instantiate at runtime.

## What Tramai Provides

Tramai ships a small metadata generator:

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

Register every `@AiService` interface that Tramai will instantiate in the native binary.

That usually means:

- application service interfaces used through `Tramai.create(...)`
- Spring-discovered `@AiService` interfaces if you use `tramai-spring`

## Current Boundary

The current native-image story is intentionally explicit:

- Tramai keeps runtime proxying in v1
- applications generate proxy metadata for their own service interfaces
- Tramai does not yet ship KSP-generated implementations or automatic full native-image AOT integration

This gives you a documented path without pretending the native-image problem is solved by hidden magic.
