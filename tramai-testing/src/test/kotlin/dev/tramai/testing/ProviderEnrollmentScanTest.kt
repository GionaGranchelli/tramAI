package dev.tramai.testing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression tests for the provider enrollment scanner.
 *
 * The first two cases are the observed defect: a KDoc/line comment mentioning
 * `ModelProvider` was read as an implemented interface, so the gate failed naming an
 * unrelated declaration.
 *
 * The shape matters and is reproduced faithfully here. The rule reads from a `class`
 * /`object` keyword to the next `{` **anywhere in the file**, so a comment only lands
 * inside a declaration's "header" when that declaration has **no body brace** — and the
 * span only becomes a non-empty supertype section when the comment contains a **depth-0
 * `:`**. A class with a body brace puts its `{` before the comment, the span stops there,
 * and the defect cannot occur. Both cases below therefore fail against the pre-fix
 * scanner and pass with comment stripping; a test written with a body brace would pass
 * either way and prove nothing.
 */
class ProviderEnrollmentScanTest {
    @Test
    fun `a KDoc comment does not enroll a preceding brace-less declaration`() {
        val source =
            """
            package example

            @JvmInline
            value class AuthorizedCandidates internal constructor(
                val candidates: Set<Int>,
            )

            /**
             * What the boundary guarantees:
             *
             * authorization:  may TramAI use this candidate?
             * viability:      can TramAI use it right now?
             *
             * capability comes from `ModelProvider.supportsCapability(...)`.
             */
            class CandidateViability(
                private val runtimeConstraintRefusal: (Int) -> Int? = { null },
            ) {
                fun decisions() = 1
            }
            """.trimIndent()

        // Before the fix this returned ["AuthorizedCandidates"].
        assertThat(ProviderEnrollmentScan.modelProviderImplementations(source)).isEmpty()
    }

    @Test
    fun `a line comment does not enroll a preceding brace-less declaration`() {
        val source =
            """
            package example

            class Innocent(
                val size: Int = 0,
            )

            // note: this is not a ModelProvider; see the capability section:
            class Other(
                val label: String = "x",
            ) {
                fun use() = label
            }
            """.trimIndent()

        // Same shape requirement: Innocent has no body brace, so the span runs to Other's
        // `{`, and the comment's depth-0 `:` makes the supertype section non-empty. Without
        // the colon this case is not red at all — the shape is the reproducer.
        assertThat(ProviderEnrollmentScan.modelProviderImplementations(source)).isEmpty()
    }

    @Test
    fun `a real implementation is still reported`() {
        val source =
            """
            package example

            class Real(val id: String) : ModelProvider {
                override fun id() = id
            }
            """.trimIndent()

        assertThat(ProviderEnrollmentScan.modelProviderImplementations(source)).containsExactly("Real")
    }

    @Test
    fun `a multi-supertype declaration is still reported`() {
        val source =
            """
            package example

            class Real(
                val id: String,
            ) : ModelProvider,
                SomeBase(id) {
                override fun id() = id
            }
            """.trimIndent()

        assertThat(ProviderEnrollmentScan.modelProviderImplementations(source)).containsExactly("Real")
    }

    @Test
    fun `a constructor parameter of type ModelProvider is not an implementation`() {
        val source =
            """
            package example

            class Wrapper(val delegate: ModelProvider) {
                fun delegateTo() = delegate
            }
            """.trimIndent()

        assertThat(ProviderEnrollmentScan.modelProviderImplementations(source)).isEmpty()
    }

    @Test
    fun `an object implementing ModelProvider is still reported`() {
        val source =
            """
            package example

            object Single : ModelProvider {
                override fun id() = "single"
            }
            """.trimIndent()

        assertThat(ProviderEnrollmentScan.modelProviderImplementations(source)).containsExactly("Single")
    }

    /**
     * Regression test for the silent-miss shape: a textual comment strip truncates a header at the
     * `//` inside a URL, deleting the closing `)`, the `: ModelProvider` and the `{`, so the
     * provider is not reported — a quiet pass on a gate, which is the one failure mode that must
     * not exist. Covers a regular string and a raw string.
     */
    @Test
    fun `a line-comment marker inside a string does not hide a real implementation`() {
        val source =
            """
            package example

            class Http(val url: String = "http://localhost:11434") : ModelProvider {
                override fun id() = "http"
            }

            class Raw : ModelProvider {
                override fun id() = "raw"

                val doc = ${"\"\"\""}
                // not a comment, this is raw string content
                ${"\"\"\""}.trimIndent()
            }
            """.trimIndent()

        assertThat(ProviderEnrollmentScan.modelProviderImplementations(source))
            .containsExactly("Http", "Raw")
    }

    /**
     * The invariant is that nothing which is not code contributes structure to the heuristic —
     * not merely that comment markers inside literals are ignored. Each of these literals would
     * otherwise move where the header scan stops (`{`) or where the supertype section begins
     * (`(`, `:`), silently dropping a real implementation.
     */
    @Test
    fun `literal contents cannot shift the header scan or the supertype section`() {
        val cases =
            listOf(
                "Url" to "class Url(val x: String = \"http://host\") : ModelProvider {}",
                "Brace" to "class Brace(val x: String = \"{\") : ModelProvider {}",
                "Paren" to "class Paren(val x: String = \"(\") : ModelProvider {}",
                "Colon" to "class Colon(val x: String = \":\") : ModelProvider {}",
            )

        cases.forEach { (name, source) ->
            assertThat(ProviderEnrollmentScan.modelProviderImplementations(source))
                .describedAs("literal contents shifted the scan for %s: %s", name, source)
                .containsExactly(name)
        }
    }

    /** A literal that looks like a declaration must not invent one. */
    @Test
    fun `a declaration-looking string does not invent an implementation`() {
        val source =
            """
            package example

            val text = "class Fake : ModelProvider {"

            class Real : ModelProvider {
                override fun id() = "real"
            }
            """.trimIndent()

        assertThat(ProviderEnrollmentScan.modelProviderImplementations(source)).containsExactly("Real")
    }

    /**
     * Kotlin block comments nest, so masking must be depth-aware: a declaration written between an
     * inner and an outer close is still comment text. Masking to the first terminator leaves it in
     * code position and the scanner reports it.
     */
    @Test
    fun `a declaration between nested block-comment closes is not reported`() {
        val source =
            """
            package example

            /* outer
               /* inner */
               class Fake : ModelProvider {
            */
            class Real : ModelProvider {}
            """.trimIndent()

        assertThat(ProviderEnrollmentScan.modelProviderImplementations(source)).containsExactly("Real")
    }

    /**
     * A `${...}` interpolation may contain nested string literals. Masking must locate the real end
     * of the outer string, or the nested `{` / `)` leaks into structural scanning and the
     * implementation is silently missed — the failure mode this gate must not have.
     */
    @Test
    fun `string templates with nested strings do not leak structure`() {
        val cases =
            listOf(
                "Brace" to "class Brace(val x: String = \"\${foo(\"{\")}\") : ModelProvider {}",
                "Paren" to "class Paren(val x: String = \"\${foo(\")\")}\") : ModelProvider {}",
            )

        cases.forEach { (name, source) ->
            assertThat(ProviderEnrollmentScan.modelProviderImplementations(source))
                .describedAs("a nested string in a template leaked structure for %s: %s", name, source)
                .containsExactly(name)
        }
    }
}
