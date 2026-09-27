package jp.frost.guide;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** 音声入力で聞き取った文字を、いま入力中の欄に入れる(ユーザー補助サービス) */
public class FrostInputService extends AccessibilityService {

    private static volatile FrostInputService instance;

    static boolean isEnabled() { return instance != null; }

    /** 範囲スクショを始める(ユーザー補助がオンのときだけ) */
    static boolean startRegionShot(RegionShot.Listener l) {
        FrostInputService s = instance;
        if (s == null) return false;
        if (s.regionShot == null) s.regionShot = new RegionShot(s);
        s.regionShot.toggle(l);
        return true;
    }

    private RegionShot regionShot;

    /** 入力中の欄に文字を入れる。入れられたら true */
    static boolean insert(String text) {
        FrostInputService s = instance;
        return s != null && s.insertText(text);
    }

    @Override
    protected void onServiceConnected() { instance = this; }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    private boolean insertText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) return false;

        // 1. 貼り付けで入れる(ほとんどのアプリで、カーソル位置にそのまま入る)
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData previous = cm.hasPrimaryClip() ? cm.getPrimaryClip() : null;
        cm.setPrimaryClip(ClipData.newPlainText("voice", text));
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        // もとのクリップボードの中身に戻す
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (previous != null) cm.setPrimaryClip(previous);
        }, 600);
        if (ok) return true;

        // 2. 貼り付けできない欄は、文字をつなげて書き換える
        if (!node.isEditable()) return false;
        CharSequence cur = node.getText();
        String now = cur == null ? "" : cur.toString();
        int start = Math.max(0, Math.min(node.getTextSelectionStart(), now.length()));
        int end = Math.max(start, Math.min(node.getTextSelectionEnd(), now.length()));
        if (node.getTextSelectionStart() < 0) { start = now.length(); end = now.length(); }
        String next = now.substring(0, start) + text + now.substring(end);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
        boolean set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (set) {
            Bundle sel = new Bundle();
            int caret = start + text.length();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        }
        return set;
    }
}
