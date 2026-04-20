package dev.tramai.core.secret

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves external secret references for provider credentials.
 */
fun interface SecretValueResolver {
    /**
     * Resolves the given [secretRef] (e.g., "env:API_KEY") to its raw value.
     * Returns null if this resolver does not handle the given reference scheme or if the secret is missing.
     */
    fun resolve(secretRef: String): String?
}

/**
 * Resolves secrets from environment variables using the "env:" prefix.
 */
object EnvironmentSecretValueResolver : SecretValueResolver {
    override fun resolve(secretRef: String): String? {
        val name = secretRef.removePrefix("env:").takeIf { secretRef.startsWith("env:") && it.isNotBlank() } ?: return null
        return System.getenv(name)
    }
}

/**
 * Resolves secrets from local files using the "file:" prefix.
 */
object FileSecretValueResolver : SecretValueResolver {
    override fun resolve(secretRef: String): String? {
        val pathText = secretRef.removePrefix("file:").takeIf { secretRef.startsWith("file:") && it.isNotBlank() } ?: return null
        val path = Path.of(pathText)
        return if (Files.exists(path)) Files.readString(path).trim() else null
    }
}

/**
 * Composite resolver that tries multiple resolvers in order.
 */
class CompositeSecretValueResolver(
    private val resolvers: List<SecretValueResolver>,
) : SecretValueResolver {
    override fun resolve(secretRef: String): String? = resolvers.firstNotNullOfOrNull { resolver ->
        resolver.resolve(secretRef)?.takeIf { it.isNotBlank() }
    }
}
