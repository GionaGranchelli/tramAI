package dev.tramai.build.quality

import java.io.File

/**
 * 10.3c1-C9: writes the survivor inventory review artifact. Classifications
 * are deliberately NOT written here — survivor classification is a 10.3c2
 * review decision.
 */
object MutationSurvivorInventory {
    fun write(
        baseline: MutationPopulationBaseline,
        target: File,
    ) {
        val nonKilled = baseline.mutants.filter { it.status != "KILLED" }
        val inventory =
            nonKilled.map { mutant ->
                mapOf(
                    "identity" to mutant.identity,
                    "family" to mutant.family,
                    "module" to mutant.module,
                    "className" to mutant.className,
                    "method" to mutant.method,
                    "methodDescription" to mutant.methodDescription,
                    "mutator" to mutant.mutator,
                    "description" to mutant.description,
                    "status" to mutant.status,
                    "outcome" to mutant.outcome,
                    "sourceFile" to mutant.sourceFile,
                    "line" to mutant.line,
                )
            }
        ReportNormalizer.writeJson(
            mapOf(
                "schemaVersion" to baseline.schemaVersion,
                "identitySchemaVersion" to baseline.identitySchemaVersion,
                "measuredCommit" to baseline.measuredCommit,
                "survivorCount" to nonKilled.size,
                "survivors" to inventory,
            ),
            target,
        )
    }
}
