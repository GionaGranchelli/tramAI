package dev.tramai.persistence.file

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals

class StoreManifestV1Test {

    @Test
    fun `toJson produces valid JSON`() {
        val manifest = StoreManifestV1(
            formatVersion = 1,
            module = "tramai-persistence-file",
            createdAt = "2025-01-15T10:30:00Z",
        )
        val json = manifest.toJson()

        // Jackson's writeValueAsString produces compact JSON (no spaces after colons/commas)
        assertContains(json, "\"formatVersion\":1")
        assertContains(json, "\"module\":\"tramai-persistence-file\"")
        assertContains(json, "\"createdAt\":\"2025-01-15T10:30:00Z\"")
        assertContains(json, "{")
        assertContains(json, "}")
    }

    @Test
    fun `fromJson round-trips`() {
        val original = StoreManifestV1(
            formatVersion = 1,
            module = "tramai-persistence-file",
            createdAt = "2025-06-01T12:00:00Z",
        )
        val json = original.toJson()
        val restored = StoreManifestV1.fromJson(json)

        assertEquals(original.formatVersion, restored.formatVersion)
        assertEquals(original.module, restored.module)
        assertEquals(original.createdAt, restored.createdAt)
    }

    @Test
    fun `fromJson rejects invalid format`() {
        // Empty JSON — missing required createdAt field
        assertThrows<IllegalArgumentException> {
            StoreManifestV1.fromJson("{}")
        }

        // Missing required createdAt field
        assertThrows<IllegalArgumentException> {
            StoreManifestV1.fromJson("""{"formatVersion": 1}""")
        }

        // Not a JSON object (array)
        assertThrows<IllegalArgumentException> {
            StoreManifestV1.fromJson("[]")
        }

        // Malformed JSON
        assertThrows<IllegalArgumentException> {
            StoreManifestV1.fromJson("not-json")
        }

        // Wrong field types
        assertThrows<IllegalArgumentException> {
            StoreManifestV1.fromJson("""{"formatVersion": "one", "module": "test", "createdAt": "now"}""")
        }
    }
}
