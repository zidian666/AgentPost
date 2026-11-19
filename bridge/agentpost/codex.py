import json
import time

from websockets.sync.client import unix_connect


class BridgeError(Exception):
    def __init__(self, message, uncertain=False):
        super().__init__(message)
        self.uncertain = uncertain


class Codex:
    def __init__(self, config):
        self.config = config
        self.socket = None
        self.next_id = 0
        self.events = []

    def __enter__(self):
        self.socket = unix_connect(self.config["socket"], uri="ws://localhost/", open_timeout=8,
                                   close_timeout=2, max_size=32 * 1024 * 1024)
        try:
            self.rpc("initialize", {"clientInfo": {"name": "agentpost", "version": "0.1.0"}})
            self.send({"method": "initialized", "params": {}})
        except BaseException:
            self.socket.close()
            raise
        return self

    def __exit__(self, *_):
        self.socket.close()

    def send(self, value):
        self.socket.send(json.dumps(value))

    def receive(self, deadline):
        return json.loads(self.socket.recv(timeout=max(0, deadline - time.monotonic())))

    def rpc(self, method, params):
        self.next_id += 1
        identifier = self.next_id
        self.send({"id": identifier, "method": method, "params": params})
        deadline = time.monotonic() + 15
        while True:
            message = self.receive(deadline)
            if message.get("id") == identifier and "method" not in message:
                if "error" in message:
                    if method == "turn/start":
                        raise BridgeError("Codex 未接受消息。请刷新会话并检查主机状态。")
                    raise BridgeError("主机上的 Codex 无法读取或打开该会话。")
                return message["result"]
            if message.get("method") == "turn/completed":
                self.events.append(message)

    def run(self, request):
        op = request["op"]
        thread_id = request.get("threadId")
        cursor = {"cursor": request["cursor"]} if request.get("cursor") else {}
        if op == "list":
            result = self.rpc("thread/list", {"limit": 20, "sortKey": "updated_at", **cursor})
            return {"sessions": [summary(thread) for thread in result["data"]],
                    "nextCursor": result.get("nextCursor")}
        if op == "history":
            return self.history(thread_id, cursor)
        return self.submit(thread_id, request["text"])

    def history(self, thread_id, cursor=None):
        result = self.rpc("thread/turns/list", {
            "threadId": thread_id, "limit": 8, "itemsView": "full",
            "sortDirection": "desc", **(cursor or {}),
        })
        messages = []
        for turn in reversed(result["data"]):
            for item in turn.get("items", []):
                entry = convert(item)
                if entry:
                    messages.append(entry)
        return {"messages": messages, "nextCursor": result.get("nextCursor")}

    def submit(self, thread_id, text):
        if thread_id:
            existing = self.rpc("thread/read", {"threadId": thread_id})["thread"]
            if summary(existing)["status"] == "active":
                raise BridgeError("这个会话正在运行。请等待当前任务结束，再发送消息。")
            opened = self.rpc("thread/resume", {"threadId": thread_id, "excludeTurns": True})
        else:
            opened = self.rpc("thread/start", {"cwd": self.config["workspace"],
                                               "approvalPolicy": "never", "sandbox": "workspace-write"})
            thread_id = opened["thread"]["id"]
        thread = opened["thread"]
        workspace = thread.get("cwd") or self.config["workspace"]
        try:
            turn = self.rpc("turn/start", {
                "threadId": thread_id, "input": [{"type": "text", "text": text}],
                "approvalPolicy": "never",
                "sandboxPolicy": {"type": "workspaceWrite", "writableRoots": [workspace], "networkAccess": False},
            })["turn"]
        except BridgeError:
            raise
        except Exception as exc:
            raise BridgeError("无法确认 Codex 是否已接收消息。请先在主机记录中核对，避免重复发送。", uncertain=True) from exc
        try:
            self.wait_for_turn(thread_id, turn["id"])
            thread = self.rpc("thread/read", {"threadId": thread_id})["thread"]
            return {"thread": summary(thread), "history": self.history(thread_id)}
        except BridgeError as exc:
            if exc.uncertain:
                raise
            raise BridgeError("消息已提交，但暂时无法读取结果。请在主机查看会话。", uncertain=True) from exc
        except Exception as exc:
            raise BridgeError("无法确认 Codex 是否已完成。请先在主机记录中核对，避免重复发送。", uncertain=True) from exc

    def wait_for_turn(self, thread_id, turn_id):
        deadline = time.monotonic() + self.config.get("turn_timeout", 240)
        while self.events or time.monotonic() < deadline:
            event = self.events.pop(0) if self.events else self.receive(deadline)
            if event.get("method") != "turn/completed":
                continue
            params = event.get("params", {})
            turn = params.get("turn", {})
            if params.get("threadId") == thread_id and turn.get("id") == turn_id:
                if turn.get("status") != "completed":
                    raise BridgeError("Codex 任务已失败或中断。请刷新会话查看已保存的内容。", uncertain=True)
                return
        raise TimeoutError("Timed out waiting for the Codex turn")


def summary(thread):
    preview = (thread.get("preview") or "")[:160]
    return {
        "id": thread["id"], "title": (thread.get("name") or preview or "未命名会话")[:100],
        "preview": preview, "cwd": (thread.get("cwd") or "")[:300],
        "updatedAt": thread.get("updatedAt") or 0,
        "status": (thread.get("status") or {}).get("type", "idle"),
    }


def convert(item):
    kind = item.get("type")
    if kind == "userMessage":
        text = "\n".join(part.get("text", "") for part in item.get("content", []) if part.get("type") == "text")
        role = "user"
    elif kind == "agentMessage" and item.get("phase") != "commentary":
        text, role = item.get("text", ""), "assistant"
    else:
        return None
    if not text.strip():
        return None
    if len(text) > 6000:
        text = text[:6000] + "\n\n[内容过长，请在主机查看完整记录]"
    return {"id": item["id"], "role": role, "text": text}
