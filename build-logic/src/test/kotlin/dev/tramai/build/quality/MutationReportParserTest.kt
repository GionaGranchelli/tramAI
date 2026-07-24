package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MutationReportParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parses PIT XML and creates line independent identity`() {
        val parser = MutationReportParser()
        val first = parser.parse(":engine", "routing", xml(mutation(12))).mutants.single()
        val second = parser.parse(":engine", "routing", xml(mutation(99))).mutants.single()
        assertEquals("SURVIVED", first.status)
        assertEquals(first.identity, second.identity)
    }

    @Test
    fun `malformed report fails`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(":engine", "routing", xml("<mutations>"))
        }
    }

    @Test
    fun `absolute source path fails`() {
        assertFailsWith<GradleException> {
            MutationReportParser().parse(
                ":engine",
                "routing",
                xml(mutation(1).replace("Router.kt", "/home/user/Router.kt"))
            )
        }
    }

    private fun mutation(line: Int) =
        """
        <mutations>
          <mutation status="SURVIVED">
            <sourceFile>Router.kt</sourceFile>
            <mutatedClass>dev.tramai.Router</mutatedClass>
            <mutatedMethod>route</mutatedMethod>
            <lineNumber>$line</lineNumber>
            <mutator>org.pitest.mutationtest.engine.gregor.mutators.BooleanFalseReturnValsMutator</mutator>
            <description>replaced boolean return with false</description>
          </mutation>
        </mutations>
        """.trimIndent()

    private fun xml(content: String) = tempDir.resolve("mutations.xml").toFile().apply { writeText(content) }
}
