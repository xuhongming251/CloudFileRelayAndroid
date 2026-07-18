#!/usr/bin/env python3
"""Inspect the currently debug-enabled Android WebView through CDP."""

import json
import time
import urllib.request

import websocket


targets = json.load(urllib.request.urlopen("http://127.0.0.1:9223/json"))
target = next(item for item in targets if item.get("type") == "page")
socket = websocket.create_connection(target["webSocketDebuggerUrl"], timeout=1)


def send(message_id, method, params=None):
    socket.send(json.dumps({"id": message_id, "method": method, "params": params or {}}))


send(1, "Network.enable")
send(2, "Runtime.enable")
send(3, "Runtime.evaluate", {
    "expression": "JSON.stringify({ua:navigator.userAgent,styles:[...document.styleSheets].map(s=>s.href),links:[...document.querySelectorAll('link[rel=stylesheet]')].map(l=>({href:l.href,media:l.media}))})",
    "returnByValue": True,
})

deadline = time.time() + 3
while time.time() < deadline:
    try:
        event = json.loads(socket.recv())
    except Exception:
        continue
    if event.get("id") == 3:
        value = event.get("result", {}).get("result", {}).get("value")
        print(json.dumps(json.loads(value), ensure_ascii=False, indent=2))
        break

send(4, "Page.reload", {"ignoreCache": True})
deadline = time.time() + 12
while time.time() < deadline:
    try:
        event = json.loads(socket.recv())
    except Exception:
        continue
    method = event.get("method")
    params = event.get("params", {})
    if method == "Network.responseReceived" and params.get("type") in {"Stylesheet", "Script"}:
        response = params.get("response", {})
        print(json.dumps({
            "kind": params.get("type"),
            "status": response.get("status"),
            "mime": response.get("mimeType"),
            "url": response.get("url"),
        }, ensure_ascii=False))
    elif method == "Network.loadingFailed":
        print(json.dumps({
            "kind": "failed",
            "type": params.get("type"),
            "error": params.get("errorText"),
            "blocked": params.get("blockedReason"),
        }, ensure_ascii=False))

socket.close()
