package com.cloudfilerelay.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AppDialog {
    private AppDialog() {}

    static Controller create(Activity activity, String symbol, String title, String subtitle,
                             int accentColor, int accentSoftColor, View content,
                             String negativeText, String positiveText) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.background(Color.WHITE, 24, activity));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 20),
                Ui.dp(activity, 20), Ui.dp(activity, 12));

        TextView badge = Ui.text(activity, symbol, 17, accentColor, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.background(accentSoftColor, 21, activity));
        header.addView(badge, new LinearLayout.LayoutParams(
                Ui.dp(activity, 42), Ui.dp(activity, 42)));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(Ui.dp(activity, 12), 0, 0, 0);
        TextView titleView = Ui.text(activity, title, 19, Ui.TEXT, true);
        heading.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 27)));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = Ui.text(activity, subtitle, 12, Ui.MUTED, false);
            subtitleView.setSingleLine(true);
            heading.addView(subtitleView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 21)));
        }
        header.addView(heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(header);

        if (content != null) {
            card.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        View divider = new View(activity);
        divider.setBackgroundColor(Color.rgb(241, 245, 249));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 1));
        dividerParams.setMargins(Ui.dp(activity, 20), Ui.dp(activity, 14),
                Ui.dp(activity, 20), 0);
        card.addView(divider, dividerParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 12),
                Ui.dp(activity, 20), Ui.dp(activity, 16));

        TextView negative = null;
        if (negativeText != null && !negativeText.isEmpty()) {
            negative = action(activity, negativeText, Ui.MUTED,
                    Color.rgb(241, 245, 249));
            actions.addView(negative, new LinearLayout.LayoutParams(
                    0, Ui.dp(activity, 46), 1));
        }

        TextView positive = action(activity, positiveText, Color.WHITE, accentColor);
        LinearLayout.LayoutParams positiveParams = new LinearLayout.LayoutParams(
                0, Ui.dp(activity, 46), 1);
        if (negative != null) positiveParams.leftMargin = Ui.dp(activity, 10);
        actions.addView(positive, positiveParams);
        card.addView(actions);

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.42f;
            window.setAttributes(attributes);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        Controller controller = new Controller(activity, dialog, positive, negative);
        positive.setOnClickListener(v -> dialog.dismiss());
        if (negative != null) negative.setOnClickListener(v -> dialog.dismiss());
        return controller;
    }

    static View infoSteps(Activity activity, String[][] steps) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 2),
                Ui.dp(activity, 20), 0);
        for (int index = 0; index < steps.length; index++) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.background(Color.rgb(248, 250, 252), 15, activity));
            row.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 11),
                    Ui.dp(activity, 12), Ui.dp(activity, 11));

            TextView number = Ui.text(activity, String.valueOf(index + 1), 13, Ui.BRAND, true);
            number.setGravity(Gravity.CENTER);
            number.setBackground(Ui.background(Ui.BRAND_SOFT, 15, activity));
            row.addView(number, new LinearLayout.LayoutParams(
                    Ui.dp(activity, 30), Ui.dp(activity, 30)));

            LinearLayout copy = new LinearLayout(activity);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(Ui.dp(activity, 11), 0, 0, 0);
            copy.addView(Ui.text(activity, steps[index][0], 14, Ui.TEXT, true),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            Ui.dp(activity, 23)));
            TextView detail = Ui.text(activity, steps[index][1], 12, Ui.MUTED, false);
            detail.setLineSpacing(Ui.dp(activity, 2), 1f);
            copy.addView(detail, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(copy, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index > 0) rowParams.topMargin = Ui.dp(activity, 9);
            body.addView(row, rowParams);
        }
        return body;
    }

    static View messageCard(Activity activity, String message, int backgroundColor) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 2),
                Ui.dp(activity, 20), 0);
        TextView text = Ui.text(activity, message, 13, Ui.MUTED, false);
        text.setLineSpacing(Ui.dp(activity, 3), 1f);
        text.setBackground(Ui.background(backgroundColor, 15, activity));
        text.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 13),
                Ui.dp(activity, 14), Ui.dp(activity, 13));
        wrapper.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private static TextView action(Activity activity, String label, int textColor, int fillColor) {
        TextView action = Ui.text(activity, label, 14, textColor, true);
        action.setGravity(Gravity.CENTER);
        action.setBackground(Ui.background(fillColor, 14, activity));
        action.setMinWidth(0);
        action.setMinimumWidth(0);
        action.setClickable(true);
        action.setFocusable(true);
        return action;
    }

    static final class Controller {
        private final Activity activity;
        private final Dialog dialog;
        private final TextView positive;
        private final TextView negative;

        Controller(Activity activity, Dialog dialog, TextView positive, TextView negative) {
            this.activity = activity;
            this.dialog = dialog;
            this.positive = positive;
            this.negative = negative;
        }

        void setPositiveAction(View.OnClickListener listener) {
            positive.setOnClickListener(listener);
        }

        void setNegativeAction(View.OnClickListener listener) {
            if (negative != null) negative.setOnClickListener(listener);
        }

        void show() {
            dialog.show();
            Window window = dialog.getWindow();
            if (window == null) return;
            int available = activity.getResources().getDisplayMetrics().widthPixels
                    - Ui.dp(activity, 32);
            int max = Ui.dp(activity, 420);
            window.setLayout(Math.min(available, max), ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        void dismiss() {
            dialog.dismiss();
        }
    }
}
