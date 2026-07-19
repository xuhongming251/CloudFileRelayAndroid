package com.cloudfilerelay.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    static final String EXTRA_CLEAR_HOME_LINK = "clear_home_link";
    private FrameLayout content;
    private LinearLayout navHome;
    private LinearLayout navBrowse;
    private LinearLayout navTasks;
    private TextView navHomeLabel;
    private TextView navBrowseLabel;
    private TextView navTasksLabel;
    private TextView taskBadge;
    private ImageView navHomeIcon;
    private ImageView navBrowseIcon;
    private ImageView navTasksIcon;
    private String taskFilter = "all";
    private String currentTab = "home";
    private final Handler taskPollHandler = new Handler(Looper.getMainLooper());
    private boolean taskRefreshInFlight;
    private boolean resumed;
    private LinearLayout selectedNav;
    private View openSwipeCard;
    private TextView activeTaskFilename;
    private String expandedTaskId;
    private String homeLinkDraft = "";
    private EditText homeLinkInput;
    private DiscoverPage discoverPage;
    private BrowserPage homeBrowserPage;
    private final Runnable taskPoll = () -> refreshTasks(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        content = findViewById(R.id.content);
        navHome = findViewById(R.id.nav_home);
        navBrowse = findViewById(R.id.nav_browse);
        navTasks = findViewById(R.id.nav_tasks);
        navHomeLabel = findViewById(R.id.nav_home_label);
        navBrowseLabel = findViewById(R.id.nav_browse_label);
        navTasksLabel = findViewById(R.id.nav_tasks_label);
        taskBadge = findViewById(R.id.task_badge);
        navHomeIcon = findViewById(R.id.nav_home_icon);
        navBrowseIcon = findViewById(R.id.nav_browse_icon);
        navTasksIcon = findViewById(R.id.nav_tasks_icon);
        updateTaskBadge();
        clearSubmittedHomeLink(getIntent());

        navHome.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); showHome(); });
        navBrowse.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); showBrowserPicker(); });
        navTasks.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); showTasks(); });

        String startUrl = getIntent().getStringExtra("start_url");
        if (startUrl != null && startUrl.startsWith("http")) {
            openBrowser(startUrl, getIntent().getBooleanExtra("direct_download", false));
            return;
        }
        if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            String shared = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && shared.startsWith("http")) {
                openBrowser(shared);
                return;
            }
        }
        String requestedTab = getIntent().getStringExtra("tab");
        if ("tasks".equals(requestedTab)) showTasks();
        else if ("browse".equals(requestedTab)) showBrowserPicker();
        else showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateTaskBadge();
        if ("tasks".equals(currentTab)) {
            showTasks();
        } else if ("browse".equals(currentTab) && discoverPage != null) {
            discoverPage.start();
        }
        scheduleTaskRefresh(450);
    }

    @Override
    protected void onPause() {
        resumed = false;
        taskPollHandler.removeCallbacks(taskPoll);
        if (discoverPage != null) discoverPage.stop();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        clearSubmittedHomeLink(intent);
        String startUrl = intent.getStringExtra("start_url");
        if (startUrl != null && startUrl.startsWith("http")) {
            openBrowser(startUrl, intent.getBooleanExtra("direct_download", false));
            return;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null && shared.startsWith("http")) {
                openBrowser(shared);
                return;
            }
        }
        String requestedTab = intent.getStringExtra("tab");
        if ("tasks".equals(requestedTab)) showTasks();
        else if ("browse".equals(requestedTab)) showBrowserPicker();
    }

    private void selectNav(LinearLayout selected) {
        applyNavState(navHome, navHomeIcon, navHomeLabel, selected == navHome);
        applyNavState(navBrowse, navBrowseIcon, navBrowseLabel, selected == navBrowse);
        applyNavState(navTasks, navTasksIcon, navTasksLabel, selected == navTasks);
        if (selectedNav != selected) {
            selected.setScaleX(0.94f);
            selected.setScaleY(0.94f);
            selected.animate().scaleX(1f).scaleY(1f).setDuration(170).start();
            selectedNav = selected;
        }
    }

    private void applyNavState(LinearLayout item, ImageView icon, TextView label, boolean active) {
        int color = active ? Ui.BRAND : Ui.MUTED;
        label.setTextColor(color);
        label.setTypeface(android.graphics.Typeface.DEFAULT,
                active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        icon.setColorFilter(color);
        item.setBackground(active ? Ui.background(Ui.BRAND_SOFT, 18, this) : null);
    }

    private void handleTasksChanged() {
        runOnUiThread(() -> {
            updateTaskBadge();
            scheduleTaskRefresh(350);
        });
    }

    private void updateTaskBadge() {
        if (taskBadge == null) return;
        int active = 0;
        for (TaskItem item : TaskStore.load(this)) {
            if (isActiveTask(item)) active++;
        }
        if (active <= 0) {
            taskBadge.setVisibility(View.GONE);
            taskBadge.setText("");
            taskBadge.setContentDescription("当前没有正在进行的任务");
            return;
        }
        taskBadge.setText(active > 99 ? "99+" : String.valueOf(active));
        taskBadge.setContentDescription("正在进行 " + active + " 个任务");
        taskBadge.setVisibility(View.VISIBLE);
    }

    private boolean isActiveTask(TaskItem item) {
        return !TaskItem.COMPLETED.equals(item.status)
                && !TaskItem.FAILED.equals(item.status)
                && !TaskItem.SERVICE_REQUIRED.equals(item.status);
    }

    private void replaceContent(View page) {
        replaceContent(page, true);
    }

    private void replaceContent(View page, boolean animate) {
        content.removeAllViews();
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!animate) return;
        page.setAlpha(0f);
        page.setTranslationY(Ui.dp(this, 8));
        page.animate().alpha(1f).translationY(0f).setDuration(210).start();
    }

    private LinearLayout verticalRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Ui.setPadding(root, 18, 18);
        return root;
    }

    private void showHome() {
        stopDiscoverPage();
        currentTab = "home";
        selectNav(navHome);
        if (homeBrowserPage != null) {
            replaceContent(homeBrowserPage.view());
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = verticalRoot();
        scroll.addView(root);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(Ui.text(this, "云端转存", 27, Ui.TEXT, true));
        root.addView(titleBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));

        root.addView(linkTransferCard());

        root.addView(sectionLabel("浏览模型页面，自动发现模型，实时转存"));
        LinearLayout providers = new LinearLayout(this);
        providers.setOrientation(LinearLayout.HORIZONTAL);
        providers.setClipChildren(false);
        providers.setClipToPadding(false);
        providers.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 5));
        View civitai = providerTile(R.drawable.ic_civitai_brand, "Civitai", "模型与 LoRA",
                Color.rgb(239, 246, 255), Color.rgb(37, 99, 235), "https://civitai.com/");
        View huggingFace = providerTile(R.drawable.ic_huggingface_brand, "Hugging Face", "模型仓库",
                Color.rgb(255, 247, 214), Color.rgb(245, 158, 11), "https://huggingface.co/models");
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 130), 1);
        tileParams.rightMargin = Ui.dp(this, 6);
        providers.addView(civitai, tileParams);
        LinearLayout.LayoutParams secondTileParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 130), 1);
        secondTileParams.leftMargin = Ui.dp(this, 6);
        providers.addView(huggingFace, secondTileParams);
        LinearLayout.LayoutParams providersParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 138));
        providersParams.bottomMargin = Ui.dp(this, 22);
        root.addView(providers, providersParams);

        List<TaskItem> tasks = TaskStore.load(this);
        root.addView(sectionLabel("最近任务"));
        if (tasks.isEmpty()) {
            TextView empty = Ui.text(this, "暂无任务，浏览模型后即可开始转存", 14, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 18, this));
            root.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 92)));
        } else {
            root.addView(taskRow(tasks.get(0)));
        }
        replaceContent(scroll);
    }

    private TextView sectionLabel(String text) {
        TextView label = Ui.text(this, text, 15, Ui.TEXT, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 38));
        label.setLayoutParams(params);
        return label;
    }

    private View linkTransferCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 22, this));
        card.setElevation(Ui.dp(this, 2));
        Ui.setPadding(card, 14, 12);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(this, "输入下载链接", 15, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1));
        TextView help = Ui.text(this, "?", 15, Ui.BRAND, true);
        help.setGravity(Gravity.CENTER);
        help.setContentDescription("使用说明");
        help.setBackground(Ui.background(Ui.BRAND_SOFT, 17, this));
        help.setOnClickListener(v -> showLinkHelp());
        header.addView(help, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        card.addView(header);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(this);
        homeLinkInput = input;
        input.setSingleLine(true);
        input.setText(homeLinkDraft);
        input.setTextSize(13);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Color.rgb(148, 163, 184));
        input.setHint("粘贴实际的文件下载地址");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setBackground(Ui.bordered(Color.rgb(248, 250, 252), Ui.BORDER, 15, this));
        input.setPadding(Ui.dp(this, 13), 0, Ui.dp(this, 12), 0);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                homeLinkDraft = s.toString();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        TextView transfer = Ui.text(this, "转存", 15, Color.WHITE, true);
        transfer.setGravity(Gravity.CENTER);
        transfer.setBackground(Ui.background(Ui.BRAND, 15, this));
        transfer.setOnClickListener(v -> submitHomeLink(input));
        LinearLayout.LayoutParams transferParams = new LinearLayout.LayoutParams(Ui.dp(this, 78), Ui.dp(this, 52));
        transferParams.leftMargin = Ui.dp(this, 9);
        inputRow.addView(transfer, transferParams);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        inputParams.topMargin = Ui.dp(this, 8);
        card.addView(inputRow, inputParams);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submitHomeLink(input);
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = Ui.dp(this, 20);
        card.setLayoutParams(cardParams);
        return card;
    }

    private View providerTile(int iconRes, String name, String subtitle, int iconBackground,
                              int iconTint, String url) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 22, this));
        card.setElevation(Ui.dp(this, 1));
        Ui.setPadding(card, 16, 12);
        ImageView badge = new ImageView(this);
        badge.setImageResource(iconRes);
        badge.setColorFilter(iconTint);
        badge.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        badge.setBackground(Ui.background(iconBackground, 14, this));
        badge.setContentDescription(name);
        card.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        TextView title = Ui.text(this, name, 16, Ui.TEXT, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 30));
        titleParams.topMargin = Ui.dp(this, 8);
        card.addView(title, titleParams);
        card.addView(Ui.text(this, subtitle, 12, Ui.MUTED, false));
        card.setOnClickListener(v -> openHomeBrowser(url));
        return card;
    }

    private View providerCard(int iconRes, String name, String subtitle, int iconBackground,
                              int iconTint, String url) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 20, this));
        card.setElevation(Ui.dp(this, 1));
        Ui.setPadding(card, 14, 8);

        ImageView badge = new ImageView(this);
        badge.setImageResource(iconRes);
        badge.setColorFilter(iconTint);
        badge.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        badge.setBackground(Ui.background(iconBackground, 12, this));
        badge.setContentDescription(name);
        card.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(Ui.dp(this, 14), 0, 0, 0);
        labels.addView(Ui.text(this, name, 17, Ui.TEXT, true));
        labels.addView(Ui.text(this, subtitle, 12, Ui.MUTED, false));
        card.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(Ui.text(this, "›", 28, Ui.MUTED, false), new LinearLayout.LayoutParams(Ui.dp(this, 30), ViewGroup.LayoutParams.MATCH_PARENT));
        card.setOnClickListener(v -> openBrowser(url));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 78));
        params.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(params);
        return card;
    }

    private void showBrowserPicker() {
        stopDiscoverPage();
        currentTab = "browse";
        selectNav(navBrowse);
        discoverPage = new DiscoverPage(this);
        replaceContent(discoverPage.view());
        if (resumed) discoverPage.start();
    }

    private void stopDiscoverPage() {
        if (discoverPage != null) {
            discoverPage.stop();
            discoverPage = null;
        }
    }

    private void openHomeBrowser(String url) {
        stopDiscoverPage();
        stopHomeBrowser();
        currentTab = "home";
        selectNav(navHome);
        homeBrowserPage = new BrowserPage(this, url, false,
                this::closeHomeBrowser, null, this::handleTasksChanged);
        replaceContent(homeBrowserPage.view());
    }

    private void closeHomeBrowser() {
        stopHomeBrowser();
        showHome();
    }

    private void stopHomeBrowser() {
        if (homeBrowserPage != null) {
            homeBrowserPage.destroy();
            homeBrowserPage = null;
        }
    }

    private void openBrowser(String url) {
        openBrowser(url, false);
    }

    private void openBrowser(String url, boolean directDownload) {
        Intent intent = new Intent(this, directDownload
                ? DirectTransferActivity.class : BrowserActivity.class);
        intent.putExtra("start_url", url);
        intent.putExtra("direct_download", directDownload);
        startActivity(intent);
        if (directDownload) overridePendingTransition(0, 0);
        else overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void clearSubmittedHomeLink(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_CLEAR_HOME_LINK, false)) {
            intent.removeExtra(EXTRA_CLEAR_HOME_LINK);
            homeLinkDraft = "";
            if (homeLinkInput != null) homeLinkInput.setText("");
        }
    }

    private void submitHomeLink(EditText input) {
        String value = input.getText().toString().trim();
        if (!value.contains("://") && value.contains(".")) value = "https://" + value;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            input.setError("请输入有效的下载链接");
            input.requestFocus();
            return;
        }
        homeLinkDraft = value;
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        openBrowser(value, true);
    }

    private void showLinkHelp() {
        View content = AppDialog.infoSteps(this, new String[][]{
                {"输入下载地址", "粘贴实际的文件下载地址，点击“转存”后确认保存位置。"},
                {"浏览模型页面", "打开 Civitai 或 Hugging Face，发现文件后即可一键转存。"}
        });
        AppDialog.Controller dialog = AppDialog.create(this, "?", "如何使用云端转存？",
                "两种方式都可以快速创建转存任务", Ui.BRAND, Ui.BRAND_SOFT,
                content, null, "知道了");
        dialog.setPositiveAction(v -> dialog.dismiss());
        dialog.show();
    }

    private void showTasks() {
        showTasks(true);
    }

    private void showTasks(boolean animatePage) {
        stopDiscoverPage();
        currentTab = "tasks";
        openSwipeCard = null;
        activeTaskFilename = null;
        selectNav(navTasks);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), 0);

        List<TaskItem> all = TaskStore.load(this);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "转存任务", 27, Ui.TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        if (!all.isEmpty()) {
            TextView clear = Ui.text(this, "清空", 13, Ui.DANGER, true);
            clear.setGravity(Gravity.CENTER);
            clear.setBackground(Ui.background(Color.rgb(254, 242, 242), 14, this));
            clear.setOnClickListener(v -> confirmClearTasks());
            LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 44));
            clearParams.rightMargin = Ui.dp(this, 8);
            header.addView(clear, clearParams);
        }
        TextView refresh = Ui.text(this, "刷新", 13, Ui.BRAND, true);
        refresh.setGravity(Gravity.CENTER);
        android.graphics.drawable.Drawable refreshIcon = getDrawable(R.drawable.ic_refresh).mutate();
        refreshIcon.setTint(Ui.BRAND);
        refreshIcon.setBounds(0, 0, Ui.dp(this, 18), Ui.dp(this, 18));
        refresh.setCompoundDrawables(refreshIcon, null, null, null);
        refresh.setCompoundDrawablePadding(Ui.dp(this, 5));
        refresh.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        refresh.setMinWidth(0);
        refresh.setMinimumWidth(0);
        refresh.setContentDescription("刷新任务状态");
        refresh.setBackgroundResource(R.drawable.refresh_button_background);
        refresh.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            refreshTasks(true);
        });
        header.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42)));
        page.addView(header);

        int active = 0, queued = 0, complete = 0;
        for (TaskItem item : all) {
            if (TaskItem.COMPLETED.equals(item.status)) complete++;
            else if (TaskItem.QUEUED.equals(item.status) || TaskItem.SUBMITTING.equals(item.status)) queued++;
            else if (!TaskItem.FAILED.equals(item.status)) active++;
        }
        String stateText = active > 0
                ? active + " 个任务正在处理中，状态会自动更新"
                : queued > 0 ? queued + " 个任务正在连接执行器" : "已完成 " + complete + " 个任务";
        TextView stats = Ui.text(this, stateText, 13, active > 0 || queued > 0 ? Ui.BRAND : Ui.MUTED, false);
        page.addView(stats, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 36)));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 18));
        page.addView(filterBar(all, list, scroll));
        renderTaskList(list, all);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        replaceContent(page, animatePage);
        scheduleTaskRefresh(900);
    }

    private void renderTaskList(LinearLayout list, List<TaskItem> all) {
        list.removeAllViews();
        List<TaskItem> filtered = filter(all);
        if (filtered.isEmpty()) {
            TextView empty = Ui.text(this, "这里还没有任务", 15, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 180)));
        } else {
            for (TaskItem item : filtered) list.addView(taskRow(item));
        }
    }

    private View filterBar(List<TaskItem> all, LinearLayout list, ScrollView scroll) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.background(Color.rgb(241, 245, 249), 14, this));
        renderTaskFilterTabs(bar, all, list, scroll);
        return bar;
    }

    private void renderTaskFilterTabs(LinearLayout bar, List<TaskItem> all,
                                      LinearLayout list, ScrollView scroll) {
        bar.removeAllViews();
        String[][] filters = {{"all", "全部"}, {"active", "进行中"}, {"completed", "已完成"}, {"failed", "失败"}};
        for (String[] filter : filters) {
            TextView tab = Ui.text(this, filter[1], 13, filter[0].equals(taskFilter) ? Color.WHITE : Ui.MUTED, filter[0].equals(taskFilter));
            tab.setGravity(Gravity.CENTER);
            if (filter[0].equals(taskFilter)) tab.setBackground(Ui.background(Ui.BRAND, 12, this));
            tab.setOnClickListener(v -> {
                if (filter[0].equals(taskFilter)) return;
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                taskFilter = filter[0];
                renderTaskFilterTabs(bar, all, list, scroll);
                renderTaskList(list, all);
                scroll.scrollTo(0, 0);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1);
            params.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
            bar.addView(tab, params);
        }
    }

    private List<TaskItem> filter(List<TaskItem> all) {
        ArrayList<TaskItem> output = new ArrayList<>();
        for (TaskItem item : all) {
            boolean include;
            switch (taskFilter) {
                case "completed": include = TaskItem.COMPLETED.equals(item.status); break;
                case "failed": include = TaskItem.FAILED.equals(item.status) || TaskItem.SERVICE_REQUIRED.equals(item.status); break;
                case "active": include = !TaskItem.COMPLETED.equals(item.status) && !TaskItem.FAILED.equals(item.status) && !TaskItem.SERVICE_REQUIRED.equals(item.status); break;
                default: include = true;
            }
            if (include) output.add(item);
        }
        return output;
    }

    private View taskRow(TaskItem item) {
        FrameLayout swipeContainer = new FrameLayout(this);

        TextView delete = Ui.text(this, "删除", 14, Color.WHITE, true);
        delete.setGravity(Gravity.CENTER);
        delete.setBackground(Ui.background(Ui.DANGER, 16, this));
        delete.setAlpha(0f);
        delete.setContentDescription("删除任务 " + item.filename);
        FrameLayout.LayoutParams deleteParams = new FrameLayout.LayoutParams(
                Ui.dp(this, 78), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        swipeContainer.addView(delete, deleteParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.background(Color.WHITE, 16, this));
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 7));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = Ui.text(this, "Hugging Face".equals(item.provider) ? "HF" : "C", 11, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.background(Ui.BRAND, 9, this));
        top.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        TextView filename = Ui.text(this, item.filename, 13, Ui.TEXT, true);
        filename.setSingleLine(true);
        filename.setHorizontallyScrolling(true);
        filename.setEllipsize(android.text.TextUtils.TruncateAt.END);
        filename.setMarqueeRepeatLimit(-1);
        filename.setTag(item.filename);
        filename.setContentDescription("任务文件名：" + item.filename);
        labels.addView(filename);
        labels.addView(Ui.text(this, item.provider + " → " + item.targetName() + " · " + item.size, 10, Ui.MUTED, false));
        top.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        int statusColor = statusColor(item);
        TextView action = Ui.text(this, TaskItem.COMPLETED.equals(item.status) ? "打开" : item.statusName() + (item.progress > 0 && item.progress < 100 ? " " + item.progress + "%" : ""), 11, statusColor, true);
        action.setGravity(Gravity.CENTER);
        if (TaskItem.COMPLETED.equals(item.status)) {
            action.setBackground(Ui.bordered(Color.WHITE, Ui.SUCCESS, 8, this));
        } else if (TaskItem.FAILED.equals(item.status) || TaskItem.SERVICE_REQUIRED.equals(item.status)) {
            action.setBackground(Ui.bordered(Color.WHITE, Ui.DANGER, 8, this));
        }
        top.addView(action, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 34)));
        card.addView(top);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(item.progress);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(statusColor));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(241, 245, 249)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3));
        progressParams.topMargin = Ui.dp(this, 6);
        card.addView(progress, progressParams);
        card.setTag(delete);
        attachSwipeGesture(card, item, Ui.dp(this, 86), filename, action, delete);
        swipeContainer.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (item.id.equals(expandedTaskId)) activateTaskFilename(item.id, filename);
        delete.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            TaskStore.remove(this, item.id);
            updateTaskBadge();
            openSwipeCard = null;
            Toast.makeText(this, "任务已删除", Toast.LENGTH_SHORT).show();
            if ("tasks".equals(currentTab)) showTasks(false);
            else showHome();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 82));
        params.bottomMargin = Ui.dp(this, 9);
        swipeContainer.setLayoutParams(params);
        return swipeContainer;
    }

    private void attachSwipeGesture(View card, TaskItem item, int revealWidth,
                                    TextView filename, View action, View delete) {
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        float[] down = new float[3];
        boolean[] swiping = {false};
        card.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (openSwipeCard != null && openSwipeCard != card) {
                        openSwipeCard.animate().translationX(0).setDuration(150).start();
                        Object previousDelete = openSwipeCard.getTag();
                        if (previousDelete instanceof View) {
                            ((View) previousDelete).animate().alpha(0f).setDuration(120).start();
                        }
                        openSwipeCard = null;
                    }
                    card.animate().cancel();
                    delete.animate().cancel();
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    down[2] = card.getTranslationX();
                    swiping[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!swiping[0] && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        swiping[0] = true;
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (swiping[0]) {
                        float target = Math.max(-revealWidth, Math.min(0, down[2] + dx));
                        card.setTranslationX(target);
                        delete.setAlpha(Math.min(1f, Math.max(0f,
                                -target / (revealWidth * 0.55f))));
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    settleSwipe(card, delete, revealWidth,
                            card.getTranslationX() < -revealWidth * 0.42f);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (swiping[0]) {
                        settleSwipe(card, delete, revealWidth,
                                card.getTranslationX() < -revealWidth * 0.42f);
                    } else if (card.getTranslationX() < 0) {
                        settleSwipe(card, delete, revealWidth, false);
                    } else {
                        activateTaskFilename(item.id, filename);
                        if (isPointInside(action, event.getRawX(), event.getRawY())) {
                            if (TaskItem.COMPLETED.equals(item.status)) {
                                openResource(item);
                            } else if (TaskItem.FAILED.equals(item.status)
                                    || TaskItem.SERVICE_REQUIRED.equals(item.status)) {
                                Toast.makeText(this, "请返回模型页面重新提交", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void activateTaskFilename(String taskId, TextView filename) {
        if (activeTaskFilename != null && activeTaskFilename != filename) {
            activeTaskFilename.setSelected(false);
            activeTaskFilename.scrollTo(0, 0);
            activeTaskFilename.setEllipsize(android.text.TextUtils.TruncateAt.END);
            activeTaskFilename.setContentDescription(
                    "任务文件名：" + String.valueOf(activeTaskFilename.getTag()));
        }
        expandedTaskId = taskId;
        activeTaskFilename = filename;
        filename.setSelected(false);
        filename.scrollTo(0, 0);
        filename.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        filename.setMarqueeRepeatLimit(-1);
        filename.setContentDescription("正在滚动完整文件名：" + String.valueOf(filename.getTag()));
        filename.postDelayed(() -> {
            if (activeTaskFilename == filename) filename.setSelected(true);
        }, 120);
    }

    private boolean isPointInside(View view, float rawX, float rawY) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        return view.getGlobalVisibleRect(bounds)
                && bounds.contains(Math.round(rawX), Math.round(rawY));
    }

    private void settleSwipe(View card, View delete, int revealWidth, boolean reveal) {
        card.animate().translationX(reveal ? -revealWidth : 0).setDuration(170).start();
        delete.animate().alpha(reveal ? 1f : 0f).setDuration(150).start();
        if (reveal) {
            if (openSwipeCard != card) card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            openSwipeCard = card;
        } else if (openSwipeCard == card) {
            openSwipeCard = null;
        }
    }

    private void confirmClearTasks() {
        View content = AppDialog.messageCard(this,
                "所有任务记录将从当前设备移除，不会影响已经转存到网盘的文件。",
                Color.rgb(254, 242, 242));
        AppDialog.Controller dialog = AppDialog.create(this, "!", "清空任务列表？",
                "此操作只会清理本机任务记录", Ui.DANGER, Color.rgb(254, 242, 242),
                content, "取消", "确认清空");
        dialog.setPositiveAction(v -> {
            dialog.dismiss();
            taskPollHandler.removeCallbacks(taskPoll);
            TaskStore.clear(this);
            updateTaskBadge();
            Toast.makeText(this, "任务列表已清空", Toast.LENGTH_SHORT).show();
            showTasks(false);
        });
        dialog.show();
    }

    private int statusColor(TaskItem item) {
        if (TaskItem.COMPLETED.equals(item.status)) return Ui.SUCCESS;
        if (TaskItem.FAILED.equals(item.status) || TaskItem.SERVICE_REQUIRED.equals(item.status)) return Ui.DANGER;
        if (TaskItem.QUEUED.equals(item.status) || TaskItem.SUBMITTING.equals(item.status)) return Ui.WARNING;
        return Ui.BRAND;
    }

    private void openResource(TaskItem item) {
        if (item.shareUrl == null || item.shareUrl.isEmpty()) {
            Toast.makeText(this, "资源链接正在生成", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.shareUrl)));
        } catch (Exception error) {
            Toast.makeText(this, "无法打开资源链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void scheduleTaskRefresh(long delayMs) {
        taskPollHandler.removeCallbacks(taskPoll);
        if (!resumed) return;
        for (TaskItem item : TaskStore.load(this)) {
            if (isRefreshable(item)) {
                taskPollHandler.postDelayed(taskPoll, delayMs);
                return;
            }
        }
    }

    private boolean isRefreshable(TaskItem item) {
        return !TaskItem.COMPLETED.equals(item.status)
                && !TaskItem.FAILED.equals(item.status)
                && !TaskItem.SERVICE_REQUIRED.equals(item.status)
                && !item.id.startsWith("local_")
                && !item.id.startsWith("demo_");
    }

    private void refreshTasks(boolean manual) {
        if (taskRefreshInFlight) {
            if (manual) Toast.makeText(this, "正在更新任务状态", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TaskItem> tasks = TaskStore.load(this);
        ArrayList<TaskItem> active = new ArrayList<>();
        for (TaskItem item : tasks) {
            if (isRefreshable(item)) active.add(item);
        }
        if (active.isEmpty()) {
            updateTaskBadge();
            if (manual) Toast.makeText(this, "任务状态已是最新", Toast.LENGTH_SHORT).show();
            return;
        }

        taskRefreshInFlight = true;
        AtomicInteger remaining = new AtomicInteger(active.size());
        Runnable finishOne = () -> {
            if (remaining.decrementAndGet() != 0) return;
            taskRefreshInFlight = false;
            updateTaskBadge();
            if ("tasks".equals(currentTab)) showTasks(false);
            scheduleTaskRefresh(2200);
        };
        for (TaskItem item : active) {
            RelayClient.refresh(this, item, new RelayClient.Callback() {
                @Override public void onSuccess(TaskItem updated) { finishOne.run(); }
                @Override public void onError(TaskItem updated, String message) { finishOne.run(); }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if ("home".equals(currentTab) && homeBrowserPage != null) {
            homeBrowserPage.handleBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        taskPollHandler.removeCallbacksAndMessages(null);
        stopDiscoverPage();
        stopHomeBrowser();
        super.onDestroy();
    }
}
