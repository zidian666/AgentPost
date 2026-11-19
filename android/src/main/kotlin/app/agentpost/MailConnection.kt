package app.agentpost

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.agentpost.core.*
import com.sun.mail.imap.IMAPFolder
import java.security.KeyStore
import java.util.Base64
import java.util.Date
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SubjectTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class HostProfile(
    val name: String,
    val email: String,
    val password: String,
    val bridge: String,
    val pairing: String,
    val imap: String = "imap.qq.com",
    val smtp: String = "smtp.qq.com",
)

class ProfileVault(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val alias = "agentpost-profile-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
    }

    fun read(): HostProfile? {
        val value = prefs.getString("sealed", null) ?: return null
        val bytes = Base64.getDecoder().decode(value)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes, 12, bytes.size - 12)
        }
        return wireJson.decodeFromString(HostProfile.serializer(), plain.decodeToString())
    }

    fun save(profile: HostProfile) {
        val value = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key())
            iv + doFinal(wireJson.encodeToString(HostProfile.serializer(), profile).encodeToByteArray())
        }
        check(prefs.edit().putString("sealed", Base64.getEncoder().encodeToString(value)).commit())
    }

    fun clear() { prefs.edit().remove("sealed").apply() }
}

class MailConnection(private val profile: HostProfile) : Connection {
    private val codec = MailCodec(profile.pairing)
    private val session = Session.getInstance(Properties().apply {
        for (protocol in listOf("imaps", "smtps")) {
            setProperty("mail." + protocol + ".ssl.checkserveridentity", "true")
            setProperty("mail." + protocol + ".connectiontimeout", "10000")
            setProperty("mail." + protocol + ".timeout", "12000")
            setProperty("mail." + protocol + ".writetimeout", "12000")
        }
        setProperty("mail.smtps.auth", "true")
        setProperty("mail.imaps.peek", "true")
    })

    suspend fun verifyMailbox() = withContext(Dispatchers.IO) {
        session.getStore("imaps").use { it.connect(profile.imap, 993, profile.email, profile.password) }
    }

    override suspend fun call(request: Request, onAccepted: () -> Unit): Response {
        withContext(Dispatchers.IO) {
            val mail = MimeMessage(session).apply {
                setFrom(InternetAddress(profile.email, true))
                setRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(profile.bridge, true))
                subject = codec.subject("request")
                sentDate = Date()
                setText(codec.seal(request), "US-ASCII")
                setHeader("Content-Transfer-Encoding", "base64")
            }
            session.getTransport("smtps").use {
                it.connect(profile.smtp, 465, profile.email, profile.password)
                it.sendMessage(mail, mail.allRecipients)
            }
        }
        return await(request, onAccepted)
    }

    override suspend fun await(request: Request, onAccepted: () -> Unit): Response {
        val deadline = android.os.SystemClock.elapsedRealtime() + if (request.op == "send") 300_000L else 85_000L
        var seen = 0L
        var uidValidity = 0L
        var accepted = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                val replies = withContext(Dispatchers.IO) {
                    session.getStore("imaps").use { store ->
                        store.connect(profile.imap, 993, profile.email, profile.password)
                        val folder = store.getFolder("INBOX") as IMAPFolder
                        folder.open(Folder.READ_ONLY)
                        try {
                            if (uidValidity != folder.uidValidity) {
                                uidValidity = folder.uidValidity
                                seen = 0L
                            }
                            val filter = AndTerm(SubjectTerm(codec.subject("response")),
                                ReceivedDateTerm(ComparisonTerm.GE, Date((request.sentAt - 86_400) * 1000)))
                            folder.search(filter).map { folder.getUID(it) to it }
                                .filter { (uid, _) -> uid > seen }.sortedBy { it.first }
                                .mapNotNull { (uid, mail) ->
                                    if (mail.subject != codec.subject("response") || mail.size > 1_150_000) {
                                        seen = uid
                                        null
                                    } else {
                                        val body = mail.content as? String
                                        seen = uid
                                        body?.takeIf { it.length <= 820_000 }?.let {
                                            runCatching { codec.openResponse(it) }.getOrNull()
                                        }
                                    }
                                }.filter { it.id == request.id }
                        } finally { folder.close(false) }
                    }
                }
                replies.firstOrNull { it.stage == "done" || it.stage == "error" }?.let { return it }
                if (!accepted && replies.any { it.stage == "accepted" }) {
                    accepted = true
                    onAccepted()
                }
            } catch (e: AuthenticationFailedException) {
                throw e
            } catch (_: MessagingException) {
                // The request may already be running. Retry inbox reads, never resubmit automatically.
            }
            delay(4000)
        }
        throw ReplyTimeout()
    }
}

class ReplyTimeout : Exception()
