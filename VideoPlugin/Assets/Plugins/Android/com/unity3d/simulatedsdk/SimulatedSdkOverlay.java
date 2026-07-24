package com.unity3d.simulatedsdk;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.unity3d.player.UnityPlayer;

/**
 * Minimal SDK-like in-process overlay used to test SDK UI without starting a
 * second Activity. Since it is a child of Unity's content view, it does not
 * trigger Unity Activity pause/resume callbacks or replace the EGL window.
 */
public final class SimulatedSdkOverlay {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static View activeOverlay;

    private SimulatedSdkOverlay() {
    }

    public static void Show(final Activity activity, final String callbackObject,
            final String callbackMethod, final long loginDelayMs) {
        if (activity == null) {
            return;
        }

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showOnUiThread(activity, callbackObject, callbackMethod,
                        Math.max(0L, loginDelayMs));
            }
        });
    }

    private static void showOnUiThread(Activity activity, String callbackObject,
            String callbackMethod, long loginDelayMs) {
        dismissActiveOverlay();

        final ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            sendStage(callbackObject, callbackMethod, "Failed",
                    "Unity content view is unavailable");
            return;
        }

        final FrameLayout overlayRoot = new FrameLayout(activity);
        overlayRoot.setBackgroundColor(Color.argb(107, 0, 0, 0));
        // Consume touches on the dimmed area, like a normal SDK dialog.
        overlayRoot.setClickable(true);
        overlayRoot.setFocusable(true);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 24), dp(activity, 24),
                dp(activity, 24), dp(activity, 24));
        card.setBackground(createCardBackground());

        TextView title = new TextView(activity);
        title.setText("模拟 SDK Overlay");
        title.setTextColor(Color.rgb(30, 30, 30));
        title.setTextSize(22.0f);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(activity);
        status.setTextColor(Color.DKGRAY);
        status.setTextSize(15.0f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(activity, 14), 0, dp(activity, 14));
        status.setText("Initializing\nStartGlobalSdk / InitGPCSDK");
        card.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ProgressBar progressBar = new ProgressBar(activity);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(progressBar, progressParams);

        final TextView loginText = new TextView(activity);
        loginText.setText("这是一个直接覆盖在 Unity 画面上的模拟登录页面。\n\n点击登录后，等待模拟 SDK 初始化完成。");
        loginText.setTextColor(Color.DKGRAY);
        loginText.setTextSize(15.0f);
        loginText.setGravity(Gravity.CENTER);
        loginText.setVisibility(View.GONE);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        card.addView(loginText, textParams);

        final Button loginButton = createButton(activity, "模拟登录");
        loginButton.setVisibility(View.GONE);
        card.addView(loginButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f),
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.68f));
        cardParams.gravity = Gravity.CENTER;
        overlayRoot.addView(card, cardParams);
        content.addView(overlayRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activeOverlay = overlayRoot;

        sendStage(callbackObject, callbackMethod, "Initializing",
                "StartGlobalSdk / InitGPCSDK");
        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activeOverlay != overlayRoot) {
                    return;
                }

                progressBar.setVisibility(View.GONE);
                loginText.setVisibility(View.VISIBLE);
                loginButton.setVisibility(View.VISIBLE);
                status.setText("LoggingIn\nLogin begin");
                sendStage(callbackObject, callbackMethod, "LoggingIn", "Login begin");
            }
        }, 250L);

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loginButton.setEnabled(false);
                loginText.setText("模拟登录已完成，正在返回 Unity...");
                status.setText("LoggingIn\nLogin success callback pending");
                progressBar.setVisibility(View.VISIBLE);
                sendStage(callbackObject, callbackMethod, "LoggingIn",
                        "Login success callback pending");

                MAIN_HANDLER.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (activeOverlay != overlayRoot) {
                            return;
                        }

                        sendStage(callbackObject, callbackMethod, "Initialized",
                                "SDK initialization complete");
                        removeOverlay(overlayRoot);
                    }
                }, loginDelayMs);
            }
        });
    }

    private static void dismissActiveOverlay() {
        if (activeOverlay instanceof ViewGroup) {
            removeOverlay(activeOverlay);
        }
    }

    private static void removeOverlay(final View overlay) {
        MAIN_HANDLER.removeCallbacksAndMessages(overlay);
        ViewParentHolder.removeFromParent(overlay);
        if (activeOverlay == overlay) {
            activeOverlay = null;
        }
    }

    private static GradientDrawable createCardBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(20.0f);
        return background;
    }

    private static Button createButton(Activity activity, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setTextSize(16.0f);
        return button;
    }

    private static void sendStage(String callbackObject, String callbackMethod,
            String state, String message) {
        if (callbackObject != null && callbackMethod != null) {
            UnityPlayer.UnitySendMessage(callbackObject, callbackMethod,
                    state + "|" + message);
        }
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Small helper keeps the remove operation compatible with the minimum API. */
    private static final class ViewParentHolder {
        private static void removeFromParent(View view) {
            if (view != null && view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        }
    }
}
