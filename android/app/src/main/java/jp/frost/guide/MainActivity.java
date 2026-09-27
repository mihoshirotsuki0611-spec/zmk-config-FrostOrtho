package jp.frost.guide;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 設定画面: 許可を出して、ガイドの小窓を表示・停止する */
public class MainActivity extends Activity {

    static final String PREFS = "frost_guide";
    static final String KEY_SIZE = "size_dp";
    static final String KEY_FULL = "full";

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        root.setBackgroundColor(0xFFF6EFE0);

        TextView title = text("FrostOrtho ガイド", 22, true);
        root.addView(title);
        root.addView(text("キーボードのレイヤーを、DeXの画面に小窓で表示します。上から順に押してください。", 14, false));

        status = text("", 14, true);
        status.setTextColor(0xFFC0672F);
        root.addView(status);

        root.addView(button("1. 他のアプリの上に表示を許可", v -> openOverlaySettings(), false));
        root.addView(button("2. Bluetooth・通知・マイクを許可", v -> requestRuntimePermissions(), false));
        root.addView(button("3. ガイドを表示", v -> startGuide(), true));
        root.addView(text("音声入力(F13)を使うとき", 14, true));
        root.addView(text("F13 を長押し=押している間だけ聞き取り / 2回タップ=もう一度押すまで聞き取り。聞き取った文字は入力中の欄に入ります。", 13, false));
        root.addView(button("4. ユーザー補助で「FrostOrtho 音声入力」をオン", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)), false));
        root.addView(button("ガイドを止める", v -> stopService(new Intent(this, OverlayService.class)), false));

        root.addView(text("小窓の大きさ", 14, true));
        LinearLayout sizes = new LinearLayout(this);
        sizes.setOrientation(LinearLayout.HORIZONTAL);
        sizes.addView(button("小", v -> setSize(380), false));
        sizes.addView(button("中", v -> setSize(480), false));
        sizes.addView(button("大", v -> setSize(600), false));
        sizes.addView(button("全画面", v -> setFull(), false));
        root.addView(sizes);

        root.addView(text("小窓の上のバーをドラッグすると動かせます。「□」で全画面ともとの大きさを切り替え、「ー」で小さく畳めます。", 13, false));
        root.addView(text("音声入力とスクショキーは、キーボードがスマホにつながっているとき(DeXの接続先)だけ反応します。", 13, false));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF6EFE0);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        List<String> missing = new ArrayList<>();
        if (!Settings.canDrawOverlays(this)) missing.add("他のアプリの上に表示");
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing.add("Bluetooth");
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add("マイク(音声入力用)");
        if (!FrostInputService.isEnabled()) missing.add("ユーザー補助(音声入力用)");
        status.setText(missing.isEmpty() ? "準備できています。「3. ガイドを表示」を押してください。"
                : "まだ許可されていないもの: " + String.join("、", missing));
    }

    private void openOverlaySettings() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void requestRuntimePermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        perms.add(Manifest.permission.RECORD_AUDIO);
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        refreshStatus();
    }

    private void startGuide() {
        if (!Settings.canDrawOverlays(this)) {
            status.setText("先に「1. 他のアプリの上に表示を許可」をオンにしてください。");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status.setText("先に「2. Bluetooth・通知・マイクを許可」を押してください。");
            return;
        }
        startForegroundService(new Intent(this, OverlayService.class));
        status.setText("ガイドを表示しました。");
    }

    private void setFull() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FULL, true).apply();
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_RESIZE);
        if (OverlayService.running) startService(i);
        status.setText("全画面にしました。小窓の上のバーの「□」でもとの大きさに戻ります。");
    }

    private void setSize(int widthDp) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        p.edit().putInt(KEY_SIZE, widthDp).putBoolean(KEY_FULL, false).apply();
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_RESIZE);
        if (OverlayService.running) startService(i);
        status.setText("小窓の幅を " + widthDp + "dp にしました。");
    }

    // ---- 見た目の小さな部品 ----
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFF5B4A3A);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private Button button(String s, View.OnClickListener l, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? Color.WHITE : 0xFF5B4A3A);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(primary ? 0xFF7CC49A : 0xFFFFFAF0);
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.rightMargin = dp(8);
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
