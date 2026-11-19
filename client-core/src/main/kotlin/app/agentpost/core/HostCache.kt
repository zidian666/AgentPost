package app.agentpost.core

import kotlinx.serialization.Serializable

@Serializable
data class HostCache(
    val sessions: SessionPage? = null,
    val histories: Map<String, History> = emptyMap(),
) {
    fun withHistory(threadId: String, history: History): HostCache {
        val recent = linkedMapOf(threadId to history)
        for ((id, page) in histories) {
            if (recent.size == 10) break
            if (id != threadId) recent[id] = page
        }
        return copy(histories = recent)
    }

    fun encodeWithin(maxBytes: Int): ByteArray? {
        var snapshot = this
        while (true) {
            val bytes = wireJson.encodeToString(serializer(), snapshot).encodeToByteArray()
            if (bytes.size <= maxBytes) return bytes
            // Keep whole histories so the oldest remaining message still matches its pagination cursor.
            val oldest = snapshot.histories.keys.lastOrNull() ?: return null
            snapshot = snapshot.copy(histories = snapshot.histories - oldest)
        }
    }
}
