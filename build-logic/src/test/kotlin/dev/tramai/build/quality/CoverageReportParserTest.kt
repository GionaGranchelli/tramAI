package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoverageReportParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parses root line and branch counters`() {
        val result = CoverageReportParser().parse(":core", xml(
            """<report name="core"><counter type="LINE" missed="2" covered="8"/><counter type="BRANCH" missed="3" covered="7"/></report>"""
        ))
        assertEquals(8, result.linesCovered)
        assertEquals(2, result.linesMissed)
        assertEquals(7, result.branchesCovered)
        assertEquals(70.0, result.branchCoverage)
    }

    @Test
    fun `missing branch counter produces zero branch coverage`() {
        val result = CoverageReportParser().parse(":core", xml(
            """<report name="core"><counter type="LINE" missed="2" covered="8"/></report>"""
        ))
        assertEquals(0, result.branchesCovered)
        assertEquals(0, result.branchesMissed)
        assertEquals(0.0, result.branchCoverage)
    }

    @Test
    fun `missing line counter produces zero line coverage`() {
        val result = CoverageReportParser().parse(":core", xml(
            """<report name="core"><counter type="BRANCH" missed="3" covered="7"/></report>"""
        ))
        assertEquals(0, result.linesCovered)
        assertEquals(0, result.linesMissed)
        assertEquals(0.0, result.lineCoverage)
        // Branch coverage is still parsed when present
        assertEquals(7, result.branchesCovered)
    }

    @Test
    fun `malformed XML fails`() {
        assertFailsWith<GradleException> {
            CoverageReportParser().parse(":core", xml("<report>"))
        }
    }

    @Test
    fun `absolute path leak fails`() {
        assertFailsWith<GradleException> {
            CoverageReportParser().parse(":core", xml(
                """<report name="/home/user/core"><counter type="LINE" missed="2" covered="8"/><counter type="BRANCH" missed="3" covered="7"/></report>"""
            ))
        }
    }

    private fun xml(content: String) = tempDir.resolve("jacoco.xml").toFile().apply { writeText(content) }
}
