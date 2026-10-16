package io.github.prittstreet.kotoba;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live mode: listens to the Japanese spoken around (someone talking to Ronan, station and train
 * announcements) with the phone's microphone and translates it phrase by phrase into French, said in
 * the earphones and shown on the Live screen. Kept apart from the conversation.
 *
 * A foreground service ("microphone" type) so it goes on with the phone in the pocket, screen off.
 * The recognizer ends a session after each phrase: the next one starts at once. The earphones'
 * own microphone is not used: it works only in call mode and filters out the voices around.
 */
public class LiveService extends Service implements RecognitionListener, TextToSpeech.OnInitListener {
    static final String ACTION_STOP = "io.github.prittstreet.kotoba.LIVE_STOP";
    private static final String CHANNEL = "live";
    private static final int NOTIFICATION = 7;
    private static final String MODEL = "claude-haiku-5-5";
    private static final int MAX_PHRASES = 100;
    /** A session that fails faster than this is not listening: wait a little before the next. */
    private static final long SHORT_SESSION_MS = 700;

    /** The screen showing the session (MainActivity), while it exists. Main thread. */
    interface Listener {
        void onLive(JSONObject ev);
    }

    static Listener listener;
    private static volatile LiveService running;
    private static String key = "", workspace = "";
    private static boolean male;
    /** The phrases of the last session: still shown after it stops. Guarded by itself. */
    private static final List<JSONObject> phrases = new ArrayList<>();
    /** Tokens used, not yet added to the page's cost estimate: [input, output] per phrase. */
    private static final List<int[]> usage = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable restart = this::listen;
    private final ExecutorService net = Executors.newSingleThreadExecutor(); // one at a time, in order
    private SpeechRecognizer rec;
    private Intent intent;
    private TextToSpeech tts;
    private boolean ttsReady;
    private PowerManager.WakeLock wake;
    private boolean active;
    private String partial = "";
    private long sessionAt;
    private int failures;
    private int seq;

    // ---------- called by MainActivity / Bridge ----------

    static void start(Context c, String k, String ws, boolean m) {
        key = k == null ? "" : k;
        workspace = ws == null ? "" : ws;
        male = m;
        ContextCompat.startForegroundService(c, new Intent(c, LiveService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, LiveService.class));
    }

    /** {running, headphones, partial, phrases: [{id, ja, fr, status: pending|done|skip|error}]} */
    static String state() {
        JSONObject o = new JSONObject();
        LiveService s = running;
        try {
            o.put("running", s != null);
            o.put("headphones", s != null && s.headphones());
            o.put("partial", s == null ? "" : s.partial);
            synchronized (phrases) {
                o.put("phrases", new JSONArray(phrases.toString()));
            }
        } catch (JSONException e) {
            return "{\"running\":false,\"phrases\":[]}";
        }
        return o.toString();
    }

    static String takeUsage() {
        JSONArray a = new JSONArray();
        synchronized (usage) {
            for (int[] u : usage) a.put(new JSONArray().put(u[0]).put(u[1]));
            usage.clear();
        }
        return a.toString();
    }

    // ---------- service ----------

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && ACTION_STOP.equals(i.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ServiceCompat.startForeground(this, NOTIFICATION, notification(null),
                Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
        if (!active) begin();
        return START_NOT_STICKY;
    }

    private void begin() {
        active = true;
        running = this;
        synchronized (phrases) {
            phrases.clear();
        }
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kotoba:live");
        wake.acquire(4 * 60 * 60 * 1000L); // never more than 4 h, even if forgotten
        tts = new TextToSpeech(this, this);
        intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emitState("error", "Pas de reconnaissance vocale sur ce téléphone.");
            stopSelf();
            return;
        }
        create();
        listen();
        emitState("listening", null);
    }

    @Override
    public void onDestroy() {
        active = false;
        running = null;
        main.removeCallbacksAndMessages(null);
        if (rec != null) {
            rec.cancel();
            rec.destroy();
            rec = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (wake != null && wake.isHeld()) wake.release();
        net.shutdownNow();
        emitState("stopped", null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private Notification notification(String last) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Kotoba Live", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, LiveService.class)
                .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Kotoba Live : j’écoute le japonais")
                .setContentText(last != null ? last : "Les traductions arrivent dans tes écouteurs.")
                .setContentIntent(open)
                .addAction(0, "Arrêter", stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    // ---------- listening ----------

    private void create() {
        if (rec != null) rec.destroy();
        rec = SpeechRecognizer.createSpeechRecognizer(this);
        rec.setRecognitionListener(this);
    }

    private void listen() {
        if (!active) return;
        if (rec == null) create();
        partial = "";
        sessionAt = SystemClock.elapsedRealtime();
        try {
            rec.startListening(intent);
        } catch (RuntimeException e) {
            again(1000);
        }
    }

    private void again(long delay) {
        main.removeCallbacks(restart);
        main.postDelayed(restart, delay);
    }

    @Override
    public void onResults(Bundle results) {
        if (!active) return;
        String text = best(results);
        heard(text.isEmpty() ? partial : text);
        failures = 0;
        again(0);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String text = best(partialResults);
        if (!active || text.isEmpty() || text.equals(partial)) return;
        partial = text;
        emit("partial", "text", text);
    }

    @Override
    public void onError(int error) {
        if (!active) return;
        boolean quick = SystemClock.elapsedRealtime() - sessionAt < SHORT_SESSION_MS;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                // silence or noise: keep what was heard, listen again
                if (!partial.isEmpty()) heard(partial);
                again(quick ? 300 : 0);
                return;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                emitState("error", "Kotoba n’a pas le droit d’utiliser le micro.");
                stopSelf();
                return;
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                emitState("error", "Le japonais n’est pas disponible pour la reconnaissance vocale de ce téléphone.");
                stopSelf();
                return;
            case SpeechRecognizer.ERROR_CLIENT:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                create(); // this recognizer is stuck: a fresh one
                again(300);
                return;
            default:
                // network, server, audio: try again, slower and slower
                failures++;
                if (failures == 3) emitState("error", "La reconnaissance vocale ne répond pas (réseau ?). Je réessaie…");
                again(Math.min(5000, 500L * failures));
        }
    }

    @Override public void onReadyForSpeech(Bundle params) {}
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

    // ---------- translating ----------

    /** A phrase heard: shown at once, translated after the ones before it. */
    private void heard(String ja) {
        partial = "";
        if (ja == null || ja.trim().length() < 2) return;
        JSONObject p = new JSONObject();
        String context;
        try {
            p.put("id", ++seq).put("ja", ja.trim()).put("fr", "").put("status", "pending");
        } catch (JSONException e) {
            return;
        }
        synchronized (phrases) {
            context = contextOf();
            phrases.add(p);
            while (phrases.size() > MAX_PHRASES) phrases.remove(0);
        }
        emitPhrase(p);
        net.execute(() -> {
            String fr, err = "";
            try {
                fr = ask(p.optString("ja"), context);
            } catch (IOException | JSONException e) {
                fr = "";
                err = e instanceof IOException && e.getMessage() != null && !e.getMessage().isEmpty()
                        ? e.getMessage() : "Pas de réseau : traduction impossible.";
            }
            String text = fr, problem = err;
            main.post(() -> done(p, text, problem));
        });
    }

    private void done(JSONObject p, String fr, String err) {
        boolean noise = err.isEmpty() && (fr.isEmpty() || fr.equals("-"));
        synchronized (phrases) {
            try {
                p.put("fr", noise ? "" : fr).put("status", !err.isEmpty() ? "error" : noise ? "skip" : "done");
                if (!err.isEmpty()) p.put("err", err);
            } catch (JSONException ignored) {
                // the strings are valid
            }
        }
        emitPhrase(p);
        if (!active || noise || !err.isEmpty()) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(fr));
        // out loud only in the earphones: from the loudspeaker the microphone would hear it
        if (ttsReady && headphones()) tts.speak(fr, TextToSpeech.QUEUE_ADD, null, "live" + p.optInt("id"));
    }

    /** The last phrases translated, so that a phrase cut in two still makes sense. Under the lock. */
    private static String contextOf() {
        StringBuilder b = new StringBuilder();
        int from = Math.max(0, phrases.size() - 3);
        for (int i = from; i < phrases.size(); i++) {
            JSONObject q = phrases.get(i);
            if ("done".equals(q.optString("status"))) b.append("- ").append(q.optString("ja")).append("  →  ").append(q.optString("fr")).append('\n');
        }
        return b.toString();
    }

    private static String prompt(String ja, String context) {
        return "You are the interpreter of a French speaker living in Japan. The phone's microphone just heard the Japanese below around them, "
                + "transcribed by speech recognition: someone talking to them, an announcement in a station, train or shop, a guide…\n"
                + "Translate it into short, natural French that they will hear in their earphones.\n\n"
                + "Rules:\n"
                + "- Fix obvious recognition mistakes silently.\n"
                + "- Keep station and place names in rōmaji (Shinjuku, Tōkyō), and numbers, times, platforms and prices.\n"
                + "- If it is only noise, a filler or makes no sense, answer exactly: -\n"
                + "- Answer with the French only: no quotes, no notes, no Markdown.\n"
                + (context.isEmpty() ? "" : "\nHeard just before (context only, do not translate):\n" + context)
                + "\nHeard now:\n\"\"\"\n" + ja + "\n\"\"\"";
    }

    /** One call to Claude; the French, or "-" for noise. Network thread. */
    private String ask(String ja, String context) throws IOException, JSONException {
        if (key.isEmpty()) throw new IOException("Ajoute ta clé API dans les réglages.");
        JSONObject body = new JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 400)
                .put("output_config", new JSONObject().put("effort", "low"))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt(ja, context))));
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
            if (!workspace.isEmpty()) c.setRequestProperty("anthropic-workspace-id", workspace);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String res = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new IOException(problem(code, res));
            JSONObject j = new JSONObject(res);
            StringBuilder text = new StringBuilder();
            JSONArray content = j.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject b = content.optJSONObject(i);
                if (b != null && "text".equals(b.optString("type"))) text.append(b.optString("text"));
            }
            JSONObject u = j.optJSONObject("usage");
            if (u != null) {
                synchronized (usage) {
                    usage.add(new int[]{u.optInt("input_tokens"), u.optInt("output_tokens")});
                }
            }
            return text.toString().trim();
        } finally {
            c.disconnect();
        }
    }

    private static String problem(int code, String res) {
        String msg = "";
        try {
            msg = new JSONObject(res).optJSONObject("error").optString("message");
        } catch (JSONException | NullPointerException ignored) {
            // no JSON: the status says enough
        }
        if (code == 401) return "Clé API refusée : vérifie-la dans les réglages.";
        if (msg.toLowerCase(Locale.ROOT).contains("credit balance")) return "Plus de crédit sur le compte Claude.";
        if (msg.toLowerCase(Locale.ROOT).contains("usage limit")) return "Limite de dépenses de la clé atteinte.";
        if (code == 429) return "Trop de demandes d’un coup.";
        if (code == 529 || code >= 500) return "Claude est surchargé.";
        return "Erreur " + code + (msg.isEmpty() ? "" : " : " + msg);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    // ---------- voice ----------

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) return;
        tts.setLanguage(Locale.FRANCE);
        tts.setSpeechRate(1.05f);
        android.speech.tts.Voice chosen = null;
        try {
            for (android.speech.tts.Voice v : tts.getVoices()) {
                if (v.getLocale() == null || !"fr".equals(v.getLocale().getLanguage())) continue;
                if ((male ? "male" : "female").equals(Voice.gender(v))) {
                    chosen = v;
                    if ("FR".equals(v.getLocale().getCountry())) break;
                }
            }
        } catch (RuntimeException ignored) {
            // some engines throw instead of listing their voices
        }
        if (chosen != null) tts.setVoice(chosen);
        else if (male) tts.setPitch(0.78f); // no man's voice installed: the default one, lower
        ttsReady = true;
    }

    @SuppressLint("InlinedApi") // TYPE_BLE_HEADSET is only compared, never required
    private boolean headphones() {
        AudioManager am = getSystemService(AudioManager.class);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_BLE_HEADSET) return true;
        }
        return false;
    }

    // ---------- to the Live screen ----------

    private void emit(String type, String k, String v) {
        try {
            emit(new JSONObject().put("type", type).put(k, v));
        } catch (JSONException ignored) {
            // valid strings
        }
    }

    private void emitPhrase(JSONObject p) {
        try {
            JSONObject copy;
            synchronized (phrases) {
                copy = new JSONObject(p.toString());
            }
            emit(new JSONObject().put("type", "phrase").put("phrase", copy));
        } catch (JSONException ignored) {
            // valid JSON
        }
    }

    private void emitState(String state, String text) {
        try {
            emit(new JSONObject().put("type", "state").put("state", state).put("text", text == null ? "" : text)
                    .put("headphones", active && headphones()));
        } catch (JSONException ignored) {
            // valid strings
        }
    }

    private void emit(JSONObject ev) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onLive(ev);
        });
    }
}
