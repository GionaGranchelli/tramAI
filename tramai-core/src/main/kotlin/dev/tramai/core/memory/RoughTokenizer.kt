package dev.tramai.core.memory

/**
 * Estimates the number of tokens in a text string using a character-count heuristic.
 *
 * Uses a simple ratio of ~1 token per 3 characters, which is a reasonable
 * approximation for English text (roughly matching GPT-2 BPE tokenizer density).
 * Always returns at least 1 token for any non-empty input.
 *
 * For production use with a specific LLM, provide a model-specific tokenizer
 * (e.g., tiktoken for OpenAI models) instead of this heuristic.
 */
fun roughTokenizer(): Tokenizer = Tokenizer { content ->
    (content.length / 3).coerceAtLeast(1)
}
