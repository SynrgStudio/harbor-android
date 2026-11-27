package com.synrg.harbor.stremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors from stremio-core's own tests (stremio-watched-bitfield), so both read the same values. */
class WatchedBitfieldTest {
    private val nine = (1..9).map { "tt2934286:1:$it" }

    @Test
    fun readsStremioValue() {
        val wb = WatchedBitfield.parse("tt2934286:1:5:5:eJyTZwAAAEAAIA==", nine)
        assertTrue(wb["tt2934286:1:5"])
        assertFalse(wb["tt2934286:1:6"])
        // Writing it back gives a value Stremio reads the same way.
        val again = WatchedBitfield.parse(wb.serialize(), nine)
        assertEquals(wb.watched, again.watched)
        assertTrue(wb.serialize().startsWith("tt2934286:1:5:5:"))
    }

    @Test
    fun emptyMatchesStremio() {
        assertEquals("undefined:1:eJwDAAAAAAE=", WatchedBitfield.empty(emptyList()).serialize())
    }

    @Test
    fun roundTripsManyVideos() {
        val ids = (1 until 500).map { "tt2934286:1:$it" }
        val wb = WatchedBitfield.empty(ids)
        ids.forEachIndexed { i, id -> wb[id] = i % 2 == 0 }
        val back = WatchedBitfield.parse(wb.serialize(), ids)
        ids.forEachIndexed { i, id -> assertEquals(i % 2 == 0, back[id]) }
    }

    @Test
    fun linesUpWhenEpisodesAreAddedBefore() {
        // Watched 1x1..1x3 when the list started at 1x1; now two specials come first.
        val before = (1..5).map { "tt1:1:$it" }
        val wb = WatchedBitfield.empty(before)
        (1..3).forEach { wb["tt1:1:$it"] = true }
        val after = listOf("tt1:0:1", "tt1:0:2") + before
        val moved = WatchedBitfield.parse(wb.serialize(), after)
        assertEquals(setOf("tt1:1:1", "tt1:1:2", "tt1:1:3"), moved.watched)
    }

    @Test
    fun unknownAnchorMeansNothingWatched() {
        val wb = WatchedBitfield.parse("tt9:1:1:1:eJyTZwAAAEAAIA==", nine)
        assertTrue(wb.watched.isEmpty())
    }
}
