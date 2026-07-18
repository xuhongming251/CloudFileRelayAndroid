#!/usr/bin/env python3
import json
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

tasks = {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(fmt % args, flush=True)

    def _json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        path = urlparse(self.path).path
        if path.endswith("model.safetensors") and path.startswith("/resolve/"):
            self.send_response(302)
            self.send_header("Location", "/files/model.safetensors?Authorization=test-only")
            self.end_headers()
            return
        if path == "/files/model.safetensors":
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", "343932928")
            self.end_headers()
            return
        self.send_response(404)
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/test-model":
            body = b"""<!doctype html><html><head><meta name='viewport' content='width=device-width'><title>Relay Test Model</title></head>
            <body style='font-family:sans-serif;padding:20px;background:#f8fafc'>
              <div style='height:240px;border-radius:24px;background:linear-gradient(135deg,#6366f1,#a5b4fc);display:flex;align-items:center;justify-content:center;color:white;font-size:30px'>Flux Portrait LoRA</div>
              <h1>Flux Portrait LoRA</h1><p>Version 1.2</p>
              <a style='display:block;padding:18px;background:white;border-radius:16px' href='/resolve/main/model.safetensors?download=true'>model.safetensors 328 MB</a>
            </body></html>"""
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith("/v1/tasks/"):
            task_id = path.rsplit("/", 1)[-1]
            task = tasks.get(task_id)
            if not task:
                self._json(404, {"message": "not found"})
                return
            task["polls"] += 1
            if task["polls"] >= 2:
                task.update(status="completed", progress=100, share_url="https://pan.quark.cn/s/cloudfilerelay-demo")
            else:
                task.update(status="uploading", progress=64)
            self._json(200, task)
            return
        self._json(404, {"message": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        if path != "/v1/tasks":
            self._json(404, {"message": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        task_id = "mock_" + uuid.uuid4().hex[:12]
        task = {
            "task_id": task_id,
            "status": "queued",
            "progress": 0,
            "share_url": "",
            "polls": 0,
            "filename": payload.get("filename", "model.safetensors"),
            "created_at": int(time.time()),
        }
        tasks[task_id] = task
        self._json(201, task)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8787), Handler).serve_forever()
