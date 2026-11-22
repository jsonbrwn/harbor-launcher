package dev.harbor.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Shows one of Harbor's bundled illustrated race-circuit wallpapers (assets/wallpapers), with a
 * crossfade when it changes. A heavily blurred copy is shown behind the Apps grid.
 */
class WallpaperView extends View {

    private static final String DIR = "wallpapers";

    private final List<String> files = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();

    private int index = -1;
    private Bitmap current, previous, heavy;
    private float fade = 1f;          // 0 = previous fully visible, 1 = current fully visible
    private boolean showHeavy;
    private int loadToken;

    WallpaperView(Context c, int startIndex) {
        super(c);
        try {
            String[] names = c.getAssets().list(DIR);
            if (names != null) {
                Arrays.sort(names);
                for (String n : names) if (n.endsWith(".jpg") || n.endsWith(".png")) files.add(n);
            }
        } catch (Exception ignored) { }
        setBackgroundColor(0xFF101018);
        show(startIndex, false);
    }

    int count() { return files.size(); }
    int getIndex() { return index; }

    /** "07-lakeside-dawn.jpg" -> "Lakeside Dawn" */
    String nameOf(int i) {
        String f = files.get(i);
        String base = f.substring(0, f.lastIndexOf('.'));
        int dash = base.indexOf('-');
        if (dash >= 0 && dash < 4) base = base.substring(dash + 1);
        StringBuilder sb = new StringBuilder();
        for (String w : base.split("-")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.substring(0, 1).toUpperCase(Locale.US)).append(w.substring(1));
        }
        return sb.toString();
    }

    void show(int i, final boolean animate) {
        if (files.isEmpty()) return;
        final int target = ((i % files.size()) + files.size()) % files.size();
        if (target == index && current != null) return;
        index = target;
        final int token = ++loadToken;
        final String file = files.get(target);
        final AssetManager am = getContext().getAssets();
        new Thread(new Runnable() {
            public void run() {
                final Bitmap b = decode(am, file);
                final Bitmap h = b == null ? null : heavyBlur(b);
                main.post(new Runnable() {
                    public void run() {
                        if (token != loadToken || b == null) return;
                        swapIn(b, h, animate);
                    }
                });
            }
        }, "harbor-wallpaper").start();
    }

    private void swapIn(Bitmap b, Bitmap h, boolean animate) {
        if (previous != null && previous != current) previous.recycle();
        previous = animate ? current : null;
        if (!animate && current != null) current.recycle();
        current = b;
        if (heavy != null) heavy.recycle();
        heavy = h;
        if (animate && previous != null) {
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(1600);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator a) {
                    fade = (Float) a.getAnimatedValue();
                    if (fade >= 1f && previous != null) {
                        previous.recycle();
                        previous = null;
                    }
                    invalidate();
                }
            });
            fade = 0f;
            va.start();
        } else {
            fade = 1f;
            invalidate();
        }
    }

    void setHeavyBlur(boolean on) {
        showHeavy = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        dst.set(0, 0, getWidth(), getHeight());
        if (showHeavy && heavy != null) {
            canvas.drawBitmap(heavy, null, dst, paint);
            canvas.drawColor(0x59000000);
            return;
        }
        if (previous != null && !previous.isRecycled() && fade < 1f) {
            paint.setAlpha(255);
            canvas.drawBitmap(previous, null, dst, paint);
        }
        if (current != null) {
            paint.setAlpha(Math.round(255 * fade));
            canvas.drawBitmap(current, null, dst, paint);
            paint.setAlpha(255);
        }
    }

    private Bitmap decode(AssetManager am, String file) {
        InputStream in = null;
        try {
            in = am.open(DIR + "/" + file);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    /** A small, strongly blurred copy for behind the Apps grid. */
    private static Bitmap heavyBlur(Bitmap src) {
        int w = 240, h = Math.max(1, Math.round(240f * src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        small.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < 3; p++) {
            boxH(a, b, w, h, 6);
            boxV(b, a, w, h, 6);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        small.recycle();
        return out;
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int px = in[row + clamp(i, w)];
                sr += (px >> 16) & 0xFF; sg += (px >> 8) & 0xFF; sb += px & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int add = in[row + clamp(x + r + 1, w)], rem = in[row + clamp(x - r, w)];
                sr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int px = in[clamp(i, h) * w + x];
                sr += (px >> 16) & 0xFF; sg += (px >> 8) & 0xFF; sb += px & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int add = in[clamp(y + r + 1, h) * w + x], rem = in[clamp(y - r, h) * w + x];
                sr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                sb += (add & 0xFF) - (rem & 0xFF);
            }
        }
    }

    private static int clamp(int v, int n) { return v < 0 ? 0 : (v >= n ? n - 1 : v); }
}
