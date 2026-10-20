package io.github.prittstreet.kotoba;

/**
 * Live's automatic volume, for voices far away in a quiet room (an actor on stage, a guide): the
 * microphone hears them very faintly and the speech recognizer takes them for silence.
 * - a high-pass filter (150 Hz, 12 dB per octave) removes the rumble (air conditioning, mains hum,
 *   traffic) that is not voice;
 * - the room's background noise and the voices' loudness are followed apart;
 * - the gain brings the voices to a normal loudness, up to MAX_GAIN, but never raises the
 *   background noise above NOISE_CEILING (a quiet hiss);
 * - it drops at once for a loud sound near the phone (a cough) and comes back within a second
 *   (the voices' loudness is followed in decibels);
 * - a soft limiter keeps peaks from clipping.
 * Pure Java (tested on a computer). One instance per microphone thread.
 */
final class Agc {
    static final float TARGET = 6000f;         // voices' loudness after gain (RMS, of 32767)
    static final float MAX_GAIN = 120f;        // 42 dB: a far voice in a quiet room
    static final float NOISE_CEILING = 900f;   // the background noise after gain, at most
    // second-order Butterworth high-pass, 150 Hz at 16 kHz
    private static final float B0, B1, B2, A1, A2;

    static {
        double w = Math.tan(Math.PI * 150 / 16000), q = Math.sqrt(2), norm = 1 / (1 + q * w + w * w);
        B0 = (float) norm;
        B1 = (float) (-2 * norm);
        B2 = (float) norm;
        A1 = (float) (2 * (w * w - 1) * norm);
        A2 = (float) ((1 - q * w + w * w) * norm);
    }

    private float x1, x2, y1, y2;
    private float noise = 30f;   // background noise RMS (follows the quietest frames)
    private float voice = 0f;    // voices' RMS (frames clearly above the noise); 0 until one is heard
    private float gain = 1f;
    private float lastRms;
    private float[] buf = new float[0];

    /** One frame (any length) of 16-bit samples: filtered, raised, limited into out. Returns the gain used. */
    float process(short[] in, int n, short[] out) {
        // high-pass first: the loudness below is the voices', not the rumble's
        if (buf.length < n) buf = new float[n];
        float[] f = buf;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            float x = in[i];
            float y = B0 * x + B1 * x1 + B2 * x2 - A1 * y1 - A2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            f[i] = y;
            sum += (double) y * y;
        }
        float rms = n > 0 ? (float) Math.sqrt(sum / n) : 0f;
        lastRms = rms;
        // background noise: falls quickly to quieter frames, rises very slowly (a voice is not noise)
        if (rms < noise) noise += (rms - noise) * 0.1f;
        else noise += (rms - noise) * 0.002f;
        noise = Math.max(noise, 2f);
        // a voice: clearly above the background noise
        if (rms > noise * 2.5f && rms > 8f) {
            if (voice == 0f) voice = rms;
            else {
                // in decibels: back from a cough's level to the actor's within about half a second
                double lv = Math.log(voice), lr = Math.log(rms);
                voice = (float) Math.exp(lv + (lr - lv) * (lr > lv ? 0.15 : 0.08));
            }
        }
        float want = voice > 0f ? TARGET / voice : MAX_GAIN;
        want = Math.min(want, NOISE_CEILING / noise);
        want = Math.max(1f, Math.min(MAX_GAIN, want));
        // down at once (no clipping), up gently (no pumping)
        gain += (want - gain) * (want < gain ? 0.3f : 0.05f);
        for (int i = 0; i < n; i++) {
            float v = f[i] * gain;
            if (v > 24000f) v = 24000f + (v - 24000f) * 0.15f; // soft limit instead of clipping
            else if (v < -24000f) v = -24000f + (v + 24000f) * 0.15f;
            out[i] = (short) Math.max(-32767f, Math.min(32767f, v));
        }
        return gain;
    }

    /** RMS of the last frame before gain (after the filter). */
    float rms() {
        return lastRms;
    }

    float noise() {
        return noise;
    }
}
