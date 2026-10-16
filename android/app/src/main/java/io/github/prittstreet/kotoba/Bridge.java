package io.github.prittstreet.kotoba;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * window.KotobaAndroid in the page (web/app.js). Called on a background thread:
 * everything that touches Android UI or services goes back to the main thread.
 */
final class Bridge {
    private final MainActivity app;

    Bridge(MainActivity app) {
        this.app = app;
    }

    @JavascriptInterface
    public boolean canListen() {
        return app.canListen;
    }

    /**
     * lang: "fr-FR" or "ja-JP". Dictation ends after silenceMs without new words.
     * Results come back through MainActivity.emitSpeech.
     */
    @JavascriptInterface
    public void startListening(String lang, int silenceMs) {
        app.runOnUiThread(() -> app.listen(lang, silenceMs));
    }

    /** Ends the dictation now: what was heard is sent as the final result. */
    @JavascriptInterface
    public void stopListening() {
        app.runOnUiThread(() -> app.speech.stop());
    }

    /** Drops the dictation, no result. */
    @JavascriptInterface
    public void cancelListening() {
        app.runOnUiThread(() -> app.speech.cancel());
    }

    /** Keyboard language for the text field, "fr" or "ja"; show: also open the keyboard. */
    @JavascriptInterface
    public void keyboard(String lang, boolean show) {
        app.runOnUiThread(() -> app.keyboard(lang, show));
    }

    /** lang: "fr" or "ja". */
    @JavascriptInterface
    public void speak(String text, String lang) {
        app.runOnUiThread(() -> app.voice.speak(text, lang));
    }

    /** "male" or "female": the voice used from now on. */
    @JavascriptInterface
    public void voice(String gender) {
        app.runOnUiThread(() -> app.voice.setMale("male".equals(gender)));
    }

    /** Installed voices per language, with their guessed gender (Voice.describe). */
    @JavascriptInterface
    public String voices() {
        return app.voice.describe();
    }

    @JavascriptInterface
    public void stopSpeaking() {
        app.runOnUiThread(() -> app.voice.stop());
    }

    /** Returns true when Android shows its own "copied" notice (13 and newer). */
    @JavascriptInterface
    public boolean copy(String text) {
        app.runOnUiThread(() -> {
            ClipboardManager cm = app.getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Kotoba", text));
        });
        return Build.VERSION.SDK_INT >= 33;
    }

    /** "[top, bottom]": height of the status and navigation bars in CSS px. */
    @JavascriptInterface
    public String insets() {
        return "[" + app.insetTop + "," + app.insetBottom + "]";
    }

    /** true while the dark photo viewer is open. */
    @JavascriptInterface
    public void darkBars(boolean dark) {
        app.runOnUiThread(() -> app.darkBars(dark));
    }

    /** Key and workspace built into a private APK (empty in public builds): {"apiKey", "workspace"}. */
    @JavascriptInterface
    public String config() {
        JSONObject c = new JSONObject();
        try {
            c.put("apiKey", BuildConfig.API_KEY);
            c.put("workspace", BuildConfig.WORKSPACE);
        } catch (JSONException e) {
            return "{}";
        }
        return c.toString();
    }

    // ---------- Live mode: Japanese heard around, translated phrase by phrase (LiveService) ----------

    @JavascriptInterface
    public void liveStart(String key, String workspace, boolean male) {
        app.runOnUiThread(() -> app.startLive(key, workspace, male));
    }

    @JavascriptInterface
    public void liveStop() {
        app.runOnUiThread(() -> LiveService.stop(app));
    }

    /** {running, headphones, partial, phrases}: to draw the Live screen when it opens. */
    @JavascriptInterface
    public String liveState() {
        return LiveService.state();
    }

    /** Tokens used by Live since the last call, for the cost estimate: [[input, output], …]. */
    @JavascriptInterface
    public String liveUsage() {
        return LiveService.takeUsage();
    }

    @JavascriptInterface
    public String version() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
