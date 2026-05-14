package dev.tramai.core.memory

/**
 * Counts tokens in text content for token-aware memory eviction.
 *
 * Implementations must return a non-negative integer. The count is used by
 * [dev.tramai.memory.TokenAwareChatMemory] to enforce a token budget per
 * conversation. A deterministic implementation is recommended for consistent
 * eviction behavior.
 */
fun interface Tokenizer {
    fun countTokens(content: String): Int
}
