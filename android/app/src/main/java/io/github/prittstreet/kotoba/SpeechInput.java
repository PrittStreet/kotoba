package io.github.prittstreet.kotoba;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Android speech recognition (Google's, the same as the keyboard microphone), with the language
 * chosen by the button, so nobody has to switch the keyboard. Main thread only.
 *
 * The recognizer ends a session by itself after about a second of silence. So one dictation can
 * span several sessions: when one ends, the next starts at once on the same recognizer, until the
 * speaker has been silent for the chosen time or taps the button again. The silence-length extras
 * of RecognizerIntent are not used: Android warns they give unexpected results, and they do.
 */
final class SpeechInput implements RecognitionListener {
    /** Before the first word, wait at least this long. */
    private static final long FIRST_WORDS_MS = 6000;
    /** After asking the recognizer to stop, how long to wait for its last words. */
    private static final long LAST_WORDS_MS = 1500;
    /** A session that fails faster than this is not listening: wait a little before the next. */
    private static final long SHORT_SESSION_MS = 700;

    private final MainActivity app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable silenceOver = () -> finish("silence");
    private final Runnable deliver = this::deliver;
    private final Runnable restart = this::listen;
    private SpeechRecognizer rec;
    private Intent intent;
    private boolean active;      // a dictation is under way
    private boolean ending;      // waiting for the last words before delivering
    private boolean ready;       // "ready" was sent to the page
    private int retries;
    private int sessions;
    private long silenceMs;
    private long startedAt;      // the dictation
    private long sessionAt;      // the current recognizer session
    private String sep = " ";
    private String heard = "";   // words of the finished sessions
    private String partial = ""; // words of the current session so far

    SpeechInput(MainActivity app) {
        this.app = app;
    }

    /** lang: "fr-FR" or "ja-JP". Ends after silenceMs without new words. */
    void start(String lang, long silenceMs) {
        cancel();
        this.silenceMs = Math.max(1000, Math.min(silenceMs, 30000));
        sep = lang.startsWith("ja") ? "" : " ";
        intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.getPackageName());
        heard = "";
        partial = "";
        retries = 0;
        sessions = 0;
        ready = false;
        ending = false;
        active = true;
        startedAt = SystemClock.elapsedRealtime();
        log("début " + lang + ", fin après " + this.silenceMs + " ms de silence");
        main.postDelayed(silenceOver, Math.max(this.silenceMs, FIRST_WORDS_MS));
        create();
        listen();
    }

    /** The button was tapped again: finish now with what was heard. */
    void stop() {
        finish("bouton");
    }

    /** Drops the dictation, no result. */
    void cancel() {
        if (active) log("annulée");
        active = false;
        ending = false;
        main.removeCallbacks(silenceOver);
        main.removeCallbacks(deliver);
        main.removeCallbacks(restart);
        release();
    }

    private void create() {
        release();
        rec = SpeechRecognizer.createSpeechRecognizer(app);
        rec.setRecognitionListener(this);
    }

    private void listen() {
        if (!active || ending) return;
        if (rec == null) create();
        partial = "";
        sessions++;
        sessionAt = SystemClock.elapsedRealtime();
        log("écoute (session " + sessions + ")");
        rec.startListening(intent);
    }

    /** Next session on the same recognizer, right away unless the last one failed at once. */
    private void listenAgain() {
        long lasted = SystemClock.elapsedRealtime() - sessionAt;
        main.removeCallbacks(restart);
        main.postDelayed(restart, lasted < SHORT_SESSION_MS ? 500 : 0);
    }

    private void release() {
        if (rec == null) return;
        rec.cancel();
        rec.destroy();
        rec = null;
    }

    /** New words: the silence count starts again. */
    private void heardWords() {
        retries = 0;
        main.removeCallbacks(silenceOver);
        main.postDelayed(silenceOver, silenceMs);
    }

    /** Silent long enough, or the button was tapped. */
    private void finish(String why) {
        if (!active || ending) return;
        ending = true;
        log("fin (" + why + ")");
        main.removeCallbacks(silenceOver);
        main.removeCallbacks(restart);
        if (rec != null && !partial.isEmpty()) {
            rec.stopListening(); // the recognizer settles the last words in onResults
            main.postDelayed(deliver, LAST_WORDS_MS);
        } else {
            deliver();
        }
    }

    private void deliver() {
        if (!active) return;
        String text = join(heard, partial);
        log("texte final : " + text.length() + " caractères");
        active = false; // no "annulée" in the log
        cancel();
        if (text.isEmpty()) app.emitSpeech("error", null, "no-speech");
        else app.emitSpeech("final", text, null);
    }

    private String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + sep + b;
    }

    /** One line in the page's dictation log (Settings > Dictée), to see what happens on the phone. */
    private void log(String what) {
        app.emitSpeech("log", "+" + (SystemClock.elapsedRealtime() - startedAt) + " ms  " + what, null);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        if (!active) return;
        log("prêt");
        if (ready) return;
        ready = true;
        app.emitSpeech("ready", null, null);
    }

    @Override
    public void onBeginningOfSpeech() {
        if (!active) return;
        log("voix détectée");
        if (!ending) heardWords();
    }

    @Override
    public void onEndOfSpeech() {
        if (active) log("fin de voix");
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String text = best(partialResults);
        if (!active || text.isEmpty() || text.equals(partial)) return;
        partial = text;
        if (!ending) heardWords();
        app.emitSpeech("partial", join(heard, partial), null);
    }

    @Override
    public void onResults(Bundle results) {
        if (!active) return;
        String text = best(results);
        log("résultat : « " + (text.isEmpty() ? partial : text) + " »");
        heard = join(heard, text.isEmpty() ? partial : text);
        partial = "";
        if (ending) {
            main.removeCallbacks(deliver);
            deliver();
            return;
        }
        if (!heard.isEmpty()) app.emitSpeech("partial", heard, null);
        listenAgain(); // the speaker may go on after a pause
    }

    @Override
    public void onError(int error) {
        if (!active) return;
        // Some phones send a late "no match" for the session that just ended: not this one's.
        if (error == SpeechRecognizer.ERROR_NO_MATCH && !ending
                && SystemClock.elapsedRealtime() - sessionAt < 200) {
            log("erreur " + error + " ignorée (session précédente)");
            return;
        }
        log("erreur " + error + " (" + code(error) + ")");
        if (ending) {
            main.removeCallbacks(deliver);
            deliver();
            return;
        }
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                // A pause: keep listening until the chosen silence is over.
                heard = join(heard, partial);
                partial = "";
                listenAgain();
                return;
            case SpeechRecognizer.ERROR_CLIENT:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                if (retries++ < 3) {
                    heard = join(heard, partial);
                    partial = "";
                    create(); // this recognizer is stuck: a fresh one
                    main.removeCallbacks(restart);
                    main.postDelayed(restart, 300);
                    return;
                }
                break;
            default:
                break;
        }
        // A real problem: keep what was heard, otherwise report it.
        String text = join(heard, partial);
        active = false;
        cancel();
        if (!text.isEmpty()) app.emitSpeech("final", text, null);
        else app.emitSpeech("error", null, code(error));
    }

    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    private static String best(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty() || r.get(0) == null) return "";
        return r.get(0).trim();
    }

    /** Same names as the Web Speech API errors, which the page already handles. */
    @SuppressWarnings("InlinedApi")
    private static String code(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "no-match";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "no-speech";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "not-allowed";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "network";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return "busy";
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio-capture";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "language-not-supported";
            default:
                return "other";
        }
    }
}
