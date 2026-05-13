package dev.tramai.core.memory

import java.util.UUID

/**
 * [ConversationIdProvider] that generates a new random UUID for every [resolve] call.
 *
 * This is suitable as a single-turn fallback when no caller-provided conversation ID is available.
 */
class UuidConversationIdProvider : ConversationIdProvider {
    override fun resolve(): String = UUID.randomUUID().toString()
}
