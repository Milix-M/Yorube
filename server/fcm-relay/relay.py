"""FCM relay for the Hermes Android client (M3).

Watches one Hermes run to its terminal event, then pushes a single FCM
data message. Hermes itself never talks to FCM; this small sidecar does.

Endpoints:
  GET  /health
  POST /watch  {"run_id", "session_id", "fcm_token", "title"?}

Environment:
  HERMES_BASE_URL   e.g. https://hermes-host:8642 (required)
  HERMES_API_KEY     API server bearer key (required)
  GOOGLE_APPLICATION_CREDENTIALS  service account JSON for firebase-admin (required)
  RELAY_AUTH_TOKEN   optional shared secret, checked as `Authorization: Bearer <token>`
  PORT               default 8090

Run:
  pip install -r requirements.txt
  export HERMES_BASE_URL=... HERMES_API_KEY=... GOOGLE_APPLICATION_CREDENTIALS=...
  python relay.py
"""

import json
import logging
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests

try:
    import firebase_admin
    from firebase_admin import credentials, messaging
except ImportError:  # pragma: no cover
    firebase_admin = None

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("fcm-relay")

HERMES_BASE_URL = os.environ.get("HERMES_BASE_URL", "").rstrip("/")
HERMES_API_KEY = os.environ.get("HERMES_API_KEY", "")
RELAY_AUTH_TOKEN = os.environ.get("RELAY_AUTH_TOKEN", "")
PORT = int(os.environ.get("PORT", "8090"))
MAX_BODY = 64 * 1024

_firebase_ready = False
_firebase_lock = threading.Lock()


def ensure_firebase() -> bool:
    global _firebase_ready
    with _firebase_lock:
        if _firebase_ready:
            return True
        if firebase_admin is None:
            log.error("firebase-admin is not installed")
            return False
        try:
            if not firebase_admin._apps:
                cred_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
                if not cred_path:
                    log.error("GOOGLE_APPLICATION_CREDENTIALS is not set")
                    return False
                firebase_admin.initialize_app(credentials.Certificate(cred_path))
            _firebase_ready = True
            return True
        except Exception:
            log.exception("firebase init failed")
            return False


def hermes_headers() -> dict:
    return {"Authorization": f"Bearer {HERMES_API_KEY}"}


def wait_terminal(run_id: str) -> dict:
    """Stream GET /v1/runs/{id}/events until a terminal run.* event. Returns fields."""
    url = f"{HERMES_BASE_URL}/v1/runs/{run_id}/events"
    terminal = {"status": "done", "output": "", "error": ""}
    try:
        with requests.get(url, headers=hermes_headers(), stream=True, timeout=(10, 300)) as r:
            if r.status_code != 200:
                terminal["error"] = f"events HTTP {r.status_code}"
                return terminal
            event_name = "message"
            data_lines: list[str] = []
            for line in r.iter_lines(decode_unicode=True):
                if line is None:
                    continue
                if line == "":
                    payload = parse_frame(event_name, "\n".join(data_lines))
                    event_name = "message"
                    data_lines = []
                    if payload is not None and payload.get("event", "").startswith("run."):
                        terminal["status"] = payload["event"].split(".", 1)[1]
                        terminal["output"] = str(payload.get("output") or "")
                        terminal["error"] = str(payload.get("error") or "")
                        return terminal
                elif line.startswith(":"):
                    continue  # keepalive comment
                elif line.startswith("event:"):
                    event_name = line[len("event:"):].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[len("data:"):].strip())
    except Exception:
        log.exception("event stream failed for %s", run_id)
        terminal["error"] = "stream failed"
    return terminal


def parse_frame(event_name: str, data: str):
    data = data.strip()
    if not data or data == "[DONE]":
        return None
    try:
        obj = json.loads(data)
    except json.JSONDecodeError:
        return None
    if isinstance(obj, dict) and obj.get("event"):
        return obj
    return {"event": event_name, "data": obj}


def push_and_watch(run_id: str, session_id: str, fcm_token: str, title: str) -> None:
    terminal = wait_terminal(run_id)
    if not ensure_firebase():
        return
    status = terminal["status"]
    body = (terminal["output"] or terminal["error"] or f"run {status}")[:200]
    try:
        messaging.send(
            messaging.Message(
                data={
                    "title": title or "Hermes",
                    "body": body,
                    "session_id": session_id,
                    "run_id": run_id,
                    "status": status,
                },
                token=fcm_token,
            )
        )
        log.info("pushed %s for run %s", status, run_id)
    except Exception:
        log.exception("FCM send failed for run %s", run_id)


class Handler(BaseHTTPRequestHandler):
    server_version = "HermesFcmRelay/0.1"

    def log_message(self, *args):  # quieter default logging
        log.info("%s %s", self.command, self.path)

    def _send(self, status: int, obj: dict) -> None:
        payload = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/watch":
            self._send(404, {"error": "not found"})
            return
        if RELAY_AUTH_TOKEN:
            auth = self.headers.get("Authorization", "")
            if auth != f"Bearer {RELAY_AUTH_TOKEN}":
                self._send(401, {"error": "unauthorized"})
                return
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > MAX_BODY:
            self._send(400, {"error": "bad body"})
            return
        try:
            req = json.loads(self.rfile.read(length))
        except json.JSONDecodeError:
            self._send(400, {"error": "invalid JSON"})
            return
        run_id = str(req.get("run_id", ""))
        session_id = str(req.get("session_id", ""))
        fcm_token = str(req.get("fcm_token", ""))
        title = str(req.get("title", "Hermes"))
        if not run_id or not fcm_token:
            self._send(400, {"error": "run_id and fcm_token are required"})
            return
        threading.Thread(
            target=push_and_watch, args=(run_id, session_id, fcm_token, title), daemon=True
        ).start()
        self._send(202, {"status": "watching", "run_id": run_id})


def main() -> None:
    if not HERMES_BASE_URL or not HERMES_API_KEY:
        raise SystemExit("HERMES_BASE_URL and HERMES_API_KEY are required")
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    log.info("relay on :%d -> %s", PORT, HERMES_BASE_URL)
    server.serve_forever()


if __name__ == "__main__":
    main()
