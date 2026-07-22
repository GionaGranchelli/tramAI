# Run Log

## Local Model Invocation Proof

```bash
export TRAMAI_ENABLE_LOCAL_MODEL_TEST=true
export TRAMAI_LOCAL_BASE_URL=http://localhost:11434/v1
export TRAMAI_LOCAL_API_KEY=local-dev
export TRAMAI_LOCAL_MODEL=qwen2.5:7b

./gradlew verifySovereignLabLocalModel
```

**Output:**

```
Paste terminal output here.
```

## CI Smoke Test

```bash
./gradlew verifySovereignLabRuntimeSmoke
```

**Output:**

```
Paste terminal output here.
```

## Lab Boot

```bash
docker compose -f examples/sovereign-lab/docker-compose.yml up -d
mkdir -p .local
openssl rand -base64 32 > .local/sovereign.key

./gradlew :examples:spring-sovereign-starter:bootRun \
  --args='--spring.profiles.active=sovereign-lab'
```

**Output (relevant log lines):**

```
Paste log output showing provider auto-configuration and sovereign-lab profile activation.
```
