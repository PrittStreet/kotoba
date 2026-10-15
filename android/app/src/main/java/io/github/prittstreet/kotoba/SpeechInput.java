package io.github.prittstreet.kotoba;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Android speech recognition (Google's, the same as the keyboard microphone), with the language
 * chosen by the button, so nobody has to switch the keyboard. Main thread only.
 *
 * The recognizer ends a session by itself after a second or two of silence and often ignores the
 * silence length it is asked for. So one dictation can span several sessions: when one ends, the
 * next starts, until the speaker has been silent for the chosen time or taps the button again.
 */
final class SpeechInput implements RecognitionListener {
    /** Before the first word, wait at least this long. */
    private static final long FIRST_WORDS_MS = 6000;
    /** After asking the recognizer to stop, how long to wait for its last words. */
    private static final long LAST_WORDS_MS = 2000;

    private final MainActivity app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable silenceOver = this::finish;
    private final Runnable deliver = this::deliver;
    private final Runnable restart = this::listen;
    private SpeechRecognizer rec;
    private Intent intent;
    private boolean active;      // a dictation is under way
    private boolean ending;      // waiting for the last words before delivering
    private boolean ready;       // "ready" was sent to the page
    private int retries;
    private long silenceMs;
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
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.getPackageName())
                // Honoured by some recognizers only; the session chaining below covers the others.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, this.silenceMs)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, this.silenceMs);
        heard = "";
        partial = "";
        retries = 0;
        ready = false;
        ending = false;
        active = true;
        main.postDelayed(silenceOver, Math.max(this.silenceMs, FIRST_WORDS_MS));
        listen();
    }

    /** The button was tapped again: finish now with what was heard. */
    void stop() {
        finish();
    }

    /** Drops the dictation, no result. */
    void cancel() {
        active = false;
        ending = false;
        main.removeCallbacks(silenceOver);
        main.removeCallbacks(deliver);
        main.removeCallbacks(restart);
        release();
    }

    private void listen() {
        if (!active || ending) return;
        release(); // a fresh recognizer each time avoids "busy" errors on some phones
        rec = SpeechRecognizer.createSpeechRecognizer(app);
        rec.setRecognitionListener(this);
        partial = "";
        rec.startListening(intent);
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
    private void finish() {
        if (!active || ending) return;
        ending = true;
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
        cancel();
        if (text.isEmpty()) app.emitSpeech("error", null, "no-speech");
        else app.emitSpeech("final", text, null);
    }

    private String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + sep + b;
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        if (!active || ready) return;
        ready = true;
        app.emitSpeech("ready", null, null);
    }

    @Override
    public void onBeginningOfSpeech() {
        if (active && !ending) heardWords();
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
        heard = join(heard, text.isEmpty() ? partial : text);
        partial = "";
        if (ending) {
            main.removeCallbacks(deliver);
            deliver();
            return;
        }
        if (!heard.isEmpty()) app.emitSpeech("partial", heard, null);
        main.postDelayed(restart, 100); // the speaker may go on after a pause
    }

    @Override
    public void onError(int error) {
        if (!active) return;
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
                main.postDelayed(restart, 100);
                return;
            case SpeechRecognizer.ERROR_CLIENT:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                if (retries++ < 3) {
                    main.postDelayed(restart, 400);
                    return;
                }
                break;
            default:
                break;
        }
        // A real problem: keep what was heard, otherwise report it.
        String text = join(heard, partial);
        cancel();
        if (!text.isEmpty()) app.emitSpeech("final", text, null);
        else app.emitSpeech("error", null, code(error));
    }

    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
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
