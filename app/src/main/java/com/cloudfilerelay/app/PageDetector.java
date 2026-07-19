package com.cloudfilerelay.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class PageDetector {
    static final class FileCandidate {
        final String name;
        final String url;
        final String size;
        final boolean packageAll;
        final boolean submitOriginalUrl;

        FileCandidate(String name, String url, String size) {
            this(name, url, size, false, false);
        }

        FileCandidate(String name, String url, String size, boolean packageAll) {
            this(name, url, size, packageAll, false);
        }

        FileCandidate(String name, String url, String size, boolean packageAll,
                      boolean submitOriginalUrl) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.packageAll = packageAll;
            this.submitOriginalUrl = submitOriginalUrl;
        }
    }

    private PageDetector() {}

    static String providerFor(String pageUrl) {
        try {
            String host = Uri.parse(pageUrl).getHost();
            if (host == null) return "Web";
            if (host.endsWith("civitai.com")) return "Civitai";
            if (host.endsWith("huggingface.co")) return "Hugging Face";
        } catch (Exception ignored) {}
        return "Web";
    }

    static boolean isPreferredModelFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".safetensors") || lower.endsWith(".gguf")
                || lower.endsWith(".ckpt") || lower.endsWith(".bin")
                || lower.endsWith(".pt") || lower.endsWith(".pth")
                || lower.endsWith(".onnx") || lower.endsWith(".tflite");
    }

    static String filenameForDirectUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.contains("/api/download/models/")) return "Civitai 模型文件";
            String segment = uri.getLastPathSegment();
            if (segment != null && !segment.isEmpty()) {
                String decoded = Uri.decode(segment).trim();
                if (!decoded.matches("[0-9]+")) return decoded;
            }
        } catch (Exception ignored) {}
        return "待转存模型文件";
    }

    static List<FileCandidate> parse(String json) {
        ArrayList<FileCandidate> output = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray files = root.optJSONArray("files");
            if (files == null) return output;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String url = file.optString("url");
                String name = cleanName(file.optString("name"), url);
                if (url.startsWith("http") && !name.isEmpty()) {
                    output.add(new FileCandidate(name, url, file.optString("size"),
                            file.optBoolean("packageAll", false)));
                }
            }
        } catch (Exception ignored) {}
        output.sort(Comparator.comparing((FileCandidate f) -> f.packageAll)
                .thenComparing(f -> !f.name.toLowerCase(Locale.ROOT).endsWith(".safetensors"))
                .thenComparing(f -> !isPreferredModelFile(f.name))
                .thenComparing(f -> f.name));
        return output;
    }

    private static String cleanName(String text, String url) {
        String value = text == null ? "" : text.trim().replace('\n', ' ');
        if (value.contains("  ")) value = value.replaceAll("\\s+", " ");
        boolean huggingFaceResolve = false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            huggingFaceResolve = host != null
                    && (host.equals("huggingface.co") || host.endsWith(".huggingface.co"))
                    && uri.getPath() != null && uri.getPath().contains("/resolve/");
        } catch (Exception ignored) {}
        if (huggingFaceResolve) {
            value = filenameForDirectUrl(url);
        } else if (url.contains("/api/download/models/") && !isPreferredModelFile(value)) {
            value = "Civitai 模型文件";
        } else if (value.matches("(?i)^[0-9]+(?:\\.[0-9]+)?\\s*(?:B|KB|MB|GB|TB)(?:\\s+(?:xet|lfs))?$")
                || value.length() > 120
                || !value.matches("(?i).*\\.(?:safetensors|gguf|ckpt|bin|pt|pth|onnx|tflite|json|zip)(?:\\s.*)?$")) {
            try {
                String segment = Uri.parse(url).getLastPathSegment();
                if (segment != null && !segment.isEmpty()) value = segment;
            } catch (Exception ignored) {}
        }
        value = value.replaceFirst("\\s+[0-9]+(?:\\.[0-9]+)?\\s*(?:B|KB|MB|GB|TB)(?:\\s.*)?$", "");
        return value;
    }

    static final String SCRIPT = "(function(){try{" +
            "var out=[],seen={};" +
            "function add(a){var u=a.href||'';if(!u||seen[u])return;" +
            "var t=(a.getAttribute('download')||a.innerText||'').trim().replace(/\\s+/g,' ');" +
            "var n=t;try{var p=new URL(u).pathname.split('/');if(!n||n.length>120)n=decodeURIComponent(p[p.length-1]);}catch(e){}" +
            "var m=t.match(/([0-9.]+\\s*(?:KB|MB|GB|TB))/i);seen[u]=1;out.push({name:n,url:u,size:m?m[1]:''});}" +
            "var mm=location.pathname.match(/\\/models\\/(\\d+)/);" +
            "if(mm&&window.__cfrModelId!==mm[1]){window.__cfrModelId=mm[1];window.__cfrApiFiles=[];" +
            "fetch('/api/v1/models/'+mm[1]).then(function(r){return r.json();}).then(function(d){" +
            "var a=document.querySelector('a[href*=\"/api/download/models/\"]');var vid='';" +
            "if(a){var vm=a.href.match(/\\/api\\/download\\/models\\/(\\d+)/);if(vm)vid=vm[1];}" +
            "if(!vid){try{vid=new URL(location.href).searchParams.get('modelVersionId')||'';}catch(e){}}" +
            "var vs=d.modelVersions||[];var v=vs.find(function(x){return String(x.id)===String(vid);})||vs[0];" +
            "window.__cfrApiFiles=v?(v.files||[]).map(function(f){var kb=f.sizeKB||0;var sz=kb>=1048576?(kb/1048576).toFixed(2)+' GB':kb>=1024?(kb/1024).toFixed(1)+' MB':kb.toFixed(0)+' KB';return{name:f.name||'Civitai 模型文件',url:'https://civitai.com/api/download/models/'+v.id+'?fileId='+f.id,size:sz};}):[];" +
            "}).catch(function(){window.__cfrApiFiles=[];});}" +
            "(window.__cfrApiFiles||[]).forEach(function(f){if(!seen[f.url]){seen[f.url]=1;out.push(f);}});" +
            "document.querySelectorAll('a[href*=\"/api/download/models/\"]').forEach(add);" +
            "document.querySelectorAll('a[href*=\"/resolve/\"]').forEach(function(a){if(/download=true|\\.(safetensors|gguf|ckpt|bin|pt|pth|onnx|tflite)(\\?|$)/i.test(a.href))add(a);});" +
            "if(/\\/blob\\//.test(location.pathname)){var u=location.href.replace('/blob/','/resolve/').split('#')[0];if(u.indexOf('?')<0)u+='?download=true';add({href:u,innerText:decodeURIComponent(location.pathname.split('/').pop())});}" +
            "return JSON.stringify({page:location.href,files:out.slice(0,40)});" +
            "}catch(e){return JSON.stringify({page:location.href,files:[]});}})();";
}
