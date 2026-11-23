package dev.harbor.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small drawn weather glyphs (original artwork) for WMO weather codes. */
class WeatherIcon extends View {

    private int code = -1;
    private boolean day = true;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    WeatherIcon(Context c) { super(c); }

    void set(int wmoCode, boolean isDay) {
        code = wmoCode;
        day = isDay;
        invalidate();
    }

    static String describe(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mostly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Cloudy";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c == 85 || c == 86) return "Snow showers";
        if (c >= 95) return "Thunderstorm";
        return "";
    }

    @Override
    protected void onDraw(Canvas c) {
        if (code < 0) return;
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        boolean clear = code <= 1, partly = code == 2, cloudy = code == 3 || code == 45 || code == 48;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        boolean storm = code >= 95;

        if (clear) {
            sunOrMoon(c, cx, cy, s * 0.26f);
            return;
        }
        if (partly) sunOrMoon(c, cx - s * 0.14f, cy - s * 0.14f, s * 0.2f);
        float cloudY = (partly || cloudy) ? cy + s * 0.06f : cy - s * 0.06f;
        cloud(c, cx + (partly ? s * 0.06f : 0), cloudY, s, (rain || snow || storm) ? 0xFFD5DAE3 : 0xFFF2F4F8);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        if (rain) {
            p.setColor(0xFF7FC4FF);
            p.setStrokeWidth(s * 0.06f);
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * s * 0.17f;
                c.drawLine(x, cy + s * 0.2f, x - s * 0.05f, cy + s * 0.36f, p);
            }
        } else if (snow) {
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.FILL);
            for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.17f, cy + s * 0.3f, s * 0.045f, p);
        } else if (storm) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFD34D);
            Path bolt = new Path();
            bolt.moveTo(cx + s * 0.02f, cy + s * 0.12f);
            bolt.lineTo(cx - s * 0.1f, cy + s * 0.3f);
            bolt.lineTo(cx, cy + s * 0.3f);
            bolt.lineTo(cx - s * 0.06f, cy + s * 0.46f);
            bolt.lineTo(cx + s * 0.12f, cy + s * 0.24f);
            bolt.lineTo(cx + s * 0.02f, cy + s * 0.24f);
            bolt.close();
            c.drawPath(bolt, p);
        } else if (code == 45 || code == 48) {
            p.setColor(0xCCFFFFFF);
            p.setStrokeWidth(s * 0.05f);
            c.drawLine(cx - s * 0.3f, cy + s * 0.3f, cx + s * 0.3f, cy + s * 0.3f, p);
            c.drawLine(cx - s * 0.22f, cy + s * 0.4f, cx + s * 0.22f, cy + s * 0.4f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void sunOrMoon(Canvas c, float x, float y, float r) {
        p.setStyle(Paint.Style.FILL);
        if (day) {
            p.setColor(0xFFFFC94A);
            c.drawCircle(x, y, r * 0.62f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.16f);
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                c.drawLine(x + (float) Math.cos(a) * r * 0.85f, y + (float) Math.sin(a) * r * 0.85f,
                        x + (float) Math.cos(a) * r * 1.15f, y + (float) Math.sin(a) * r * 1.15f, p);
            }
            p.setStyle(Paint.Style.FILL);
        } else {
            p.setColor(0xFFE9ECF5);
            Path moon = new Path();
            moon.addCircle(x, y, r * 0.8f, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(x + r * 0.42f, y - r * 0.28f, r * 0.66f, Path.Direction.CW);
            moon.op(bite, Path.Op.DIFFERENCE);
            c.drawPath(moon, p);
        }
    }

    private void cloud(Canvas c, float x, float y, float s, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawCircle(x - s * 0.15f, y, s * 0.15f, p);
        c.drawCircle(x + s * 0.02f, y - s * 0.08f, s * 0.2f, p);
        c.drawCircle(x + s * 0.2f, y + s * 0.02f, s * 0.13f, p);
        c.drawRoundRect(new RectF(x - s * 0.3f, y, x + s * 0.33f, y + s * 0.15f), s * 0.07f, s * 0.07f, p);
    }
}
