package dev.tramai.build.release

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException

private const val MIN_AUDIT_FINDINGS = 15

/**
 * Verifies that all P0/P1 independent audit findings from Epic 12.3 are CLOSED and
 * that all deferred P2/P3 findings retain assigned owners and rationales (Epic 12.4a).
 */
@DisableCachingByDefault(because = "Audit closure verification inspects release review findings")
abstract class VerifyAuditClosureTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val auditFindingsFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val file = auditFindingsFile.get().asFile
        val root = parseAuditJson(file)
        val errors = evaluateFindings(root)

        if (errors.isNotEmpty()) {
            throw GradleException(
                "verifyAuditClosure failed with ${errors.size} violations:\n - " +
                    errors.joinToString("\n - "),
            )
        }

        logger.lifecycle(
            "verifyAuditClosure: verified all P0/P1 audit findings CLOSED and " +
                "all P2/P3 deferrals documented.",
        )
    }

    private fun parseAuditJson(file: File): JsonNode {
        if (!file.isFile) {
            throw GradleException(
                "verifyAuditClosure: Audit findings evidence file missing: ${file.absolutePath}",
            )
        }
        return try {
            ObjectMapper().readTree(file)
        } catch (e: IOException) {
            throw GradleException(
                "verifyAuditClosure: Failed to parse audit findings evidence JSON: ${e.message}",
                e,
            )
        }
    }

    private fun evaluateFindings(root: JsonNode): List<String> {
        val errors = mutableListOf<String>()
        val findingsNode = root.get("findings")
        if (findingsNode == null || !findingsNode.isArray || findingsNode.size() < MIN_AUDIT_FINDINGS) {
            val count = findingsNode?.size() ?: 0
            errors.add(
                "Audit evidence must contain at least $MIN_AUDIT_FINDINGS findings, found $count.",
            )
            return errors
        }

        val p0p1RequiredClosed = setOf("R12-001", "R12-002", "R12-003")
        val foundIds = mutableSetOf<String>()

        for (finding in findingsNode) {
            validateFindingEntry(finding, p0p1RequiredClosed, foundIds, errors)
        }

        val missingP0P1 = p0p1RequiredClosed - foundIds
        if (missingP0P1.isNotEmpty()) {
            errors.add("Missing mandatory release-blocking audit findings: $missingP0P1")
        }
        return errors
    }

    private fun validateFindingEntry(
        finding: JsonNode,
        p0p1RequiredClosed: Set<String>,
        foundIds: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        val id = finding.get("id")?.asText() ?: return
        foundIds.add(id)
        val severity = finding.get("severity")?.asText() ?: ""
        val status = finding.get("status")?.asText() ?: ""
        val owner = finding.get("owner")?.asText() ?: ""

        if (id in p0p1RequiredClosed || severity in setOf("P0", "P1")) {
            if (!status.equals("CLOSED", ignoreCase = true)) {
                errors.add("Release-blocking audit finding $id ($severity) is not CLOSED (status: '$status').")
            }
        } else {
            val rationale =
                finding.get("rationale")?.asText()
                    ?: finding.get("remediationPlan")?.asText()
                    ?: ""
            if (owner.isBlank()) {
                errors.add("Deferred audit finding $id must have an assigned 'owner'.")
            }
            if (rationale.isBlank()) {
                errors.add("Deferred audit finding $id must have a documented 'rationale'.")
            }
        }
    }
}
