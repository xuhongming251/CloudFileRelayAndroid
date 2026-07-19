(function () {
  "use strict";

  const port = browser.runtime.connectNative("cloudfilerelay");
  port.postMessage({type: "ready"});

  function isHuggingFaceResolve(url) {
    try {
      const parsed = new URL(url);
      return (parsed.hostname === "huggingface.co" || parsed.hostname.endsWith(".huggingface.co")) && parsed.pathname.includes("/resolve/");
    } catch (_) {
      return false;
    }
  }

  function isAuthenticationPage(url) {
    try {
      const parsed = new URL(url);
      return parsed.hostname === "auth.civitai.com"
        || (parsed.hostname.endsWith(".civitai.com") && /\/login(?:\/|$)/i.test(parsed.pathname));
    } catch (_) {
      return false;
    }
  }

  function isHtmlResponse(response) {
    const type = (response.headers.get("content-type") || "").toLowerCase();
    return type.includes("text/html") || type.includes("application/xhtml");
  }

  async function request(url, method, headers) {
    return fetch(url, {
      method,
      headers: headers || {},
      credentials: "include",
      redirect: "follow",
      cache: "no-store"
    });
  }

  function readableSize(kb) {
    const value = Number(kb || 0);
    if (value >= 1048576) return (value / 1048576).toFixed(2) + " GB";
    if (value >= 1024) return (value / 1024).toFixed(1) + " MB";
    return value > 0 ? value.toFixed(0) + " KB" : "";
  }

  function readableBytes(bytes) {
    const value = Number(bytes || 0);
    if (value >= 1073741824) return (value / 1073741824).toFixed(2) + " GB";
    if (value >= 1048576) return (value / 1048576).toFixed(1) + " MB";
    if (value >= 1024) return (value / 1024).toFixed(0) + " KB";
    return value > 0 ? value + " B" : "";
  }

  browser.runtime.onMessage.addListener(message => {
    if (!message) return undefined;
    if (message.type === "loadHuggingFaceFiles") {
      return (async () => {
        const apiUrl = "https://huggingface.co/api/models/" + message.repo + "/tree/" + message.treePath
          + "?recursive=false&expand=false";
        const response = await fetch(apiUrl, {credentials: "include", cache: "no-store"});
        if (!response.ok) throw new Error("Hugging Face API " + response.status);
        const entries = await response.json();
        const revision = String(message.treePath || "main").split("/")[0] || "main";
        return {
          files: (Array.isArray(entries) ? entries : [])
            .filter(item => item.type === "file" && /\.(safetensors|gguf|ckpt|bin|pt|pth|onnx|tflite)$/i.test(item.path || ""))
            .map(item => ({
              name: item.path.split("/").pop(),
              url: "https://huggingface.co/" + message.repo + "/resolve/" + encodeURIComponent(revision) + "/"
                + item.path.split("/").map(encodeURIComponent).join("/") + "?download=true",
              size: readableBytes(item.size)
            }))
        };
      })();
    }
    if (message.type !== "loadCivitaiFiles") return undefined;
    return (async () => {
      const response = await fetch("https://civitai.com/api/v1/models/" + message.modelId, {
        credentials: "include",
        cache: "no-store"
      });
      if (!response.ok) throw new Error("Civitai API " + response.status);
      const model = await response.json();
      const versions = model.modelVersions || [];
      const selected = String(message.versionId || "");
      const version = versions.find(item => String(item.id) === selected) || versions[0];
      if (!version) return {files: []};
      return {
        files: (version.files || []).map(file => ({
          name: file.name || (model.name + ".safetensors"),
          url: file.downloadUrl || ("https://civitai.com/api/download/models/" + version.id + "?fileId=" + file.id),
          size: readableSize(file.sizeKB)
        }))
      };
    })();
  });

  port.onMessage.addListener(async message => {
    if (!message || message.type !== "resolve") return;
    const requestId = message.requestId;
    const sourceUrl = message.url;
    try {
      const response = await request(sourceUrl, "GET", {Range: "bytes=0-0"});
      if (response.status === 401 || response.status === 403) throw new Error("获取下载地址失败，请先登录！");
      if (isAuthenticationPage(response.url) || isHtmlResponse(response)) {
        throw new Error("获取下载地址失败，请先登录！");
      }
      if (!response.ok && response.status !== 206) throw new Error("下载地址暂不可用（" + response.status + "）");
      let finalUrl = response.url || sourceUrl;
      if (isHuggingFaceResolve(sourceUrl)) finalUrl = sourceUrl;
      port.postMessage({
        type: "resolved",
        requestId,
        url: finalUrl,
        status: response.status,
        contentType: response.headers.get("content-type") || "",
        contentRange: response.headers.get("content-range") || ""
      });
      if (response.body) response.body.cancel().catch(() => {});
    } catch (error) {
      port.postMessage({type: "resolveError", requestId, message: error && error.message ? error.message : "无法获取下载地址"});
    }
  });
})();
