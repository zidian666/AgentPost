import argparse
import email.policy
import hashlib
import imaplib
import json
import logging
import os
from pathlib import Path
import smtplib
import sqlite3
import ssl
import threading
import time
import tomllib
from contextlib import closing
from email import message_from_bytes
from email.message import EmailMessage
from email.utils import formatdate, getaddresses

from .codex import BridgeError, Codex
from .wire import Codec, MAX_REQUEST_BYTES, PREFIX, encode, valid_request

LOG = logging.getLogger("agentpost")
TLS = ssl.create_default_context()
RESTART_MESSAGE = "桥接服务重启，无法确认上一条消息是否已完成。请先查看主机会话。"


def database(path):
    db = sqlite3.connect(path, timeout=10)
    db.row_factory = sqlite3.Row
    return db


def initialize(path):
    with closing(database(path)) as db, db:
        db.executescript("""
            PRAGMA journal_mode = WAL;
            CREATE TABLE IF NOT EXISTS jobs (
                device TEXT NOT NULL, id TEXT NOT NULL, request TEXT NOT NULL, state TEXT NOT NULL,
                PRIMARY KEY(device, id)
            );
            CREATE TABLE IF NOT EXISTS outbox (
                number INTEGER PRIMARY KEY, device TEXT NOT NULL, body TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS inbox_cursor (
                id INTEGER PRIMARY KEY CHECK (id=1), validity INTEGER NOT NULL, uid INTEGER NOT NULL
            );
        """)


def enqueue_reply(db, key_id, codec, identifier, stage, **fields):
    body = codec.seal("response", {"id": identifier, "stage": stage, **fields})
    if len(body) > 800_000:
        body = codec.seal("response", {"id": identifier, "stage": "error", "uncertain": True,
                                     "error": "回复超出邮件传输上限，请在主机查看完整会话。"})
    db.execute("INSERT INTO outbox(device, body) VALUES (?, ?)", (key_id, body))


def ingest(db, devices, raw):
    message = message_from_bytes(raw, policy=email.policy.default)
    subject = str(message.get("Subject", ""))
    key_id = subject.removeprefix(f"{PREFIX} request ")
    if key_id not in devices:
        return
    address, codec = devices[key_id]
    if subject != codec.subject("request"):
        return
    senders = [addr for _, addr in getaddresses(message.get_all("From", []))]
    if len(senders) != 1 or senders[0].casefold() != address.casefold():
        return
    part = message.get_body(preferencelist=("plain",))
    if part is None:
        return
    try:
        body = part.get_content()
        if not isinstance(body, str) or len(body) > MAX_REQUEST_BYTES:
            return
        payload = codec.open("request", body)
    except (ValueError, LookupError, UnicodeError):
        return
    if not valid_request(payload):
        return
    if db.execute("INSERT OR IGNORE INTO jobs VALUES (?, ?, ?, 'queued')",
                  (key_id, payload["id"], json.dumps(payload))).rowcount:
        enqueue_reply(db, key_id, codec, payload["id"], "accepted")


def receive(db, mail, devices):
    with imaplib.IMAP4_SSL(mail["imap"], 993, ssl_context=TLS, timeout=12) as inbox:
        inbox.login(mail["address"], mail["password"])
        status, _ = inbox.select("INBOX", readonly=True)
        if status != "OK":
            raise RuntimeError("Cannot open INBOX")
        validity = int(inbox.response("UIDVALIDITY")[1][0])
        state = db.execute("SELECT validity, uid FROM inbox_cursor WHERE id=1").fetchone()
        last = state["uid"] if state and state["validity"] == validity else 0
        status, ids = inbox.uid("SEARCH", None, "UID", f"{last + 1}:*",
                                "HEADER", "Subject", f'"{PREFIX} request "')
        if status != "OK":
            raise RuntimeError("Cannot search INBOX")
        for uid in sorted(int(entry) for entry in ids[0].split() if int(entry) > last)[:30]:
            status, data = inbox.uid("FETCH", str(uid), f"(BODY.PEEK[]<0.{MAX_REQUEST_BYTES}>)")
            if status != "OK":
                break
            raw = next((entry[1] for entry in data if isinstance(entry, tuple)), None)
            with db:
                if raw and len(raw) < MAX_REQUEST_BYTES:
                    ingest(db, devices, raw)
                db.execute("INSERT OR REPLACE INTO inbox_cursor VALUES (1, ?, ?)", (validity, uid))


def deliver(db, mail, devices):
    replies = db.execute("SELECT * FROM outbox ORDER BY number LIMIT 20").fetchall()
    if not replies:
        return
    with smtplib.SMTP_SSL(mail["smtp"], 465, context=TLS, timeout=12) as smtp:
        smtp.login(mail["address"], mail["password"])
        for reply in replies:
            device = devices.get(reply["device"])
            if device:
                address, codec = device
                message = EmailMessage()
                message["From"] = mail["address"]
                message["To"] = address
                message["Subject"] = codec.subject("response")
                message["Date"] = formatdate()
                fingerprint = hashlib.sha256(reply["body"].encode("ascii")).hexdigest()
                domain = mail["address"].rsplit("@", 1)[-1]
                message["Message-ID"] = f"<agentpost.{fingerprint}@{domain}>"
                message.set_content(reply["body"], cte="base64")
                smtp.send_message(message)
            with db:
                db.execute("DELETE FROM outbox WHERE number=?", (reply["number"],))


def recover(db, devices):
    with db:
        for job in db.execute("SELECT device, id FROM jobs WHERE state='running'").fetchall():
            if job["device"] in devices:
                codec = devices[job["device"]][1]
                enqueue_reply(db, job["device"], codec, job["id"], "error",
                              error=RESTART_MESSAGE, uncertain=True)
        db.execute("UPDATE jobs SET state='done', request='' WHERE state='running'")


def process_job(db, config, devices, codex=Codex):
    with db:
        job = db.execute("SELECT * FROM jobs WHERE state='queued' ORDER BY rowid LIMIT 1").fetchone()
        if job is None:
            return False
        db.execute("UPDATE jobs SET state='running' WHERE device=? AND id=?", (job["device"], job["id"]))
    device = devices.get(job["device"])
    if device:
        request = json.loads(job["request"])
        fields = {}
        stage = "done"
        try:
            if not valid_request(request):
                raise BridgeError("请求在队列中已过期，请重试。")
            with codex(config) as api:
                fields["data"] = api.run(request)
        except BridgeError as exc:
            fields = {"error": str(exc), "uncertain": exc.uncertain}
            stage = "error"
        except Exception as exc:
            LOG.warning("Codex connection failed (%s)", type(exc).__name__)
            fields = {"error": "主机无法确认 Codex 的处理结果，请稍后查看会话。",
                      "uncertain": request["op"] == "send"}
            stage = "error"
        with db:
            enqueue_reply(db, job["device"], device[1], job["id"], stage, **fields)
            db.execute("UPDATE jobs SET state='done', request='' WHERE device=? AND id=?",
                       (job["device"], job["id"]))
    else:
        with db:
            db.execute("UPDATE jobs SET state='done', request='' WHERE device=? AND id=?",
                       (job["device"], job["id"]))
    return True


def worker(path, config, devices):
    with closing(database(path)) as db:
        while True:
            if not process_job(db, config, devices):
                time.sleep(0.5)


def serve(config):
    import fcntl

    mail = config["mail"]
    mail["password"] = Path(mail["password_file"]).expanduser().read_text().strip()
    codex = config["codex"]
    codex["socket"] = str(Path(codex["socket"]).expanduser().resolve())
    codex["workspace"] = str(Path(codex["workspace"]).expanduser().resolve(strict=True))
    devices = {}
    for device in config.get("device", []):
        codec = Codec(device["key"])
        if codec.key_id in devices:
            raise ValueError("Each configured device must have a different key")
        devices[codec.key_id] = (device["email"], codec)
    if not devices or not mail["password"]:
        raise ValueError("Configure at least one device and a bridge mailbox password")
    bridge = config["bridge"]
    path = Path(bridge["database"]).expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.with_suffix(".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        initialize(path)
        with closing(database(path)) as db:
            recover(db, devices)
            runner = threading.Thread(target=worker, args=(path, codex, devices), daemon=True)
            runner.start()
            LOG.info("AgentPost bridge ready for %d paired device(s)", len(devices))
            while runner.is_alive():
                for operation in (receive, deliver):
                    try:
                        operation(db, mail, devices)
                    except (OSError, imaplib.IMAP4.error, smtplib.SMTPException, RuntimeError) as exc:
                        LOG.warning("%s failed (%s); will retry", operation.__name__, type(exc).__name__)
                time.sleep(max(2, bridge.get("poll_seconds", 4)))
            raise RuntimeError("The Codex worker has stopped")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path)
    parser.add_argument("--generate-key", action="store_true")
    args = parser.parse_args()
    if args.generate_key:
        print(encode(os.urandom(32)))
        return
    if args.config is None:
        parser.error("--config is required")
    os.umask(0o077)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    with args.config.open("rb") as stream:
        serve(tomllib.load(stream))


if __name__ == "__main__":
    main()
