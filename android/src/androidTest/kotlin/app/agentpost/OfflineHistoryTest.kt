package app.agentpost

import android.app.Application
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.agentpost.core.History
import app.agentpost.core.HostCache
import app.agentpost.core.Message
import app.agentpost.core.SessionPage
import app.agentpost.core.SessionSummary
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineHistoryTest {
    @Test
    fun restoresAnEncryptedHistoryAndOpensItWithoutAnEmailRequest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val vault = ProfileVault(app)
        assumeTrue("Use an unpaired test installation", vault.read() == null)
        val profile = HostProfile(
            "Fixture host", "device@example.invalid", "fixture", "bridge@example.invalid",
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            imap = "mail.example.invalid", smtp = "mail.example.invalid",
        )
        val scope = MailConnection(profile).cacheScope
        val thread = SessionSummary("fixture-thread", "Offline fixture")
        val history = History(listOf(Message("answer", "assistant", "The saved response.")), "older")
        try {
            vault.save(profile)
            vault.saveCache(scope, HostCache(SessionPage(listOf(thread))).withHistory(thread.id, history))
            assertNull(vault.readCache(MailConnection(profile.copy(bridge = "other@example.invalid")).cacheScope))
            assertEquals(history, ProfileVault(app).readCache(scope)?.histories?.get(thread.id))

            lateinit var model: AgentPostModel
            instrumentation.runOnMainSync { model = AgentPostModel(app) }
            val deadline = SystemClock.uptimeMillis() + 5000
            var restored = false
            while (!restored && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { restored = model.listFromCache && model.busy == null }
                if (!restored) SystemClock.sleep(25)
            }
            assertTrue("The list should load before any mailbox request", restored)

            instrumentation.runOnMainSync {
                repeat(2) {
                    model.open(model.sessions.single())
                    assertNull("Cached history should not start a mailbox request", model.busy)
                    assertTrue(model.historyFromCache)
                    assertEquals(history.messages, model.messages)
                    assertEquals("older", model.historyCursor)
                    model.back()
                }
                model.disconnect()
            }
            assertNull(vault.readCache(scope))
        } finally {
            vault.clear()
        }
    }
}
