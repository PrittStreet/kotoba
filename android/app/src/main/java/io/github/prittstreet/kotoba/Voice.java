package io.github.prittstreet.kotoba;

import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads translations aloud with the phone's voices (Android text-to-speech), as a man or a woman.
 * Android does not say a voice's gender: it is guessed from the voice's name (Google's voices are
 * known). Without a man's voice for the language, the default voice is pitched lower. Main thread only.
 */
final class Voice implements TextToSpeech.OnInitListener {
    /** Google voices ("ja-jp-x-jac-local"): the code after "-x-". Same as Google Cloud's Standard A–E. */
    private static final Set<String> GOOGLE_MALE = new HashSet<>(Arrays.asList("jac", "jad", "frb", "frd"));
    private static final Set<String> GOOGLE_FEMALE = new HashSet<>(Arrays.asList("htm", "jab", "fra", "frc", "vlf"));
    private static final Pattern GOOGLE_CODE = Pattern.compile("-x-([a-z]+)(-|$)");
    /** No man's voice: the default one, lower. */
    private static final float DEEPER = 0.78f;

    private final MainActivity app;
    private final TextToSpeech tts;
    private volatile boolean ready; // also read by describe(), on the page's bridge thread
    private boolean male;
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

    void setMale(boolean male) {
        this.male = male;
    }

    /** lang: "fr" or "ja". */
    void speak(String text, String lang) {
        if (text == null || text.isEmpty()) return;
        if (!ready) {
            pending = new String[]{text, lang};
            return;
        }
        boolean ja = "ja".equals(lang);
        Locale locale = ja ? Locale.JAPAN : Locale.FRANCE;
        int r = tts.setLanguage(locale);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            app.toast((ja ? "Voix japonaise" : "Voix française")
                    + " absente : installe-la dans Réglages Android > Synthèse vocale.");
            return;
        }
        // setLanguage chose the phone's default voice: kept when it fits (most are women's).
        String want = male ? "male" : "female";
        String has = gender(current());
        boolean deeper = false;
        if (!has.equals(want) && !(has.isEmpty() && !male)) {
            android.speech.tts.Voice chosen = pick(locale, want);
            if (chosen != null) tts.setVoice(chosen);
            else deeper = male;
        }
        tts.setPitch(deeper ? DEEPER : 1f);
        tts.setSpeechRate(ja ? 0.92f : 1f);
        // the engine refuses texts longer than its limit (4000 characters): read them in pieces
        int max = Math.max(100, TextToSpeech.getMaxSpeechInputLength() - 1);
        for (int i = 0; i < text.length(); i += max) {
            String part = text.substring(i, Math.min(text.length(), i + max));
            tts.speak(part, i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "kotoba" + i);
        }
    }

    private android.speech.tts.Voice current() {
        try {
            return tts.getVoice();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The installed voice of that gender for the language, the phone's own country first; null if none. */
    private android.speech.tts.Voice pick(Locale locale, String gender) {
        Set<android.speech.tts.Voice> all;
        try {
            all = tts.getVoices();
        } catch (RuntimeException e) {
            return null; // some engines throw instead of returning nothing
        }
        if (all == null) return null;
        android.speech.tts.Voice best = null;
        int bestScore = -1;
        for (android.speech.tts.Voice v : all) {
            if (!usable(v, locale) || !gender.equals(gender(v))) continue;
            int score = (locale.getCountry().equals(v.getLocale().getCountry()) ? 2 : 0)
                    + (v.isNetworkConnectionRequired() ? 0 : 1);
            if (score > bestScore) {
                best = v;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean usable(android.speech.tts.Voice v, Locale locale) {
        if (v.getLocale() == null || !locale.getLanguage().equals(v.getLocale().getLanguage())) return false;
        Set<String> f = v.getFeatures();
        return f == null || !f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    /** "male", "female" or "" (unknown). */
    static String gender(android.speech.tts.Voice v) {
        if (v == null) return "";
        String name = v.getName() == null ? "" : v.getName().toLowerCase(Locale.ROOT);
        if (v.getFeatures() != null) {
            for (String f : v.getFeatures()) {
                String s = f.toLowerCase(Locale.ROOT);
                if (s.contains("female")) return "female";
                if (s.contains("male")) return "male";
            }
        }
        if (name.contains("female")) return "female";
        if (name.contains("male")) return "male";
        Matcher m = GOOGLE_CODE.matcher(name);
        if (m.find()) {
            if (GOOGLE_MALE.contains(m.group(1))) return "male";
            if (GOOGLE_FEMALE.contains(m.group(1))) return "female";
        }
        return "";
    }

    /** For the settings: {"fr": {"male": n, "female": n, "names": [...]}, "ja": {...}}; {} before the engine is ready. */
    String describe() {
        JSONObject out = new JSONObject();
        if (!ready) return out.toString();
        Set<android.speech.tts.Voice> all;
        try {
            all = tts.getVoices();
        } catch (RuntimeException e) {
            return out.toString();
        }
        if (all == null) return out.toString();
        try {
            for (Locale locale : new Locale[]{Locale.FRANCE, Locale.JAPAN}) {
                int men = 0, women = 0;
                JSONArray names = new JSONArray();
                for (android.speech.tts.Voice v : all) {
                    if (!usable(v, locale)) continue;
                    String g = gender(v);
                    if (g.equals("male")) men++;
                    if (g.equals("female")) women++;
                    names.put(v.getName() + (g.isEmpty() ? "" : " (" + g + ")"));
                }
                out.put(locale.getLanguage(), new JSONObject().put("male", men).put("female", women).put("names", names));
            }
        } catch (JSONException e) {
            return "{}";
        }
        return out.toString();
    }

    void stop() {
        pending = null;
        if (ready) tts.stop();
    }

    void shutdown() {
        tts.shutdown();
    }
}
