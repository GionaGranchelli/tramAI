package dev.tramai.build.release

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

private val LINK_PATTERN = Regex("""\[([^\]]*)\]\(([^)]+)\)""")
private val DEV_HOME_PATTERN = Regex("""/home/(?!\.\.\.)[^\s\)"]+""")

/**
 * Verifies repository documentation link integrity and path hygiene (Epic 12.4a).
 */
@DisableCachingByDefault(because = "Release documentation verification performs link integrity analysis")
abstract class VerifyReleaseDocumentationTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val documentationFiles: ConfigurableFileCollection

    @get:Internal
    abstract val rootDir: Property<File>

    @TaskAction
    fun verify() {
        val root = rootDir.get()
        val files = documentationFiles.files.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
        if (files.isEmpty()) {
            throw GradleException("verifyReleaseDocumentation: No documentation files provided for verification.")
        }

        val brokenLinks = mutableListOf<String>()
        val securityViolations = mutableListOf<String>()
        var checkedLinksCount = 0

        for (file in files) {
            val relPath = file.relativeToOrSelf(root).path
            val content = file.readText(Charsets.UTF_8)

            checkSecurityViolations(relPath, content, securityViolations)
            checkedLinksCount += checkRelativeLinks(file, content, root, brokenLinks)
        }

        reportViolations(securityViolations, brokenLinks)

        logger.lifecycle(
            "verifyReleaseDocumentation: successfully verified $checkedLinksCount " +
                "links across ${files.size} documentation files.",
        )
    }

    private fun checkSecurityViolations(
        relPath: String,
        content: String,
        violations: MutableList<String>,
    ) {
        for (match in LINK_PATTERN.findAll(content)) {
            val target = match.groupValues[2].trim()
            if (target.startsWith("file://") || target.startsWith("file:/")) {
                violations.add("file:// link scheme forbidden in $relPath: '$target'")
            }
            if (target.startsWith("/home/") || target.startsWith("/Users/")) {
                violations.add("Absolute developer home link forbidden in $relPath: '$target'")
            }
        }
        for (match in DEV_HOME_PATTERN.findAll(content)) {
            val path = match.value
            if (!isPlaceholderPath(path)) {
                violations.add("Hardcoded local user path found in $relPath: '$path'")
            }
        }
    }

    private fun isPlaceholderPath(path: String): Boolean =
        path.startsWith("/home/you/") ||
            path.startsWith("/home/user/") ||
            path.startsWith("/home/deploy/") ||
            path.startsWith("/home/`,")

    private fun isIgnoredLinkTarget(target: String): Boolean =
        target.startsWith("http://") ||
            target.startsWith("https://") ||
            target.startsWith("mailto:") ||
            target.startsWith("#") ||
            target.startsWith("git@")

    private fun checkRelativeLinks(
        file: File,
        content: String,
        root: File,
        broken: MutableList<String>,
    ): Int {
        val relPath = file.relativeToOrSelf(root).path
        var count = 0
        for (match in LINK_PATTERN.findAll(content)) {
            val rawTarget = match.groupValues[2].trim()
            if (validateRelativeLink(rawTarget, file, root, relPath, broken)) {
                count++
            }
        }
        return count
    }

    private fun validateRelativeLink(
        rawTarget: String,
        file: File,
        root: File,
        relPath: String,
        broken: MutableList<String>,
    ): Boolean {
        var isCounted = false
        if (!isIgnoredLinkTarget(rawTarget)) {
            val cleanTarget = extractCleanTarget(rawTarget)
            if (cleanTarget != null) {
                val resolved =
                    if (cleanTarget.startsWith("/")) {
                        File(root, cleanTarget.removePrefix("/"))
                    } else {
                        file.parentFile.resolve(cleanTarget).normalize()
                    }

                if (!resolved.exists()) {
                    broken.add("$relPath -> '$rawTarget' (resolved to: ${resolved.path})")
                }
                isCounted = true
            }
        }
        return isCounted
    }

    private fun extractCleanTarget(rawTarget: String): String? {
        val clean =
            rawTarget
                .split(Regex("""\s+"""))[0]
                .substringBefore('#')
                .substringBefore('?')

        return if (clean.isBlank() || clean.contains("NNN") || clean.contains("example")) null else clean
    }

    private fun reportViolations(
        securityViolations: List<String>,
        brokenLinks: List<String>,
    ) {
        val allErrors = mutableListOf<String>()
        if (securityViolations.isNotEmpty()) {
            allErrors.add(
                "Found ${securityViolations.size} documentation path/URL violation(s):\n" +
                    securityViolations.joinToString("\n  - ", prefix = "  - "),
            )
        }
        if (brokenLinks.isNotEmpty()) {
            allErrors.add(
                "Found ${brokenLinks.size} broken relative link(s):\n" +
                    brokenLinks.joinToString("\n  - ", prefix = "  - "),
            )
        }
        if (allErrors.isNotEmpty()) {
            throw GradleException(
                "verifyReleaseDocumentation failed:\n" + allErrors.joinToString("\n\n"),
            )
        }
    }
}
