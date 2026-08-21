# tramai-spring-consumer-boundary

Test-only module. **Epic 6.3 dependency-boundary oracle.**

It proves what a minimal Spring consumer gets on its classpath **today** by
declaring exactly one dependency — `testImplementation(project(":tramai-spring"))` —
and asserting what classes it can load.

## Current reality (characterized)

`tramai-spring` has `implementation` dependencies on `tramai-anthropic`,
`tramai-openai`, `tramai-ollama`, and the AWS SDK (`auth`, `regions`,
`secretsmanager`). Because `implementation` deps of a project dependency are
transitive at runtime, a consumer's **runtime classpath carries all provider
SDKs even though it never declared them**. Epic 6.3's acceptance criterion
("consumers only receive dependencies for selected adapters") is **not** met today.

## The tests

| Test | Today | After #261 |
|---|---|---|
| provider SDK classes loadable (`OpenAiProvider`, `AnthropicProvider`, `OllamaProvider`, `SecretsManagerClient`) | PASS (asserts the leak) | must be flipped to assert `ClassNotFoundException` |
| generic spring integration classes present (`TramaiAutoConfiguration`, `standalone.Tramai`) | PASS | stays PASS |
| module's own `build.gradle.kts` declares no provider/AWS deps | PASS (leak is transitive, not declared) | stays PASS |

## How #261 flips the oracle

After the adapter split, the four loadability assertions in
`runtime classpath currently carries provider SDKs` must be inverted to expect
`ClassNotFoundException` (or an equivalent classpath check). The other two tests
are unchanged. The test names and assertion messages reference #261 so the flip
is mechanical.

## Run

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :tramai-spring-consumer-boundary:test
```

Diagnose the leak graph with:

```bash
./gradlew :tramai-spring-consumer-boundary:dependencies --configuration testRuntimeClasspath
```
