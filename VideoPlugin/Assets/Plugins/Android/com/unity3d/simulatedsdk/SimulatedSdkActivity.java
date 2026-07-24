package com.unity3d.simulatedsdk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.unity3d.player.UnityPlayer;

/**
 * Minimal SDK-like Activity used for lifecycle testing.
 * It deliberately uses a real Android Activity so Unity receives pause/focus
 * transitions while the simulated agreement and login pages are visible.
 */
public final class SimulatedSdkActivity extends Activity {
    private static final String EXTRA_CALLBACK_OBJECT = "callback_object";
    private static final String EXTRA_CALLBACK_METHOD = "callback_method";
    private static final String EXTRA_CORE_DELAY_MS = "core_delay_ms";
    private static final String EXTRA_LOGIN_DELAY_MS = "login_delay_ms";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String callbackObject;
    private String callbackMethod;
    private long loginDelayMs;

    private TextView statusText;
    private LinearLayout contentLayout;
    private ProgressBar progressBar;

    public static void Launch(Activity activity, String callbackObject, String callbackMethod,
            long coreDelayMs, long loginDelayMs) {
        Intent intent = new Intent(activity, SimulatedSdkActivity.class);
        intent.putExtra(EXTRA_CALLBACK_OBJECT, callbackObject);
        intent.putExtra(EXTRA_CALLBACK_METHOD, callbackMethod);
        intent.putExtra(EXTRA_CORE_DELAY_MS, Math.max(0L, coreDelayMs));
        intent.putExtra(EXTRA_LOGIN_DELAY_MS, Math.max(0L, loginDelayMs));
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        callbackObject = getIntent().getStringExtra(EXTRA_CALLBACK_OBJECT);
        callbackMethod = getIntent().getStringExtra(EXTRA_CALLBACK_METHOD);
        loginDelayMs = getIntent().getLongExtra(EXTRA_LOGIN_DELAY_MS, 1000L);

        buildBasePage();
        sendStage("Initializing", "StartGlobalSdk / InitGPCSDK");

        long coreDelayMs = getIntent().getLongExtra(EXTRA_CORE_DELAY_MS, 3000L);
        if (coreDelayMs > 0L) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showAgreementPage();
                }
            }, coreDelayMs);
        } else {
            showLoginPage();
        }
    }

    private void buildBasePage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("模拟 SDK Activity");
        title.setTextColor(Color.rgb(30, 30, 30));
        title.setTextSize(24.0f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setTextSize(16.0f);
        statusText.setPadding(0, dp(18), 0, dp(12));
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.VISIBLE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(0, dp(18), 0, 0);
        root.addView(contentLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        setContentView(root);
        configureFloatingWindow();
    }

    private void configureFloatingWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.42f;
        window.setAttributes(attributes);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int screenHeight = getResources().getDisplayMetrics().heightPixels;
        final int dialogWidth = (int) (screenWidth * 0.88f);
        final int dialogHeight = (int) (screenHeight * 0.68f);

        window.getDecorView().post(new Runnable() {
            @Override
            public void run() {
                getWindow().setLayout(dialogWidth, dialogHeight);
                getWindow().setGravity(Gravity.CENTER);
            }
        });
    }

    private void showAgreementPage() {
        progressBar.setVisibility(View.GONE);
        sendStage("ShowingAgreement", "PrepareWebView / ShowAgreementSigningAgreeDialogV2");

        contentLayout.removeAllViews();

        TextView agreement = new TextView(this);
        agreement.setText("这是一个模拟 SDK 协议页面。\n\n点击下方按钮模拟用户同意协议并进入登录页面。\n\n此页面由独立 Android Activity 提供，用于观察 Unity 视频组件在 Activity 切换期间的表现。");
        agreement.setTextColor(Color.DKGRAY);
        agreement.setTextSize(16.0f);
        agreement.setGravity(Gravity.START);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(agreement);
        contentLayout.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        Button agreeButton = createButton("同意协议并继续");
        agreeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showLoginPage();
            }
        });
        contentLayout.addView(agreeButton);
    }

    private void showLoginPage() {
        sendStage("LoggingIn", "Login begin");
        contentLayout.removeAllViews();

        TextView loginText = new TextView(this);
        loginText.setText("模拟登录页面\n\n点击登录按钮，Activity 将模拟 SDK 登录完成后返回 Unity。");
        loginText.setTextColor(Color.DKGRAY);
        loginText.setTextSize(16.0f);
        contentLayout.addView(loginText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        Button loginButton = createButton("模拟登录");
        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                completeLogin();
            }
        });
        contentLayout.addView(loginButton);
    }

    private void completeLogin() {
        sendStage("LoggingIn", "Login success callback pending");
        contentLayout.removeAllViews();
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("模拟登录完成，正在返回游戏...");

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendStage("Initialized", "SDK initialization complete");
                setResult(RESULT_OK);
                finish();
            }
        }, loginDelayMs);
    }

    private Button createButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(16.0f);
        return button;
    }

    @Override
    public void onBackPressed() {
        sendStage("Failed", "Simulated SDK Activity closed by user");
        setResult(RESULT_CANCELED);
        finish();
    }

    private void sendStage(String state, String message) {
        statusText.setText(state + "\n" + message);
        if (callbackObject != null && callbackMethod != null) {
            UnityPlayer.UnitySendMessage(callbackObject, callbackMethod, state + "|" + message);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
