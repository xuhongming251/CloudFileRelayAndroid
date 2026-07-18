package com.cloudfilerelay.app;

import android.content.Context;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/** Keeps a single persistent Gecko profile/runtime for the whole app process. */
final class GeckoRuntimeProvider {
    private static GeckoRuntime runtime;

    private GeckoRuntimeProvider() {}

    static synchronized GeckoRuntime get(Context context) {
        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .webFontsEnabled(true)
                    .loginAutofillEnabled(true)
                    .consoleOutput(BuildConfig.DEBUG)
                    .remoteDebuggingEnabled(BuildConfig.DEBUG)
                    .build();
            runtime = GeckoRuntime.create(context.getApplicationContext(), settings);
            GeckoBridge.initialize(runtime);
        }
        return runtime;
    }
}
