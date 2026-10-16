package io.github.prittstreet.kotoba;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Kotoba: the web app from /web shown full screen, plus what a web page can't do well on Android:
 * a microphone per language, voices, the camera and the clipboard (see Bridge).
 */
public class MainActivity extends Activity implements LiveService.Listener {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILES = 1;
    private static final int REQ_MIC = 2;
    private static final int REQ_LIVE = 3;
    private static final int REQ_CAMERA_LIVE = 4;

    private KotobaWebView web;
    private WebViewAssetLoader assets;
    SpeechInput speech;
    Voice voice;
    boolean canListen;
    /** Height of the status and navigation bars in CSS px, for the page (it draws under them). */
    volatile int insetTop, insetBottom;
    private WindowInsetsControllerCompat bars;
    private boolean night;
    private ValueCallback<Uri[]> pendingFiles;
    private File pendingPhoto;
    private Uri pendingPhotoUri;
    private String pendingLang;
    private String[] pendingLive; // key, workspace, male: Live waiting for the microphone permission
    private int pendingSilenceMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LiveService.listener = this;
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);

        // Edge to edge: the page draws under the status and navigation bars and keeps clear of them itself
        // (CSS --inset-top / --inset-bottom); the keyboard and the sides shrink the page.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.paper));
        web = new KotobaWebView(this);
        web.setBackgroundColor(getColor(R.color.paper));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bars = WindowCompat.getInsetsController(getWindow(), root);
        darkBars(false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(sys.left, 0, sys.right, ime.bottom);
            float density = getResources().getDisplayMetrics().density;
            insetTop = Math.round(sys.top / density);
            insetBottom = ime.bottom > 0 ? 0 : Math.round(sys.bottom / density);
            web.evaluateJavascript("window.kotobaInsets && kotobaInsets(" + insetTop + "," + insetBottom + ")", null);
            return WindowInsetsCompat.CONSUMED;
        });

        speech = new SpeechInput(this);
        voice = new Voice(this);
        canListen = SpeechRecognizer.isRecognitionAvailable(this);
        if (savedInstanceState == null) clearPhotos();

        assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                // a picture taken in camera live, handed to the page's photo translation
                .addPathHandler("/photos/", new WebViewAssetLoader.InternalStoragePathHandler(this, new File(getCacheDir(), "photos")))
                .build();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);
        // The page follows the system font size (Samsung phones are often set large), up to 120 %:
        // beyond that the microphone keys and the translations drawn on photos no longer fit.
        s.setTextZoom(Math.min(120, Math.round(getResources().getConfiguration().fontScale * 100)));
        web.addJavascriptInterface(new Bridge(this), "KotobaAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }

            // Links (the Claude console) open in the browser, never inside the app.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException e) {
                    toast("Aucun navigateur pour ouvrir ce lien.");
                }
                return true;
            }

            // The page's process crashed or was killed for memory (big photos): start again instead of crashing.
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                recreate();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return chooseFile(callback, params.isCaptureEnabled());
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
        web.loadUrl(START_URL);
    }

    // ---------- back: close the page's top layer first ----------

    private void goBack() {
        web.evaluateJavascript("window.kotobaBack ? kotobaBack() : false", (handled) -> {
            if (!"true".equals(handled)) moveTaskToBack(true);
        });
    }

    // Android 12 and older only: from Android 13 the callback registered in onCreate handles back.
    @Override
    @SuppressWarnings("deprecation")
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        goBack();
    }

    // ---------- microphone ----------

    void listen(String lang, int silenceMs) {
        voice.stop();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = lang;
            pendingSilenceMs = silenceMs;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        speech.start(lang, silenceMs);
    }

    // ---------- Live mode (LiveService) ----------

    void startLive(String key, String workspace, boolean male) {
        speech.cancel();
        voice.stop();
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        // the notification shows Live is on (and holds its Stop key): asked once, not required
        boolean notify = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (mic && notify) {
            LiveService.start(this, key, workspace, male);
            return;
        }
        pendingLive = new String[]{key, workspace, male ? "1" : ""};
        requestPermissions(Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.RECORD_AUDIO}, REQ_LIVE);
    }

    // ---------- Camera live (CameraLiveActivity asks for the camera itself) ----------

    void startCameraLive(String key, String workspace) {
        speech.cancel();
        voice.stop();
        startActivityForResult(new Intent(this, CameraLiveActivity.class)
                .putExtra(CameraLiveActivity.EXTRA_KEY, key)
                .putExtra(CameraLiveActivity.EXTRA_WORKSPACE, workspace), REQ_CAMERA_LIVE);
    }

    @Override
    public void onLive(JSONObject ev) {
        web.evaluateJavascript("window.kotobaLive && kotobaLive(" + ev + ")", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_LIVE) {
            String[] l = pendingLive;
            pendingLive = null;
            if (l == null) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                LiveService.start(this, l[0], l[1], !l[2].isEmpty());
            } else {
                try {
                    onLive(new JSONObject().put("type", "state").put("state", "error")
                            .put("text", "Autorise le micro pour Kotoba (Réglages Android > Applis > Kotoba > Autorisations)."));
                } catch (JSONException ignored) {
                    // valid strings
                }
            }
            return;
        }
        if (requestCode != REQ_MIC) return;
        String lang = pendingLang;
        pendingLang = null;
        if (lang == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) speech.start(lang, pendingSilenceMs);
        else emitSpeech("error", null, "not-allowed");
    }

    /** Sends a speech event to the page: window.kotobaNative.speech({type, text, code}). */
    void emitSpeech(String type, String text, String code) {
        JSONObject ev = new JSONObject();
        try {
            ev.put("type", type);
            if (text != null) ev.put("text", text);
            if (code != null) ev.put("code", code);
        } catch (JSONException e) {
            return;
        }
        web.evaluateJavascript("window.kotobaNative && kotobaNative.speech(" + ev + ")", null);
    }

    /** The silence count (re)starts: the dictation ends in ms unless words are heard (the key's outline). */
    void emitTimer(long ms) {
        JSONObject ev = new JSONObject();
        try {
            ev.put("type", "timer");
            ev.put("ms", ms);
        } catch (JSONException e) {
            return;
        }
        web.evaluateJavascript("window.kotobaNative && kotobaNative.speech(" + ev + ")", null);
    }

    // ---------- keyboard ----------

    /** lang: "fr" or "ja". The keyboard opens in that language (if installed); show: open it now. */
    void keyboard(String lang, boolean show) {
        web.setKeyboardLanguage("ja".equals(lang) ? Locale.JAPAN : Locale.FRANCE);
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm == null) return;
        imm.restartInput(web); // the keyboard reconnects and reads the new language
        if (show) {
            web.requestFocus();
            imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            // Not every keyboard follows the hint (Samsung's doesn't): tell the page which language
            // the keyboard really shows, so it can say how to switch.
            web.postDelayed(() -> reportKeyboard(lang), 900);
        }
    }

    @SuppressWarnings("deprecation") // getLocale: older keyboards only fill that one
    private void reportKeyboard(String lang) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm == null) return;
        InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
        String shown = subtype == null ? "" : subtype.getLanguageTag();
        if (shown.isEmpty() && subtype != null) shown = subtype.getLocale();
        String ime = null;
        try {
            ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        } catch (SecurityException ignored) {
            // not readable on this phone: the page gives the general advice
        }
        JSONObject ev = new JSONObject();
        try {
            ev.put("wanted", lang);
            ev.put("actual", shown);
            ev.put("ime", ime == null ? "" : ime);
        } catch (JSONException e) {
            return;
        }
        web.evaluateJavascript("window.kotobaNative && kotobaNative.keyboard && kotobaNative.keyboard(" + ev + ")", null);
    }

    // ---------- photos: camera app or photo picker ----------

    private boolean chooseFile(ValueCallback<Uri[]> callback, boolean camera) {
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        pendingFiles = null;
        pendingPhoto = null;
        pendingPhotoUri = null;
        Intent intent = camera ? cameraIntent() : galleryIntent();
        if (intent == null) return false;
        try {
            startActivityForResult(intent, REQ_FILES);
        } catch (ActivityNotFoundException e) {
            pendingPhoto = null;
            pendingPhotoUri = null;
            toast(camera ? "Aucune appli appareil photo trouvée." : "Aucune galerie trouvée.");
            return false;
        }
        pendingFiles = callback;
        return true;
    }

    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "photos");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File file = File.createTempFile("photo-", ".jpg", dir);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            pendingPhoto = file;
            pendingPhotoUri = uri;
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.setClipData(ClipData.newRawUri("", uri));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return i;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private Intent galleryIntent() {
        if (Build.VERSION.SDK_INT >= 33) return new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        return new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_CAMERA_LIVE) {
            String name = resultCode == RESULT_OK && data != null ? data.getStringExtra(CameraLiveActivity.EXTRA_PHOTO) : null;
            if (name != null && name.matches("[\\w.-]+")) {
                web.evaluateJavascript("window.kotobaPhoto && kotobaPhoto('/photos/" + name + "')", null);
            }
            return;
        }
        if (requestCode != REQ_FILES) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        ValueCallback<Uri[]> callback = pendingFiles;
        File photo = pendingPhoto;
        Uri photoUri = pendingPhotoUri;
        pendingFiles = null;
        pendingPhoto = null;
        pendingPhotoUri = null;
        if (callback == null) return;
        Uri result = null;
        if (resultCode == RESULT_OK) {
            if (photo != null) {
                if (photo.length() > 0) result = photoUri;
            } else if (data != null) {
                result = data.getData();
                if (result == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                    result = data.getClipData().getItemAt(0).getUri();
                }
            }
        }
        if (result == null) {
            callback.onReceiveValue(null);
            return;
        }
        Uri picked = result;
        new Thread(() -> {
            Uri usable = Photos.usable(this, picked);
            runOnUiThread(() -> callback.onReceiveValue(new Uri[]{usable}));
        }, "kotoba-photo").start();
    }

    private void clearPhotos() {
        File[] old = new File(getCacheDir(), "photos").listFiles();
        if (old == null) return;
        for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ---------- misc ----------

    /** Light icons on the status and navigation bars: over the dark photo viewer, or in the dark theme. */
    void darkBars(boolean dark) {
        bars.setAppearanceLightStatusBars(!dark && !night);
        bars.setAppearanceLightNavigationBars(!dark && !night);
    }

    void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // onStart/onStop rather than onResume/onPause: the microphone permission dialog must not stop dictation.
    @Override
    protected void onStart() {
        super.onStart();
        web.onResume();
    }

    @Override
    protected void onStop() {
        speech.cancel();
        voice.stop();
        web.onPause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (LiveService.listener == this) LiveService.listener = null; // Live itself goes on
        speech.cancel();
        voice.shutdown();
        ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
