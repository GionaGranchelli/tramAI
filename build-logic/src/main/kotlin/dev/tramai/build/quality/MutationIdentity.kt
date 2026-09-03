package dev.tramai.build.quality

import java.security.MessageDigest

/**
 * Stable mutation identity (schema v2).
 *
 * Identity deliberately excludes everything that can move without the
 * mutation itself changing: absolute paths, build/report directories and
 * line numbers. `methodDescription` (JVM descriptor) and `index` (PIT bytecode
 * mutation point) are included so overloaded methods and distinct bytecode
 * mutation points that share textual fields cannot collapse into one identity.
 */
data class MutationIdentity(
    val module: String,
    val className: String,
    val method: String,
    val methodDescription: String,
    val mutator: String,
    val description: String,
    val block: Int = 0,
    val index: Int = 0,
) {
    fun stableKey(): String {
        val canonical =
            listOf(
                module,
                className,
                method,
                methodDescription,
                mutator,
                description,
                block.toString(),
                index.toString(),
            ).joinToString("\u001f")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
