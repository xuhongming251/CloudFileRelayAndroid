(function () {
  "use strict";

  let lastPayload = "";
  let civitaiSelectionKey = "";
  let civitaiRefreshSequence = 0;
  let apiFiles = [];
  let huggingFaceTreeKey = "";
  let huggingFaceRefreshSequence = 0;
  let huggingFaceFiles = [];
  let huggingFaceFilesReady = false;
  let nativePort = null;

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

  async function resolveDownload(message, replyPort) {
    const requestId = message.requestId;
    const sourceUrl = message.url;
    try {
      const response = await fetch(sourceUrl, {
        method: "GET",
        headers: {Range: "bytes=0-0"},
        credentials: "include",
        redirect: "follow",
        cache: "no-store"
      });
      if (response.status === 401 || response.status === 403) {
        throw new Error("获取下载地址失败，请先登录！");
      }
      if (isAuthenticationPage(response.url) || isHtmlResponse(response)) {
        throw new Error("获取下载地址失败，请先登录！");
      }
      if (!response.ok && response.status !== 206) {
        throw new Error("下载地址暂不可用（" + response.status + "）");
      }
      let finalUrl = response.url || sourceUrl;
      // Xet signs the probe URL for the exact Range header above. It cannot be
      // reused for a full download, and its cdn.hf.co path is easily mistaken
      // for a repository. After validation always submit the canonical
      // /resolve/ URL so the downloader obtains a fresh full-file signature.
      if (isHuggingFaceResolve(sourceUrl)) {
        finalUrl = sourceUrl;
      }
      replyPort.postMessage({
        type: "resolved",
        requestId,
        url: finalUrl,
        status: response.status,
        contentType: response.headers.get("content-type") || "",
        contentRange: response.headers.get("content-range") || ""
      });
      if (response.body) response.body.cancel().catch(() => {});
    } catch (error) {
      replyPort.postMessage({
        type: "resolveError",
        requestId,
        message: error && error.message ? error.message : "无法获取下载地址"
      });
    }
  }

  function connectNativePort() {
    try {
      const port = browser.runtime.connectNative("cloudfilerelay");
      nativePort = port;
      port.onMessage.addListener(message => {
        if (message && message.type === "resolve") resolveDownload(message, port);
      });
      port.onDisconnect.addListener(() => {
        if (nativePort === port) nativePort = null;
        setTimeout(connectNativePort, 600);
      });
      port.postMessage({type: "contentReady"});
    } catch (_) {
      setTimeout(connectNativePort, 600);
    }
  }

  function readableSize(kb) {
    const value = Number(kb || 0);
    if (value >= 1048576) return (value / 1048576).toFixed(2) + " GB";
    if (value >= 1024) return (value / 1024).toFixed(1) + " MB";
    return value > 0 ? value.toFixed(0) + " KB" : "";
  }

  function filenameFromUrl(url) {
    try {
      const parsed = new URL(url);
      const segments = parsed.pathname.split("/").filter(Boolean);
      return decodeURIComponent(segments[segments.length - 1] || "").trim();
    } catch (_) {
      return "";
    }
  }

  function isSizeLabel(text) {
    return /^[0-9]+(?:\.[0-9]+)?\s*(?:B|KB|MB|GB|TB)(?:\s+(?:xet|lfs))?$/i.test(text.trim());
  }

  function isHuggingFaceResolve(url) {
    try {
      const parsed = new URL(url);
      return (parsed.hostname === "huggingface.co" || parsed.hostname.endsWith(".huggingface.co"))
        && parsed.pathname.includes("/resolve/");
    } catch (_) {
      return false;
    }
  }

  function isModelFileUrl(url) {
    try {
      return /\.(?:safetensors|gguf|ckpt|bin|pt|pth|onnx|tflite)$/i.test(new URL(url).pathname);
    } catch (_) {
      return false;
    }
  }

  function huggingFaceRepositoryInfo() {
    if (location.hostname !== "huggingface.co") return null;
    const parts = location.pathname.split("/").filter(Boolean);
    if (parts.length < 2) return null;
    const reserved = new Set([
      "models", "datasets", "spaces", "docs", "settings", "login", "join",
      "organizations", "new", "tasks", "posts", "blog", "pricing", "enterprise"
    ]);
    if (reserved.has(parts[0].toLowerCase())) return null;
    let repositoryName;
    try {
      repositoryName = decodeURIComponent(parts[1]);
    } catch (_) {
      repositoryName = parts[1];
    }
    if (!repositoryName) return null;
    return {
      repo: parts.slice(0, 2).join("/"),
      rootUrl: location.origin + "/" + parts.slice(0, 2).join("/"),
      archiveName: repositoryName + ".zip"
    };
  }

  function selectedCivitaiVersionId() {
    try {
      const anchors = Array.from(document.querySelectorAll('a[href*="/api/download/models/"]'));
      const visible = anchors.find(anchor => anchor.getClientRects().length > 0) || anchors[0];
      const match = visible && visible.href.match(/\/api\/download\/models\/(\d+)/);
      if (match) return match[1];
      return new URL(location.href).searchParams.get("modelVersionId") || "";
    } catch (_) {
      return "";
    }
  }

  async function refreshCivitaiFiles() {
    const match = location.pathname.match(/\/models\/(\d+)/);
    if (!match) {
      civitaiSelectionKey = "";
      civitaiRefreshSequence++;
      apiFiles = [];
      return;
    }
    const currentModelId = match[1];
    const selectedVersion = selectedCivitaiVersionId();
    const selectionKey = currentModelId + ":" + selectedVersion;
    if (civitaiSelectionKey === selectionKey) return;
    civitaiSelectionKey = selectionKey;
    const refreshSequence = ++civitaiRefreshSequence;
    apiFiles = [];
    try {
      const result = await browser.runtime.sendMessage({
        type: "loadCivitaiFiles",
        modelId: currentModelId,
        versionId: selectedVersion || ""
      });
      if (refreshSequence !== civitaiRefreshSequence || civitaiSelectionKey !== selectionKey) return;
      apiFiles = result && Array.isArray(result.files) ? result.files : [];
      detect();
    } catch (_) {
      if (refreshSequence === civitaiRefreshSequence && civitaiSelectionKey === selectionKey) {
        apiFiles = [];
      }
    }
  }

  async function refreshHuggingFaceFiles() {
    if (location.hostname !== "huggingface.co") {
      huggingFaceTreeKey = "";
      huggingFaceRefreshSequence++;
      huggingFaceFiles = [];
      huggingFaceFilesReady = false;
      return;
    }
    const repository = huggingFaceRepositoryInfo();
    const parts = location.pathname.split("/").filter(Boolean);
    const treeIndex = parts.indexOf("tree");
    let treePath = "";
    if (repository && parts.length === 2) {
      treePath = "main";
    } else if (repository && treeIndex === 2 && parts.length >= 4) {
      treePath = parts.slice(3).join("/") || "main";
    } else {
      huggingFaceTreeKey = "";
      huggingFaceRefreshSequence++;
      huggingFaceFiles = [];
      huggingFaceFilesReady = false;
      return;
    }
    const key = repository.repo + ":" + treePath;
    if (huggingFaceTreeKey === key) return;
    huggingFaceTreeKey = key;
    const refreshSequence = ++huggingFaceRefreshSequence;
    huggingFaceFiles = [];
    huggingFaceFilesReady = false;
    try {
      const result = await browser.runtime.sendMessage({
        type: "loadHuggingFaceFiles",
        repo: repository.repo,
        treePath
      });
      if (refreshSequence !== huggingFaceRefreshSequence || huggingFaceTreeKey !== key) return;
      huggingFaceFiles = result && Array.isArray(result.files) ? result.files : [];
      huggingFaceFilesReady = true;
      detect();
    } catch (_) {
      if (refreshSequence !== huggingFaceRefreshSequence || huggingFaceTreeKey !== key) return;
      huggingFaceFiles = [];
      huggingFaceFilesReady = true;
      detect();
    }
  }

  function detect() {
    try {
      refreshCivitaiFiles();
      refreshHuggingFaceFiles();
      const files = [];
      const seen = new Set();
      function add(source) {
        const url = source.href || "";
        if (!url || seen.has(url)) return;
        let text = (source.getAttribute && source.getAttribute("download")) || source.innerText || "";
        text = text.trim().replace(/\s+/g, " ");
        let name = text;
        const urlFilename = filenameFromUrl(url);
        // Hugging Face download controls often expose only labels such as
        // "10.2 GB xet". The /resolve/ URL always contains the real filename.
        if (isHuggingFaceResolve(url) || !name || name.length > 120 || isSizeLabel(name)) {
          name = urlFilename || name;
        }
        const sizeMatch = text.match(/([0-9.]+\s*(?:KB|MB|GB|TB))/i);
        seen.add(url);
        files.push({name, url, size: sizeMatch ? sizeMatch[1] : ""});
      }

      apiFiles.forEach(file => {
        if (!seen.has(file.url)) {
          seen.add(file.url);
          files.push(file);
        }
      });
      huggingFaceFiles.forEach(file => {
        if (!seen.has(file.url)) {
          seen.add(file.url);
          files.push(file);
        }
      });
      const huggingFaceRepository = huggingFaceRepositoryInfo();
      if (huggingFaceRepository && huggingFaceFilesReady && !seen.has(huggingFaceRepository.rootUrl)) {
        seen.add(huggingFaceRepository.rootUrl);
        files.push({
          name: huggingFaceRepository.archiveName,
          url: huggingFaceRepository.rootUrl,
          size: "",
          packageAll: true
        });
      }
      if (apiFiles.length === 0) {
        document.querySelectorAll('a[href*="/api/download/models/"]').forEach(add);
      }
      if (location.hostname === "huggingface.co" || location.hostname.endsWith(".huggingface.co")) {
        document.querySelectorAll('a[href*="/resolve/"]').forEach(anchor => {
          if (isModelFileUrl(anchor.href)) add(anchor);
        });
        document.querySelectorAll('a[href*="/blob/"]').forEach(anchor => {
          if (!/\.(safetensors|gguf|ckpt|bin|pt|pth|onnx|tflite)(\?|$)/i.test(anchor.href)) return;
          const url = anchor.href.replace("/blob/", "/resolve/").split("#")[0];
          add({
            href: url + (url.includes("?") ? "&download=true" : "?download=true"),
            innerText: decodeURIComponent(new URL(anchor.href).pathname.split("/").pop())
          });
        });
        if (/\/blob\//.test(location.pathname)) {
          let url = location.href.replace("/blob/", "/resolve/").split("#")[0];
          if (!url.includes("?")) url += "?download=true";
          add({href: url, innerText: decodeURIComponent(location.pathname.split("/").pop())});
        }
      }

      const payload = JSON.stringify({type: "detection", page: location.href, files: files.slice(0, 250)});
      if (payload !== lastPayload) {
        lastPayload = payload;
        browser.runtime.sendNativeMessage("cloudfilerelay", JSON.parse(payload)).catch(() => {});
      }
    } catch (_) {}
  }

  connectNativePort();
  detect();
  setInterval(detect, 1600);
  window.addEventListener("popstate", () => setTimeout(detect, 100));
  document.addEventListener("click", () => {
    if (!/\/models\/\d+/.test(location.pathname)) return;
    // Civitai changes version tabs through client-side state. Probe shortly
    // after the click and again after its animated content has settled.
    setTimeout(detect, 80);
    setTimeout(detect, 360);
    setTimeout(detect, 900);
  }, true);
  const civitaiObserver = new MutationObserver(() => {
    if (!/\/models\/\d+/.test(location.pathname)) return;
    clearTimeout(civitaiObserver.pendingDetection);
    civitaiObserver.pendingDetection = setTimeout(detect, 120);
  });
  civitaiObserver.observe(document.documentElement, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ["href", "aria-selected"]
  });
})();
