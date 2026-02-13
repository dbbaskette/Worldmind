#!/usr/bin/env python3
"""SSE normalizing proxy for Tanzu GenAI ↔ Goose CLI compatibility.

Fixes three streaming incompatibilities:
1. Missing space after "data:" prefix  (data:{...} → data: {...})
2. Missing "index" field on tool_calls  (Goose requires index on each delta)
3. Hardcoded gpt-4o-mini fast model     (rewritten to GOOSE_MODEL)

Usage: sse-proxy.py <upstream_base_url> <api_key> <model_name>
Prints the listening port to stdout, then serves until killed.
"""

import json
import re
import socket
import sys
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

UPSTREAM_BASE = sys.argv[1].rstrip("/")
API_KEY = sys.argv[2]
MODEL_NAME = sys.argv[3] if len(sys.argv) > 3 else ""


def fix_sse_line(line_bytes):
    """Fix a single SSE line (as bytes), returning corrected bytes."""
    line = line_bytes.decode("utf-8", errors="replace")

    # Fix 1: add space after "data:" if missing
    if line.startswith("data:") and not line.startswith("data: "):
        line = "data: " + line[5:]

    if not line.startswith("data: {"):
        return line.encode("utf-8")

    # Parse the JSON payload to apply fixes
    try:
        payload = json.loads(line[6:])
    except (json.JSONDecodeError, ValueError):
        return line.encode("utf-8")

    changed = False

    # Fix 2: add missing "index" to tool_calls deltas
    for choice in payload.get("choices", []):
        delta = choice.get("delta", {})
        for tc in delta.get("tool_calls", []):
            if "index" not in tc:
                tc["index"] = 0
                changed = True

    # Fix 3: rewrite gpt-4o-mini → configured model
    if MODEL_NAME and payload.get("model", "").startswith("gpt-4o-mini"):
        payload["model"] = MODEL_NAME
        changed = True

    if changed:
        return ("data: " + json.dumps(payload, separators=(",", ":"))).encode("utf-8")
    return line.encode("utf-8")


class ProxyHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        self._proxy()

    def do_GET(self):
        self._proxy()

    def _proxy(self):
        content_len = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_len) if content_len else None

        # Rewrite gpt-4o-mini in request body too
        if body and MODEL_NAME:
            try:
                req_json = json.loads(body)
                if req_json.get("model", "").startswith("gpt-4o-mini"):
                    req_json["model"] = MODEL_NAME
                    body = json.dumps(req_json).encode("utf-8")
            except (json.JSONDecodeError, ValueError):
                pass

        url = UPSTREAM_BASE + self.path
        req = Request(url, data=body, method=self.command)
        req.add_header("Authorization", f"Bearer {API_KEY}")
        req.add_header("Content-Type", self.headers.get("Content-Type", "application/json"))
        # Force HTTP/1.1 behavior — avoid HTTP/2 RST_STREAM issues
        req.add_header("Connection", "keep-alive")

        try:
            resp = urlopen(req, timeout=600)
        except HTTPError as e:
            self.send_response(e.code)
            for key, val in e.headers.items():
                if key.lower() not in ("transfer-encoding", "connection"):
                    self.send_header(key, val)
            self.end_headers()
            try:
                self.wfile.write(e.read())
            except BrokenPipeError:
                pass
            return
        except (URLError, OSError) as e:
            self.send_response(502)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(f"Upstream error: {e}\n".encode())
            return

        self.send_response(resp.status)
        content_type = resp.headers.get("Content-Type", "")
        is_streaming = "text/event-stream" in content_type
        for key, val in resp.headers.items():
            if key.lower() not in ("transfer-encoding", "connection"):
                self.send_header(key, val)
        self.end_headers()

        if is_streaming:
            self._stream_sse(resp)
        else:
            try:
                self.wfile.write(resp.read())
            except BrokenPipeError:
                pass

    def _stream_sse(self, resp):
        """Read upstream SSE, fix each line, forward to client."""
        saw_done = False
        try:
            for raw_line in resp:
                raw_line = raw_line.rstrip(b"\r\n")
                if raw_line == b"data: [DONE]" or raw_line == b"data:[DONE]":
                    saw_done = True
                    self.wfile.write(b"data: [DONE]\n\n")
                    self.wfile.flush()
                    continue
                fixed = fix_sse_line(raw_line)
                self.wfile.write(fixed + b"\n")
                # Blank line after data lines to delimit SSE events
                if fixed.startswith(b"data: "):
                    self.wfile.write(b"\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            return
        finally:
            resp.close()
        # Ensure stream ends with [DONE]
        if not saw_done:
            try:
                self.wfile.write(b"data: [DONE]\n\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass

    def log_message(self, fmt, *args):
        """Suppress per-request logs — too noisy in container."""
        pass


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


def main():
    # Bind to any available port on localhost
    server = ThreadedHTTPServer(("127.0.0.1", 0), ProxyHandler)
    port = server.server_address[1]
    # Print port for entrypoint to capture — MUST be first line of stdout
    print(port, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
