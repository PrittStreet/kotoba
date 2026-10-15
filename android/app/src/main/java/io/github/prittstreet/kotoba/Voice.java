package io.github.prittstreet.kotoba;

import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Reads translations aloud with the phone's voices (Android text-to-speech). Main thread only. */
final class Voice implements TextToSpeech.OnInitListener {
    private final MainActivity app;
    private final TextToSpeech tts;
    private boolean ready;
    private String[] pending;

    Voice(MainActivity app) {
        this.app = app;
        tts = new TextToSpeech(app, this);
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        String[] p = pending;
        pending = null;
        if (p == null) return;
        if (ready) speak(p[0], p[1]);
        else app.toast("Pas de synthèse vocale sur ce téléphone.");
    }

    /** lang: "fr" or "ja". */
    void speak(String text, String lang) {
        if (text == null || text.isEmpty()) return;
        if (!ready) {
            pending = new String[]{text, lang};
            return;
        }
        boolean ja = "ja".equals(lang);
        int r = tts.setLanguage(ja ? Locale.JAPAN : Locale.FRANCE);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            app.toast((ja ? "Voix japonaise" : "Voix française")
                    + " absente : installe-la dans Réglages Android > Synthèse vocale.");
            return;
        }
        tts.setSpeechRate(ja ? 0.92f : 1f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kotoba");
    }

    void stop() {
        pending = null;
        if (ready) tts.stop();
    }

    void shutdown() {
        tts.shutdown();
    }
}
