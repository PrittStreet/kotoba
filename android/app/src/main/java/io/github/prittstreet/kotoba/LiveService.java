package io.github.prittstreet.kotoba;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
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
    /** What happened, for the Live screen's journal (to send when something goes wrong). */
    private static final List<String> journal = new ArrayList<>();

    // Boosted microphone (Android 13+): Kotoba records the phone's own microphone (never the
    // earphones'), raises quiet and distant voices, and hands that sound to the recognizer.
    private static final int RATE = 16000;

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
    private AudioRecord mic;
    private Thread micThread;
    private volatile boolean boosted;              // the recognizer gets our boosted sound
    private volatile ParcelFileDescriptor pipeOut; // where the current session's sound goes
    private ParcelFileDescriptor pipeIn;           // the recognizer's end of it
    private volatile int unread;                   // sound the recognizer did not take (20 ms pieces)
    private int quickFails;
    private long levelAt;
    // Phrases are cut by Kotoba: with its own sound the recognizer may never decide a phrase is over.
    // Phrase by phrase, translated naturally (Ronan tried rough word-for-word pieces and preferred this).
    private static final long PAUSE_MS = 1200;    // no new words for this long: the phrase is over
    private static final long LONGEST_MS = 12000; // a very long announcement is cut every 12 s
    private static final long SILENT_MS = 30000;  // a session that heard nothing at all starts again
    private final Runnable cutIfQuiet = this::cutIfQuiet;
    private final Runnable gaveUp = this::gaveUp;
    private final Runnable silentTooLong = this::silentTooLong;
    private boolean ending;   // the phrase was cut: waiting for the recognizer's last words
    private long phraseAt;    // when the current phrase's first words came
    private long partialAt;   // when its last new words came
    /** Sound recorded between two sessions, given to the next one (pump thread only). */
    private final ArrayDeque<byte[]> backlog = new ArrayDeque<>();

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
            synchronized (journal) {
                o.put("journal", new JSONArray(journal));
            }
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

    /** Tokens spent by the Android side (Live, camera), for the page's cost estimate. */
    static void addUsage(int in, int out) {
        synchronized (usage) {
            usage.add(new int[]{in, out});
        }
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
        synchronized (journal) {
            journal.clear();
        }
        boosted = startMic();
        note(boosted ? "micro du téléphone amplifié" : "micro standard de la reconnaissance vocale");
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
        stopMic();
        closePipe();
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

    @SuppressLint("InlinedApi") // the audio-source extras are only used when boosted, that is on Android 13+
    private void listen() {
        if (!active) return;
        if (rec == null) create();
        partial = "";
        ending = false;
        phraseAt = 0;
        main.removeCallbacks(cutIfQuiet);
        main.removeCallbacks(gaveUp);
        main.removeCallbacks(silentTooLong);
        main.postDelayed(silentTooLong, SILENT_MS);
        sessionAt = SystemClock.elapsedRealtime();
        try {
            Intent i = intent;
            if (boosted) {
                closePipe();
                ParcelFileDescriptor[] p = ParcelFileDescriptor.createPipe();
                pipeIn = p[0];
                pipeOut = p[1];
                unread = 0;
                i = new Intent(intent)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipeIn)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
            }
            rec.startListening(i);
        } catch (IOException e) {
            fallBack("pas de canal pour le son amplifié");
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
        main.removeCallbacks(gaveUp);
        main.removeCallbacks(cutIfQuiet);
        String text = best(results);
        heard(text.isEmpty() ? partial : text);
        failures = 0;
        again(0);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String text = best(partialResults);
        if (!active || ending || text.isEmpty() || text.equals(partial)) return;
        partial = text;
        long now = SystemClock.elapsedRealtime();
        partialAt = now;
        if (phraseAt == 0) phraseAt = now;
        emit("partial", "text", text);
        main.removeCallbacks(cutIfQuiet);
        if (now - phraseAt >= LONGEST_MS) cut();
        else main.postDelayed(cutIfQuiet, PAUSE_MS);
    }

    private void cutIfQuiet() {
        if (active && !ending && !partial.isEmpty() && SystemClock.elapsedRealtime() - partialAt >= PAUSE_MS) cut();
    }

    /** The phrase is over: the recognizer gives its last words, a new session starts. */
    private void cut() {
        ending = true;
        main.removeCallbacks(cutIfQuiet);
        ParcelFileDescriptor o = pipeOut;
        pipeOut = null; // end of this phrase's sound; the pump keeps what follows for the next one
        try {
            if (o != null) o.close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            if (rec != null) rec.stopListening();
        } catch (RuntimeException ignored) {
            // the next session starts anyway
        }
        main.postDelayed(gaveUp, 2000);
    }

    /** The recognizer did not finish the phrase: keep what it had heard and go on. */
    private void gaveUp() {
        if (!active || !ending) return;
        heard(partial);
        if (rec != null) rec.cancel();
        again(0);
    }

    /** Half a minute without a word: a fresh session (some recognizers stall on their own sound). */
    private void silentTooLong() {
        if (!active || ending || !partial.isEmpty()) return;
        if (rec != null) rec.cancel();
        again(0);
    }

    @Override
    public void onError(int error) {
        if (!active) return;
        boolean quick = SystemClock.elapsedRealtime() - sessionAt < SHORT_SESSION_MS;
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) note("erreur " + error);
        main.removeCallbacks(cutIfQuiet);
        if (ending) { // the phrase was cut: what was heard is enough
            main.removeCallbacks(gaveUp);
            heard(partial);
            again(0);
            return;
        }
        if (boosted && quick && error != SpeechRecognizer.ERROR_NO_MATCH && ++quickFails >= 3) {
            fallBack("la reconnaissance refuse le son amplifié (erreur " + error + ")");
            return;
        }
        if (!quick) quickFails = 0;
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
    @Override
    public void onRmsChanged(float rmsdB) {
        if (!boosted) level((rmsdB + 2f) / 12f); // the recognizer's own scale, about -2 to 10 dB
    }
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onEvent(int eventType, Bundle params) {}

    private static String best(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r == null || r.isEmpty() || r.get(0) == null) return "";
        return r.get(0).trim();
    }

    // ---------- boosted microphone ----------

    private boolean startMic() {
        if (Build.VERSION.SDK_INT < 33) return false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
        // The raw microphone when the phone has it: its "voice recognition" sound is noise-reduced on
        // some phones, and a voice far away (an actor on stage) sounds like noise to that filter.
        // Agc does the loudness and removes the rumble.
        boolean raw = "true".equals(getSystemService(AudioManager.class).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        mic = open(raw ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION);
        if (mic == null && raw) mic = open(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        else if (raw) note("micro brut, sans filtre");
        if (mic == null) {
            note("micro amplifié indisponible");
            return false;
        }
        // the phone's microphone, even with earphones connected (theirs filters out the voices around)
        AudioManager am = getSystemService(AudioManager.class);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                mic.setPreferredDevice(d);
                break;
            }
        }
        try {
            mic.startRecording();
        } catch (IllegalStateException e) {
            note("micro amplifié refusé");
            mic.release();
            mic = null;
            return false;
        }
        AudioDeviceInfo routed = mic.getRoutedDevice();
        if (routed != null) note("entrée : " + (routed.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "micro du téléphone" : "appareil " + routed.getType()));
        micThread = new Thread(this::pump, "kotoba-live-mic");
        micThread.start();
        return true;
    }

    private AudioRecord open(int source) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            AudioRecord r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2));
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (IllegalArgumentException | SecurityException e) {
            note("micro amplifié impossible : " + e.getMessage());
        }
        return null;
    }

    /** Microphone thread: raise the voices to a steady loudness (Agc) and pass them to the current session. */
    private void pump() {
        short[] in = new short[RATE / 50]; // 20 ms
        short[] raised = new short[in.length];
        byte[] out = new byte[in.length * 2];
        StructPollfd[] poll = {new StructPollfd()};
        Agc agc = new Agc();
        long noted = 0;
        AudioRecord m = mic;
        while (m != null && boosted) {
            int n = m.read(in, 0, in.length);
            if (n <= 0) {
                if (n < 0) break;
                continue;
            }
            float gain = agc.process(in, n, raised);
            for (int i = 0; i < n; i++) {
                short x = raised[i];
                out[2 * i] = (byte) (x & 0xff);
                out[2 * i + 1] = (byte) ((x >> 8) & 0xff);
            }
            level(Math.min(1f, agc.rms() * gain / 12000f));
            // in the journal every 10 s: how loud the room is and how much it is raised, for the tuning
            long now = SystemClock.elapsedRealtime();
            if (now - noted > 10000) {
                noted = now;
                note(String.format(Locale.FRANCE, "son : fond %.0f, gain ×%.0f", agc.noise(), gain));
            }
            ParcelFileDescriptor o = pipeOut;
            if (o == null) {
                // between two phrases: kept (1 s at most) for the next session, so no word is lost
                backlog.add(Arrays.copyOf(out, n * 2));
                while (backlog.size() > 50) backlog.poll();
                continue;
            }
            try {
                FileDescriptor fd = o.getFileDescriptor();
                poll[0].fd = fd;
                poll[0].events = (short) OsConstants.POLLOUT;
                poll[0].revents = 0;
                if (Os.poll(poll, 0) > 0 && (poll[0].revents & OsConstants.POLLOUT) != 0) {
                    for (byte[] b; (b = backlog.poll()) != null; ) Os.write(fd, b, 0, b.length);
                    Os.write(fd, out, 0, n * 2);
                    unread = 0;
                } else if (++unread == 75 && SystemClock.elapsedRealtime() - sessionAt > 1500) {
                    // 1.5 s the recognizer has not read: it listens to its own microphone instead
                    main.post(() -> fallBack("la reconnaissance n’utilise pas le son amplifié"));
                }
            } catch (ErrnoException | IOException e) {
                // the session just ended and its pipe was closed: the next one takes over
            }
        }
    }

    private void fallBack(String why) {
        if (!boosted) return;
        note("retour au micro standard : " + why);
        boosted = false;
        stopMic();
        closePipe();
        if (rec != null) rec.cancel();
        again(200);
    }

    private void stopMic() {
        boosted = false;
        AudioRecord m = mic;
        mic = null;
        if (m != null) {
            try {
                m.stop();
            } catch (IllegalStateException ignored) {
                // not recording
            }
            m.release();
        }
        micThread = null;
    }

    private void closePipe() {
        ParcelFileDescriptor o = pipeOut, i = pipeIn;
        pipeOut = null;
        pipeIn = null;
        try {
            if (o != null) o.close();
            if (i != null) i.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    /** 0..1 for the Live screen's level bar, a few times a second. */
    private void level(float v) {
        long now = SystemClock.elapsedRealtime();
        if (now - levelAt < 250) return;
        levelAt = now;
        try {
            emit(new JSONObject().put("type", "level").put("level", Math.max(0f, Math.min(1f, v))));
        } catch (JSONException ignored) {
            // a number
        }
    }

    private static void note(String line) {
        synchronized (journal) {
            journal.add(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + line);
            while (journal.size() > 80) journal.remove(0);
        }
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
        note("entendu : " + p.optString("ja"));
        net.execute(() -> {
            String fr, err = "";
            try {
                fr = Claude.ask(key, workspace, prompt(p.optString("ja"), context), 400);
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
        if (!err.isEmpty()) note("traduction impossible : " + err);
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
                + "- A very long announcement may be cut in the middle: translate what is there, do not invent the rest.\n"
                + "- Fix obvious recognition mistakes silently.\n"
                + "- Keep station and place names in rōmaji (Shinjuku, Tōkyō), and numbers, times, platforms and prices.\n"
                + "- If it is only noise, a filler or makes no sense, answer exactly: -\n"
                + "- Answer with the French only: no quotes, no notes, no Markdown.\n"
                + (context.isEmpty() ? "" : "\nHeard just before (context only, do not translate):\n" + context)
                + "\nHeard now:\n\"\"\"\n" + ja + "\n\"\"\"";
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
