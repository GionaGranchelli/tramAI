package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MutationEvolutionLoaderTest {
    @Test
    fun `missing file is empty`(
        @TempDir root: File,
    ) {
        assertEquals(emptyList(), MutationEvolutionLoader.load(root).records)
    }

    @Test
    fun `empty records list is empty`(
        @TempDir root: File,
    ) {
        write(root, "schemaVersion: \"1\"\nrecords: []")
        assertEquals(emptyList(), MutationEvolutionLoader.load(root).records)
    }

    @Test
    fun `from base sha is loaded`(
        @TempDir root: File,
    ) {
        write(
            root,
            """
            schemaVersion: "1"
            records:
              - id: id
                fromBaseSha: base
                reason: reason
            """.trimIndent(),
        )
        assertEquals(
            "base",
            MutationEvolutionLoader
                .load(root)
                .records
                .single()
                .fromBaseSha,
        )
    }

    @Test
    fun `missing schema version fails`(
        @TempDir root: File,
    ) {
        write(root, "records: []")
        assertFailsWith<GradleException> { MutationEvolutionLoader.load(root) }
    }

    @Test
    fun `malformed yaml fails`(
        @TempDir root: File,
    ) {
        write(root, "schemaVersion: [")
        assertFailsWith<GradleException> { MutationEvolutionLoader.load(root) }
    }

    @Test
    fun `blank id reason or base sha fails`(
        @TempDir root: File,
    ) {
        write(root, "schemaVersion: \"1\"\nrecords:\n  - id: \" \"\n    reason: ok")
        assertFailsWith<GradleException> { MutationEvolutionLoader.load(root) }
        write(root, "schemaVersion: \"1\"\nrecords:\n  - id: id\n    fromBaseSha: \" \"\n    reason: ok")
        assertFailsWith<GradleException> { MutationEvolutionLoader.load(root) }
        write(root, "schemaVersion: \"1\"\nrecords:\n  - id: id\n    reason: \" \"")
        assertFailsWith<GradleException> { MutationEvolutionLoader.load(root) }
    }

    @Test
    fun `duplicate ids fail`(
        @TempDir root: File,
    ) {
        write(
            root,
            """
            schemaVersion: "1"
            records:
              - id: same
                reason: one
              - id: same
                reason: two
            """.trimIndent(),
        )
        assertFailsWith<GradleException> { MutationEvolutionLoader.load(root) }
    }

    @Test
    fun `property defaults and rejects unknown values`() {
        assertEquals(MutationPopulationEvolution.FORBID, MutationPopulationEvolution.fromProperty(null))
        assertEquals(MutationPopulationEvolution.FORBID, MutationPopulationEvolution.fromProperty(""))
        assertFailsWith<GradleException> { MutationPopulationEvolution.fromProperty("future") }
    }

    private fun write(
        root: File,
        content: String,
    ) {
        File(root, "config/quality/mutation-evolution.yml").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}
