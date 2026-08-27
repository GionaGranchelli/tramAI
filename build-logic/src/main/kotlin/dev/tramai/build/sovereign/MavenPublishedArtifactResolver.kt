package dev.tramai.build.sovereign

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resolves actual Maven artifact filenames under release and unique-SNAPSHOT
 * versioning (9.2b extraction). Pure and Gradle-free for unit testing.
 *
 * Strategy (preserved from the historical root script):
 * 1. literal `moduleDir/<base>-<version>.<ext>`;
 * 2. unique snapshot naming via `maven-metadata.xml` snapshotVersion value —
 *    parsed as XML (multiline/pretty-printed safe), never regex;
 * 3. deterministic directory scan fallback: candidates sorted by filename,
 *    and FAIL CLOSED when the metadata did not resolve an unambiguous
 *    version and multiple timestamped snapshots exist.
 */
object MavenPublishedArtifactResolver {

    fun resolve(moduleDir: File, baseNameWithoutVersion: String, expectedVersion: String, extension: String): File {
        val literal = moduleDir.resolve("$baseNameWithoutVersion-$expectedVersion.$extension")
        if (literal.isFile) return literal

        val metadata = moduleDir.resolve("maven-metadata.xml")
        if (metadata.isFile) {
            val snapshotValue = snapshotValueForExtension(metadata, extension)
            if (snapshotValue != null) {
                val artifact = moduleDir.resolve("$baseNameWithoutVersion-$snapshotValue.$extension")
                if (artifact.isFile) return artifact
            }
            // Deterministic fallback: never firstOrNull on an unsorted listing.
            val candidates = moduleDir.listFiles()
                ?.filter { it.name.endsWith(".$extension") && it.name.startsWith("$baseNameWithoutVersion-") }
                ?.sortedBy { it.name }
                .orEmpty()
            if (candidates.size == 1) return candidates.single()
            if (candidates.size > 1) {
                error(
                    "Ambiguous unique-SNAPSHOT artifacts for $baseNameWithoutVersion-$expectedVersion.$extension in " +
                        "${moduleDir.absolutePath}: maven-metadata.xml did not resolve a snapshot version and " +
                        "${candidates.size} candidate files exist (${candidates.joinToString { it.name }}). " +
                        "Refusing to guess nondeterministically."
                )
            }
        }
        return literal
    }

    private fun snapshotValueForExtension(metadata: File, extension: String): String? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            // No network access, no DTD fetching — local metadata only.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val document = factory.newDocumentBuilder().parse(metadata)
            val snapshotVersions = document.getElementsByTagName("snapshotVersion")
            for (i in 0 until snapshotVersions.length) {
                val node = snapshotVersions.item(i)
                if (node is Element && childText(node, "extension") == extension) {
                    childText(node, "value")?.let { return it }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun childText(element: Element, tagName: String): String? =
        element.getElementsByTagName(tagName).item(0)?.textContent?.takeIf { it.isNotBlank() }
}
