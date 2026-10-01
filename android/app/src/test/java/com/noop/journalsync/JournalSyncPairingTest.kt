package com.noop.journalsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the clipboard pairing parser (spec §10). Values must never be logged. */
class JournalSyncPairingTest {
    @Test
    fun `valid json parses all four fields`() {
        val cfg = JournalSyncPairing.parse(
            "{\"url\": \"https://example.com\", \"token\": \"t0k\", \"cf_id\": \"id1\", \"cf_secret\": \"sec1\"}",
        )!!
        assertEquals("https://example.com", cfg.url)
        assertEquals("t0k", cfg.token)
        assertEquals("id1", cfg.cfId)
        assertEquals("sec1", cfg.cfSecret)
    }

    @Test
    fun `cf fields are optional`() {
        val cfg = JournalSyncPairing.parse("{\"url\":\"https://example.com\",\"token\":\"t\"}")!!
        assertEquals("https://example.com", cfg.url)
        assertEquals("t", cfg.token)
        assertNull(cfg.cfId)
        assertNull(cfg.cfSecret)
    }

    @Test
    fun `missing token is an error`() {
        assertNull(JournalSyncPairing.parse("{\"url\":\"https://example.com\"}"))
    }

    @Test
    fun `missing url is an error`() {
        assertNull(JournalSyncPairing.parse("{\"token\":\"t\"}"))
    }

    @Test
    fun `garbage returns null without crashing`() {
        assertNull(JournalSyncPairing.parse(""))
        assertNull(JournalSyncPairing.parse("not json"))
        assertNull(JournalSyncPairing.parse("[]"))
        assertNull(JournalSyncPairing.parse("{}"))
        assertNull(JournalSyncPairing.parse("{\"url\":\"https://x\",\"token\":\"t\",}"))
        assertNull(JournalSyncPairing.parse("{\"url\":\"https://x\",\"token\":\"t\"} trailing"))
        assertNull(JournalSyncPairing.parse("{\"url\":[\"a\"],\"token\":\"t\"}"))
        assertNull(JournalSyncPairing.parse("{\"ur\\\"l\":\"x\",\"token\":\"t\"}"))
    }

    @Test
    fun `extra keys are ignored and strings are trimmed`() {
        val cfg = JournalSyncPairing.parse(
            "{\"extra\":\"x\",\"url\":\" https://example.com \",\"token\":\" t \",\"cf_id\":\"\"}",
        )!!
        assertEquals("https://example.com", cfg.url)
        assertEquals("t", cfg.token)
        assertNull(cfg.cfId)
    }
}
