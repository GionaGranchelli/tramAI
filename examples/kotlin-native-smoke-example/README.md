# Kotlin Native Smoke Example

This example exists only to prove TramAI's documented native-image path against a real GraalVM compilation.

It keeps the scope intentionally narrow:

- one blocking `@AiService`
- one in-process stub `ModelProvider`
- one generated `proxy-config.json`
- one GraalVM native executable

## Commands

Generate proxy metadata:

```bash
./gradlew -p examples/kotlin-native-smoke-example generateNativeImageProxyConfig
```

Compile the native binary:

```bash
JAVA_HOME=/path/to/graalvm ./gradlew -p examples/kotlin-native-smoke-example nativeSmokeCompile
```

Run the compiled native binary:

```bash
JAVA_HOME=/path/to/graalvm ./gradlew -p examples/kotlin-native-smoke-example nativeSmokeRun
```
