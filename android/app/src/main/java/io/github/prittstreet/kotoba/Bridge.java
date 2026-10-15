package io.github.prittstreet.kotoba;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;

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

    /** lang: "fr-FR" or "ja-JP". Results come back through MainActivity.emitSpeech. */
    @JavascriptInterface
    public void startListening(String lang) {
        app.runOnUiThread(() -> app.listen(lang));
    }

    /** Ends the sentence now: what was heard is sent as the final result. */
    @JavascriptInterface
    public void stopListening() {
        app.runOnUiThread(() -> app.speech.stop());
    }

    /** Drops the sentence, no result. */
    @JavascriptInterface
    public void cancelListening() {
        app.runOnUiThread(() -> app.speech.cancel());
    }

    /** lang: "fr" or "ja". */
    @JavascriptInterface
    public void speak(String text, String lang) {
        app.runOnUiThread(() -> app.voice.speak(text, lang));
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

    @JavascriptInterface
    public String version() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
