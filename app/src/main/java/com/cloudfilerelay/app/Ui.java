package com.cloudfilerelay.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

final class Ui {
    static final int BRAND = Color.rgb(99, 102, 241);
    static final int BRAND_SOFT = Color.rgb(238, 242, 255);
    static final int TEXT = Color.rgb(15, 23, 42);
    static final int MUTED = Color.rgb(100, 116, 139);
    static final int BORDER = Color.rgb(226, 232, 240);
    static final int SUCCESS = Color.rgb(16, 185, 129);
    static final int WARNING = Color.rgb(245, 158, 11);
    static final int DANGER = Color.rgb(239, 68, 68);

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable background(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable bordered(int color, int borderColor, float radiusDp, Context context) {
        GradientDrawable drawable = background(color, radiusDp, context);
        drawable.setStroke(dp(context, 1), borderColor);
        return drawable;
    }

    static TextView text(Context context, String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static void setPadding(View view, int horizontalDp, int verticalDp) {
        int h = dp(view.getContext(), horizontalDp);
        int v = dp(view.getContext(), verticalDp);
        view.setPadding(h, v, h, v);
    }
}
