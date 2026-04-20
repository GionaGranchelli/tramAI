package dev.tramai.spring

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves external secret references for provider credentials.
 */
fun interface SecretValueResolver {
    fun resolve(secretRef: String): String?
}

internal class CompositeSecretValueResolver(
    private val resolvers: List<SecretValueResolver>,
) : SecretValueResolver {
    override fun resolve(secretRef: String): String? = resolvers.firstNotNullOfOrNull { resolver ->
        resolver.resolve(secretRef)?.takeIf { it.isNotBlank() }
    }
}

internal object EnvironmentSecretValueResolver : SecretValueResolver {
    override fun resolve(secretRef: String): String? {
        val name = secretRef.removePrefix("env:").takeIf { secretRef.startsWith("env:") && it.isNotBlank() } ?: return null
        return System.getenv(name)
    }
}

internal object FileSecretValueResolver : SecretValueResolver {
    override fun resolve(secretRef: String): String? {
        val pathText = secretRef.removePrefix("file:").takeIf { secretRef.startsWith("file:") && it.isNotBlank() } ?: return null
        val path = Path.of(pathText)
        return if (Files.exists(path)) Files.readString(path).trim() else null
    }
}
