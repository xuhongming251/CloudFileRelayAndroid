package com.cloudfilerelay.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

final class DiscoverPage {
    private static final long MIN_CHECK_AGE_MS = 3 * 60_000L;
    private static final long MIN_AUTO_DELAY_MS = 3 * 60_000L;
    private static final long MAX_AUTO_DELAY_MS = 5 * 60_000L;
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final ModelCatalogRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger searchGeneration = new AtomicInteger();
    private final LinearLayout page;
    private final TextView catalogStatus;
    private final TextView refreshButton;
    private final EditText searchInput;
    private final LinearLayout keywordRow;
    private final TextView resultTitle;
    private final TextView sortLabel;
    private final ListView listView;
    private final LinearLayout stateView;
    private final ProgressBar stateProgress;
    private final TextView stateText;
    private final CatalogAdapter adapter;
    private final Runnable delayedSearch = this::runSearch;
    private final Runnable automaticCheck = () -> refresh(false);
    private List<ModelCatalogItem> allItems = new ArrayList<>();
    private String scrollingModelKey = "";
    private boolean active;
    private boolean checking;

    DiscoverPage(Activity activity) {
        this.activity = activity;
        repository = ModelCatalogRepository.get(activity);
        page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 13), Ui.dp(activity, 16), 0);
        page.setFocusableInTouchMode(true);
        page.requestFocus();

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(activity, "发现模型", 27, Ui.TEXT, true);
        heading.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 40)));
        catalogStatus = Ui.text(activity, "正在读取模型列表…", 12, Ui.MUTED, false);
        heading.addView(catalogStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 23)));
        TextView autoUpdate = Ui.text(activity, "每 3–5 分钟自动更新", 11,
                Color.rgb(148, 163, 184), false);
        heading.addView(autoUpdate, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 20)));
        header.addView(heading, new LinearLayout.LayoutParams(0, Ui.dp(activity, 86), 1));

        refreshButton = Ui.text(activity, "刷新", 13, Ui.BRAND, true);
        refreshButton.setGravity(Gravity.CENTER);
        android.graphics.drawable.Drawable refreshIcon = activity.getDrawable(R.drawable.ic_refresh).mutate();
        refreshIcon.setTint(Ui.BRAND);
        refreshIcon.setBounds(0, 0, Ui.dp(activity, 18), Ui.dp(activity, 18));
        refreshButton.setCompoundDrawables(refreshIcon, null, null, null);
        refreshButton.setCompoundDrawablePadding(Ui.dp(activity, 5));
        refreshButton.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 10), 0);
        refreshButton.setMinWidth(0);
        refreshButton.setMinimumWidth(0);
        refreshButton.setContentDescription("手动刷新模型列表");
        refreshButton.setBackgroundResource(R.drawable.refresh_button_background);
        refreshButton.setOnClickListener(v -> refresh(true));
        header.addView(refreshButton,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        Ui.dp(activity, 42)));
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 92)));

        LinearLayout searchBox = new LinearLayout(activity);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(Ui.bordered(Color.WHITE, Ui.BORDER, 17, activity));
        TextView searchIcon = Ui.text(activity, "⌕", 26, Ui.MUTED, false);
        searchIcon.setGravity(Gravity.CENTER);
        searchBox.addView(searchIcon,
                new LinearLayout.LayoutParams(Ui.dp(activity, 46), ViewGroup.LayoutParams.MATCH_PARENT));
        searchInput = new EditText(activity);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(14);
        searchInput.setTextColor(Ui.TEXT);
        searchInput.setHintTextColor(Color.rgb(148, 163, 184));
        searchInput.setHint("输入多个关键词，空格分隔");
        searchInput.setContentDescription("模型搜索");
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setPadding(0, 0, Ui.dp(activity, 4), 0);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView clear = Ui.text(activity, "×", 19, Color.WHITE, false);
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("清除搜索");
        clear.setBackground(Ui.background(Color.rgb(148, 163, 184), 15, activity));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> searchInput.setText(""));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 30), Ui.dp(activity, 30));
        clearParams.rightMargin = Ui.dp(activity, 10);
        searchBox.addView(clear, clearParams);
        page.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 54)));

        HorizontalScrollView keywordScroll = new HorizontalScrollView(activity);
        keywordScroll.setHorizontalScrollBarEnabled(false);
        keywordScroll.setFillViewport(true);
        keywordRow = new LinearLayout(activity);
        keywordRow.setGravity(Gravity.CENTER_VERTICAL);
        keywordScroll.addView(keywordRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(keywordScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 42)));
        renderKeywords("");

        LinearLayout listHeader = new LinearLayout(activity);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        resultTitle = Ui.text(activity, "最新模型", 15, Ui.TEXT, true);
        sortLabel = Ui.text(activity, "按时间排序", 12, Ui.MUTED, false);
        sortLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        listHeader.addView(resultTitle,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        listHeader.addView(sortLabel, new LinearLayout.LayoutParams(
                Ui.dp(activity, 110), ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(listHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 39)));

        FrameLayout listArea = new FrameLayout(activity);
        listView = new ListView(activity);
        listView.setDivider(Ui.background(Ui.BORDER, 0, activity));
        listView.setDividerHeight(Ui.dp(activity, 1));
        listView.setVerticalScrollBarEnabled(false);
        listView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        listView.setBackgroundColor(Color.TRANSPARENT);
        adapter = new CatalogAdapter();
        listView.setAdapter(adapter);
        listArea.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stateView = new LinearLayout(activity);
        stateView.setOrientation(LinearLayout.VERTICAL);
        stateView.setGravity(Gravity.CENTER);
        stateProgress = new ProgressBar(activity);
        stateView.addView(stateProgress,
                new LinearLayout.LayoutParams(Ui.dp(activity, 34), Ui.dp(activity, 34)));
        stateText = Ui.text(activity, "正在加载模型列表…", 13, Ui.MUTED, false);
        stateText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stateTextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 54));
        stateTextParams.setMargins(Ui.dp(activity, 20), Ui.dp(activity, 8),
                Ui.dp(activity, 20), 0);
        stateView.addView(stateText, stateTextParams);
        stateText.setOnClickListener(v -> {
            if (!checking) refresh(true);
        });
        listArea.addView(stateView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(listArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                renderKeywords(s.toString());
                main.removeCallbacks(delayedSearch);
                main.postDelayed(delayedSearch, 150);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean submitAction = actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_UNSPECIFIED;
            boolean enterKey = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (submitAction || enterKey) {
                if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                    submitSearchAndHideKeyboard();
                }
                return true;
            }
            return false;
        });
    }

    private void submitSearchAndHideKeyboard() {
        main.removeCallbacks(delayedSearch);
        runSearch();
        searchInput.clearFocus();
        page.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(
                Activity.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    View view() { return page; }

    void start() {
        if (active) return;
        active = true;
        List<ModelCatalogItem> memory = repository.currentItems();
        if (!memory.isEmpty()) applyCatalog(memory, repository.currentUpdatedAt());
        repository.loadCached(result -> {
            if (!active) return;
            if (!result.items.isEmpty()) applyCatalog(result.items, result.updatedAt);
            if (result.items.isEmpty()) {
                showState(true, "正在下载模型列表…");
                refresh(false);
            } else if (System.currentTimeMillis() - repository.lastCheckedAt() >= MIN_CHECK_AGE_MS) {
                refresh(false);
            } else {
                scheduleNextCheck();
            }
        });
    }

    void stop() {
        active = false;
        main.removeCallbacks(delayedSearch);
        main.removeCallbacks(automaticCheck);
        searchGeneration.incrementAndGet();
    }

    private void refresh(boolean manual) {
        if (!active || checking) {
            if (manual && checking) Toast.makeText(activity, "正在检查更新", Toast.LENGTH_SHORT).show();
            return;
        }
        checking = true;
        refreshButton.setAlpha(0.55f);
        if (allItems.isEmpty()) showState(true, "正在下载模型列表…");
        else catalogStatus.setText(formatCount(allItems.size()) + " 个可转存模型 · 正在检查更新…");
        repository.refresh(manual, result -> {
            checking = false;
            refreshButton.setAlpha(1f);
            if (!active) return;
            if (!result.items.isEmpty()) applyCatalog(result.items, result.updatedAt);
            if (!result.error.isEmpty()) {
                if (allItems.isEmpty()) showState(false, result.error + "，点击重试");
                if (manual || allItems.isEmpty()) {
                    Toast.makeText(activity, result.error, Toast.LENGTH_SHORT).show();
                }
            } else if (manual) {
                Toast.makeText(activity, result.changed
                        ? "模型列表已更新" : "模型列表已是最新", Toast.LENGTH_SHORT).show();
            }
            scheduleNextCheck();
        });
    }

    private void scheduleNextCheck() {
        main.removeCallbacks(automaticCheck);
        if (!active) return;
        long delay = ThreadLocalRandom.current().nextLong(
                MIN_AUTO_DELAY_MS, MAX_AUTO_DELAY_MS + 1);
        main.postDelayed(automaticCheck, delay);
    }

    private void applyCatalog(List<ModelCatalogItem> source, String updatedAt) {
        allItems = source;
        catalogStatus.setText(formatCount(source.size()) + " 个可转存模型 · " + relativeTime(updatedAt));
        showState(false, "");
        runSearch();
    }

    private void runSearch() {
        int generation = searchGeneration.incrementAndGet();
        String query = searchInput.getText().toString();
        List<ModelCatalogItem> source = allItems;
        if (source.isEmpty()) {
            adapter.setItems(new ArrayList<>());
            return;
        }
        SEARCH_EXECUTOR.execute(() -> {
            List<ModelCatalogItem> result = ModelCatalogSearch.search(source, query);
            main.post(() -> {
                if (!active || generation != searchGeneration.get()) return;
                adapter.setItems(result);
                if (query.trim().isEmpty()) {
                    resultTitle.setText("最新模型");
                    sortLabel.setText("按时间排序");
                } else {
                    resultTitle.setText("找到 " + formatCount(result.size()) + " 个模型");
                    sortLabel.setText("按匹配度排序");
                }
                if (result.isEmpty()) showState(false, "未找到同时匹配这些关键词的模型");
                else showState(false, "");
                listView.setSelection(0);
            });
        });
    }

    private void renderKeywords(String query) {
        keywordRow.removeAllViews();
        List<String> tokens = ModelCatalogSearch.tokens(query);
        if (tokens.isEmpty()) {
            TextView hint = Ui.text(activity, "支持模糊组合，例如：wan lora fp16", 11,
                    Color.rgb(148, 163, 184), false);
            keywordRow.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        for (String token : tokens) {
            TextView chip = Ui.text(activity, token, 11, Ui.BRAND, true);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(Ui.background(Ui.BRAND_SOFT, 13, activity));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 27));
            params.rightMargin = Ui.dp(activity, 7);
            chip.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), 0);
            keywordRow.addView(chip, params);
        }
        TextView rule = Ui.text(activity, "全部匹配 · 顺序不限", 10, Ui.MUTED, false);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ruleParams.leftMargin = Ui.dp(activity, 5);
        keywordRow.addView(rule, ruleParams);
    }

    private void showState(boolean progress, String message) {
        if (progress) {
            stateView.setVisibility(View.VISIBLE);
            stateProgress.setVisibility(View.VISIBLE);
            stateText.setText(message);
            listView.setVisibility(View.GONE);
        } else if (!message.isEmpty()) {
            stateView.setVisibility(View.VISIBLE);
            stateProgress.setVisibility(View.GONE);
            stateText.setText(message);
            listView.setVisibility(View.GONE);
        } else {
            stateView.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
        }
    }

    private void openShare(ModelCatalogItem item) {
        if (item.shareUrl.isEmpty()) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.shareUrl)));
        } catch (Exception problem) {
            Toast.makeText(activity, "无法打开网盘链接", Toast.LENGTH_SHORT).show();
        }
    }

    private static String relativeTime(String iso) {
        if (iso == null || iso.isEmpty()) return "等待首次更新";
        try { return relativeTime(Instant.parse(iso).toEpochMilli()); }
        catch (Exception ignored) { return "已更新"; }
    }

    private static String formatCount(int count) {
        return String.format(Locale.US, "%,d", count);
    }

    private static String relativeTime(long timeMillis) {
        if (timeMillis <= 0) return "已更新";
        long difference = Math.max(0L, System.currentTimeMillis() - timeMillis);
        long minutes = difference / 60_000L;
        if (minutes < 1) return "刚刚更新";
        if (minutes < 60) return minutes + " 分钟前更新";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前更新";
        long days = hours / 24;
        if (days < 30) return days + " 天前更新";
        return Math.max(1, days / 30) + " 个月前更新";
    }

    private final class CatalogAdapter extends BaseAdapter {
        private final ArrayList<ModelCatalogItem> displayed = new ArrayList<>();

        void setItems(List<ModelCatalogItem> source) {
            displayed.clear();
            displayed.addAll(source);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return displayed.size(); }
        @Override public ModelCatalogItem getItem(int position) { return displayed.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 7),
                        Ui.dp(activity, 6), Ui.dp(activity, 7));
                row.setMinimumHeight(Ui.dp(activity, 78));
                row.setBackground(Ui.background(Color.WHITE, 0, activity));

                TextView badge = Ui.text(activity, "ST", 10, Color.WHITE, true);
                badge.setGravity(Gravity.CENTER);
                row.addView(badge,
                        new LinearLayout.LayoutParams(Ui.dp(activity, 42), Ui.dp(activity, 42)));

                LinearLayout labels = new LinearLayout(activity);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 8), 0);
                TextView filename = Ui.text(activity, "", 12.5f, Ui.TEXT, true);
                filename.setMaxLines(2);
                filename.setEllipsize(TextUtils.TruncateAt.END);
                filename.setGravity(Gravity.CENTER_VERTICAL);
                labels.addView(filename, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
                TextView time = Ui.text(activity, "", 10, Ui.MUTED, false);
                labels.addView(time, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 20)));
                row.addView(labels, new LinearLayout.LayoutParams(
                        0, Ui.dp(activity, 62), 1));

                TextView transfer = Ui.text(activity, "转存网盘", 11, Color.WHITE, true);
                transfer.setGravity(Gravity.CENTER);
                transfer.setBackground(Ui.background(Ui.BRAND, 10, activity));
                row.addView(transfer,
                        new LinearLayout.LayoutParams(Ui.dp(activity, 78), Ui.dp(activity, 38)));
                holder = new RowHolder(row, badge, filename, time, transfer);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            ModelCatalogItem item = getItem(position);
            holder.badge.setText(item.typeLabel());
            int badgeColor = "gguf".equals(item.extension) ? Color.rgb(16, 185, 129)
                    : "onnx".equals(item.extension) ? Color.rgb(14, 165, 233) : Ui.BRAND;
            holder.badge.setBackground(Ui.background(badgeColor, 11, activity));
            holder.filename.setText(item.filename);
            String modelKey = item.shareUrl.isEmpty() ? item.filename : item.shareUrl;
            boolean scrolling = modelKey.equals(scrollingModelKey);
            holder.filename.setSelected(false);
            holder.filename.scrollTo(0, 0);
            holder.filename.setHorizontallyScrolling(scrolling);
            holder.filename.setSingleLine(scrolling);
            holder.filename.setMaxLines(scrolling ? 1 : 2);
            holder.filename.setEllipsize(scrolling
                    ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            holder.filename.setMarqueeRepeatLimit(-1);
            holder.filename.setTag(modelKey);
            holder.filename.setContentDescription((scrolling
                    ? "正在滚动完整模型名：" : "发现模型文件名：") + item.filename);
            if (scrolling) {
                holder.filename.postDelayed(() -> {
                    if (modelKey.equals(scrollingModelKey)
                            && modelKey.equals(holder.filename.getTag())) {
                        holder.filename.setSelected(true);
                    }
                }, 120);
            }
            holder.time.setText(relativeTime(item.completedAtMillis).replace("更新", ""));
            holder.row.setContentDescription("点击查看完整模型名：" + item.filename);
            holder.row.setOnClickListener(v -> {
                scrollingModelKey = modelKey;
                notifyDataSetChanged();
            });
            holder.transfer.setOnClickListener(v -> openShare(item));
            return convertView;
        }
    }

    private static final class RowHolder {
        final LinearLayout row;
        final TextView badge;
        final TextView filename;
        final TextView time;
        final TextView transfer;

        RowHolder(LinearLayout row, TextView badge, TextView filename,
                  TextView time, TextView transfer) {
            this.row = row;
            this.badge = badge;
            this.filename = filename;
            this.time = time;
            this.transfer = transfer;
        }
    }
}
