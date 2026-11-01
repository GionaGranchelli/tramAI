package dev.tramai.security.evidence

/**
 * 0.7.6g1: registered enforcement-boundary provenance at the evidence boundary, split into two facts.
 *
 * Authorization and enforcement are different facts, and so are **strength** and **origin**:
 *
 * - [STRENGTH_KEY] says what a registration **entitles** a boundary to claim.
 * - [ORIGIN_KEY] says **who established** that this particular event came from that boundary.
 *
 * Conflating them is how a declared identifier came to read as proven prevention. This object keeps
 * them apart, and the split is enforced on both sides.
 *
 * ## The data path can only declare
 *
 * [provenanceFor] sees event metadata, not the boundary. Given a registered identifier it emits
 * [DECLARED] with [ORIGIN_DECLARED] -- never the registration's entitlement, which only an attester may
 * spend, and no attester exists in this runtime yet:
 *
 * - a caller-supplied [STRENGTH_KEY] is refused as self-assertion;
 * - a caller-supplied [ORIGIN_KEY] is refused for the same reason, in either direction, because an
 *   origin a producer can write is an origin a producer can forge;
 * - an unregistered identifier is refused rather than downgraded: an identifier no contract covers
 *   cannot be validated against anything.
 *
 * [DECLARED] is deliberately not [OBSERVED]. Observation asserts the boundary behaved; a declaration
 * asserts only that a producer named an identifier. Unknown identifiers, a strength above the
 * declaration, and an `attested` origin are refusals, not capped evidence.
 *
 * ## What this does not prove
 *
 * A record's `declared` origin is true of a genuine boundary event and of a fabricated one alike. This
 * object removes the false positive (fabrication can no longer read as authoritative enforcement); it
 * does not yet add the true positive. Verified origin attribution needs a capability minted by the
 * framework at wiring and held by the governed boundary (0.7.6g Slice 2), and out-of-process custody
 * when evidence crosses a deployment boundary. Pinned by
 * `ToolPermissionBoundaryProvenanceTest.a fabricated event naming a registered boundary cannot claim
 * authoritative enforcement`.
 *
 * The in-process ceiling is part of the claim: any code able to reach the boundary may attempt a call,
 * and hostile code with unrestricted reflection can forge process-internal state. This is an authority
 * statement, not cryptographic authentication.
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

    /**
     * A producer **declared** this boundary. Nothing establishes that the boundary participated in the
     * request, observed it, or prevented anything -- only that a producer named an identifier. This is
     * the strength the data-only export path emits, and it is deliberately *not* [OBSERVED]: observation
     * is a claim about the boundary's own behaviour, and a declaration makes no such claim.
     */
    const val DECLARED = "DECLARED"

    const val ID_KEY = "boundary.id"
    const val STRENGTH_KEY = "boundary.enforcementStrength"

    /**
     * The attribution axis, kept separate from strength on purpose: strength says what a registration
     * grants, origin says who established that *this* event came from that boundary. Conflating them is
     * how a declared identifier came to read as enforced behaviour.
     */
    const val ORIGIN_KEY = "boundary.origin"
    const val ORIGIN_DECLARED = "declared"
    const val ORIGIN_ATTESTED = "attested"

    /**
     * The strength vocabulary is closed. A value outside it is not a weaker claim that can be capped;
     * it is unparseable provenance and is refused by [validate].
     */
    val strengths = setOf(AUTHORITATIVE, INSTRUMENTED, OBSERVED, IMPORTED, DECLARED)

    /** Closed origin vocabulary. `attested` is reserved: nothing in this runtime may mint it yet. */
    val origins = setOf(ORIGIN_DECLARED, ORIGIN_ATTESTED)

    /** Reserved keys, validated atomically: zero is legacy evidence, exactly three is governed provenance. */
    val metadataKeys = setOf(ID_KEY, STRENGTH_KEY, ORIGIN_KEY)

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
        if (eventMetadata.containsKey(ORIGIN_KEY)) {
            reject(
                "a caller-supplied $ORIGIN_KEY is a self-asserted origin; origin is established by the " +
                    "attester that holds the boundary's authority, never by the event",
            )
        }
        val boundaryId = eventMetadata[ID_KEY] ?: return emptyMap()
        val boundary = resolve(boundaryId, enforcementPoint)
        // The registration's strength is an ENTITLEMENT, not a finding about this event: this path sees
        // data, not the boundary. Emitting [DECLARED] is the strongest true statement available here.
        return mapOf(
            ID_KEY to boundary.id,
            STRENGTH_KEY to DECLARED,
            ORIGIN_KEY to ORIGIN_DECLARED,
        )
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
                val origin = metadata.getValue(ORIGIN_KEY)
                require(strength in strengths) {
                    "unknown enforcement strength '$strength'; the vocabulary is ${strengths.sorted()}"
                }
                require(origin in origins) {
                    "unknown enforcement origin '$origin'; the vocabulary is ${origins.sorted()}"
                }
                require(origin != ORIGIN_ATTESTED) {
                    "boundary origin '$ORIGIN_ATTESTED' has no attester in this runtime: verified origin " +
                        "attribution is not implemented, so no record may claim it"
                }
                val boundary = resolve(boundaryId, enforcementPoint)
                require(strength == DECLARED) {
                    "boundary '${boundary.id}' carries declared origin and cannot claim '$strength'; a " +
                        "declaration establishes neither participation nor prevention, and the registered " +
                        "entitlement ${boundary.strength} requires an attester"
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
