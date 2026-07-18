package com.cloudfilerelay.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class BrowserPage {
    private final Activity activity;
    private final View root;
    private final Runnable onClose;
    private final Runnable onOpenTasks;
    private View browserBack;
    private GeckoView geckoView;
    private GeckoSession session;
    private TextView address;
    private ProgressBar progress;
    private LinearLayout detectedPanel;
    private TextView detectedFile;
    private Button selectFileButton;
    private Button startTransferButton;
    private final List<PageDetector.FileCandidate> candidates = new ArrayList<>();
    private final LinkedHashSet<String> selectedUrls = new LinkedHashSet<>();
    private String lastDetectionKey = "";
    private String currentUrl = "";
    private boolean canGoBack;
    private boolean directDownloadMode;
    private boolean detectionDismissed;

    BrowserPage(Activity activity, String requestedUrl, boolean directDownloadMode,
                Runnable onClose, Runnable onOpenTasks) {
        this.activity = activity;
        this.directDownloadMode = directDownloadMode;
        this.onClose = onClose;
        this.onOpenTasks = onOpenTasks;
        if (requestedUrl == null || !requestedUrl.startsWith("http")) {
            requestedUrl = "https://civitai.com/";
        }
        currentUrl = requestedUrl;
        root = activity.getLayoutInflater().inflate(R.layout.activity_browser, null, false);
        geckoView = root.findViewById(R.id.gecko_view);
        address = root.findViewById(R.id.browser_address);
        progress = root.findViewById(R.id.browser_progress);
        detectedPanel = root.findViewById(R.id.detected_panel);
        detectedFile = root.findViewById(R.id.detected_file);
        selectFileButton = root.findViewById(R.id.select_file);
        startTransferButton = root.findViewById(R.id.start_transfer);
        browserBack = root.findViewById(R.id.browser_back);
        renderBackState();
        root.findViewById(R.id.detected_close).setOnClickListener(v -> {
            detectionDismissed = true;
            detectedPanel.animate().alpha(0f).translationY(Ui.dp(activity, 12))
                    .setDuration(160)
                    .withEndAction(() -> {
                        detectedPanel.setVisibility(View.GONE);
                        detectedPanel.setAlpha(1f);
                        detectedPanel.setTranslationY(0f);
                    })
                    .start();
        });
        // Some vendor themes re-tint buttonBar buttons and can turn activity action
        // almost white. Keep the primary action on our own high-contrast state list.
        startTransferButton.setBackgroundTintList(null);
        startTransferButton.setBackgroundResource(R.drawable.primary_action_button);
        startTransferButton.setTextColor(Color.WHITE);

        GeckoRuntime runtime = GeckoRuntimeProvider.get(activity);
        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .allowJavascript(true)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build();
        session = new GeckoSession(sessionSettings);
        GeckoBridge.attachSession(session, this::handleDetection);

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onLocationChange(GeckoSession value, String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                    Boolean hasUserGesture) {
                if (url == null) return;
                if (directDownloadMode && url.startsWith("about:blank")) return;
                if (!url.equals(currentUrl)) {
                    currentUrl = url;
                    lastDetectionKey = "";
                    detectionDismissed = false;
                    candidates.clear();
                    selectedUrls.clear();
                    renderDetection();
                }
                address.setText(displayHost(url));
            }

            @Override public void onCanGoBack(GeckoSession value, boolean valueCanGoBack) {
                canGoBack = valueCanGoBack;
                renderBackState();
            }

            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession value,
                    GeckoSession.NavigationDelegate.LoadRequest request) {
                Uri uri = Uri.parse(request.uri);
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                        value.load(new GeckoSession.Loader().uri(request.uri));
                        return GeckoResult.deny();
                    }
                    return GeckoResult.allow();
                }
                try { activity.startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return GeckoResult.deny();
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession value, String url) {
                if (directDownloadMode && url != null && url.startsWith("about:blank")) {
                    address.setText(displayHost(currentUrl));
                    progress.setVisibility(View.GONE);
                    return;
                }
                detectionDismissed = false;
                lastDetectionKey = "";
                currentUrl = url == null ? currentUrl : url;
                address.setText(displayHost(currentUrl));
                progress.setProgress(0);
                progress.setVisibility(View.VISIBLE);
            }

            @Override public void onProgressChange(GeckoSession value, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override public void onPageStop(GeckoSession value, boolean success) {
                progress.setVisibility(View.GONE);
            }
        });

        session.open(runtime);
        geckoView.setSession(session);

        browserBack.setOnClickListener(v -> {
            if (canGoBack && session != null) session.goBack();
        });
        root.findViewById(R.id.browser_home).setOnClickListener(v -> {
            if (onClose != null) onClose.run();
        });
        root.findViewById(R.id.browser_refresh).setOnClickListener(v -> session.reload());
        selectFileButton.setOnClickListener(v -> chooseFile());
        startTransferButton.setOnClickListener(v -> {
            List<PageDetector.FileCandidate> selected = selectedCandidates();
            if (!selected.isEmpty()) showTransferDialog(selected);
        });

        if (directDownloadMode) {
            PageDetector.FileCandidate direct = new PageDetector.FileCandidate(
                    PageDetector.filenameForDirectUrl(currentUrl), currentUrl, "");
            candidates.add(direct);
            selectedUrls.add(direct.url);
            address.setText(displayHost(currentUrl));
            renderDetection();
        } else {
            session.load(new GeckoSession.Loader().uri(currentUrl));
        }
    }

    private String displayHost(String url) {
        if (url == null) return "正在加载…";
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? url : "🔒  " + host;
        } catch (Exception ignored) { return url; }
    }

    private void handleDetection(String rawResult) {
        try {
            JSONObject root = new JSONObject(rawResult);
            String page = root.optString("page");
            if (!page.isEmpty()) currentUrl = page;
            List<PageDetector.FileCandidate> found = PageDetector.parse(rawResult);
            if (found.isEmpty()) return;
            if (detectionDismissed) return;
            // The page scan can arrive before the provider API. Compare the
            // complete payload so richer filenames/sizes are not discarded.
            String key = rawResult;
            if (key.equals(lastDetectionKey)) return;
            lastDetectionKey = key;
            Set<String> previousSelection = new LinkedHashSet<>(selectedUrls);
            candidates.clear();
            candidates.addAll(found);
            selectedUrls.clear();
            for (PageDetector.FileCandidate candidate : candidates) {
                if (previousSelection.contains(candidate.url)) selectedUrls.add(candidate.url);
            }
            if (selectedUrls.isEmpty() && !candidates.isEmpty()) selectedUrls.add(candidates.get(0).url);
            renderDetection();
        } catch (Exception ignored) {}
    }

    private void renderDetection() {
        if (candidates.isEmpty()) {
            detectedPanel.setVisibility(View.GONE);
            return;
        }
        List<PageDetector.FileCandidate> selected = selectedCandidates();
        if (selected.isEmpty()) {
            selectedUrls.add(candidates.get(0).url);
            selected = selectedCandidates();
        }
        PageDetector.FileCandidate first = selected.get(0);
        String count = candidates.size() > 1 ? " · 共 " + candidates.size() + " 个文件" : "";
        String size = first.size == null || first.size.isEmpty() ? "" : " · " + first.size;
        String selectedText = selected.size() > 1 ? " · 已选 " + selected.size() + " 个" : "";
        detectedFile.setText(String.format(java.util.Locale.getDefault(), "%s%s%s%s", first.name, size, count, selectedText));
        selectFileButton.setText(candidates.size() > 1 ? "选择文件" : "查看文件");
        startTransferButton.setText("立即转存（" + selected.size() + "）");
        startTransferButton.setEnabled(!selected.isEmpty());
        startTransferButton.setAlpha(selected.isEmpty() ? 0.45f : 1f);
        detectedPanel.setVisibility(View.VISIBLE);
    }

    private void chooseFile() {
        if (candidates.isEmpty()) return;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Ui.background(Color.WHITE, 28, activity));
        sheet.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 10), Ui.dp(activity, 20), Ui.dp(activity, 18));

        View handle = new View(activity);
        handle.setBackground(Ui.background(Color.rgb(203, 213, 225), 3, activity));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(Ui.dp(activity, 42), Ui.dp(activity, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = Ui.dp(activity, 16);
        sheet.addView(handle, handleParams);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(activity);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(Ui.text(activity, "选择模型文件", 21, Ui.TEXT, true));
        titleBox.addView(Ui.text(activity, "可同时选择多个文件转存", 12, Ui.MUTED, false));
        header.addView(titleBox, new LinearLayout.LayoutParams(0, Ui.dp(activity, 58), 1));
        TextView selectAll = Ui.text(activity, "全选", 14, Ui.BRAND, true);
        selectAll.setGravity(Gravity.CENTER);
        selectAll.setBackground(Ui.background(Ui.BRAND_SOFT, 12, activity));
        header.addView(selectAll, new LinearLayout.LayoutParams(Ui.dp(activity, 68), Ui.dp(activity, 38)));
        sheet.addView(header);

        View.OnTouchListener dismissGesture = createSheetDismissGesture(dialog, sheet);
        handle.setOnTouchListener(dismissGesture);
        titleBox.setOnTouchListener(dismissGesture);

        LinkedHashSet<String> draftSelection = new LinkedHashSet<>(selectedUrls);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        ArrayList<LinearLayout> rowViews = new ArrayList<>();
        ArrayList<CheckBox> checks = new ArrayList<>();

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rows);
        int listHeight = Math.min(Ui.dp(activity, 410), Ui.dp(activity, Math.max(62, candidates.size() * 49)));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight);
        scrollParams.topMargin = Ui.dp(activity, 10);
        scrollParams.bottomMargin = Ui.dp(activity, 12);
        sheet.addView(scroll, scrollParams);

        Button done = new Button(activity);
        done.setAllCaps(false);
        done.setTextSize(15);
        done.setTextColor(Color.WHITE);
        done.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        done.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.BRAND));
        sheet.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 54)));

        Runnable refreshRows = () -> {
            for (int i = 0; i < candidates.size(); i++) {
                boolean checked = draftSelection.contains(candidates.get(i).url);
                checks.get(i).setChecked(checked);
                rowViews.get(i).setBackground(checked
                        ? Ui.bordered(Ui.BRAND_SOFT, Color.rgb(199, 210, 254), 16, activity)
                        : Ui.bordered(Color.WHITE, Ui.BORDER, 16, activity));
            }
            boolean hasSelection = !draftSelection.isEmpty();
            done.setEnabled(hasSelection);
            done.setAlpha(hasSelection ? 1f : 0.45f);
            done.setText(hasSelection ? "完成 · 已选 " + draftSelection.size() + " 个文件" : "至少选择一个文件");
            selectAll.setText(draftSelection.size() == candidates.size() ? "取消全选" : "全选");
        };

        for (PageDetector.FileCandidate file : candidates) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Ui.setPadding(row, 10, 3);

            TextView name = Ui.text(activity, file.name, 13, Ui.TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(null);
            name.setPadding(0, 0, Ui.dp(activity, 12), 0);
            HorizontalScrollView nameScroller = new HorizontalScrollView(activity);
            nameScroller.setHorizontalScrollBarEnabled(true);
            nameScroller.setScrollbarFadingEnabled(false);
            nameScroller.setFillViewport(false);
            nameScroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            nameScroller.addView(name, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(nameScroller, new LinearLayout.LayoutParams(
                    0, Ui.dp(activity, 30), 1));

            if (file.size != null && !file.size.isEmpty()) {
                TextView size = Ui.text(activity, file.size, 11, Ui.MUTED, false);
                size.setGravity(Gravity.CENTER);
                size.setPadding(Ui.dp(activity, 7), 0, Ui.dp(activity, 3), 0);
                row.addView(size, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 30)));
            }

            CheckBox check = new CheckBox(activity);
            check.setClickable(false);
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.BRAND));
            row.addView(check, new LinearLayout.LayoutParams(Ui.dp(activity, 40), Ui.dp(activity, 40)));

            row.setOnClickListener(v -> {
                if (!draftSelection.remove(file.url)) draftSelection.add(file.url);
                refreshRows.run();
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 46));
            rowParams.bottomMargin = Ui.dp(activity, 3);
            rows.addView(row, rowParams);
            rowViews.add(row);
            checks.add(check);
        }

        selectAll.setOnClickListener(v -> {
            if (draftSelection.size() == candidates.size()) draftSelection.clear();
            else {
                draftSelection.clear();
                for (PageDetector.FileCandidate file : candidates) draftSelection.add(file.url);
            }
            refreshRows.run();
        });
        done.setOnClickListener(v -> {
            selectedUrls.clear();
            selectedUrls.addAll(draftSelection);
            renderDetection();
            dialog.dismiss();
        });
        refreshRows.run();

        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.46f;
            window.setAttributes(attributes);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (window != null) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight());
            sheet.animate().translationY(0f).setDuration(220).start();
        });
    }

    private View.OnTouchListener createSheetDismissGesture(Dialog dialog, View sheet) {
        int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        int dismissDistance = Ui.dp(activity, 92);
        float[] downY = new float[1];
        boolean[] dragging = {false};
        return (view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sheet.animate().cancel();
                    downY[0] = event.getRawY();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float distance = Math.max(0f, event.getRawY() - downY[0]);
                    if (!dragging[0] && distance > touchSlop) {
                        dragging[0] = true;
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging[0]) {
                        sheet.setTranslationY(distance);
                        float fade = 1f - Math.min(0.16f, distance / Math.max(sheet.getHeight(), 1f) * 0.32f);
                        sheet.setAlpha(fade);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging[0] && sheet.getTranslationY() >= dismissDistance) {
                        float target = Math.max(sheet.getHeight(), Ui.dp(activity, 560));
                        sheet.animate().translationY(target).alpha(0.82f).setDuration(190)
                                .withEndAction(dialog::dismiss).start();
                    } else {
                        sheet.animate().translationY(0f).alpha(1f).setDuration(180).start();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    sheet.animate().translationY(0f).alpha(1f).setDuration(180).start();
                    return true;
                default:
                    return false;
            }
        };
    }

    private List<PageDetector.FileCandidate> selectedCandidates() {
        ArrayList<PageDetector.FileCandidate> output = new ArrayList<>();
        for (PageDetector.FileCandidate candidate : candidates) {
            if (selectedUrls.contains(candidate.url)) output.add(candidate);
        }
        return output;
    }

    private void showTransferDialog(List<PageDetector.FileCandidate> files) {
        PageDetector.FileCandidate file = files.get(0);
        boolean multiple = files.size() > 1;
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(activity, 22), Ui.dp(activity, 6), Ui.dp(activity, 22), 0);

        String summaryText = multiple
                ? "已选择 " + files.size() + " 个文件\n" + file.name + (files.size() > 1 ? " 等" : "")
                : file.name + (file.size == null || file.size.isEmpty() ? "" : "\n" + file.size);
        TextView summary = Ui.text(activity, summaryText, 14, Ui.TEXT, true);
        summary.setBackground(Ui.background(Color.rgb(248, 250, 252), 14, activity));
        Ui.setPadding(summary, 14, 10);
        form.addView(summary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 64)));

        EditText filename = null;
        if (!multiple) {
            TextView filenameLabel = Ui.text(activity, "文件名", 12, Ui.MUTED, true);
            form.addView(filenameLabel, marginTop(36, 10));
            filename = new EditText(activity);
            filename.setText(file.name);
            filename.setTextSize(14);
            filename.setSingleLine(true);
            filename.setInputType(InputType.TYPE_CLASS_TEXT);
            filename.setSelectAllOnFocus(false);
            form.addView(filename, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 50)));
        } else {
            TextView naming = Ui.text(activity, "每个文件将保留识别到的原始文件名", 12, Ui.MUTED, false);
            form.addView(naming, marginTop(36, 8));
        }

        form.addView(Ui.text(activity, "转存到", 12, Ui.MUTED, true), marginTop(36, 8));
        RadioGroup targets = new RadioGroup(activity);
        targets.setOrientation(RadioGroup.HORIZONTAL);
        String[] values = {"quark", "baidu", "mobile"};
        String[] labels = {"夸克网盘", "百度网盘", "移动云盘"};
        String remembered = activity.getSharedPreferences("relay_ui", Activity.MODE_PRIVATE).getString("target", "quark");
        for (int i = 0; i < values.length; i++) {
            RadioButton option = new RadioButton(activity);
            option.setId(View.generateViewId());
            option.setTag(values[i]);
            option.setText(labels[i]);
            option.setTextSize(12);
            option.setChecked(values[i].equals(remembered));
            targets.addView(option, new RadioGroup.LayoutParams(0, Ui.dp(activity, 52), 1));
        }
        form.addView(targets);

        CheckBox remember = new CheckBox(activity);
        remember.setText("下次默认使用所选网盘");
        remember.setTextSize(12);
        remember.setChecked(true);
        form.addView(remember, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 44)));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("确认转存")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton(multiple ? "转存 " + files.size() + " 个文件" : "开始转存", null)
                .create();
        EditText finalFilename = filename;
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String finalName = multiple ? null : finalFilename.getText().toString().trim();
            if (!multiple && finalName.isEmpty()) {
                finalFilename.setError("请输入文件名");
                return;
            }
            RadioButton selected = targets.findViewById(targets.getCheckedRadioButtonId());
            String target = selected == null ? "quark" : String.valueOf(selected.getTag());
            if (remember.isChecked()) activity.getSharedPreferences("relay_ui", Activity.MODE_PRIVATE).edit().putString("target", target).apply();
            dialog.dismiss();
            beginTransfer(files, finalName, target);
        }));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.BRAND);
    }

    private LinearLayout.LayoutParams marginTop(int heightDp, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, heightDp));
        params.topMargin = Ui.dp(activity, topDp);
        return params;
    }

    private void beginTransfer(List<PageDetector.FileCandidate> files, String singleFilename, String target) {
        Toast.makeText(activity, files.size() > 1
                ? "正在创建 " + files.size() + " 个转存任务…" : "正在获取下载地址…", Toast.LENGTH_SHORT).show();
        AtomicInteger remaining = new AtomicInteger(files.size());
        AtomicInteger created = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        Runnable finishOne = () -> {
            if (remaining.decrementAndGet() != 0) return;
            if (created.get() > 0) {
                Toast.makeText(activity,
                        errors.get() == 0 ? "转存任务已创建" : "已创建 " + created.get() + " 个，" + errors.get() + " 个失败",
                        Toast.LENGTH_SHORT).show();
                openTaskList();
            } else {
                Toast.makeText(activity, "未能创建转存任务，请重试", Toast.LENGTH_LONG).show();
            }
        };

        for (PageDetector.FileCandidate file : files) {
            DownloadResolver.resolve(session, file.url, new DownloadResolver.Callback() {
                @Override public void onResolved(String finalUrl) {
                    String provider = PageDetector.providerFor(currentUrl);
                    if ("Web".equals(provider)) provider = file.url.contains("civitai.com") ? "Civitai" : "Hugging Face";
                    String filename = files.size() == 1 && singleFilename != null ? singleFilename : file.name;
                    TaskItem task = TaskStore.add(activity, filename, provider, target, file.size, finalUrl);
                    RelayClient.submit(activity, task, finalUrl, new RelayClient.Callback() {
                        @Override public void onSuccess(TaskItem item) {
                            created.incrementAndGet();
                            finishOne.run();
                        }

                        @Override public void onError(TaskItem item, String message) {
                            created.incrementAndGet();
                            errors.incrementAndGet();
                            finishOne.run();
                        }
                    });
                }

                @Override public void onError(String message) {
                    errors.incrementAndGet();
                    finishOne.run();
                }
            });
        }
    }

    private void openTaskList() {
        if (onOpenTasks != null) onOpenTasks.run();
    }

    void handleBack() {
        if (canGoBack && session != null) session.goBack();
        else if (onClose != null) onClose.run();
    }

    private void renderBackState() {
        if (browserBack == null) return;
        browserBack.setEnabled(canGoBack);
        browserBack.setAlpha(canGoBack ? 1f : 0.32f);
    }

    View view() {
        return root;
    }

    void destroy() {
        if (session != null) {
            GeckoBridge.detachSession(session);
            if (geckoView != null && geckoView.getSession() == session) {
                geckoView.releaseSession();
            }
            session.stop();
            session.close();
            session = null;
        }
    }
}
