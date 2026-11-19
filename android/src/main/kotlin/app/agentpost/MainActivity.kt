package app.agentpost

import android.os.Bundle
import android.content.ClipData
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.agentpost.core.Message
import app.agentpost.core.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val Paper = Color(0xfff5f6ef)
private val Ink = Color(0xff192d27)
private val Forest = Color(0xff153930)
private val Green = Color(0xff255448)
private val Mint = Color(0xffd7ebac)
private val Muted = Color(0xff52675d)
private val Line = Color(0xffdde4d8)
private val White = Color(0xfffcfdf8)
private val Warm = Color(0xfff2e8d4)
private val Rounded = RoundedCornerShape(22.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Green, onPrimary = Color.White, primaryContainer = Mint,
                onPrimaryContainer = Forest, surface = White, onSurface = Ink,
                background = Paper, onBackground = Ink, outline = Muted,
                surfaceVariant = Line, onSurfaceVariant = Muted,
            )) {
                val model: AgentPostModel = viewModel()
                BackHandler(model.chat) { model.back() }
                val page = if (model.host == null) 0 else if (model.chat) 2 else 1
                Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
                    Crossfade(page, modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                        label = "页面") {
                        when (it) {
                            0 -> ConnectScreen(model)
                            1 -> SessionsScreen(model)
                            else -> ChatScreen(model)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen(model: AgentPostModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var bridge by rememberSaveable { mutableStateOf("") }
    var pairing by remember { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var pairingStep by remember { mutableStateOf(false) }
    val busy = model.busy != null
    val focus = LocalFocusManager.current
    val login = email.trim()
    val target = bridge.trim()
    val emailShape = remember { Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") }
    BackHandler(pairingStep && !busy) { pairingStep = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (pairingStep) Action(Icons.AutoMirrored.Rounded.ArrowBack, "返回邮箱登录", !busy) {
                focus.clearFocus()
                pairingStep = false
            } else Brand()
            Text("AgentPost", color = Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Spacer(Modifier.height(10.dp))
        Surface(color = Forest, shape = Rounded) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(if (pairingStep) Icons.Rounded.Dns else Icons.Rounded.MailOutline, null,
                    tint = Mint, modifier = Modifier.size(25.dp))
                Text(if (pairingStep) "连接 Codex 主机" else "登录 QQ 邮箱",
                    color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (pairingStep) "使用目标主机的邮箱和设备配对密钥。"
                    else "使用邮箱授权码登录。配对后可以继续主机上的 Codex 会话。",
                    color = Color(0xffd0ded2), lineHeight = 22.sp,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        if (pairingStep) {
            Text("使用 " + login, color = Muted, fontSize = 13.sp)
            Field(host, { host = it }, "主机名称", busy)
            Field(bridge, { bridge = it }, "主机邮箱", busy, keyboard = KeyboardType.Email)
            Field(pairing, { pairing = it }, "配对密钥", busy, secret = true)
            Button(
                onClick = {
                    focus.clearFocus()
                    model.connect(HostProfile(host.trim(), login, password.trim(), target, pairing.trim()))
                },
                enabled = !busy && emailShape.matches(target) && host.isNotBlank() && pairing.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text(if (model.busy == "connect") "正在验证邮箱并等待主机…" else "连接主机") }
        } else {
            Field(email, { email = it }, "QQ 邮箱", busy, keyboard = KeyboardType.Email)
            Field(password, { password = it }, "邮箱授权码", busy, secret = true)
            Button(
                onClick = { focus.clearFocus(); pairingStep = true },
                enabled = !busy && emailShape.matches(login) && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text("继续配对") }
            OutlinedButton(onClick = { focus.clearFocus(); model.enterDemo() }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("体验示例会话") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        model.issue?.let { Notice(it) }
    }
}

@Composable
private fun SessionsScreen(model: AgentPostModel) {
    var filter by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    val busy = model.busy != null
    val searching = searchFocused && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val rows = model.sessions.filter {
        filter.isBlank() || listOf(it.title, it.preview, it.cwd).any { field -> field.contains(filter.trim(), true) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Brand()
            Text("AgentPost", modifier = Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Action(Icons.Rounded.Refresh, "刷新会话", !busy) { model.refresh() }
            Box {
                Action(Icons.Rounded.MoreVert, "主机选项", !busy) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("断开当前主机") }, onClick = { menu = false; disconnect = true })
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(color = Forest, shape = Rounded) {
                    Row(Modifier.fillMaxWidth().padding(19.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        Icon(Icons.Rounded.Dns, null, tint = Mint, modifier = Modifier.size(25.dp))
                        Column(Modifier.weight(1f)) {
                            Text("当前主机", color = Color(0xffc4d5c8), fontSize = 12.sp)
                            Text(model.host.orEmpty(), color = Color.White, fontSize = 21.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val source = when {
                            model.demo -> "演示"
                            model.listFromCache -> "本机缓存"
                            else -> "邮件连接"
                        }
                        Text(source, color = Mint, fontSize = 12.sp)
                    }
                }
            }
            item {
                Text("会话", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 9.dp, bottom = 2.dp))
            }
            item {
                OutlinedTextField(filter, { filter = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused }, singleLine = true,
                    placeholder = { Text("搜索已加载的会话") }, shape = CircleShape,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (filter.isNotEmpty()) Action(Icons.Rounded.Close, "清空搜索") { filter = "" } })
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 3.dp)) }
            model.issue?.let { item { Notice(it, "重试") { model.refresh() } } }
            if (rows.isEmpty() && !busy && model.issue == null) item {
                if (filter.isBlank()) {
                    Empty(Icons.Rounded.Forum, "这台主机还没有会话", "新建会话，向主机上的 Codex 发一条消息。")
                } else {
                    Text("没有匹配的会话", color = Muted, modifier = Modifier.padding(12.dp))
                }
            }
            items(rows, key = { it.id }) { session ->
                SessionRow(session, !busy) { model.open(session) }
            }
            if (model.listCursor != null) item {
                TextButton(onClick = { model.refresh(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("加载更多会话")
                }
            }
        }
        if (!searching) Surface(color = Paper, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { model.open(null) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp)
                    .height(54.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("新建会话", fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false },
        title = { Text("断开当前主机？") },
        text = { Text("重新连接时需要邮箱授权码和配对密钥。") },
        confirmButton = { TextButton(onClick = { disconnect = false; model.disconnect() }) { Text("断开") } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text("保留连接") } })
}

@Composable
private fun SessionRow(session: SessionSummary, enabled: Boolean, open: () -> Unit) {
    Surface(onClick = open, enabled = enabled, color = White, shape = Rounded,
        border = BorderStroke(1.dp, Line)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xffe9efe3)),
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Forum, null, tint = Green, modifier = Modifier.size(19.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(session.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = session.preview.ifBlank { session.cwd }
                if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (session.status == "active") Text("运行中", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (session.updatedAt > 0) Text(formatDate(session.updatedAt), color = Muted, fontSize = 11.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ChatScreen(model: AgentPostModel) {
    val scroll = rememberLazyListState()
    val focus = LocalFocusManager.current
    val last = model.messages.lastOrNull()
    var previousLast by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    val isAtBottom by remember { derivedStateOf { !scroll.canScrollForward } }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress) follow = isAtBottom
    }
    LaunchedEffect(last?.id) {
        if (last != null && (previousLast == null || follow || last.role == "user")) {
            scroll.requestScrollToItem(model.messages.size + if (model.historyCursor != null) 1 else 0)
        }
        previousLast = last?.id
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Action(Icons.AutoMirrored.Rounded.ArrowBack, "返回会话列表", model.busy == null && model.delivery != "回复尚未确认") { model.back() }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.selected?.title ?: "新建会话", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val source = when {
                    model.demo -> "演示"
                    model.historyFromCache -> "本机缓存"
                    else -> "Codex"
                }
                Text(model.host.orEmpty() + " · " + source, color = Muted,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (model.selected != null) Action(Icons.Rounded.Refresh, "刷新历史", model.busy == null && model.delivery != "回复尚未确认") { model.history() }
        }
        HorizontalDivider(color = Line)
        if (model.busy == "history") LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(state = scroll, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 19.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (model.historyCursor != null) item(key = "older") {
                TextButton(onClick = { model.history(true) }, enabled = model.busy == null && model.delivery != "回复尚未确认",
                    modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") }
            }
            if (model.messages.isEmpty() && model.busy != "history" && model.issue == null) item(key = "empty") {
                Empty(Icons.Rounded.Forum, if (model.selected == null) "开始一段新会话" else "这里还没有消息",
                    if (model.demo) "试着发送一条消息，体验主机回执和回复。" else "消息会发往当前主机，由 Codex 在那里处理。")
            }
            items(model.messages, key = { it.id }) { MessageRow(it) }
            item(key = "end") { Spacer(Modifier.height(1.dp)) }
        }
        model.delivery?.let { status ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (model.busy == "send") CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(status, color = Green, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                if (model.busy == "send") TextButton(onClick = model::pauseWaiting) { Text("暂停等待") }
            }
        }
        model.issue?.let { issue ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (model.delivery == "回复尚未确认") {
                    Notice(issue, "查询回复") { model.checkReply() }
                    TextButton(onClick = model::dismissPending) { Text("结束等待") }
                } else Notice(issue)
            }
        }
        val locked = model.busy != null || model.delivery == "回复尚未确认"
        Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 8.dp, bottom = 9.dp)
            .clip(RoundedCornerShape(26.dp)).background(White).border(1.dp, Line, RoundedCornerShape(26.dp))
            .padding(start = 16.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(model.draft, { if (it.length <= 12_000) model.draft = it },
                modifier = Modifier.weight(1f).padding(vertical = 13.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Green), maxLines = 5,
                decorationBox = { content ->
                    Box { if (model.draft.isEmpty()) Text("发给 Codex…", color = Muted); content() }
                })
            FilledIconButton(onClick = { focus.clearFocus(); model.send() },
                enabled = model.draft.isNotBlank() && !locked, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Send, "发送消息", modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun MessageRow(message: Message) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text ->
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("AgentPost", text))) }
    }
    if (message.role == "user") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(Modifier.fillMaxWidth(0.86f), color = Mint,
                shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp)) {
                SelectionContainer { Text(message.text, Modifier.padding(15.dp), color = Ink, lineHeight = 24.sp) }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(25.dp).clip(CircleShape).background(Forest), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Terminal, null, tint = Mint, modifier = Modifier.size(15.dp))
                }
                Text(if (message.role == "assistant") "Codex" else "主机", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            SelectionContainer {
                MessageMarkdown(message.text, copy)
            }
            IconButton(onClick = { copy(message.text) }) {
                Icon(Icons.Rounded.ContentCopy, "复制回复", tint = Muted, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun Brand() {
    Box(Modifier.size(43.dp).clip(RoundedCornerShape(14.dp)).background(Forest), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Rounded.Send, null, tint = Mint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Action(icon: ImageVector, description: String, enabled: Boolean = true, click: () -> Unit) {
    IconButton(onClick = click, enabled = enabled) { Icon(icon, description) }
}

@Composable
private fun Empty(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 34.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, tint = Green, modifier = Modifier.size(29.dp))
        Text(title, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = Muted, lineHeight = 22.sp)
    }
}

@Composable
private fun Notice(text: String, action: String? = null, click: () -> Unit = {}) {
    Surface(color = Warm, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Info, null, tint = Forest, modifier = Modifier.size(19.dp))
            Text(text, Modifier.weight(1f).padding(vertical = 5.dp), color = Ink, fontSize = 13.sp)
            action?.let { TextButton(onClick = click) { Text(it) } }
        }
    }
}

@Composable
private fun Field(value: String, change: (String) -> Unit, title: String, busy: Boolean,
    secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(value, change, enabled = !busy, singleLine = true,
        label = { Text(title) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        trailingIcon = if (secret) { { Action(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            if (reveal) "隐藏" + title else "显示" + title) { reveal = !reveal } } } else null)
}

private fun formatDate(epoch: Long): String =
    SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(epoch * 1000))
