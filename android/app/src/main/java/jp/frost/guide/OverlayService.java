package jp.frost.guide;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.UUID;

/**
 * 常に手前に浮かぶガイドの小窓 + キーボードとの Bluetooth 通信。
 * キーボードは独自サービス(6f5a0001-...)で Mac 版と同じパケットを通知してくる。
 */
public class OverlayService extends Service {

    static final String ACTION_RESIZE = "jp.frost.guide.RESIZE";
    static volatile boolean running = false;
    private static final int SHOT_POSITION = 40; // 右手最下段のいちばん右(スクショキー)

    private static final UUID SVC = UUID.fromString("6f5a0001-3c1d-4e8a-9b2e-0f7a1c2d3e4f");
    private static final UUID CHR = UUID.fromString("6f5a0002-3c1d-4e8a-9b2e-0f7a1c2d3e4f");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String CHANNEL = "guide";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout box;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private boolean folded = false;
    private BluetoothGatt gatt;
    private boolean pageReady = false;
    private VoiceInput voice;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESIZE.equals(intent.getAction())) {
            applySize();
            return START_STICKY;
        }
        if (running) return START_STICKY;
        running = true;
        startAsForeground();
        showOverlay();
        voice = new VoiceInput(this, this::pushVoice);
        connectKeyboard();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (voice != null) voice.destroy();
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (SecurityException ignored) { }
        if (box != null) { try { wm.removeView(box); } catch (Exception ignored) { } }
        super.onDestroy();
    }

    // ---------- 常駐の通知 ----------
    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ガイドの表示", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("FrostOrtho ガイドを表示中")
                .setContentText("タップで設定を開く")
                .setSmallIcon(R.drawable.ic_guide)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(1, n, type);
        } else {
            startForeground(1, n);
        }
    }

    // ---------- 小窓 ----------
    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void showOverlay() {
        wm = getSystemService(WindowManager.class);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xFFF6EFE0);
        box.setBackground(bg);
        box.setClipToOutline(true);

        // つまんで動かすバー
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(2), dp(6), dp(2));
        TextView grip = new TextView(this);
        grip.setText("≡  FrostOrtho");
        grip.setTextColor(0xFFA38F78);
        grip.setTextSize(11);
        bar.addView(grip, new LinearLayout.LayoutParams(0, dp(24), 1f));
        grip.setGravity(Gravity.CENTER_VERTICAL);
        TextView fold = new TextView(this);
        fold.setText("ー");
        fold.setTextColor(0xFF5B4A3A);
        fold.setTextSize(14);
        fold.setGravity(Gravity.CENTER);
        fold.setOnClickListener(v -> toggleFold());
        bar.addView(fold, new LinearLayout.LayoutParams(dp(32), dp(24)));
        box.addView(bar);

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushStatus(gatt != null && connected, connected ? "接続中" : "接続待ち");
            }
        });
        web.loadUrl("file:///android_asset/guide.html#android");
        box.addView(web);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(40);
        lp.y = dp(80);

        final float[] down = new float[4];
        bar.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY(); down[2] = lp.x; down[3] = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = (int) (down[2] + e.getRawX() - down[0]);
                    lp.y = (int) (down[3] + e.getRawY() - down[1]);
                    wm.updateViewLayout(box, lp);
                    return true;
                default:
                    return false;
            }
        });

        wm.addView(box, lp);
        applySize();
    }

    private void applySize() {
        if (box == null) return;
        int w = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(MainActivity.KEY_SIZE, 480);
        int webH = folded ? 0 : Math.round(w * 0.56f);
        web.setLayoutParams(new LinearLayout.LayoutParams(dp(w), dp(webH)));
        web.setVisibility(folded ? View.GONE : View.VISIBLE);
        lp.width = dp(folded ? 160 : w);
        wm.updateViewLayout(box, lp);
    }

    private void toggleFold() {
        folded = !folded;
        applySize();
    }

    // ---------- Bluetooth ----------
    private boolean connected = false;

    private void connectKeyboard() {
        try {
            BluetoothAdapter ad = getSystemService(BluetoothManager.class).getAdapter();
            if (ad == null || !ad.isEnabled()) { pushStatus(false, "Bluetoothがオフ"); retryLater(); return; }
            BluetoothDevice kb = null;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                String n = d.getName();
                if (n != null && n.toLowerCase().contains("frost")) { kb = d; break; }
            }
            if (kb == null) { pushStatus(false, "FrostOrthoが未登録"); retryLater(); return; }
            pushStatus(false, "接続中…");
            gatt = kb.connectGatt(this, true, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            pushStatus(false, "Bluetoothの許可が必要");
        }
    }

    private void retryLater() {
        main.postDelayed(() -> { if (running && gatt == null) connectKeyboard(); }, 5000);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false;
                    pushStatus(false, "切断(自動で再接続します)");
                }
            } catch (SecurityException ignored) { }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) { pushStatus(false, "キーボードのファームウェアが古い"); return; }
            BluetoothGattCharacteristic ch = svc.getCharacteristic(CHR);
            if (ch == null) return;
            try {
                g.setCharacteristicNotification(ch, true);
                BluetoothGattDescriptor d = ch.getDescriptor(CCC);
                if (d != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(d);
                    }
                }
                connected = true;
                pushStatus(true, "接続中");
            } catch (SecurityException ignored) { }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            pushPacket(value);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (Build.VERSION.SDK_INT < 33) pushPacket(ch.getValue());
        }
    };

    // ---------- ガイド(HTML)へ渡す ----------
    private void pushPacket(byte[] v) {
        if (v == null) return;
        // F13 の押下・離しは音声入力へ(0xF1: キー押下パケット [0xF1, 0, 位置, 押した=1])
        if (v.length >= 4 && (v[0] & 0xFF) == 0xF1 && (v[2] & 0xFF) == VoiceInput.F13_POSITION) {
            final boolean down = v[3] != 0;
            main.post(() -> { if (voice != null) voice.onKey(down); });
        }
        // 右下のキー(位置40)を押したら範囲スクショ(DeXモード中はキーボードからは何も送らず、ここで処理する)
        if (v.length >= 4 && (v[0] & 0xFF) == 0xF1 && (v[2] & 0xFF) == SHOT_POSITION && v[3] != 0) {
            main.post(() -> {
                if (!FrostInputService.startRegionShot(this::pushVoice)) {
                    pushVoice("error", "ユーザー補助で「FrostOrtho 音声入力」をオンにしてください");
                }
            });
        }
        StringBuilder sb = new StringBuilder("window.frostPacket&&frostPacket([");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i] & 0xFF); }
        sb.append("])");
        final String js = sb.toString();
        main.post(() -> { if (pageReady && web != null) web.evaluateJavascript(js, null); });
    }

    private void pushVoice(String state, String text) {
        final String js = "window.frostVoice&&frostVoice('" + state + "','"
                + text.replace("\\", "").replace("'", "").replace("\n", " ") + "')";
        main.post(() -> { if (pageReady && web != null) web.evaluateJavascript(js, null); });
    }

    private void pushStatus(boolean on, String text) {
        final String js = "window.frostStatus&&frostStatus(" + on + ",'" + text.replace("'", "") + "')";
        main.post(() -> { if (pageReady && web != null) web.evaluateJavascript(js, null); });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
