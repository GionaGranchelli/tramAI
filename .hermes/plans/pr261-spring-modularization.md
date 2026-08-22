# PR #261 — Epic 6.3: Modularize Spring integration

Branch: `refactor/0.6.0-spring-modularization`
Classification: `runtime-behaviour`
Base: master (`9debb0f2` = merged #260)

## Target module layout

```
tramai-spring                    facade: api(project(":tramai-spring-core")) ONLY
tramai-spring-core               generic runtime assembly (composition, no provider/secrets impls)
tramai-spring-provider-openai    -> tramai-openai (+ openai-compatible + codex-auth)
tramai-spring-provider-anthropic -> tramai-anthropic
tramai-spring-provider-ollama    -> tramai-ollama
tramai-spring-secrets-file       file secret resolver wiring
tramai-spring-secrets-vault      -> VaultSecretValueResolver + props + validation
tramai-spring-secrets-aws        -> AWS SDK (auth/regions/secretsmanager)
```

## Two core-owned markers (preserve #260 semantics)

1. `SpringConfiguredModelProvider(providerId: String, provider: ModelProvider)`
   - Provider modules contribute these as beans (their auto-config constructs the
     concrete provider, resolves secrets via injected SecretValueResolver).
   - Core reads `ObjectProvider<SpringConfiguredModelProvider>` = "property-generated
     providers"; `ObjectProvider<ModelProvider>` = user beans.
   - Precedence logic preserved EXACTLY:
     - unique beans override property-backed (providersById = props.toMap() + beans.associate)
     - property-vs-property duplicates pass through (canonical builder rejects)
     - bean duplicates pass through (canonical builder rejects)
   - Core still knows NOTHING about OpenAI/Anthropic/Ollama.

2. `SpringBuiltInSecretValueResolver : SecretValueResolver` (marker interface in core)
   - Vault/AWS modules contribute resolver beans implementing this marker.
   - Core distinguishes: user resolvers = SecretValueResolver beans minus
     SpringBuiltInSecretValueResolver bean names; built-ins = marker beans.
   - Chain built in the exact current order:
     - bootstrap = user + [Environment, File]  (used to resolve vault token / aws creds)
     - full     = user + [vault, aws] + [Environment, File]
   - Core still knows nothing about Vault/AWS — only the marker.

## Property compatibility

- `tramai.providers.openai.*`, `tramai.providers.anthropic.*`,
  `tramai.providers.ollama.base-url`, `tramai.secrets.vault.*`,
  `tramai.secrets.aws-secrets-manager.*`, `tramai.secrets.file.*` — NO namespace changes.
- Kotlin types: provider/secret property classes move to their modules where they are
  consumed; generic properties (defaultProvider, models, fallbacks, resilience, cost,
  cache, security) stay in core TramaiProperties. External binding contract unchanged.

## Auto-config ordering

Each module gets its own `META-INF/spring/...AutoConfiguration.imports`:
- secret modules: `@AutoConfigureBefore(TramaiAutoConfiguration)`
- provider modules: `@AutoConfigureBefore(TramaiAutoConfiguration)` (need secret chain bean)
- core: `TramaiAutoConfiguration` + `SecurityClassificationAutoConfiguration`
  (security stays `after = [TramaiAutoConfiguration]`)
- #260 test "provider beans visible at assembly time" must stay green → provider beans
  must be registered before the tramai() bean builds (auto-configure-before).

## Commit order (each individually green)

1. `tramai-spring-core` created: copy tramai-spring src/main verbatim (same package
   `dev.tramai.spring`); tramai-spring becomes facade `api(core)` + keeps its tests
   with testImplementation(core implied by api) + test deps for later modules; settings,
   module-catalog.yml, api dumps regenerated (tramai-spring.api -> empty; core.api -> new).
   Characterization tests still pass unchanged (all deps still in core).
2. Marker types + OpenAI/OpenAI-compatible extraction -> tramai-spring-provider-openai.
3. Anthropic extraction -> tramai-spring-provider-anthropic.
4. Ollama extraction -> tramai-spring-provider-ollama.
5. File secret extraction -> tramai-spring-secrets-file.
6. Vault extraction -> tramai-spring-secrets-vault.
7. AWS extraction -> tramai-spring-secrets-aws.
8. Strip provider/AWS deps from core + facade; core zero provider imports (grep-proof).
9. Flip consumer-boundary oracle (4 classes -> ClassNotFoundException) + add
   selective-classpath tests (core-only, core+openai, core+ollama, core+aws, core+anthropic).
10. Docs: ROADMAP-0.6.0.md (Epic 6.3 done), CHANGELOG, spring docs migration section,
    module-catalog.yml entries, BOM additions.

## Consumers to update (commit 8-9)

- `tramai-spring-boot-starter-sovereign` testImplementation(project(":tramai-spring")) —
  needs nothing extra IF it doesn't use provider classes (check imports).
- `examples/spring-sovereign-starter`, `examples/approval-resume` —
  implementation(project(":tramai-spring")) + tramai-openai already present → add
  tramai-spring-provider-openai if they configure openai properties.
- `tramai-bom` — add new modules to BOM.
- Check all `dev.tramai.spring` importers for what they actually use.

## Gate

- Per-extraction: affected module tests + `:tramai-spring:test` (characterization oracle).
- Final: all module tests + apiChecks + verifier suite + `verifyPr -PchangeClass=runtime-behaviour`.
- CI: build, validate, zero-egress, baseline, GitGuardian.
- NO auto-merge. Open PR, present, merge only on explicit approval.
