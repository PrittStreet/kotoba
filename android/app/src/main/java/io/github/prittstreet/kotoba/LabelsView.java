package io.github.prittstreet.kotoba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The camera live mode's labels: one per line of Japanese text, drawn over it in French and moving
 * with it. The lines come from the text recognition a few times a second; the labels glide from one
 * position to the next so they follow the text smoothly. Main thread only.
 */
final class LabelsView extends View {
    /** A label not seen again for this long is gone (the text left the picture). */
    private static final long GONE_MS = 700;
    /** Gliding: the time to cover about two thirds of the way to the new position. */
    private static final float GLIDE_MS = 70f;

    /** A line read in one picture: its text and its four corners on the screen. */
    static final class Seen {
        final String key, ja;
        final float cx, cy, w, h, angle;

        Seen(String key, String ja, Point[] p) {
            this.key = key;
            this.ja = ja;
            cx = (p[0].x + p[1].x + p[2].x + p[3].x) / 4f;
            cy = (p[0].y + p[1].y + p[2].y + p[3].y) / 4f;
            w = (float) Math.hypot(p[1].x - p[0].x, p[1].y - p[0].y);
            h = (float) Math.hypot(p[3].x - p[0].x, p[3].y - p[0].y);
            float a = (float) Math.toDegrees(Math.atan2(p[1].y - p[0].y, p[1].x - p[0].x));
            // upside down or nearly: drawn the right way up, the label still covers the text
            while (a > 90) a -= 180;
            while (a <= -90) a += 180;
            angle = a;
        }
    }

    static final class Label {
        /** The text first read: its translation is the one shown. */
        final String key;
        String ja;
        float cx, cy, w, h, angle;
        private float tx, ty, tw, th, ta;
        long seenAt;
        int frames;
        private StaticLayout layout;
        private String laidText;
        private int laidSize, laidWidth;

        Label(Seen s) {
            key = s.key;
            move(s);
            cx = tx;
            cy = ty;
            w = tw;
            h = th;
            angle = ta;
        }

        void move(Seen s) {
            ja = s.ja;
            tx = s.cx;
            ty = s.cy;
            tw = s.w;
            th = s.h;
            ta = s.angle;
        }
    }

    /** The French for a text: null while it is translated, "-" for noise. */
    interface Translations {
        String french(String key);
    }

    private final List<Label> labels = new ArrayList<>();
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp, minSize, maxSize, pad, radius;
    private Translations translations;
    private boolean frozen;
    private long drawnAt;

    LabelsView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        minSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics());
        maxSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26, getResources().getDisplayMetrics());
        pad = 4 * dp;
        radius = 5 * dp;
        bg.setColor(Color.argb(240, 0xFF, 0xFF, 0xFF));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.5f * dp);
        outline.setColor(Color.argb(200, 0xFF, 0xFF, 0xFF));
        text.setColor(0xFF151A2C);
    }

    void setTranslations(Translations t) {
        translations = t;
    }

    /** Frozen picture: the labels stay where they are. */
    void setFrozen(boolean f) {
        frozen = f;
        long now = SystemClock.uptimeMillis();
        for (Label l : labels) l.seenAt = now;
        invalidate();
    }

    List<Label> labels() {
        return labels;
    }

    /** The lines of a new picture: each one moves its label, or makes a new one. */
    void update(List<Seen> seen) {
        long now = SystemClock.uptimeMillis();
        boolean[] used = new boolean[labels.size()];
        List<Seen> rest = new ArrayList<>();
        // the same text again first, then a slightly different reading of a line nearby
        for (Seen s : seen) {
            int i = find(s, used, true);
            if (i < 0) rest.add(s);
            else take(i, s, used, now);
        }
        for (Seen s : rest) {
            int i = find(s, used, false);
            if (i >= 0) take(i, s, used, now);
            else {
                Label l = new Label(s);
                l.seenAt = now;
                l.frames = 1;
                labels.add(l);
            }
        }
        if (!frozen) labels.removeIf(l -> now - l.seenAt > GONE_MS);
        invalidate();
    }

    private int find(Seen s, boolean[] used, boolean same) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < used.length; i++) {
            if (used[i]) continue;
            Label l = labels.get(i);
            float d = (float) Math.hypot(l.tx - s.cx, l.ty - s.cy);
            if (same ? !l.key.equals(s.key) : d > Math.max(l.th, s.h) * 1.2f || similarity(l.key, s.key) < 0.5f) continue;
            if (d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    private void take(int i, Seen s, boolean[] used, long now) {
        Label l = labels.get(i);
        used[i] = true;
        l.move(s);
        l.seenAt = now;
        l.frames++;
    }

    /** 0 to 1: how much two readings of a line look alike (edit distance). */
    static float similarity(String a, String b) {
        if (a.equals(b)) return 1;
        int n = a.length(), m = b.length();
        if (n == 0 || m == 0) return 0;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1f - (float) prev[m] / Math.max(n, m);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = drawnAt == 0 ? 16 : Math.min(100, now - drawnAt);
        drawnAt = now;
        float k = 1f - (float) Math.exp(-dt / GLIDE_MS);
        boolean moving = false;
        for (Label l : labels) {
            moving |= glide(l, k);
            draw(canvas, l);
        }
        if (moving) postInvalidateOnAnimation();
        else drawnAt = 0;
    }

    private static boolean glide(Label l, float k) {
        if (Math.abs(l.ta - l.angle) > 45) l.angle = l.ta;
        l.cx += (l.tx - l.cx) * k;
        l.cy += (l.ty - l.cy) * k;
        l.w += (l.tw - l.w) * k;
        l.h += (l.th - l.h) * k;
        l.angle += (l.ta - l.angle) * k;
        return Math.abs(l.tx - l.cx) > 0.5f || Math.abs(l.ty - l.cy) > 0.5f || Math.abs(l.tw - l.w) > 0.5f
                || Math.abs(l.th - l.h) > 0.5f || Math.abs(l.ta - l.angle) > 0.2f;
    }

    private void draw(Canvas canvas, Label l) {
        String fr = translations == null ? null : translations.french(l.key);
        if ("-".equals(fr) || (fr != null && fr.isEmpty())) return; // noise: the text stays visible
        canvas.save();
        canvas.translate(l.cx, l.cy);
        if (fr == null) {
            // still being translated: the line is outlined
            canvas.rotate(l.angle);
            rect.set(-l.w / 2 - pad, -l.h / 2 - pad, l.w / 2 + pad, l.h / 2 + pad);
            canvas.drawRoundRect(rect, radius, radius, outline);
            canvas.restore();
            return;
        }
        // vertical Japanese (a tall box, or a line running down the screen): the label stays
        // horizontal, its letters the size of the column's
        boolean sideways = Math.abs(l.angle) > 45;
        boolean vertical = sideways || l.h > l.w * 1.5f;
        float along = sideways || !vertical ? l.w : l.h, across = sideways || !vertical ? l.h : l.w;
        if (!vertical) canvas.rotate(l.angle);
        StaticLayout layout = layout(l, fr, across, vertical ? Math.max(along, across * 6) : along);
        float lw = Math.max(layout.getWidth() + 2 * pad, vertical ? 0 : l.w + 2 * pad);
        float lh = Math.max(layout.getHeight() + 2 * pad, vertical ? 0 : l.h + pad);
        rect.set(-lw / 2, -lh / 2, lw / 2, lh / 2);
        canvas.drawRoundRect(rect, radius, radius, bg);
        canvas.translate(-layout.getWidth() / 2f, -layout.getHeight() / 2f);
        layout.draw(canvas);
        canvas.restore();
    }

    /** The French fitted to the line: the size of its letters, shrunk to its length, wrapped if still too long. */
    private StaticLayout layout(Label l, String fr, float across, float along) {
        float size = Math.max(minSize, Math.min(maxSize, across * 0.75f));
        text.setTextSize(size);
        float width = text.measureText(fr);
        if (width > along) size = Math.max(minSize, size * along / width);
        int s = Math.round(size);
        text.setTextSize(s);
        width = text.measureText(fr);
        int lw = Math.max(1, (int) Math.ceil(Math.min(width, Math.max(along, getWidth() * 0.8f))));
        if (l.layout == null || s != l.laidSize || lw != l.laidWidth || !fr.equals(l.laidText)) {
            l.layout = StaticLayout.Builder.obtain(fr, 0, fr.length(), text, lw)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setMaxLines(4)
                    .build();
            l.laidText = fr;
            l.laidSize = s;
            l.laidWidth = lw;
        }
        return l.layout;
    }
}
