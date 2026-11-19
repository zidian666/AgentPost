package app.agentpost

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.agentpost.core.*
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentPostModel(application: Application) : AndroidViewModel(application) {
    private val vault = ProfileVault(application)
    private var connection: Connection? = null
    private var cacheScope: String? = null
    private var cache = HostCache()
    private var pending: Request? = null
    private var operation: Job? = null
    var host by mutableStateOf<String?>(null); private set
    var demo by mutableStateOf(false); private set
    var chat by mutableStateOf(false); private set
    var selected by mutableStateOf<SessionSummary?>(null); private set
    var sessions by mutableStateOf(emptyList<SessionSummary>()); private set
    var messages by mutableStateOf(emptyList<Message>()); private set
    var listCursor by mutableStateOf<String?>(null); private set
    var historyCursor by mutableStateOf<String?>(null); private set
    var listFromCache by mutableStateOf(false); private set
    var historyFromCache by mutableStateOf(false); private set
    var busy by mutableStateOf<String?>(null); private set
    var delivery by mutableStateOf<String?>(null); private set
    var issue by mutableStateOf<String?>(null); private set
    var draft by mutableStateOf("")
    var draftByThread = mutableMapOf<String?, String>()
        private set

    init {
        run("restore") {
            withContext(Dispatchers.IO) { vault.read() }?.let {
                val gateway = MailConnection(it)
                val saved = withContext(Dispatchers.IO) { vault.readCache(gateway.cacheScope) }
                val savedList = saved?.sessions
                attach(it.name, gateway, false, gateway.cacheScope)
                if (saved != null && savedList != null) {
                    cache = saved
                    sessions = savedList.sessions
                    listCursor = savedList.nextCursor
                    listFromCache = true
                } else {
                    fetchSessions(false)
                }
            }
        }
    }

    fun enterDemo() = run("list") {
        attach("示例主机", DemoConnection(), true)
        fetchSessions(false)
    }

    fun connect(profile: HostProfile) = run("connect") {
        val gateway = try { MailConnection(profile) } catch (_: IllegalArgumentException) {
            throw UserIssue("配对密钥格式有误，请从主机重新复制。")
        }
        gateway.verifyMailbox()
        val list = requireSuccess(gateway.call(Request("list"))).result<SessionPage>()
        withContext(Dispatchers.IO) { vault.save(profile) }
        attach(profile.name, gateway, false, gateway.cacheScope)
        showSessions(list)
        persistCache()
    }

    private fun attach(name: String, gateway: Connection, isDemo: Boolean, scope: String? = null) {
        connection = gateway
        cacheScope = scope
        cache = HostCache()
        host = name
        demo = isDemo
        chat = false
        sessions = emptyList()
        listCursor = null
        listFromCache = false
        historyFromCache = false
    }

    fun disconnect() {
        if (busy != null) return
        if (!demo) vault.clear()
        connection = null
        cacheScope = null
        cache = HostCache()
        host = null
        chat = false
        selected = null
        sessions = emptyList()
        messages = emptyList()
        listCursor = null
        historyCursor = null
        listFromCache = false
        historyFromCache = false
        pending = null
        delivery = null
        draft = ""
        draftByThread.clear()
        issue = null
    }

    fun refresh(more: Boolean = false) = run("list") { fetchSessions(more) }

    private suspend fun fetchSessions(more: Boolean) {
        val result = request(Request("list", cursor = if (more) listCursor else null)).result<SessionPage>()
        val page = if (more) result.copy(sessions = (sessions + result.sessions).distinctBy { it.id }) else result
        showSessions(page)
        persistCache()
    }

    private fun showSessions(page: SessionPage) {
        sessions = page.sessions
        listCursor = page.nextCursor
        listFromCache = false
        cache = cache.copy(sessions = page)
    }

    fun open(session: SessionSummary?) {
        if (busy != null) return
        issue = null
        selected = session
        val saved = session?.let { cache.histories[it.id] }
        messages = saved?.messages.orEmpty()
        historyCursor = saved?.nextCursor
        historyFromCache = saved != null
        draft = draftByThread[session?.id].orEmpty()
        chat = true
        if (session != null && saved == null) history()
    }

    fun back() {
        if (busy != null || pending != null) return
        draftByThread[selected?.id] = draft
        issue = null
        delivery = null
        chat = false
    }

    fun history(more: Boolean = false) {
        val thread = selected ?: return
        run("history") {
            val result = request(Request("history", threadId = thread.id,
                cursor = if (more) historyCursor else null)).result<History>()
            messages = (if (more) result.messages + messages else result.messages).distinctBy { it.id }
            historyCursor = result.nextCursor
            historyFromCache = false
            if (!more || messages.sumOf { it.text.length.toLong() } <= 256_000L) {
                cache = cache.withHistory(thread.id, History(messages, historyCursor))
                persistCache()
            }
        }
    }

    fun send() {
        val prompt = draft.trim()
        if (busy != null || pending != null || prompt.isEmpty()) return
        val outgoing = Request("send", threadId = selected?.id, text = prompt)
        draft = ""
        draftByThread.remove(selected?.id)
        messages = messages + Message(outgoing.id, "user", prompt)
        pending = outgoing
        submit(outgoing, false)
    }

    fun checkReply() {
        val outgoing = pending ?: return
        submit(outgoing, true)
    }

    fun pauseWaiting() {
        if (busy != "send") return
        delivery = "回复尚未确认"
        issue = "已暂停手机等待。主机可能仍在处理，可以稍后查询同一条请求。"
        operation?.cancel()
    }

    fun dismissPending() {
        if (busy != null) return
        pending = null
        delivery = null
        issue = null
        if (selected != null) history()
        else back()
    }

    private fun submit(outgoing: Request, checkOnly: Boolean) = run("send") {
        delivery = if (checkOnly) "正在查询回复" else "正在发送"
        val accepted = { delivery = "主机已接收 · 等待 Codex" }
        try {
            val result = if (checkOnly)
                requireSuccess(requireNotNull(connection).await(outgoing, accepted))
            else request(outgoing, accepted)
            val update = result.result<SendResult>()
            selected = update.thread
            sessions = listOf(update.thread) + sessions.filterNot { it.id == update.thread.id }
            cache = cache.copy(sessions = SessionPage(sessions, listCursor))
                .withHistory(update.thread.id, update.history)
            messages = update.history.messages
            historyCursor = update.history.nextCursor
            historyFromCache = false
            pending = null
            delivery = null
            persistCache()
        } catch (e: UserIssue) {
            pending = null
            delivery = "主机返回错误"
            if (selected == null && !e.uncertain) {
                draft = outgoing.text.orEmpty()
                messages = messages.filterNot { it.id == outgoing.id }
            }
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            delivery = "回复尚未确认"
            if (e is MessagingException) {
                throw UserIssue("邮件是否送达尚未确认。可以先查询回复，避免重复提交。")
            }
            throw e
        }
    }

    private suspend fun request(request: Request, accepted: () -> Unit = {}) =
        requireSuccess(requireNotNull(connection).call(request, accepted))

    private suspend fun persistCache() {
        val scope = cacheScope ?: return
        val snapshot = cache
        withContext(Dispatchers.IO) {
            // A local storage problem should not turn a successful mail request into a failed one.
            runCatching { vault.saveCache(scope, snapshot) }
        }
    }

    private fun requireSuccess(response: Response): Response {
        if (response.stage == "error") throw UserIssue(response.error ?: "主机处理请求失败。", response.uncertain)
        return response
    }

    private fun run(task: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = task
        issue = null
        operation = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                issue = when (e) {
                    is UserIssue -> e.message
                    is ReplyTimeout -> "暂未收到主机回复。请检查桥接服务，或稍后再查。"
                    is AuthenticationFailedException -> "邮箱登录失败，请检查邮箱、授权码与 IMAP 设置。"
                    is MessagingException -> "无法连接邮件服务器，请检查网络与邮箱设置。"
                    else -> "操作未完成，请重试。"
                }
            } finally {
                busy = null
            }
        }
    }

    private class UserIssue(message: String, val uncertain: Boolean = false) : Exception(message)
}
