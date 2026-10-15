package io.github.prittstreet.kotoba;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.LocaleList;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;

import java.util.Locale;

/**
 * WebView that tells the keyboard which language to type in (EditorInfo.hintLocales):
 * Gboard switches to that language when it is installed, which a web page cannot ask for.
 */
@SuppressLint("ViewConstructor")
final class KotobaWebView extends WebView {
    private LocaleList keyboardLocales;

    KotobaWebView(Context context) {
        super(context);
    }

    /** Takes effect the next time the keyboard connects (see InputMethodManager.restartInput). */
    void setKeyboardLanguage(Locale locale) {
        keyboardLocales = new LocaleList(locale);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection connection = super.onCreateInputConnection(outAttrs);
        if (keyboardLocales != null) outAttrs.hintLocales = keyboardLocales;
        return connection;
    }
}
