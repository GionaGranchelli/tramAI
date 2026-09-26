package dev.tramai.build.quality

/**
 * Backward-compatibility comparison over BCV dump text, used for `stable` API modules.
 *
 * Stable is a compatibility promise, not an immutability promise: an existing consumer must keep
 * working against a newer release. Additions therefore pass, while removals, signature changes and
 * visibility reductions fail. Contract-2 previously equated any dump inequality with instability,
 * which made a legitimate additive stable release impossible — and, once a new release line grows
 * its stable API, unsatisfiable by construction.
 *
 * ponytail: declaration-identity comparison rather than full ABI analysis. A class's declaration
 * head (visibility/kind/name) must be preserved and its supertype set may only grow; members are
 * matched by exact dump line, so a *compatible* signature change (e.g. a widened return type) is
 * reported as a removal. That is the fail-closed direction for a stability promise. Upgrade path:
 * a BCV-backed signature comparison, if stable modules ever need to accept such transitions.
 */
object ApiDumpCompatibility {
    /**
     * Why [current] is not backward compatible with [base], one entry per broken declaration.
     * An empty result means every declaration present at [base] still exists at [current].
     */
    fun incompatibleDeclarations(
        base: String,
        current: String,
    ): List<String> {
        val baseClasses = parse(base)
        val currentClasses = parse(current)
        val breakages = mutableListOf<String>()

        baseClasses.forEach { (key, baseClass) ->
            val currentClass = currentClasses[key]
            if (currentClass == null) {
                breakages += "class $key was removed"
                return@forEach
            }
            if (currentClass.head != baseClass.head) {
                breakages += "class $key changed declaration head: '${baseClass.head}' -> '${currentClass.head}'"
            }
            val droppedSupertypes = (baseClass.supertypes - currentClass.supertypes).sorted()
            if (droppedSupertypes.isNotEmpty()) {
                breakages += "class $key dropped supertype(s): ${droppedSupertypes.joinToString(", ")}"
            }
            (baseClass.members - currentClass.members).sorted().forEach { member ->
                breakages += "member '$member' of $key was removed or changed"
            }
        }
        return breakages
    }

    private data class ClassApi(
        val head: String,
        val supertypes: Set<String>,
        val members: Set<String>,
    )

    /**
     * Index every class of a dump by its descriptor, with the body of its block as member lines.
     * Blocks are tracked with a stack: a header pushes, a lone `}` pops, so nested classes and
     * their bodies are attributed to the class that owns them and compare like-for-like.
     */
    private fun parse(dump: String): Map<String, ClassApi> {
        val classes = linkedMapOf<String, ClassApi>()
        val open = ArrayDeque<String>()
        val members = mutableMapOf<String, MutableSet<String>>()

        dump.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isBlank()) return@forEach
            if (line.trim() == "}") {
                if (open.isNotEmpty()) open.removeLast()
                return@forEach
            }
            val parsed = HEADER.find(line)?.destructured
            if (parsed != null && parsed.component3().contains('/')) {
                val descriptor = parsed.component3()
                classes[descriptor] =
                    ClassApi(
                        head = "${parsed.component1()}${parsed.component2()}".trim(),
                        supertypes =
                            parsed
                                .component5()
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet(),
                        members = emptySet(),
                    )
                members[descriptor] = linkedSetOf()
                open.addLast(descriptor)
                return@forEach
            }
            open.lastOrNull()?.let { owner -> members[owner]?.add(line.trim()) }
        }

        return classes.mapValues { (key, api) -> api.copy(members = (members[key] ?: emptySet()).toSet()) }
    }

    /** `public final class dev/tramai/core/Foo : super, iface {` → head, kind, descriptor, supertypes. */
    private val HEADER = Regex("""^\s*(.*?)\b(class|interface|object)\s+(\S+?)(\s*:\s*(.*?))?\s*\{\s*$""")
}
