package app.agentpost

import app.agentpost.core.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.encodeToJsonElement

class DemoConnection : Connection {
    private val completed = mutableMapOf<String, Response>()
    private val conversations = linkedMapOf(
        "demo-deploy" to mutableListOf(
            Message("d1", "user", "帮我确认发布前的检查项。"),
            Message("d2", "assistant", "发布前可以先确认这三件事：\n\n1. 测试套件已通过。\n2. 迁移脚本已在预发布环境演练。\n3. 上一个稳定版本可以随时回滚。\n\n可以用下面的命令检查当前改动：\n\n```bash\ngit status --short\n```"),
        ),
        "demo-logs" to mutableListOf(
            Message("l1", "user", "总结最近的服务日志。"),
            Message("l2", "assistant", "这是示例会话。演示日志里只有一次重启记录，没有错误。"),
        ),
    )
    private val summaries = linkedMapOf(
        "demo-deploy" to SessionSummary("demo-deploy", "发布前检查", "检查测试、迁移和回滚方案", "/home/demo/agentpost"),
        "demo-logs" to SessionSummary("demo-logs", "服务日志排查", "查看最近的服务日志", "/home/demo/agentpost"),
    )

    override suspend fun call(request: Request, onAccepted: () -> Unit): Response {
        delay(240)
        return execute(request, onAccepted)
    }

    override suspend fun await(request: Request, onAccepted: () -> Unit): Response =
        completed[request.id] ?: execute(request, onAccepted)

    private suspend fun execute(request: Request, onAccepted: () -> Unit): Response {
        return when (request.op) {
            "ping" -> result(request, "demo")
            "list" -> result(request, SessionPage(summaries.values.sortedByDescending { it.updatedAt }))
            "history" -> result(request, History(conversations.getValue(request.threadId!!).toList()))
            "send" -> {
                onAccepted()
                delay(950)
                val id = request.threadId ?: request.id
                val prompt = request.text.orEmpty()
                val title = summaries[id]?.title ?: prompt.lineSequence().first().take(28)
                val summary = SessionSummary(id, title, prompt, "/home/demo/agentpost",
                    System.currentTimeMillis() / 1000)
                summaries[id] = summary
                val messages = conversations.getOrPut(id) { mutableListOf() }
                messages += Message(request.id, "user", prompt)
                messages += Message(request.id + "-reply", "assistant",
                    "已收到这条消息。\n\n在真实主机上，Codex 会在绑定的工作目录处理请求，主机完成后会将回复同步到这里。")
                result(request, SendResult(summary, History(messages.toList()))).also { completed[request.id] = it }
            }
            else -> error("Unsupported operation")
        }
    }

    private inline fun <reified T> result(request: Request, value: T) =
        Response(request.id, "done", wireJson.encodeToJsonElement(value))
}
