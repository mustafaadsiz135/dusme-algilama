package io.github.mustafaadsiz135.dusme;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Dusme sonrasi akis:
 *   DUSME -> (soru gecikmesi, varsayilan 4 sn) kalkmadiysa "Iyi misiniz?" sorulur
 *        -> mikrofon dinlenir:
 *             "iyiyim"  -> iptal, sistem tekrar izlemeye doner
 *             "yardim"  -> hemen arama
 *        -> (yanit suresi, varsayilan 7 sn) icinde kalkmazsa / iyiyim demezse -> arama
 * Herhangi bir anda kisi ayaga kalkarsa akis iptal olur.
 * Tum metotlar ana (UI) is parcaciginda calisir.
 */
public class EmergencyFlow {

    public enum State { IDLE, WAITING, LISTENING, CALLED }

    public interface Listener {
        void onStateChanged(State state, String message);
        void onCountdown(String text);
        void onHeard(String text);
    }

    private static final String UTT_ASK = "ask";
    private static final String UTT_INFO = "info";

    private final Context ctx;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;

    private State state = State.IDLE;
    private long fallTime;
    private long askTime;
    private boolean recognizerBusy = false;

    public EmergencyFlow(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        tts = new TextToSpeech(this.ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Turkce ses paketi yoksa motor varsayilan dille yine konusur
                tts.setLanguage(new Locale("tr", "TR"));
                ttsReady = true;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) {
                        if (UTT_ASK.equals(id)) handler.post(() -> {
                            if (state == State.LISTENING) startListening();
                        });
                    }
                    @Override public void onError(String id) {
                        if (UTT_ASK.equals(id)) handler.post(() -> {
                            if (state == State.LISTENING) startListening();
                        });
                    }
                });
            }
        });
    }

    public State getState() { return state; }

    /** Dusme algilandi. */
    public void start() {
        if (state != State.IDLE) return;
        fallTime = SystemClock.elapsedRealtime();
        setState(State.WAITING, "Düşme algılandı! Kalkmanız bekleniyor…");
        vibrate(400);
        handler.postDelayed(askRunnable, AppPrefs.getAskDelayMs(ctx));
        handler.post(tick);
    }

    /** Kamera kisinin ayaga kalktigini gordu. */
    public void onPersonStoodUp() {
        if (state == State.WAITING || state == State.LISTENING) {
            cancel("Kişi ayağa kalktı – alarm iptal edildi.");
        }
    }

    /** Ekrandaki "Iyiyim" dugmesi. */
    public void userSaysOk() {
        if (state == State.WAITING || state == State.LISTENING) {
            cancel("\"İyiyim\" onayı alındı – alarm iptal edildi.");
        }
    }

    /** Arama yapildiktan sonra izlemeye donmek icin. */
    public void reset() {
        stopAll();
        setState(State.IDLE, "İzleniyor…");
    }

    public void callNow() {
        if (state == State.CALLED) return;
        placeCall();
    }

    // ------------------------------------------------------------------

    private final Runnable askRunnable = new Runnable() {
        @Override public void run() {
            if (state != State.WAITING) return;
            askTime = SystemClock.elapsedRealtime();
            setState(State.LISTENING, "\"İyi misiniz?\" – \"İyiyim\" ya da \"Yardım\" deyin");
            vibrate(200);
            speak("İyi misiniz?", UTT_ASK);
            if (!ttsReady) startListening();
            handler.postDelayed(deadlineRunnable, AppPrefs.getAnswerWindowMs(ctx));
        }
    };

    private final Runnable deadlineRunnable = () -> {
        if (state == State.LISTENING) placeCall();
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (state == State.WAITING) {
                long left = AppPrefs.getAskDelayMs(ctx) - (now - fallTime);
                listener.onCountdown(String.format(Locale.getDefault(), "Soru: %.1f sn", Math.max(0, left) / 1000f));
            } else if (state == State.LISTENING) {
                long left = AppPrefs.getAnswerWindowMs(ctx) - (now - askTime);
                listener.onCountdown(String.format(Locale.getDefault(), "Arama: %.1f sn", Math.max(0, left) / 1000f));
            } else {
                return;
            }
            handler.postDelayed(this, 100);
        }
    };

    private void placeCall() {
        stopAll();
        String number = AppPrefs.getPhone(ctx);
        if (number.isEmpty()) {
            setState(State.CALLED, "ARAMA YAPILAMADI: Acil durum numarası ayarlı değil!");
            speak("Acil durum numarası ayarlanmamış.", UTT_INFO);
            return;
        }
        setState(State.CALLED, "Acil arama yapılıyor: " + number);
        try {
            Intent call = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null));
            call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(call);
            // Yerdeki kisi telefona uzanamayabilir: hoparloru acmayi dene
            handler.postDelayed(this::trySpeaker, 2500);
            handler.postDelayed(this::trySpeaker, 6000);
        } catch (SecurityException e) {
            setState(State.CALLED, "Arama izni yok! Ayarlardan 'Telefon' iznini verin.");
        } catch (Exception e) {
            setState(State.CALLED, "Arama başlatılamadı: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void trySpeaker() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setSpeakerphoneOn(true);
        } catch (Exception ignored) { }
    }

    private void cancel(String msg) {
        stopAll();
        setState(State.IDLE, msg);
    }

    private void stopAll() {
        handler.removeCallbacks(askRunnable);
        handler.removeCallbacks(deadlineRunnable);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(restartListening);
        destroyRecognizer();
        if (tts != null) tts.stop();
    }

    private void setState(State s, String msg) {
        state = s;
        listener.onStateChanged(s, msg);
    }

    private void speak(String text, String id) {
        if (tts == null || !ttsReady) return;
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
    }

    // ---------------- Konusma tanima ----------------

    private final Runnable restartListening = () -> {
        if (state == State.LISTENING) startListening();
    };

    private void startListening() {
        if (state != State.LISTENING || recognizerBusy) return;
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            listener.onHeard("(Bu cihazda konuşma tanıma yok – sadece süre işliyor)");
            return;
        }
        destroyRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { listener.onHeard("🎤 Dinleniyor…"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] bytes) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int i, Bundle b) { }

            @Override public void onPartialResults(Bundle b) {
                handleResults(b, false);
            }

            @Override public void onResults(Bundle b) {
                recognizerBusy = false;
                if (!handleResults(b, true)) handler.postDelayed(restartListening, 150);
            }

            @Override public void onError(int error) {
                recognizerBusy = false;
                if (state == State.LISTENING) handler.postDelayed(restartListening, 300);
            }
        });

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        try {
            recognizerBusy = true;
            recognizer.startListening(i);
        } catch (Exception e) {
            recognizerBusy = false;
            handler.postDelayed(restartListening, 500);
        }
    }

    /** @return true eger karar verildiyse (iyiyim / yardim) */
    private boolean handleResults(Bundle b, boolean isFinal) {
        if (state != State.LISTENING || b == null) return state != State.LISTENING;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return false;
        listener.onHeard("Duyulan: \"" + list.get(0) + "\"");
        for (String raw : list) {
            Answer a = classifyAnswer(raw);
            if (a == Answer.HELP) {
                listener.onHeard("Duyulan: \"" + raw + "\" → YARDIM");
                placeCall();
                return true;
            }
        }
        for (String raw : list) {
            if (classifyAnswer(raw) == Answer.OK) {
                listener.onHeard("Duyulan: \"" + raw + "\" → İYİ");
                cancel("\"İyiyim\" duyuldu – alarm iptal edildi.");
                return true;
            }
        }
        return false;
    }

    enum Answer { NONE, OK, HELP }

    static Answer classifyAnswer(String raw) {
        if (raw == null) return Answer.NONE;
        String s = raw.toLowerCase(new Locale("tr", "TR"));
        // Once yardim / olumsuz ifadeler ("iyi degilim" icinde "iyi" de gecer!)
        if (s.contains("yardım") || s.contains("yardim") || s.contains("imdat")
                || s.contains("değil") || s.contains("degil") || s.contains("kötü")
                || s.contains("ambulans") || s.contains("acil") || s.contains("hayır")) {
            return Answer.HELP;
        }
        if (s.contains("iyiyim") || s.contains("iyi") || s.contains("tamam")
                || s.contains("sorun yok") || s.contains("evet")) {
            return Answer.OK;
        }
        return Answer.NONE;
    }

    private void destroyRecognizer() {
        recognizerBusy = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    public void release() {
        stopAll();
        if (tts != null) { tts.shutdown(); tts = null; }
    }
}
