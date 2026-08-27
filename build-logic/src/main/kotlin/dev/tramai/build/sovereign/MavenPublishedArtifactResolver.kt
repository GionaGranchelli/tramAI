package dev.tramai.build.sovereign

import java.io.File

/**
 * Resolves actual Maven artifact filenames under release and unique-SNAPSHOT
 * versioning (9.2b extraction). Pure and Gradle-free for unit testing.
 *
 * Strategy (preserved from the historical root script):
 * 1. literal `moduleDir/<base>-<version>.<ext>`;
 * 2. unique snapshot naming via `maven-metadata.xml` snapshotVersion value;
 * 3. directory scan fallback for files starting with `<base>-`.
 */
object MavenPublishedArtifactResolver {

    fun resolve(moduleDir: File, baseNameWithoutVersion: String, expectedVersion: String, extension: String): File {
        val literal = moduleDir.resolve("$baseNameWithoutVersion-$expectedVersion.$extension")
        if (literal.isFile) return literal

        val metadata = moduleDir.resolve("maven-metadata.xml")
        if (metadata.isFile) {
            val text = metadata.readText()
            val snapshotVersion = Regex("""<snapshotVersion>.*?<extension>$extension</extension>.*?</snapshotVersion>""")
                .find(text, 0)
            if (snapshotVersion != null) {
                val value = Regex("""<value>([^<]+)</value>""").find(snapshotVersion.value)
                if (value != null) {
                    val artifact = moduleDir.resolve("$baseNameWithoutVersion-${value.groupValues[1]}.$extension")
                    if (artifact.isFile) return artifact
                }
            }
            val glob = moduleDir.listFiles()
                ?.firstOrNull { it.name.endsWith(".$extension") && it.name.startsWith("$baseNameWithoutVersion-") }
            if (glob != null) return glob
        }
        return literal
    }
}
