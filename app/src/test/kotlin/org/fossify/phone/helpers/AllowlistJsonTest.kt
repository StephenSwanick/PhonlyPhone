package org.fossify.phone.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistJsonTest {
    @Test
    fun missingOrBlankIsMissing() {
        assertTrue(AllowlistJson.parse(null) is AllowlistContactParse.Missing)
        assertTrue(AllowlistJson.parse("") is AllowlistContactParse.Missing)
        assertTrue(AllowlistJson.parse("   ") is AllowlistContactParse.Missing)
    }

    @Test
    fun invalidJsonIsInvalid() {
        assertTrue(AllowlistJson.parse("{not-an-array}") is AllowlistContactParse.Invalid)
        assertTrue(AllowlistJson.parse("not json") is AllowlistContactParse.Invalid)
    }

    @Test
    fun emptyArrayAppliesEmpty() {
        val parsed = AllowlistJson.parse("[]") as AllowlistContactParse.Apply
        assertEquals(0, parsed.entries.size)
    }

    @Test
    fun readsE164AndLabel() {
        val parsed = AllowlistJson.parse(
            """[{"e164":"+17046180435","label":"Test","voice":true,"sms":true}]""",
        ) as AllowlistContactParse.Apply
        assertEquals(1, parsed.entries.size)
        assertEquals("+17046180435", parsed.entries[0].e164)
        assertEquals("Test", parsed.entries[0].label)
    }

    @Test
    fun missingLabelUsesE164() {
        val parsed = AllowlistJson.parse("""[{"e164":"+17046180435"}]""") as AllowlistContactParse.Apply
        assertEquals("+17046180435", parsed.entries[0].label)
    }

    @Test
    fun duplicateNumbersKeepFirst() {
        val parsed = AllowlistJson.parse(
            """[{"e164":"+17046180435","label":"A"},{"e164":"7046180435","label":"B"}]""",
        ) as AllowlistContactParse.Apply
        assertEquals(1, parsed.entries.size)
        assertEquals("A", parsed.entries[0].label)
    }

    @Test
    fun mongoObjectUsesEntries() {
        val parsed = AllowlistJson.parse(
            """{"enabled":true,"updatedAt":"2026-08-30T17:30:26.449Z","entries":[{"e164":"+17046180435","label":"Test","voice":true,"sms":true}]}""",
        ) as AllowlistContactParse.Apply
        assertEquals(1, parsed.entries.size)
        assertEquals("Test", parsed.entries[0].label)
        assertEquals("+17046180435", parsed.entries[0].e164)
    }

    @Test
    fun mongoObjectEmptyEntries() {
        val parsed = AllowlistJson.parse(
            """{"enabled":true,"updatedAt":"2026-08-30T17:30:26.449Z","entries":[]}""",
        ) as AllowlistContactParse.Apply
        assertEquals(0, parsed.entries.size)
    }

    @Test
    fun rawFromValuesPrefersAllowlistKey() {
        val raw = AllowlistJson.rawFromValues(
            mapOf(
                "allowlist_enabled" to "true",
                "allowlist_json" to """[{"e164":"+17046180435","label":"Stephen"}]""",
            ),
        )
        assertEquals("""[{"e164":"+17046180435","label":"Stephen"}]""", raw)
    }

    @Test
    fun rawFromValuesFindsArrayOnOtherKey() {
        val raw = AllowlistJson.rawFromValues(
            mapOf("payload" to """[{"e164":"+17046180435","label":"Stephen"}]"""),
        )
        assertEquals("""[{"e164":"+17046180435","label":"Stephen"}]""", raw)
    }

    @Test
    fun rawFromValuesEmptyIsNull() {
        assertEquals(null, AllowlistJson.rawFromValues(emptyMap()))
        assertEquals(null, AllowlistJson.rawFromValues(mapOf("allowlist_json" to "  ")))
    }
}
