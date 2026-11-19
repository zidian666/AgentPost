package app.agentpost.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
const val SUBJECT_PREFIX = "AgentPost v1"

@Serializable
data class Request(
    val op: String,
    val threadId: String? = null,
    val cursor: String? = null,
    val text: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val sentAt: Long = System.currentTimeMillis() / 1000,
)

@Serializable
data class Response(
    val id: String,
    val stage: String,
    val data: JsonElement? = null,
    val error: String? = null,
    val uncertain: Boolean = false,
) {
    inline fun <reified T> result(): T = wireJson.decodeFromJsonElement(requireNotNull(data))
}

@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val preview: String = "",
    val cwd: String = "",
    val updatedAt: Long = 0,
    val status: String = "idle",
)

@Serializable
data class Message(val id: String, val role: String, val text: String)

@Serializable
data class SessionPage(val sessions: List<SessionSummary>, val nextCursor: String? = null)

@Serializable
data class History(val messages: List<Message>, val nextCursor: String? = null)

@Serializable
data class SendResult(val thread: SessionSummary, val history: History)

interface Connection {
    suspend fun call(request: Request, onAccepted: () -> Unit = {}): Response
    suspend fun await(request: Request, onAccepted: () -> Unit = {}): Response
}

class MailCodec(pairingKey: String) {
    private val decode = Base64.getUrlDecoder()
    private val encode = Base64.getUrlEncoder().withoutPadding()
    private val rawKey = decode.decode(pairingKey.trim()).also { require(it.size == 32) }
    val keyId: String = MessageDigest.getInstance("SHA-256").digest(rawKey)
        .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun subject(direction: String) = "$SUBJECT_PREFIX $direction $keyId"

    fun seal(request: Request): String {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, "request", nonce)
        val payload = wireJson.encodeToString(Request.serializer(), request).encodeToByteArray()
        return encode.encodeToString(nonce + cipher.doFinal(payload))
    }

    fun openResponse(body: String): Response {
        val bytes = decode.decode(body.trim())
        require(bytes.size in 29..600_000)
        val cipher = cipher(Cipher.DECRYPT_MODE, "response", bytes.copyOfRange(0, 12))
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
        return wireJson.decodeFromString(Response.serializer(), plain)
    }

    private fun cipher(mode: Int, direction: String, nonce: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(subject(direction).encodeToByteArray())
        }
}
