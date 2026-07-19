package com.cloudfilerelay.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.FrameLayout;

public class DirectTransferActivity extends Activity {
    private BrowserPage browserPage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String requestedUrl = getIntent().getStringExtra("start_url");
        browserPage = new BrowserPage(this, requestedUrl, true,
                this::finish, this::returnHome, null);

        FrameLayout transparentHost = new FrameLayout(this);
        transparentHost.setBackgroundColor(Color.TRANSPARENT);
        setContentView(transparentHost);
        transparentHost.post(browserPage::showDirectTransferDialog);
    }

    private void returnHome() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_CLEAR_HOME_LINK, true);
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
