package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.File

/**
 * A promotion declaration: the statement that one exact, already-certified evidence population is
 * CARRIED ACROSS a release promotion boundary rather than minted by the promotion transition.
 *
 * It grants no authority. M35 and M45 bind a newly introduced admission/certificate to the authority
 * base it was minted against, and a promotion cannot satisfy that binding by any legitimate means:
 * re-minting admissions, rewriting `fromBaseSha`, manufacturing replacement certificates and a
 * per-entry migration chain are all forbidden. This declaration states the narrow fact the promotion
 * actually needs - the appearing evidence is the SAME certified population, unchanged - so the
 * mint-binding rules do not apply to it.
 *
 * SHA-exact and candidate-side: it names the exact base SHA the promotion is proposed against, so it
 * can never authorize a different transition, and it travels in the promotion's own candidate because
 * the base branch cannot name a commit that does not exist until the declaration is committed.
 *
 * Fail-closed: an absent or malformed declaration, or a declaration that does not describe the
 * candidate exactly, means "no promotion" - every appearing row then fails M35/M45 as before.
 */
data class MutationPopulationPromotion(
    /** The exact authority base SHA this promotion is proposed against. */
    val promotionBase: String,
    /** The digest of the carried population; the only digest this declaration carries forward. */
    val populationDigest: String,
    /** The exact number of admissions in the carried population. */
    val admissions: Int,
    /** The exact number of digest-migration certificates in the carried population. */
    val certificates: Int,
) {
    companion object {
        /**
         * The declaration that legitimately carries the candidate's appearing evidence across
         * [base], or null when there is none or it does not describe that evidence exactly.
         *
         * Every field is enforced against the ledgers themselves, so a declaration cannot authorize a
         * population other than the one it names: the base SHA must be this transition's, both counts
         * must equal the number of rows appearing relative to the base, and every appearing row must
         * carry the declared digest. A single mismatch anywhere yields no promotion at all.
         */
        fun carried(
            base: MutationRatchetAuthority,
            candidate: MutationRatchetCandidate,
        ): MutationPopulationPromotion? =
            candidate.promotion?.takeIf { promotion ->
                promotion.promotionBase == base.baseSha &&
                    carriesAdmissions(base, candidate, promotion) &&
                    carriesCertificates(base, candidate, promotion)
            }

        /** The exact admission count, and every appearing row's digest. */
        private fun carriesAdmissions(
            base: MutationRatchetAuthority,
            candidate: MutationRatchetCandidate,
            promotion: MutationPopulationPromotion,
        ): Boolean {
            val baseAdmissions = base.admissions.byIdentity().keys
            val appearing =
                candidate.admissions
                    .byIdentity()
                    .filterKeys { it !in baseAdmissions }
                    .values
            return appearing.size == promotion.admissions &&
                appearing.all { it.populationDigest == promotion.populationDigest }
        }

        /** The exact certificate count, and every appearing certificate's source digest. */
        private fun carriesCertificates(
            base: MutationRatchetAuthority,
            candidate: MutationRatchetCandidate,
            promotion: MutationPopulationPromotion,
        ): Boolean {
            val baseCertificates = base.certificates.byFromDigest().keys
            val appearing =
                candidate.certificates
                    .byFromDigest()
                    .filterKeys { it !in baseCertificates }
                    .values
            return appearing.size == promotion.certificates &&
                appearing.all { it.fromDigest == promotion.populationDigest }
        }
    }
}

/**
 * Parses config/quality/mutation-population-promotions.yml.
 *
 * An absent file means "no promotion", which is fail-closed: every appearing admission and
 * certificate is then bound to the transition base by M35/M45 exactly as before. A present but
 * malformed file is a hard failure - a broken declaration must never silently degrade into "no
 * promotion happened to be needed".
 *
 * Notes carry declaration history, so a later promotion appends rather than replaces: an older
 * declaration simply never matches the transition being verified, since the base SHA is exact.
 */
object MutationPopulationPromotionLoader {
    const val FILE_NAME = "config/quality/mutation-population-promotions.yml"

    private const val SCHEMA_VERSION = "1"
    private const val MAX_ALIASES_FOR_COLLECTIONS = 20
    private val COMMIT_SHA = Regex("[0-9a-f]{40}")
    private val DIGEST = Regex("[0-9a-f]{64}")

    fun load(repositoryRoot: File): MutationPopulationPromotion? {
        val file = File(repositoryRoot, FILE_NAME)
        if (!file.isFile) {
            return null
        }
        val declarations = parseDeclarations(readLedger(file))
        // One transition has exactly one base, so at most one declaration can apply. More than one
        // would make the promotion depend on file order, which is not an authority.
        require(declarations.size <= 1) {
            "$FILE_NAME: at most one promotion declaration may be present; found ${declarations.size}"
        }
        return declarations.singleOrNull()
    }

    private fun readLedger(file: File): Map<String, Any?> {
        val loaderOptions =
            LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = MAX_ALIASES_FOR_COLLECTIONS
            }
        return try {
            @Suppress("UNCHECKED_CAST")
            Yaml(loaderOptions).load<Map<String, Any?>>(file.readText(Charsets.UTF_8)) ?: emptyMap()
        } catch (e: YAMLException) {
            throw GradleException("Invalid $FILE_NAME: ${e.message}", e)
        }
    }

    private fun parseDeclarations(raw: Map<String, Any?>): List<MutationPopulationPromotion> {
        val schemaVersion = raw["schemaVersion"]?.toString()
        require(schemaVersion == SCHEMA_VERSION) {
            "$FILE_NAME: schemaVersion must be '$SCHEMA_VERSION', was '$schemaVersion'"
        }
        val promotions = raw["promotions"]
        require(promotions is List<*>) { "$FILE_NAME: promotions must be a list" }
        return promotions.mapIndexed { index, item ->
            require(item is Map<*, *>) { "$FILE_NAME: promotions[$index] must be a mapping" }
            val promotionBase =
                requireCanonical(item, "promotionBase", index, COMMIT_SHA, "a 40-hex commit SHA")
            val populationDigest =
                requireCanonical(item, "populationDigest", index, DIGEST, "a 64-hex digest")
            MutationPopulationPromotion(
                promotionBase = promotionBase,
                populationDigest = populationDigest,
                admissions = requiredCount(item, "admissions", index),
                certificates = requiredCount(item, "certificates", index),
            )
        }
    }

    /**
     * Counts are part of the declaration's meaning, so an absent one is invalid rather than zero: a
     * declaration that forgot `certificates` must not read as "carries no certificates".
     */
    private fun requiredCount(
        entry: Map<*, *>,
        field: String,
        index: Int,
    ): Int {
        val value =
            (entry[field] as? Number)?.toInt()
                ?: throw GradleException("$FILE_NAME: promotions[$index].$field is required and must be an integer")
        require(value >= 0) {
            "$FILE_NAME: promotions[$index].$field must not be negative"
        }
        return value
    }

    private fun requireCanonical(
        entry: Map<*, *>,
        field: String,
        index: Int,
        pattern: Regex,
        expected: String,
    ): String {
        val value =
            entry[field]?.toString()?.takeIf { it.isNotBlank() }
                ?: throw GradleException("$FILE_NAME: promotions[$index].$field is required and must be non-blank")
        require(pattern.matches(value)) {
            "$FILE_NAME: promotions[$index].$field '$value' is not $expected"
        }
        return value
    }
}
