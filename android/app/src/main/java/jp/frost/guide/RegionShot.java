package jp.frost.guide;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 範囲スクショ: 今の画面を撮って止めた状態で表示し、ボールでドラッグ(または2回クリック)した範囲を切り抜く。
 * 切り抜いた画像は「写真(Pictures/Screenshots)」に保存し、クリップボードにも入れる(そのまま貼れる)。
 * もう一度キーを押すか、動かさずに右クリックでやめる。
 */
class RegionShot {

    interface Listener { void onShotState(String state, String text); }

    private final AccessibilityService svc;
    private WindowManager wm;
    private android.content.Context viewCtx;
    private SelectView view;
    private Bitmap frozen;
    private Listener listener;

    RegionShot(AccessibilityService svc) {
        this.svc = svc;
    }

    /** 撮る画面を決める: DeX(外部ディスプレイ)がつながっていればそちら、なければスマホの画面 */
    private Display targetDisplay() {
        android.hardware.display.DisplayManager dm = svc.getSystemService(android.hardware.display.DisplayManager.class);
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && d.getState() == Display.STATE_ON) return d;
        }
        return dm.getDisplay(Display.DEFAULT_DISPLAY);
    }

    void toggle(Listener l) {
        listener = l;
        if (view != null) { cancel(); return; }
        Display d = targetDisplay();
        // 選ぶ画面も、撮った画面と同じディスプレイに出す
        viewCtx = d.getDisplayId() == Display.DEFAULT_DISPLAY ? svc
                : svc.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
        wm = viewCtx.getSystemService(WindowManager.class);
        svc.takeScreenshot(d.getDisplayId(), svc.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult r) {
                        HardwareBuffer hb = r.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        frozen = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                        hb.close();
                        if (frozen == null) { say("error", "画面を撮れませんでした"); return; }
                        show();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        say("error", "画面を撮れませんでした(" + errorCode + ")");
                    }
                });
    }

    private void show() {
        view = new SelectView();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        wm.addView(view, lp);
        say("select", "範囲をドラッグ(または2か所クリック)");
    }

    private void cancel() {
        remove();
        say("idle", "");
    }

    private void remove() {
        if (view != null) { try { wm.removeView(view); } catch (Exception ignored) { } view = null; }
    }

    private void finish(RectF sel, int viewW, int viewH) {
        remove();
        Bitmap src = frozen;
        frozen = null;
        if (src == null) return;
        // 表示サイズと撮影サイズがずれていても合わせる
        float sx = (float) src.getWidth() / Math.max(1, viewW), sy = (float) src.getHeight() / Math.max(1, viewH);
        int x = clamp(Math.round(sel.left * sx), 0, src.getWidth() - 1);
        int y = clamp(Math.round(sel.top * sy), 0, src.getHeight() - 1);
        int w = clamp(Math.round(sel.width() * sx), 1, src.getWidth() - x);
        int h = clamp(Math.round(sel.height() * sy), 1, src.getHeight() - y);
        Bitmap out = Bitmap.createBitmap(src, x, y, w, h);
        try {
            String name = "FrostShot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(new Date()) + ".png";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots");
            Uri uri = svc.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("保存先を作れませんでした");
            try (OutputStream os = svc.getContentResolver().openOutputStream(uri)) {
                out.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            ClipboardManager cm = svc.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newUri(svc.getContentResolver(), "スクショ", uri));
            say("done", "コピーしました(写真にも保存)");
        } catch (Exception e) {
            say("error", "保存できませんでした");
        }
    }

    private void say(String state, String text) {
        if (listener != null) listener.onShotState(state, text);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** 撮った画面の上で範囲を選ぶ画面 */
    private class SelectView extends View {
        private final Paint dim = new Paint(), frame = new Paint(), clear = new Paint();
        private float ax = -1, ay = -1, bx, by;
        private boolean dragging, firstPointSet;

        SelectView() {
            super(viewCtx);
            dim.setColor(0x66000000);
            frame.setColor(0xFFFFD54A);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(4f);
            clear.setColor(Color.TRANSPARENT);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (frozen != null) c.drawBitmap(frozen, null, new RectF(0, 0, getWidth(), getHeight()), null);
            RectF r = rect();
            if (r == null) { c.drawRect(0, 0, getWidth(), getHeight(), dim); return; }
            // 選んでいる範囲の外だけ暗くする
            c.drawRect(0, 0, getWidth(), r.top, dim);
            c.drawRect(0, r.bottom, getWidth(), getHeight(), dim);
            c.drawRect(0, r.top, r.left, r.bottom, dim);
            c.drawRect(r.right, r.top, getWidth(), r.bottom, dim);
            c.drawRect(r, frame);
        }

        private RectF rect() {
            if (ax < 0) return null;
            return new RectF(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
        }

        @Override
        public boolean onGenericMotionEvent(MotionEvent e) {
            // 1点目を決めたあと、カーソルの動きに合わせて枠を広げる(2か所クリック方式)
            if (firstPointSet && e.getAction() == MotionEvent.ACTION_HOVER_MOVE) {
                bx = e.getX(); by = e.getY(); invalidate();
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) { cancel(); return true; }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!firstPointSet) { ax = e.getX(); ay = e.getY(); }
                    bx = e.getX(); by = e.getY();
                    dragging = false;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    bx = e.getX(); by = e.getY();
                    if (Math.abs(bx - ax) > 12 || Math.abs(by - ay) > 12) dragging = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    bx = e.getX(); by = e.getY();
                    RectF r = rect();
                    boolean big = r != null && r.width() > 12 && r.height() > 12;
                    if (dragging && big) { finish(r, getWidth(), getHeight()); return true; }
                    if (!firstPointSet) { firstPointSet = true; return true; } // 1点目を決めた
                    if (big) finish(r, getWidth(), getHeight()); else cancel();   // 2点目
                    return true;
                default:
                    return true;
            }
        }
    }
}
