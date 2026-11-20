package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule

/**
 * Composes organization, environment and workload routing policy into the
 * effective policy.
 *
 * ```text
 * organization ∩ environment ∩ workload
 * ```
 *
 * One invariant: **a narrower scope may restrict authority, never widen it.**
 * That holds structurally rather than by convention, in two ways:
 *
 * - The effective zones are the intersection of the zones each scope permits, so
 *   the result is a subset of every scope that speaks. No scope can add a zone
 *   another scope denied.
 * - Only classifications the organization policy defines can appear in the
 *   result, so nothing can be introduced from below.
 *
 * A scope that is silent about a classification abstains and imposes no
 * restriction. Silence at the organization scope is different: a classification
 * the organization does not define is absent from the effective policy, and a
 * classification it defines with no permitted zones is left with none. Authority
 * originates at the widest scope.
 *
 * For every classification, the effective `allowedFallbackZones` are the
 * intersection of the scopes' fallback sets. When every scope satisfies
 * `allowedFallbackZones` ⊆ `allowedZones`, the result does too, because
 * intersection preserves that relation (`∩ fallbacks` ⊆ `∩ alloweds`), so
 * composition never introduces a violation.
 *
 * Composition does not validate its inputs, and does not claim to.
 * `ClassificationRoutingRule` is a plain data class with no validation of its
 * own: the subset requirement is enforced where rules are configured, by
 * `ProviderRoutingConfiguration.init`, and a rule constructed directly can
 * violate it. Composition preserves such a violation rather than repairing it —
 * a relation nobody introduced is a relation nobody should silently repair.
 *
 * Pure and deterministic: no state, no I/O, and the inputs are not modified.
 */
fun effectiveRoutingRules(
    organization: Map<DataClassification, ClassificationRoutingRule>,
    environment: Map<DataClassification, ClassificationRoutingRule> = emptyMap(),
    workload: Map<DataClassification, ClassificationRoutingRule> = emptyMap(),
): Map<DataClassification, ClassificationRoutingRule> =
    organization.mapValues { (classification, organizationRule) ->
        // Always at least the organization rule, so reduce is safe.
        val scopes =
            listOfNotNull(
                organizationRule,
                environment[classification],
                workload[classification],
            )
        ClassificationRoutingRule(
            allowedZones = scopes.map { it.allowedZones }.reduce { left, right -> left intersect right },
            allowedFallbackZones =
                scopes
                    .map { it.allowedFallbackZones }
                    .reduce { left, right -> left intersect right },
        )
    }
