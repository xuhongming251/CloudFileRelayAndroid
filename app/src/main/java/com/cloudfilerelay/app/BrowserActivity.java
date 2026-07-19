package com.cloudfilerelay.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class BrowserActivity extends Activity {
    private BrowserPage browserPage;
    private boolean directDownload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String requestedUrl = getIntent().getStringExtra("start_url");
        directDownload = getIntent().getBooleanExtra("direct_download", false);
        browserPage = new BrowserPage(this, requestedUrl, directDownload,
                this::finish, this::openTaskList, null);
        setContentView(browserPage.view());
    }

    private void openTaskList() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("tab", "tasks");
        intent.putExtra(MainActivity.EXTRA_CLEAR_HOME_LINK, directDownload);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (browserPage != null) browserPage.handleBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (browserPage != null) {
            browserPage.destroy();
            browserPage = null;
        }
        super.onDestroy();
    }
}
