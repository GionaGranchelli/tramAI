# No Cloud Provider Proof

## Provider Configuration

The `application-sovereign-lab.yml` profile configures only local providers:

```yaml
tramai:
  sovereign:
    allowed-providers:
      - deterministic-local-provider
      - local-lab-provider
  providers:
    local-lab-provider:
      type: openai
      base-url: ${TRAMAI_LOCAL_BASE_URL:http://localhost:11434/v1}
```

**Verify:** `base-url` points to `localhost` — not a cloud endpoint.

## CI Smoke Verification

```bash
./gradlew verifySovereignLabRuntimeSmoke
```

**Output:**

```
Paste output here (should show BUILD SUCCESSFUL).
```

## Expected Log Messages

During boot, look for:
- `sovereign-lab` profile activated
- Provider `local-lab-provider` configured with base URL `http://localhost:...`
- No cloud provider endpoints in allowed-providers
