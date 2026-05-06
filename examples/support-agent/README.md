# Support Agent Example

A minimal TramAI application demonstrating annotations, tool calling, structured output, and local AI — all in one file.

## Prerequisites

```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull the model
ollama pull gemma3:4b
```

## Run

```bash
cd examples/support-agent
./gradlew run
```

## What It Demonstrates

| Feature | How |
|---------|-----|
| `@System` + `@User` annotations | System role + user message with `{param}` interpolation |
| `@Operation(tools = [...])` | Tool registration and model-driven tool selection |
| Structured output | `Response` data class with `@AiDescription` fields |
| `TramaiTool<I, O>` | Tool implementation via the user-facing contract |
| `Tramai.builder()` | Standalone framework-free setup |
| `tramai-ollama` | Local AI — no API key needed |

## Expected Output

```
Answer: Your order ORD-42 was shipped on April 15, 2026.
Action: informed_customer
```
