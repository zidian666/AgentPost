import base64
import hashlib
import json
import os
import time
import uuid
from binascii import Error as Base64Error

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PREFIX = "AgentPost v1"
MAX_REQUEST_BYTES = 131072
MAX_CLOCK_DRIFT = 1800


def encode(value):
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def decode(value):
    return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)


class Codec:
    def __init__(self, key):
        raw = decode(key.strip())
        if len(raw) != 32:
            raise ValueError("Device pairing key must contain 32 random bytes")
        self.key_id = hashlib.sha256(raw).hexdigest()[:24]
        self.aes = AESGCM(raw)

    def subject(self, direction):
        return f"{PREFIX} {direction} {self.key_id}"

    def seal(self, direction, value):
        payload = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
        nonce = os.urandom(12)
        return encode(nonce + self.aes.encrypt(nonce, payload, self.subject(direction).encode()))

    def open(self, direction, body):
        try:
            payload = decode(body.strip())
            plain = self.aes.decrypt(payload[:12], payload[12:], self.subject(direction).encode())
            return json.loads(plain)
        except (Base64Error, InvalidTag, ValueError, TypeError) as exc:
            raise ValueError("Invalid authenticated message") from exc


def valid_request(value, now=None):
    if not isinstance(value, dict) or set(value) - {"op", "id", "sentAt", "threadId", "cursor", "text"}:
        return False
    if now is None:
        now = int(time.time())
    stamp = value.get("sentAt")
    if type(stamp) is not int or abs(stamp - now) > MAX_CLOCK_DRIFT:
        return False
    try:
        identifier = value.get("id")
        if not isinstance(identifier, str) or str(uuid.UUID(identifier)) != identifier:
            return False
    except ValueError:
        return False
    op = value.get("op")
    thread = value.get("threadId")
    cursor = value.get("cursor")
    prompt = value.get("text")
    if thread is not None and (not isinstance(thread, str) or not 1 <= len(thread) <= 160):
        return False
    if cursor is not None and (not isinstance(cursor, str) or not 1 <= len(cursor) <= 2048):
        return False
    if op == "list":
        return thread is None and prompt is None
    if op == "history":
        return thread is not None and prompt is None
    if op == "send":
        return cursor is None and isinstance(prompt, str) and bool(prompt.strip()) and len(prompt) <= 12000
    return False
