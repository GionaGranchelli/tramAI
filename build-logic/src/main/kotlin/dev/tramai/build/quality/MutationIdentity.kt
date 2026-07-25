package dev.tramai.build.quality

import java.security.MessageDigest

data class MutationIdentity(
    val module: String,
    val className: String,
    val method: String,
    val mutator: String,
    val description: String,
    val block: Int = 0
) {
    fun stableKey(): String {
        val canonical = listOf(module, className, method, mutator, description, block.toString()).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
