import tempfile
import time
import unittest
import uuid
from email.message import EmailMessage
from pathlib import Path
from unittest.mock import patch

from agentpost.codex import BridgeError, Codex
from agentpost.service import database, deliver, ingest, initialize, process_job, recover
from agentpost.wire import Codec

KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
USER = "phone@example.com"
BOT = "bridge@example.com"


class FakeCodex:
    def __init__(self, config):
        self.calls = config["calls"]

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def run(self, request):
        self.calls.append(request)
        return {"sessions": [], "nextCursor": None}


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        path = Path(self.scratch.name) / "bridge.sqlite3"
        initialize(path)
        self.db = database(path)
        self.addCleanup(self.db.close)
        self.codec = Codec(KEY)
        self.devices = {self.codec.key_id: (USER, self.codec)}
        self.request = {"id": str(uuid.uuid4()), "sentAt": int(time.time()), "op": "list"}

    def mail(self, request=None, sender=USER):
        message = EmailMessage()
        message["From"] = sender
        message["To"] = BOT
        message["Subject"] = self.codec.subject("request")
        message.set_content(self.codec.seal("request", request or self.request), cte="base64")
        return message.as_bytes()

    def test_duplicate_delivery_executes_once_and_addresses_only_paired_user(self):
        raw = self.mail()
        with self.db:
            ingest(self.db, self.devices, raw)
            ingest(self.db, self.devices, raw)
        config = {"calls": []}
        self.assertTrue(process_job(self.db, config, self.devices, codex=FakeCodex))
        self.assertFalse(process_job(self.db, config, self.devices, codex=FakeCodex))
        self.assertEqual(config["calls"], [self.request])
        with patch("agentpost.service.smtplib.SMTP_SSL") as smtp:
            deliver(self.db, {"address": BOT, "smtp": "smtp.example.com", "password": "fixture"}, self.devices)
            sent = smtp.return_value.__enter__.return_value.send_message.call_args_list
        self.assertEqual([str(call.args[0]["To"]) for call in sent], [USER, USER])
        replies = [self.codec.open("response", call.args[0].get_content()) for call in sent]
        self.assertEqual([reply["stage"] for reply in replies], ["accepted", "done"])
        self.assertEqual(replies[-1]["data"], {"sessions": [], "nextCursor": None})
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0], 0)

    def test_unpaired_sender_expired_and_forged_messages_are_ignored(self):
        with self.db:
            ingest(self.db, self.devices, self.mail(sender="other@example.com"))
            ingest(self.db, self.devices, self.mail(request={**self.request, "sentAt": 1}))
            forged = self.mail().replace(b"Content-Transfer-Encoding: base64", b"Content-Transfer-Encoding: 7bit")
            ingest(self.db, self.devices, forged)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 0)

    def test_restart_reports_uncertain_outcome_without_reexecution(self):
        with self.db:
            ingest(self.db, self.devices, self.mail())
            self.db.execute("UPDATE jobs SET state='running'")
        recover(self.db, self.devices)
        self.assertFalse(process_job(self.db, {"calls": []}, self.devices, codex=FakeCodex))
        body = self.db.execute("SELECT body FROM outbox ORDER BY number DESC LIMIT 1").fetchone()[0]
        reply = self.codec.open("response", body)
        self.assertEqual(reply["stage"], "error")
        self.assertTrue(reply["uncertain"])

    def test_history_maps_only_visible_text_in_display_order(self):
        agent = Codex({})
        with patch.object(agent, "rpc", return_value={"data": [
            {"items": [
                {"id": "hidden", "type": "agentMessage", "phase": "commentary", "text": "progress"},
                {"id": "result", "type": "agentMessage", "phase": "final_answer", "text": "answer"},
            ]},
            {"items": [{"id": "prompt", "type": "userMessage", "content": [{"type": "text", "text": "hello"}]}]},
        ], "nextCursor": "opaque"}):
            history = agent.history("thread")
        self.assertEqual([entry["id"] for entry in history["messages"]], ["prompt", "result"])
        self.assertEqual(history["nextCursor"], "opaque")

    def test_submit_keeps_result_uncertain_if_read_fails_after_completion(self):
        agent = Codex({"workspace": "/workspace"})
        replies = [{"thread": {"id": "thread"}}, {"turn": {"id": "turn"}}, BridgeError("read error")]
        with patch.object(agent, "rpc", side_effect=replies), patch.object(agent, "wait_for_turn"):
            with self.assertRaises(BridgeError) as error:
                agent.submit(None, "hello")
        self.assertTrue(error.exception.uncertain)
