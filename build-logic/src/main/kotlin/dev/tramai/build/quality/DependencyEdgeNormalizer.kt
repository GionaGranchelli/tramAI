package dev.tramai.build.quality

object DependencyEdgeNormalizer {
    fun normalize(records: List<ResolvedDependency>): List<ResolvedDependency> =
        BaselineGenerator.sortResolvedDependencies(
            records.distinctBy(::edgeIdentity)
        )

    private fun edgeIdentity(record: ResolvedDependency): List<Any?> {
        val parent = record.dependencyPath
            .dropLast(1)
            .lastOrNull()
            .orEmpty()
        return listOf(
            record.consumers.joinToString(","),
            record.configuration,
            parent,
            record.group,
            record.artifact,
            record.requestedVersion,
            record.selectedVersion,
            record.direct,
            record.selectionReason
        )
    }
}
