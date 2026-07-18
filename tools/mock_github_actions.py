#!/usr/bin/env python3
"""Minimal GitHub Actions API mock for Android emulator smoke tests."""

import io
import json
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


class State:
    trace_id = "task_mock_quark"
    polls = 0


def result_zip():
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("result.json", json.dumps({
            "status": "success",
            "share_url": "https://pan.quark.cn/s/mock-result"
        }))
    return output.getvalue()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format, *_args):
        pass

    def send_json(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_HEAD(self):
        path = urlparse(self.path).path
        if path == "/resolve/main/model.safetensors":
            self.send_response(302)
            self.send_header("Location", "/files/model.safetensors?signature=test-only")
            self.end_headers()
        elif path == "/files/model.safetensors":
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", "343932928")
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path.endswith("/dispatches"):
            length = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(length) or b"{}")
            State.trace_id = request.get("inputs", {}).get("trace_id", State.trace_id)
            State.polls = 0
            self.send_response(204)
            self.end_headers()
            return
        self.send_json(404, {"message": "Not Found"})

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/test-model":
            body = b"""<!doctype html><html><head><meta name='viewport' content='width=device-width'></head>
            <body><h1>Flux Portrait LoRA</h1>
            <a href='/resolve/main/model.safetensors?download=true'>model.safetensors 328 MB</a>
            </body></html>"""
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path.endswith("/actions/workflows/upload.yml/runs"):
            self.send_json(200, {"workflow_runs": [{
                "id": 991,
                "name": "Upload " + State.trace_id,
                "display_title": State.trace_id
            }]})
        elif path.endswith("/actions/runs/991/jobs"):
            self.send_json(200, {"jobs": [{"steps": [
                {"name": "10-准备下载", "status": "completed"},
                {"name": "76-上传网盘", "status": "in_progress"}
            ]}]})
        elif path.endswith("/actions/runs/991/artifacts"):
            self.send_json(200, {"artifacts": [{"id": 551, "name": "result"}]})
        elif path.endswith("/actions/artifacts/551/zip"):
            body = result_zip()
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path.endswith("/actions/runs/991"):
            State.polls += 1
            completed = State.polls >= 2
            self.send_json(200, {
                "id": 991,
                "status": "completed" if completed else "in_progress",
                "conclusion": "success" if completed else None
            })
        else:
            self.send_json(404, {"message": "Not Found"})


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 8787), Handler)
    print("Mock GitHub Actions API: http://127.0.0.1:8787", flush=True)
    server.serve_forever()
