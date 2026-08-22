# tramai-spring-consumer-selective

Test-only module. **Epic 6.3 selective-adapter oracle** (companion to
`tramai-spring-consumer-boundary`).

It proves that a consumer which declares `tramai-spring` **plus** exactly one
adapter — `testImplementation(project(":tramai-spring-provider-openai"))` —
gets that adapter and nothing else.

## The tests

| Test | Post-#261 |
|---|---|
| explicitly selected openai adapter is loadable (`OpenAiProvider`, and `TramaiAutoConfiguration` still present) | PASS |
| unselected adapters and the AWS SDK stay off the classpath (`AnthropicProvider`, `OllamaProvider`, `SecretsManagerClient`) | PASS |

This proves the acceptance criterion both ways: selected adapters are present,
and the mechanism does not accidentally pull in siblings.

## Run

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :tramai-spring-consumer-selective:test
```

Diagnose the graph with:

```bash
./gradlew :tramai-spring-consumer-selective:dependencies --configuration testRuntimeClasspath
```
