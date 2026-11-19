package app.agentpost.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HostCacheTest {
    @Test
    fun preservesEmptyAndPagedHistoriesOnDisk() {
        val first = History(listOf(Message("message", "assistant", "历史回复")), "older")
        val original = HostCache(SessionPage(emptyList(), "list-next"))
            .withHistory("first", first)
            .withHistory("empty-thread", History(emptyList()))

        val bytes = assertNotNull(original.encodeWithin(2048))
        assertEquals(original, wireJson.decodeFromString(HostCache.serializer(), bytes.decodeToString()))
    }

    @Test
    fun evictsAnEntireOlderHistoryWithoutChangingTheNewestCursor() {
        val first = History(listOf(Message("a", "assistant", "older response".repeat(100))), "cursor-a")
        val newest = History(listOf(Message("b", "assistant", "current")), "cursor-b")
        val cache = HostCache().withHistory("a", first).withHistory("b", newest)

        val bytes = assertNotNull(cache.encodeWithin(600))
        val restored = wireJson.decodeFromString(HostCache.serializer(), bytes.decodeToString())
        assertEquals(mapOf("b" to newest), restored.histories)
    }
}
