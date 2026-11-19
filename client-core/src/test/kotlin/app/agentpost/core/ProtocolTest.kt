package app.agentpost.core

import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ProtocolTest {
    private val codec = MailCodec("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    private val fixture = "ICEiIyQlJicoKSorqRjPFE6iOD4qTHL-8SjE1OB53KyqtFPeXNtUInm6czlFvtU0vhUF6iTNXrB0K2y8g02JrKAeSQ6gB10xbtZrqbkE93C05IDl-AOGSch_AzqI6k4NgYOXYY6M7pZ00UsV5fx9"

    @Test
    fun decryptsIndependentAesGcmFixture() {
        val response = codec.openResponse(fixture)
        assertEquals("AgentPost v1 response 630dcd2966c4336691125448", codec.subject("response"))
        assertEquals("00000000-0000-4000-8000-000000000000", response.id)
        assertEquals(SessionPage(emptyList()), response.result<SessionPage>())
    }

    @Test
    fun rejectsModifiedCiphertextAndUsesFreshNonces() {
        val bytes = Base64.getUrlDecoder().decode(fixture)
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        val modified = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertFailsWith<AEADBadTagException> { codec.openResponse(modified) }
        val request = Request("list")
        val first = Base64.getUrlDecoder().decode(codec.seal(request)).take(12)
        val second = Base64.getUrlDecoder().decode(codec.seal(request)).take(12)
        assertNotEquals(first, second)
    }
}
