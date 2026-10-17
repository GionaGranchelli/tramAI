package dev.tramai.security.evidence

/**
 * 0.7.4b3 G2: registered enforcement-boundary provenance at the evidence boundary.
 *
 * Authorization and enforcement are different facts. A `tool.permission` record reports the policy
 * engine's decision, and [RuntimeEvidenceRecord] pins `source.component` to the decision producer
 * (`"policy-engine"`). This object supplies the *other* half of that pair, and only that half: the
 * enforcement strength the registration grants the boundary a producer **declares**, resolved from the
 * declared identifier. It resolves a declared identifier's strength; it does **not** establish that the
 * declared boundary emitted, or enforced, this particular event.
 *
 * ## Strength is derived, never asserted
 *
 * The central invariant: an integration cannot claim AUTHORITATIVE **strength** by asserting strength.
 * Strength is therefore a property of a framework-owned registration, resolved here from the boundary's
 * declared identifier. A producing boundary supplies only `boundary.id`; a caller-supplied
 * `boundary.enforcementStrength` is refused rather than silently overwritten, because accepting it would
 * make the field a self-asserted claim and ignoring it would hide the attempt.
 *
 * **The identifier itself is producer-declared and its origin is unverified, and the invariant above does
 * not cover that.** A producer that names a registered identifier obtains that registration's strength
 * without having gone through the boundary — pinned by
 * `ToolPermissionBoundaryProvenanceTest.the registration does not verify the origin of a declared
 * identifier`. What this object delivers is registered-strength resolution for a declared identifier, not
 * attestation that the boundary emitted or enforced this event. Verified event-origin attribution is
 * tracked as a separate security requirement; it is not established here.
 *
 * Registration is what makes [AUTHORITATIVE] reachable, and nothing outside this module can add an
 * entry. An unregistered identifier is refused: a record naming a boundary that no contract covers
 * cannot be validated against anything, so it is not silently capped to [OBSERVED] — that would let an
 * unknown adapter's identifier into evidence as if it were recognised. This is a deliberate choice of
 * refusal over downgrade, and it is what enforces "registered boundary identity".
 *
 * ## What this does and does not prove
 *
 * A registration plus its prevention tests is a **declared, framework-owned claim backed by tests**.
 * It is not cryptographic authentication of an arbitrary external caller: any process able to reach
 * the boundary may attempt a call. Stronger workload identity authentication remains separate,
 * unresolved work. The strength is scoped to one declared enforcement point and says nothing about any
 * other path by which the host application might reach the same side effect.
 */
internal object RuntimeBoundaryProvenance {
    /** The boundary consulted the engine before delegating and prevents execution on DENY/approval/unavailable. */
    const val AUTHORITATIVE = "AUTHORITATIVE"

    /** The boundary participates in the path but its prevention is not proven. */
    const val INSTRUMENTED = "INSTRUMENTED"

    /** The boundary records only; nothing is prevented. */
    const val OBSERVED = "OBSERVED"

    /** The boundary's evidence was imported from outside this runtime. */
    const val IMPORTED = "IMPORTED"

    const val ID_KEY = "boundary.id"
    const val STRENGTH_KEY = "boundary.enforcementStrength"

    /**
     * The strength vocabulary is closed. A value outside it is not a weaker claim that can be capped;
     * it is unparseable provenance and is refused by [validate].
     */
    val strengths = setOf(AUTHORITATIVE, INSTRUMENTED, OBSERVED, IMPORTED)

    /** Reserved keys, validated atomically: zero is legacy evidence, exactly two is governed provenance. */
    val metadataKeys = setOf(ID_KEY, STRENGTH_KEY)

    /** The declared XR1 boundary: the Spring AI callback, consumed by `GovernedToolCallback`. */
    const val SPRING_AI_TOOL_CALLBACK = "spring-ai-tool-callback"

    private val registered =
        mapOf(
            SPRING_AI_TOOL_CALLBACK to
                RegisteredBoundary(
                    id = SPRING_AI_TOOL_CALLBACK,
                    enforcementPoint = "BEFORE_TOOL_EXECUTION",
                    strength = AUTHORITATIVE,
                    contract =
                        "Consults PolicyEngine before delegating and prevents the delegate from running when the " +
                            "decision is DENY, when human approval is required, or when governance is unavailable.",
                    preventionProof =
                        "GovernanceBoundaryTest: a denied action is never invoked; an action requiring approval " +
                            "cannot execute without one; an unavailable authority prevents execution.",
                ),
        )

    /**
     * Resolves a declared boundary identifier to its registered contract.
     *
     * The applicability check is part of registration, not an extra: a boundary registered for one
     * enforcement point cannot claim strength at another, so the identifier and the point the record
     * actually carries must agree.
     */
    fun resolve(
        boundaryId: String,
        enforcementPoint: String,
    ): RegisteredBoundary {
        val boundary =
            registered[boundaryId]
                ?: reject("unregistered enforcement boundary '$boundaryId'")
        require(boundary.enforcementPoint == enforcementPoint) {
            "boundary '${boundary.id}' is registered for ${boundary.enforcementPoint} but the record " +
                "enforces at $enforcementPoint; refusing to apply a contract to an enforcement point it does not cover"
        }
        return boundary
    }

    /**
     * Builds the framework-owned provenance for a declared boundary, or an empty map for a producer
     * that declares none (legacy evidence).
     *
     * A caller-supplied strength is refused here, before any derivation, so a self-asserted claim can
     * never reach evidence even transiently.
     */
    fun provenanceFor(
        eventMetadata: Map<String, String>,
        enforcementPoint: String,
    ): Map<String, String> {
        if (eventMetadata.containsKey(STRENGTH_KEY)) {
            reject("a caller-supplied $STRENGTH_KEY is a self-asserted claim; strength is framework-derived")
        }
        val boundaryId = eventMetadata[ID_KEY] ?: return emptyMap()
        val boundary = resolve(boundaryId, enforcementPoint)
        return mapOf(ID_KEY to boundary.id, STRENGTH_KEY to boundary.strength)
    }

    /**
     * Validates provenance on a complete record, atomically and against the registration.
     *
     * Zero keys is legacy evidence and stays valid; one key is corruption; both keys must name a
     * registered boundary whose declared enforcement point matches the record and whose registered
     * strength is the one the record carries.
     */
    fun validate(
        enforcementPoint: String,
        metadata: Map<String, String>,
    ) {
        val present = metadata.keys.filter { it in metadataKeys }
        when {
            present.isEmpty() -> {
                return
            }

            present.size != metadataKeys.size -> {
                reject("partial enforcement-boundary provenance: ${present.sorted()}")
            }

            else -> {
                val boundaryId = metadata.getValue(ID_KEY)
                val strength = metadata.getValue(STRENGTH_KEY)
                require(strength in strengths) {
                    "unknown enforcement strength '$strength'; the vocabulary is ${strengths.sorted()}"
                }
                val boundary = resolve(boundaryId, enforcementPoint)
                require(boundary.strength == strength) {
                    "boundary '${boundary.id}' is registered at ${boundary.strength} and cannot claim " +
                        "'$strength'; a boundary may not exceed the strength its registration grants"
                }
            }
        }
    }

    /**
     * A registered boundary and the contract backing its strength claim.
     *
     * [preventionProof] names the tests that establish prevention for this boundary, so the strength
     * claim is traceable to evidence rather than to an assertion about the registration.
     */
    internal data class RegisteredBoundary(
        val id: String,
        val enforcementPoint: String,
        val strength: String,
        val contract: String,
        val preventionProof: String,
    )

    private fun reject(cause: String): Nothing = error("$cause; refusing to emit unestablished enforcement strength")
}
