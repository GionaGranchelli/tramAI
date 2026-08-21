# Provider Compatibility Contract (Epic 6.1 TCK)

Every published provider must pass the deterministic, offline compatibility
contract in `tramai-testing/src/testFixtures/.../provider/ProviderTck.kt`.
The TCK exercises the **actual provider adapter** through its public boundary
(`ModelProvider`/`StreamCapable`) against canned, protocol-shaped responses —
no network, no credentials.

## How a provider is enrolled

1. A thin runner class extends `ProviderTck` and pins the contract matrix in
   `ProviderTckHarness`:

   - `expectedProviderId` — the stable provider id.
   - `expectedCapabilities` — the exact capability set. The provider cannot
     skip a contract by changing its own `supportsCapability(...)` result;
     the runner's pin is authoritative.
   - fixture bodies per protocol (happy path, usage, malformed, empty, tools,
     vision, structured output, streaming).
   - `diagnosticObserver` — a recording observer proving the bounded-preview
     contract.

2. `ProviderTckEnrollmentArchitectureTest` fails the build when a roadmap
   provider loses its runner or a new `ModelProvider` implementation appears
   without one. "Future providers must pass the TCK" is architecture, not
   documentation.

## Contract coverage

| Contract | Assertion |
|---|---|
| Provider identity | `providerId()` equals the pinned stable ID |
| Capabilities | Exact pinned set, both directions; `STREAMING` ⇔ implements `StreamCapable` |
| Cancellation | `CancellationException` escapes as cancellation, never wrapped in `ProviderException` |
| Transport failure | Safe `ProviderFailureCode`, retryability, sanitized public message |
| Safe-error boundary | Raw transport detail, provider body, credentials, parser detail never in public exceptions |
| Empty response | Deterministic safe failure |
| Malformed response | Deterministic safe failure, body closed |
| Usage | Input/output (and reasoning where supported) tokens normalized |
| Resource closure | Response body closed on success, rejection, parse failure, early stream stop, cancellation |
| HTTP timeout | `ModelRequest.timeoutMillis` becomes the request timeout |
| Retryable statuses | 408, 425, 429, 500, 502, 503, 504 map retryable |
| Non-retryable | 400/401 stay non-retryable |
| Retry-After | Numeric `Retry-After: 2` → `retryAfterMillis = 2_000` |
| Diagnostic preview | Bounded to `PROVIDER_ERROR_BODY_LIMIT_BYTES` (8 KiB) |
| Tool calling | Tool definitions serialized outbound; tool calls parsed; tool-only responses are not empty-text failures |
| Vision | Image bytes base64-encoded + MIME type preserved outbound |
| Structured output | Pinned declaration proven against a fixture |
| Streaming | Token order; exactly one terminal `Complete` with concatenated text; terminal `Error` on transport failure; deterministic malformed termination; parent cancellation stays cancellation; closure on early stop / completion / malformed termination |

## Provider matrix

| Provider | Module | Transport | Text | Tools | Vision | Structured | Streaming | TCK runner |
|---|---|---|---|---|---|---|---|---|
| OpenAI-compatible | tramai-openai | HTTP | ✓ | ✓ | ✓ | ✓ | ✓ | `OpenAiCompatibleProviderTckTest` |
| OpenAI | tramai-openai | HTTP | ✓ | ✓ | ✓ | ✓ | ✓ | `OpenAiProviderTckTest` |
| Azure OpenAI | tramai-azure-openai | HTTP | ✓ | ✓ | ✓ | ✓ | ✓ | `AzureOpenAiProviderTckTest` |
| Anthropic | tramai-anthropic | HTTP | ✓ | ✓ | ✓ | ✓ | ✓ | `AnthropicProviderTckTest` |
| Ollama | tramai-ollama | HTTP | ✓ | ✗ | ✓ | ✗ | ✓ | `OllamaProviderTckTest` |
| Gemini | tramai-gemini | HTTP | ✓ | ✓ | ✓ | ✓ | ✓ | `GeminiProviderTckTest` |
| Bedrock | tramai-bedrock | SDK | ✓ | ✓ | ✓ | ✓ | ✓ | `BedrockProviderTckTest` |
| DeepSeek | tramai-deepseek | HTTP | ✓ | ✓ | ✓ | ✓ | ✓ | `DeepSeekProviderTckTest` |

## Intentional deviations (explicit, reviewed)

- **Bedrock — transport:** HTTP wire-level assertions (timeout/status mapping/
  Retry-After/rejected-body) are not applicable to the SDK transport and are
  skipped by contract; transport-independent semantics (identity, capabilities,
  cancellation, safe errors, usage, tools, vision, structured, streaming
  lifecycle, resource closure) are exercised through the
  `BedrockRuntimeClientFactory` seam with a recording fake client. The factory
  is internal — no AWS SDK type enters Tramai's stable public API — and
  production owns and closes factory-created clients.
- **Ollama — capability set:** pins VISION + STREAMING. Ollama's stream()
  exists and is pinned by the TCK; tool calling and structured output are not
  implemented by this adapter and are pinned as absent rather than claimed.
  VISION is asserted protocol-aware: the outbound `images` array carries
  base64 image bytes WITHOUT a MIME marker (the Ollama server base64-decodes
  each entry directly and rejects data URIs), so `requireMimeTypeMarker` is
  N/A for this protocol.
- **DeepSeek — wire format:** uses the OpenAI-compatible wire format and
  delegates to `OpenAiCompatibleProvider`; it still ships its own runner so
  its identity and capability configuration cannot drift unnoticed.

A deviation must name what is intentionally different and why. There is no
generic "provider-specific" exemption.

## What the TCK does NOT do

- No real external API calls; no credentials; fully hermetic.
- No protocol-specific assertions (endpoint construction, auth headers,
  provider-specific JSON shapes, finish-reason mappings) — those stay in the
  provider's own test suite.
- No Epic 6.2 work: no shared provider transport, no SSE-parser
  consolidation, no injected-clock Retry-After redesign.
