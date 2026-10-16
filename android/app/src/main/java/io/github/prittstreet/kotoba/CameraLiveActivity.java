package io.github.prittstreet.kotoba;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.mlkit.vision.MlKitAnalyzer;
import androidx.camera.view.CameraController;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Camera live: the Japanese the camera sees, translated in place, the labels moving with the text.
 * The phone reads the text itself (ML Kit, offline, several times a second) and follows each line;
 * Claude translates each new line once (Haiku, a few lines per request), then the translation is
 * remembered. "Figer" freezes the picture to read it calmly.
 */
public class CameraLiveActivity extends ComponentActivity {
    static final String EXTRA_KEY = "key";
    static final String EXTRA_WORKSPACE = "workspace";
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff\\uf900-\\ufaff\\uff66-\\uff9f]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** A line read in at least this many pictures is real text, not a reading glitch: it is translated. */
    private static final int FRAMES_BEFORE_ASKING = 2;
    private static final int MAX_BATCH = 20;
    private static final int MAX_CONTEXT = 12;
    private static final int CACHE_SIZE = 500;

    /** Translations kept between visits, by text read: "-" for noise. Main thread. */
    private static final Map<String, String> cache = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService analysis = Executors.newSingleThreadExecutor();
    private final Set<String> asking = new HashSet<>();
    private final Runnable retry = this::request;
    private String key = "", workspace = "";
    private PreviewView preview;
    private ImageView still;
    private LabelsView labels;
    private TextView status, torch, freeze;
    private LifecycleCameraController controller;
    private TextRecognizer recognizer;
    private MlKitAnalyzer analyzer;
    private OnBackPressedCallback unfreezeOnBack;
    private boolean frozen, torchOn, cameraReady, destroyed;
    private String error = "";
    private long retryAt;
    private int failures;

    private final ActivityResultLauncher<String> permission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else {
                    Toast.makeText(this, "Autorise la caméra pour Kotoba dans les réglages Android.", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        key = nonNull(getIntent().getStringExtra(EXTRA_KEY));
        workspace = nonNull(getIntent().getStringExtra(EXTRA_WORKSPACE));
        build();
        unfreezeOnBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                setFrozen(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, unfreezeOnBack);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else permission.launch(Manifest.permission.CAMERA);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    // ---------- screen ----------

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(preview, match());
        still = new ImageView(this);
        still.setScaleType(ImageView.ScaleType.FIT_XY);
        still.setVisibility(View.GONE);
        root.addView(still, match());
        labels = new LabelsView(this);
        labels.setTranslations(cache::get);
        root.addView(labels, match());

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setGravity(Gravity.CENTER);
        status.setBackground(pill(Color.argb(170, 0, 0, 0)));
        status.setPadding(dp(14), dp(8), dp(14), dp(8));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.leftMargin = sp.rightMargin = dp(16);
        root.addView(status, sp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        TextView close = button("Fermer", Color.argb(170, 0, 0, 0));
        close.setOnClickListener(v -> finish());
        torch = button("Lampe", Color.argb(170, 0, 0, 0));
        torch.setVisibility(View.GONE);
        torch.setOnClickListener(v -> setTorch(!torchOn));
        freeze = button("Figer", 0xFFBD3F2A);
        freeze.setOnClickListener(v -> setFrozen(!frozen));
        bar.addView(close, barItem());
        bar.addView(torch, barItem());
        bar.addView(freeze, barItem());
        root.addView(bar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // edge to edge: the buttons and the status keep clear of the system bars
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            bar.setPadding(s.left + dp(12), dp(12), s.right + dp(12), s.bottom + dp(16));
            sp.topMargin = s.top + dp(12);
            status.setLayoutParams(sp);
            return insets;
        });
        setContentView(root);
        show("Vise un texte en japonais.");
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams barItem() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.leftMargin = p.rightMargin = dp(6);
        return p;
    }

    private TextView button(String label, int color) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setBackground(pill(color));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(24));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void show(String text) {
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------- camera and text recognition ----------

    private void startCamera() {
        recognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        controller = new LifecycleCameraController(this);
        controller.setCameraSelector(CameraSelector.DEFAULT_BACK_CAMERA);
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS);
        // a sharp enough picture for small writing, the same shape as the preview's
        ResolutionSelector shape = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build();
        controller.setPreviewResolutionSelector(shape);
        controller.setImageAnalysisResolutionSelector(new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build());
        // the positions come back on the screen's coordinates, wherever the preview is cropped
        analyzer = new MlKitAnalyzer(Collections.singletonList(recognizer), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(this), result -> onText(result.getValue(recognizer)));
        controller.setImageAnalysisAnalyzer(analysis, analyzer);
        controller.bindToLifecycle(this);
        preview.setController(controller);
        controller.getInitializationFuture().addListener(() -> {
            try {
                controller.getInitializationFuture().get();
                cameraReady = true;
                CameraInfo info = controller.getCameraInfo();
                torch.setVisibility(info != null && info.hasFlashUnit() ? View.VISIBLE : View.GONE);
            } catch (Exception e) {
                show("Caméra indisponible.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /** A picture read: the lines in Japanese move their labels, the new ones are translated. */
    private void onText(Text text) {
        if (destroyed || frozen || text == null) return;
        List<LabelsView.Seen> seen = new ArrayList<>();
        for (Text.TextBlock b : text.getTextBlocks()) {
            for (Text.Line line : b.getLines()) {
                String ja = line.getText().trim();
                android.graphics.Point[] p = line.getCornerPoints();
                if (p == null || p.length < 4 || !JAPANESE.matcher(ja).find()) continue;
                seen.add(new LabelsView.Seen(normalize(ja), ja, p));
            }
        }
        labels.update(seen);
        request();
        syncStatus();
    }

    /** The same text read twice gives the same key: full-width letters and spaces don't count. */
    private static String normalize(String ja) {
        return SPACES.matcher(Normalizer.normalize(ja, Normalizer.Form.NFKC)).replaceAll("");
    }

    private void setFrozen(boolean f) {
        if (f == frozen || controller == null) return;
        if (f) {
            Bitmap picture = preview.getBitmap();
            if (picture == null) return; // nothing on screen yet
            frozen = true;
            still.setImageBitmap(picture);
            still.setVisibility(View.VISIBLE);
            controller.clearImageAnalysisAnalyzer();
            if (torchOn) setTorch(false);
        } else {
            frozen = false;
            still.setVisibility(View.GONE);
            still.setImageDrawable(null);
            controller.setImageAnalysisAnalyzer(analysis, analyzer);
        }
        freeze.setText(frozen ? "Reprendre" : "Figer");
        unfreezeOnBack.setEnabled(frozen);
        labels.setFrozen(frozen);
        request(); // frozen: what is on screen is translated even if read only once
        syncStatus();
    }

    private void setTorch(boolean on) {
        if (!cameraReady || controller == null) return;
        torchOn = on;
        controller.enableTorch(on);
        torch.setText(on ? "Lampe ✓" : "Lampe");
    }

    private void syncStatus() {
        if (!error.isEmpty()) {
            show(error);
            return;
        }
        int waiting = 0;
        List<LabelsView.Label> all = labels.labels();
        for (LabelsView.Label l : all) if (!cache.containsKey(l.key)) waiting++;
        if (frozen) show(waiting > 0 ? "Image figée · traduction…" : "Image figée.");
        else if (all.isEmpty()) show("Vise un texte en japonais.");
        else show(waiting > 0 && !asking.isEmpty() ? "Traduction…" : "");
    }

    // ---------- translation ----------

    /** Asks Claude for the lines on screen not translated yet: one request at a time. */
    private void request() {
        if (destroyed || !asking.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now < retryAt) {
            main.removeCallbacks(retry);
            main.postDelayed(retry, retryAt - now);
            return;
        }
        List<String> todo = new ArrayList<>();
        List<String> context = new ArrayList<>();
        for (LabelsView.Label l : labels.labels()) {
            String fr = cache.get(l.key);
            if (fr != null) {
                if (!fr.equals("-") && context.size() < MAX_CONTEXT) context.add(l.key + " → " + fr);
                continue;
            }
            if (l.frames < FRAMES_BEFORE_ASKING && !frozen) continue;
            String alike = alike(l.key);
            if (alike != null) {
                cache.put(l.key, cache.get(alike)); // the same line read slightly differently
                continue;
            }
            if (!todo.contains(l.key) && todo.size() < MAX_BATCH) todo.add(l.key);
        }
        if (todo.isEmpty()) return;
        if (key.isEmpty()) {
            error = "Ajoute ta clé API dans les réglages de Kotoba.";
            syncStatus();
            return;
        }
        asking.addAll(todo);
        String prompt = prompt(todo, context);
        int max = Math.min(2000, 100 + 60 * todo.size());
        net.execute(() -> {
            List<String> fr = null;
            String err = "";
            try {
                fr = parse(Claude.ask(key, workspace, prompt, max), todo.size());
                if (fr == null) err = "Traduction illisible, nouvel essai…";
            } catch (IOException e) {
                err = e.getMessage() == null ? "Pas de réseau : traduction impossible." : e.getMessage();
            } catch (JSONException e) {
                err = "Traduction illisible, nouvel essai…";
            }
            List<String> out = fr;
            String problem = err;
            main.post(() -> answered(todo, out, problem));
        });
        syncStatus();
    }

    private void answered(List<String> todo, List<String> fr, String err) {
        asking.removeAll(todo);
        if (fr != null) {
            for (int i = 0; i < todo.size(); i++) cache.put(todo.get(i), fr.get(i));
            error = "";
            failures = 0;
        } else {
            error = err;
            failures++;
            // a refused key or no credit won't mend in 3 s: wait longer each time
            retryAt = SystemClock.uptimeMillis() + Math.min(30000, 3000L * failures);
        }
        if (destroyed) return;
        labels.invalidate();
        syncStatus();
        request();
    }

    /** A line already translated that reads almost the same (one letter misread). */
    private static String alike(String k) {
        if (k.length() < 4) return null;
        for (Map.Entry<String, String> e : cache.entrySet()) {
            String o = e.getKey();
            if (Math.abs(o.length() - k.length()) <= 2 && LabelsView.similarity(o, k) >= 0.8f) return o;
        }
        return null;
    }

    private static String prompt(List<String> lines, List<String> context) {
        return "You translate the Japanese that a French speaker living in Japan sees through their phone's camera, live: "
                + "signs, menus, labels, buttons, notices, packaging, screens.\n"
                + "The lines below were read by text recognition, in reading order. Translate each one into short, natural French, "
                + "as it would be printed in the same place (a sign stays a sign: « Sortie », « Quai 3 »).\n\n"
                + "Rules:\n"
                + "- One French string per line, in the same order: exactly " + lines.size() + " strings.\n"
                + "- Fix obvious recognition mistakes silently.\n"
                + "- Keep place and station names in rōmaji (Shinjuku), and numbers, prices, times and sizes.\n"
                + "- A line may be only part of a sentence that goes on in the next line: translate its part so the lines read well in order.\n"
                + "- A line that is recognition noise or means nothing: exactly \"-\".\n"
                + "- Answer with only a JSON array of strings: no Markdown, no notes.\n"
                + (context.isEmpty() ? "" : "\nAlso visible, already translated (context only):\n" + String.join("\n", context) + "\n")
                + "\nLines:\n" + new JSONArray(lines);
    }

    /** The JSON array of the answer, as many strings as lines; null if it is not that. */
    private static List<String> parse(String answer, int n) throws JSONException {
        int a = answer.indexOf('['), b = answer.lastIndexOf(']');
        if (a < 0 || b < a) return null;
        JSONArray arr = new JSONArray(answer.substring(a, b + 1));
        if (arr.length() != n) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String s = arr.optString(i, "-").trim();
            out.add(s.isEmpty() ? "-" : s);
        }
        return out;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (torchOn) setTorch(false);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        net.shutdown();
        if (controller != null) controller.clearImageAnalysisAnalyzer();
        analysis.shutdown();
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }
}
