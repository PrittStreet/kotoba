package io.github.prittstreet.kotoba;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Android speech recognition (Google's, the same as the keyboard microphone), with the language
 * chosen by the button, so nobody has to switch the keyboard. One sentence per start(); main thread only.
 */
final class SpeechInput implements RecognitionListener {
    private final MainActivity app;
    private SpeechRecognizer rec;
    private boolean active;
    private String partial = "";

    SpeechInput(MainActivity app) {
        this.app = app;
    }

    void start(String lang) {
        release(); // a fresh recognizer each time avoids "busy" errors on some phones
        rec = SpeechRecognizer.createSpeechRecognizer(app);
        rec.setRecognitionListener(this);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.getPackageName());
        partial = "";
        active = true;
        rec.startListening(intent);
    }

    void stop() {
        if (rec != null && active) rec.stopListening();
    }

    void cancel() {
        active = false;
        release();
    }

    private void release() {
        if (rec == null) return;
        rec.cancel();
        rec.destroy();
        rec = null;
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        if (active) app.emitSpeech("ready", null, null);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String text = best(partialResults);
        if (!active || text.isEmpty()) return;
        partial = text;
        app.emitSpeech("partial", text, null);
    }

    @Override
    public void onResults(Bundle results) {
        if (!active) return;
        active = false;
        String text = best(results);
        app.emitSpeech("final", text.isEmpty() ? partial : text, null);
    }

    @Override
    public void onError(int error) {
        if (!active) return;
        active = false;
        // Silence after a few words: keep what was heard.
        if (!partial.isEmpty() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            app.emitSpeech("final", partial, null);
        } else {
            app.emitSpeech("error", null, code(error));
        }
    }

    @Override public void onBeginningOfSpeech() {}
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
