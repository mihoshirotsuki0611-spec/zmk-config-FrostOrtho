package jp.frost.guide;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * F13 での音声入力(Android 標準の音声認識を使う)。
 *  - 長押し: 押している間だけ聞き取る
 *  - 2回タップ: 聞き取りを始め、もう一度 F13 を押すと終わる
 */
class VoiceInput {

    interface Listener { void onVoiceState(String state, String text); }

    static final int F13_POSITION = 39; // FrostOrtho のキー番号(右手最下段、Oの下 = F13)
    private static final long HOLD_MS = 300;
    private static final long DOUBLE_TAP_MS = 400;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;

    private boolean keyDown, holdActive, handsFree, ignoreNextUp, wantListening;
    private long lastTapUp;

    VoiceInput(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    // ---------- F13 の押し方を見分ける ----------
    void onKey(boolean down) {
        long now = System.currentTimeMillis();
        if (down) {
            keyDown = true;
            if (handsFree) {             // 2回タップで始めたものを、もう一度押して終える
                handsFree = false;
                ignoreNextUp = true;
                stop();
                return;
            }
            main.postDelayed(holdCheck, HOLD_MS);
        } else {
            keyDown = false;
            main.removeCallbacks(holdCheck);
            if (ignoreNextUp) { ignoreNextUp = false; return; }
            if (holdActive) { holdActive = false; stop(); return; }
            if (now - lastTapUp < DOUBLE_TAP_MS) {   // 2回タップ
                lastTapUp = 0;
                handsFree = true;
                start();
            } else {
                lastTapUp = now;
            }
        }
    }

    private final Runnable holdCheck = () -> {
        if (keyDown && !handsFree) {
            holdActive = true;
            start();
        }
    };

    // ---------- 聞き取り ----------
    private void start() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            listener.onVoiceState("error", "音声認識が使えません");
            return;
        }
        if (!FrostInputService.isEnabled()) {
            listener.onVoiceState("error", "ユーザー補助で「FrostOrtho 音声入力」をオンにしてください");
            return;
        }
        wantListening = true;
        if (sr == null) {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx);
            sr.setRecognitionListener(recognition);
        }
        begin();
        listener.onVoiceState("listening", "");
    }

    private void begin() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        try {
            sr.startListening(i);
        } catch (SecurityException e) {
            wantListening = false;
            listener.onVoiceState("error", "マイクの許可が必要です");
        }
    }

    private void stop() {
        wantListening = false;
        if (sr != null) sr.stopListening();
        listener.onVoiceState("processing", "");
    }

    void destroy() {
        wantListening = false;
        main.removeCallbacksAndMessages(null);
        if (sr != null) { sr.destroy(); sr = null; }
    }

    private final RecognitionListener recognition = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partial) {
            String t = first(partial);
            if (t != null && wantListening) listener.onVoiceState("listening", t);
        }

        @Override
        public void onResults(Bundle results) {
            String t = first(results);
            if (t != null && !t.isEmpty()) {
                if (!FrostInputService.insert(t)) {
                    listener.onVoiceState("error", "入力欄が見つかりません");
                }
            }
            // まだ聞き取りを続ける状態なら(押しっぱなし中・2回タップ中)、続けて聞く
            if (wantListening) { begin(); listener.onVoiceState("listening", ""); }
            else listener.onVoiceState("idle", "");
        }

        @Override
        public void onError(int error) {
            boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (wantListening && silence) { begin(); return; }
            if (!wantListening && silence) { listener.onVoiceState("idle", ""); return; }
            wantListening = false;
            handsFree = false;
            String msg = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "マイクの許可が必要です"
                    : error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    ? "通信できません" : "聞き取りに失敗しました(" + error + ")";
            listener.onVoiceState("error", msg);
        }
    };

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r == null || r.isEmpty()) ? null : r.get(0);
    }
}
