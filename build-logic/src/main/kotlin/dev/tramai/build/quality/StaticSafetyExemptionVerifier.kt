package dev.tramai.build.quality

/** Count/staleness validation: every occurrence is ratcheted against the declared exemption count. */
internal class StaticSafetyExemptionVerifier(
    private val config: StaticSafetyGuardConfig,
) {
    private val exemptionMap = config.exemptions.associateBy { Triple(it.rule, it.path, it.symbol) }

    fun markExempt(findings: List<SafetyFinding>): List<SafetyFinding> =
        findings.map { f -> f.copy(exempt = Triple(f.rule, f.path, f.symbol) in exemptionMap) }

    fun countMismatches(findings: List<SafetyFinding>): List<String> =
        exemptionMap.mapNotNull { (key, ex) ->
            val actual = findings.count { Triple(it.rule, it.path, it.symbol) == key }
            when {
                actual == 0 -> {
                    null
                }

                // stale, reported separately
                actual != ex.occurrences -> {
                    "exemption count mismatch: ${key.first} | ${key.second} | ${key.third} " +
                        "| declared ${ex.occurrences}, actual $actual"
                }

                else -> {
                    null
                }
            }
        }

    fun staleTriples(findings: List<SafetyFinding>): List<Pair<Triple<String, String, String>, StaticSafetyExemption>> =
        exemptionMap.filter { (key, _) -> findings.none { Triple(it.rule, it.path, it.symbol) == key } }.toList()
}
