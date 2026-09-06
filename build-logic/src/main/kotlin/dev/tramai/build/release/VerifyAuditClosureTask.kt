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

private const val EXACT_AUDIT_FINDINGS = 15
private const val FINDING_ID_WIDTH = 3
private val P0P1_IDS = setOf("R12-001", "R12-002", "R12-003")
private val EXPECTED_FINDING_IDS =
    (1..EXACT_AUDIT_FINDINGS).map { "R12-${it.toString().padStart(FINDING_ID_WIDTH, '0')}" }.toSet()

private fun validateAuditBlock(
    root: JsonNode,
    errors: MutableList<String>,
) {
    val audit =
        root.get("audit") ?: run {
            errors.add("12.3a findings: missing 'audit' block")
            return
        }
    if (audit.get("status")?.asText().isNullOrBlank()) {
        errors.add("12.3a audit.status must preserve a non-blank historical status")
    }
    if (audit.get("disposition")?.asText().isNullOrBlank()) {
        errors.add("12.3a audit.disposition must preserve a non-blank historical disposition")
    }
}

private fun validateClosureBlock(
    root: JsonNode,
    errors: MutableList<String>,
) {
    val closure =
        root.get("closure") ?: run {
            errors.add("12.3b closure: missing 'closure' block")
            return
        }
    val status = closure.get("status")?.asText() ?: ""
    if (status != "CLOSED") {
        errors.add("12.3b closure.status must be 'CLOSED', found: '$status'")
    }
    if (closure.get("disposition")?.asText() == "READY_FOR_0.6.0_RELEASE") {
        errors.add("12.3b closure must not contain the final release certification verdict")
    }
}

private fun validateAuditDeferrals(
    auditFindings: Map<String, JsonNode>,
    errors: MutableList<String>,
) {
    auditFindings.forEach { (id, finding) ->
        if (id in P0P1_IDS) return@forEach
        if ((finding.get("owner")?.asText() ?: "").isBlank()) {
            errors.add("12.3a deferred finding $id must have an assigned owner")
        }
        if ((finding.get("rationale")?.asText() ?: "").isBlank()) {
            errors.add("12.3a deferred finding $id must have a rationale")
        }
    }
}

/**
 * Verifies that all P0/P1 independent audit findings from Epic 12.3 are CLOSED (via 12.3b closure
 * evidence) and that all deferred P2/P3 findings retain assigned owners and rationales (Epic 12.4a).
 *
 * Dual-file contract:
 * - 12.3a: original audit with its historical status and verdicts preserved
 * - 12.3b: remediation closure evidence (CLOSED, closedFindings and deferredFindings arrays)
 */
@DisableCachingByDefault(because = "Audit closure verification inspects release review findings")
abstract class VerifyAuditClosureTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val auditFindingsFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val remediationClosureFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val auditFile = auditFindingsFile.get().asFile
        val closureFile = remediationClosureFile.get().asFile

        val auditRoot = parseJson(auditFile, "auditFindingsFile")
        val closureRoot = parseJson(closureFile, "remediationClosureFile")

        val errors = mutableListOf<String>()
        validateAuditBlock(auditRoot, errors)
        validateClosureBlock(closureRoot, errors)
        validateClosureReferences(auditRoot, closureRoot, errors)
        val auditFindings = validateAuditFindings(auditRoot, errors)
        validateAuditDeferrals(auditFindings, errors)
        validateClosureFindings(auditFindings, closureRoot, errors)

        if (errors.isNotEmpty()) {
            throw GradleException(
                "verifyAuditClosure failed with ${errors.size} violations:\n - " +
                    errors.joinToString("\n - "),
            )
        }

        logger.lifecycle(
            "verifyAuditClosure: verified all P0/P1 audit findings CLOSED (via 12.3b) and " +
                "all P2/P3 deferrals documented.",
        )
    }

    private fun parseJson(
        file: File,
        inputName: String,
    ): JsonNode {
        if (!file.isFile) {
            throw GradleException(
                "verifyAuditClosure: $inputName evidence file missing: ${file.absolutePath}",
            )
        }
        return try {
            ObjectMapper().readTree(file)
        } catch (e: IOException) {
            throw GradleException(
                "verifyAuditClosure: Failed to parse $inputName JSON: ${e.message}",
                e,
            )
        }
    }

    private fun validateClosureReferences(
        auditRoot: JsonNode,
        closureRoot: JsonNode,
        errors: MutableList<String>,
    ) {
        val auditTargetCommit =
            auditRoot
                .get("audit")
                ?.get("targetCommit")
                ?.asText()
                .orEmpty()
        val closure = closureRoot.get("closure") ?: return
        if (auditTargetCommit.isBlank()) {
            errors.add("12.3a audit: missing 'targetCommit' cross-reference")
        } else if (closure.get("baseCommit")?.asText() != auditTargetCommit) {
            errors.add("12.3b closure.baseCommit must match 12.3a audit.targetCommit")
        }
        if (closure.get("auditRef")?.asText() != "12.3a-independent-review-findings.json") {
            errors.add("12.3b closure.auditRef must identify the 12.3a findings register")
        }
    }

    private fun validateAuditFindings(
        root: JsonNode,
        errors: MutableList<String>,
    ): Map<String, JsonNode> {
        val findings = root.get("findings")
        if (findings == null || !findings.isArray) {
            errors.add("12.3a findings: 'findings' array missing")
            return emptyMap()
        }
        val count = findings.size()
        if (count != EXACT_AUDIT_FINDINGS) {
            errors.add("12.3a findings: must contain exactly $EXACT_AUDIT_FINDINGS findings, found $count")
        }
        val ids = findings.map { it.get("id")?.asText()?.takeIf(String::isNotBlank) ?: "<missing>" }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.forEach { (id, duplicateCount) ->
            errors.add("12.3a findings: duplicate finding ID '$id' appears $duplicateCount times")
        }
        val foundIds = ids.toSet() - "<missing>"
        val missingIds = EXPECTED_FINDING_IDS - foundIds
        val extraIds = foundIds - EXPECTED_FINDING_IDS
        if ("<missing>" in ids) {
            errors.add("12.3a findings: every finding must have a non-blank ID")
        }
        if (missingIds.isNotEmpty()) {
            errors.add("12.3a findings: missing expected finding IDs: $missingIds")
        }
        if (extraIds.isNotEmpty()) {
            errors.add("12.3a findings: unexpected extra finding IDs: $extraIds")
        }
        return findings
            .mapNotNull { finding ->
                finding
                    .get("id")
                    ?.asText()
                    ?.takeIf { it in EXPECTED_FINDING_IDS }
                    ?.let { it to finding }
            }.toMap()
    }

    private fun validateClosureFindings(
        auditFindings: Map<String, JsonNode>,
        closureRoot: JsonNode,
        errors: MutableList<String>,
    ) {
        val entries = closureEntries(closureRoot, errors)
        validateEntryUniverse(entries, errors)
        entries.forEach { validateClosureEntry(it, auditFindings, errors) }
    }

    private fun closureEntries(
        root: JsonNode,
        errors: MutableList<String>,
    ): List<JsonNode> {
        val arrays =
            listOf("closedFindings", "deferredFindings").mapNotNull { name ->
                root.get(name)?.takeIf { it.isArray } ?: run {
                    errors.add("12.3b closure: missing '$name' array")
                    null
                }
            }
        return arrays.flatMap { it.toList() }
    }

    private fun validateEntryUniverse(
        entries: List<JsonNode>,
        errors: MutableList<String>,
    ) {
        if (entries.size != EXACT_AUDIT_FINDINGS) {
            errors.add(
                "12.3b closure: must contain exactly $EXACT_AUDIT_FINDINGS remediation entries, found ${entries.size}",
            )
        }
        val ids = entries.map { it.get("id")?.asText()?.takeIf(String::isNotBlank) ?: "<missing>" }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.forEach { (id, duplicateCount) ->
            errors.add("12.3b closure: duplicate finding ID '$id' appears $duplicateCount times")
        }
        val foundIds = ids.toSet() - "<missing>"
        val missingIds = EXPECTED_FINDING_IDS - foundIds
        val extraIds = foundIds - EXPECTED_FINDING_IDS
        if (missingIds.isNotEmpty()) errors.add("12.3b closure: missing expected finding IDs: $missingIds")
        if (extraIds.isNotEmpty()) errors.add("12.3b closure: unexpected finding IDs: $extraIds")
    }

    private fun validateClosureEntry(
        entry: JsonNode,
        auditFindings: Map<String, JsonNode>,
        errors: MutableList<String>,
    ) {
        val id = entry.get("id")?.asText() ?: "<missing>"
        val audit = auditFindings[id] ?: return
        val auditSeverity = audit.get("severity")?.asText() ?: ""
        val closureSeverity = entry.get("severity")?.asText() ?: ""
        if (closureSeverity != auditSeverity) {
            errors.add(
                "12.3b closure: severity for $id must match 12.3a ('$auditSeverity'), found '$closureSeverity'",
            )
        }
        if (id in P0P1_IDS) validateClosedEntry(id, entry, errors) else validateDeferredEntry(id, entry, errors)
    }

    private fun validateClosedEntry(
        id: String,
        entry: JsonNode,
        errors: MutableList<String>,
    ) {
        val status = entry.get("status")?.asText() ?: ""
        if (!status.equals("CLOSED", ignoreCase = true)) {
            errors.add("12.3b closure: $id must have status CLOSED, found: '$status'")
        }
        if (entry.get("closurePr")?.canConvertToInt() != true ||
            entry.get("closureCommit")?.asText().isNullOrBlank() ||
            entry.get("closureNotes")?.asText().isNullOrBlank()
        ) {
            errors.add(
                "12.3b closure: CLOSED finding $id must include closurePr, closureCommit, and closureNotes evidence",
            )
        }
    }

    private fun validateDeferredEntry(
        id: String,
        entry: JsonNode,
        errors: MutableList<String>,
    ) {
        val status = entry.get("status")?.asText() ?: ""
        if (!status.equals("DEFERRED", ignoreCase = true)) {
            errors.add("12.3b closure: $id must have status DEFERRED, found: '$status'")
        }
        if ((entry.get("owner")?.asText() ?: "").isBlank()) {
            errors.add("12.3b closure: deferred finding $id must have an assigned owner")
        }
        if ((entry.get("rationale")?.asText() ?: "").isBlank()) {
            errors.add("12.3b closure: deferred finding $id must have a rationale")
        }
    }
}
