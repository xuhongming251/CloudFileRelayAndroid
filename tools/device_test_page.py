#!/usr/bin/env python3
"""Small local model page for USB-connected Android smoke tests."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


PAGE = b"""<!doctype html>
<html><head><meta name="viewport" content="width=device-width"></head>
<body style="font-family:sans-serif;padding:24px;background:#f8fafc">
  <h1>CloudFileRelay device test</h1>
  <p>This page links to a small public Hugging Face file.</p>
  <div style="display:grid;gap:12px">
    <a href="https://huggingface.co/openai-community/gpt2/resolve/main/config.json?download=true&amp;test=1">Qwen2.5-0.5B-Instruct-IQ2_M-super-long-quantization-name.gguf 665 B</a>
    <a href="https://huggingface.co/openai-community/gpt2/resolve/main/config.json?download=true&amp;test=2">Qwen2.5-0.5B-Instruct-IQ3_M-super-long-quantization-name.gguf 665 B</a>
    <a href="https://huggingface.co/openai-community/gpt2/resolve/main/config.json?download=true&amp;test=3">Qwen2.5-0.5B-Instruct-Q4_K_M-super-long-quantization-name.gguf 665 B</a>
    <a href="https://huggingface.co/openai-community/gpt2/resolve/main/config.json?download=true&amp;test=4">Qwen2.5-0.5B-Instruct-Q5_K_L-super-long-quantization-name.gguf 665 B</a>
    <a href="https://huggingface.co/openai-community/gpt2/resolve/main/config.json?download=true&amp;test=5">Qwen2.5-0.5B-Instruct-Q6_K-super-long-quantization-name.gguf 665 B</a>
  </div>
</body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format, *_args):
        pass

    def do_GET(self):
        if self.path.split("?", 1)[0] != "/test-model":
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(PAGE)))
        self.end_headers()
        self.wfile.write(PAGE)


if __name__ == "__main__":
    print("Device test page: http://127.0.0.1:8788/test-model", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 8788), Handler).serve_forever()
