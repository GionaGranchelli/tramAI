package dev.tramai.core.secret

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Resolves external secret references for provider credentials.
 */
interface SecretValueResolver {
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
        val name = secretRef.removePrefix("env:")
            .takeIf { secretRef.startsWith("env:") && it.isNotBlank() }
        return if (name == null) null else System.getenv(name)
    }
}

/**
 * Resolves secrets from local files using the "file:" prefix.
 */
class FileSecretValueResolver(
    private val allowedDirectory: Path? = null,
) : SecretValueResolver {
    override fun resolve(secretRef: String): String? {
        val pathText = secretRef.removePrefix("file:").takeIf { secretRef.startsWith("file:") && it.isNotBlank() } ?: return null
        val allowedBase = allowedDirectory?.normalizedRealPath()
            ?: throw IllegalStateException(
                "file: secret resolution is disabled; configure an allowed directory or use env: prefix",
            )
        val requestedPath = Path.of(pathText)
        val candidatePath = if (requestedPath.isAbsolute) {
            requestedPath.toAbsolutePath().normalize()
        } else {
            allowedBase.resolve(requestedPath).normalize()
        }
        val resolvedPath = candidatePath.normalizedRealPath()
        require(resolvedPath.startsWith(allowedBase)) {
            "file: secret path '$resolvedPath' is outside the allowed directory '$allowedBase'"
        }
        return if (Files.exists(resolvedPath)) Files.readString(resolvedPath).trim() else null
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

private fun Path.normalizedRealPath(): Path = if (Files.exists(this, LinkOption.NOFOLLOW_LINKS)) {
    toRealPath()
} else {
    toAbsolutePath().normalize()
}
