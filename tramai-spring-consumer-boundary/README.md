# tramai-spring-consumer-boundary

Test-only module. **Epic 6.3 dependency-boundary oracle.**

It proves what a minimal Spring consumer gets on its classpath by declaring
exactly one dependency — `testImplementation(project(":tramai-spring"))` — and
asserting what classes it can load.

## Current reality (post-#261)

`tramai-spring` is a thin facade over `tramai-spring-core` with **no** provider
or secret adapters as main dependencies. A consumer's runtime classpath carries
**no provider adapters and no AWS SDK** unless the consumer declares the adapter
modules explicitly. Epic 6.3's acceptance criterion ("consumers only receive
dependencies for selected adapters") is met.

## The tests

| Test | Post-#261 |
|---|---|
| runtime classpath no longer carries provider adapters or the AWS SDK (`OpenAiProvider`, `AnthropicProvider`, `OllamaProvider`, `SecretsManagerClient`) | PASS — flipped to assert `ClassNotFoundException` (pre-#261 it asserted the leak) |
| generic spring integration classes present (`TramaiAutoConfiguration`, `standalone.Tramai`) | PASS |
| module's own `build.gradle.kts` declares no provider/AWS deps | PASS |

The flip was the verification: at the commit that stripped the adapter
dependencies from core, this oracle failed on its own at the first
class-loading assertion — the leak was gone — and only those assertions were
inverted.

## Run

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :tramai-spring-consumer-boundary:test
```

Diagnose the graph with:

```bash
./gradlew :tramai-spring-consumer-boundary:dependencies --configuration testRuntimeClasspath
```
