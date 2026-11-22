package dev.harbor.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.View;

/**
 * A rounded-square app tile. Shows an app's icon, or one of Harbor's own drawn glyphs.
 * Optionally draws a caption underneath (used in the Apps grid) and an indicator dot (Dock).
 */
class TileView extends View {

    static final int GLYPH_NONE = 0, GLYPH_APPS = 1, GLYPH_INPUTS = 2, GLYPH_SETTINGS = 3, GLYPH_LIVETV = 4;

    final AppEntry entry;
    private final Drawable icon;
    private final int glyph;
    private final boolean caption;
    private boolean dot;
    private boolean pinnedMark;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF r = new RectF();

    TileView(Context c, AppEntry entry, boolean caption) {
        super(c);
        this.entry = entry;
        this.icon = entry.icon;
        this.glyph = entry.glyph;
        this.caption = caption;
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(Ui.dp(c, 12));
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tp.setShadowLayer(Ui.dp(c, 2), 0, Ui.dp(c, 1), 0x99000000);
        setContentDescription(entry.label);
    }

    void setDot(boolean d) { dot = d; invalidate(); }
    void setPinnedMark(boolean m) { pinnedMark = m; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        float pad = w * 0.06f;
        float side = w - pad * 2;
        r.set(pad, pad, pad + side, pad + side);
        float rad = side * 0.225f;

        // soft drop shadow
        p.setShader(null);
        p.setColor(isFocused() ? 0x66000000 : 0x40000000);
        c.drawRoundRect(new RectF(r.left, r.top + side * 0.04f, r.right, r.bottom + side * 0.04f), rad, rad, p);

        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        int save = c.save();
        c.clipPath(clip);
        if (glyph != GLYPH_NONE) {
            drawGlyph(c, r);
        } else {
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFF7F7FA, 0xFFDCDCE2, Shader.TileMode.CLAMP));
            c.drawRect(r, p);
            if (icon != null) {
                icon.setBounds((int) r.left, (int) r.top, (int) r.right, (int) r.bottom);
                icon.draw(c);
            }
        }
        // glossy top highlight
        p.setShader(new LinearGradient(0, r.top, 0, r.top + side * 0.5f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        c.restoreToCount(save);

        p.setShader(null);
        if (isFocused()) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(getContext(), 1.5f));
            p.setColor(0xCCFFFFFF);
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        }

        if (pinnedMark) {
            float cr = side * 0.11f;
            p.setColor(0xFF3D8BFF);
            c.drawCircle(r.right - cr * 0.6f, r.top + cr * 0.6f, cr, p);
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(cr * 0.28f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float cx = r.right - cr * 0.6f, cy = r.top + cr * 0.6f;
            Path check = new Path();
            check.moveTo(cx - cr * 0.45f, cy + cr * 0.02f);
            check.lineTo(cx - cr * 0.1f, cy + cr * 0.38f);
            check.lineTo(cx + cr * 0.5f, cy - cr * 0.35f);
            c.drawPath(check, p);
            p.setStyle(Paint.Style.FILL);
        }

        if (dot) {
            p.setColor(0xE6FFFFFF);
            c.drawCircle(w / 2f, r.bottom + (getHeight() - r.bottom) / 2f, Ui.dp(getContext(), 2), p);
        }

        if (caption) {
            float y = r.bottom + pad + tp.getTextSize();
            CharSequence t = TextUtils.ellipsize(entry.label, tp, w * 1.25f, TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), w / 2f, y, tp);
        }
    }

    private void drawGlyph(Canvas c, RectF r) {
        float s = r.width();
        float cx = r.centerX(), cy = r.centerY();
        p.setStyle(Paint.Style.FILL);
        switch (glyph) {
            case GLYPH_APPS: {
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF4A4E5C, 0xFF1F2128, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
                p.setShader(null);
                int[] cols = {0xFFFF6B6B, 0xFFFFB347, 0xFFFFE066, 0xFF6BD68A, 0xFF4FC3F7, 0xFF7C83FD, 0xFFC77DFF, 0xFFFF8FC7, 0xFFFFFFFF};
                float step = s * 0.22f, dr = s * 0.075f;
                for (int i = 0; i < 9; i++) {
                    p.setColor(cols[i]);
                    c.drawCircle(cx + (i % 3 - 1) * step, cy + (i / 3 - 1) * step, dr, p);
                }
                break;
            }
            case GLYPH_INPUTS: {
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF4FA3FF, 0xFF1F5FD6, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
                p.setShader(null);
                p.setColor(0xFFFFFFFF);
                // a port shape: wide top, tapered bottom
                Path port = new Path();
                float pw = s * 0.52f, ph = s * 0.2f, top = cy - s * 0.02f;
                port.moveTo(cx - pw / 2, top);
                port.lineTo(cx + pw / 2, top);
                port.lineTo(cx + pw / 2, top + ph * 0.55f);
                port.lineTo(cx + pw * 0.38f, top + ph);
                port.lineTo(cx - pw * 0.38f, top + ph);
                port.lineTo(cx - pw / 2, top + ph * 0.55f);
                port.close();
                c.drawPath(port, p);
                p.setColor(0xFF1F5FD6);
                c.drawRect(cx - pw * 0.36f, top + ph * 0.3f, cx + pw * 0.36f, top + ph * 0.55f, p);
                // arrow pointing into the port
                p.setColor(0xFFFFFFFF);
                p.setStrokeWidth(s * 0.06f);
                p.setStrokeCap(Paint.Cap.ROUND);
                float ay = cy - s * 0.26f;
                c.drawLine(cx, ay, cx, top - s * 0.08f, p);
                Path head = new Path();
                head.moveTo(cx - s * 0.09f, top - s * 0.13f);
                head.lineTo(cx + s * 0.09f, top - s * 0.13f);
                head.lineTo(cx, top - s * 0.03f);
                head.close();
                c.drawPath(head, p);
                break;
            }
            case GLYPH_SETTINGS: {
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFB9BCC6, 0xFF6E727E, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
                p.setShader(null);
                p.setColor(0xFF2E3038);
                float outer = s * 0.33f, inner = s * 0.25f;
                Path gear = new Path();
                int teeth = 10;
                for (int i = 0; i < teeth * 2; i++) {
                    double a0 = Math.PI * 2 * i / (teeth * 2);
                    double a1 = Math.PI * 2 * (i + 1) / (teeth * 2);
                    float rr = (i % 2 == 0) ? outer : inner;
                    float x0 = cx + (float) Math.cos(a0) * rr, y0 = cy + (float) Math.sin(a0) * rr;
                    float x1 = cx + (float) Math.cos(a1) * rr, y1 = cy + (float) Math.sin(a1) * rr;
                    if (i == 0) gear.moveTo(x0, y0); else gear.lineTo(x0, y0);
                    gear.lineTo(x1, y1);
                }
                gear.close();
                c.drawPath(gear, p);
                p.setColor(0xFF9DA0AB);
                c.drawCircle(cx, cy, s * 0.12f, p);
                p.setColor(0xFF2E3038);
                c.drawCircle(cx, cy, s * 0.06f, p);
                break;
            }
            case GLYPH_LIVETV: {
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFFF7A59, 0xFFD6336C, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
                p.setShader(null);
                p.setColor(0xFFFFFFFF);
                p.setStrokeWidth(s * 0.045f);
                p.setStrokeCap(Paint.Cap.ROUND);
                float st = cy - s * 0.12f;
                c.drawLine(cx, st, cx - s * 0.14f, st - s * 0.17f, p);
                c.drawLine(cx, st, cx + s * 0.14f, st - s * 0.17f, p);
                RectF screen = new RectF(cx - s * 0.3f, st, cx + s * 0.3f, cy + s * 0.24f);
                c.drawRoundRect(screen, s * 0.06f, s * 0.06f, p);
                p.setColor(0xFFD6336C);
                RectF in = new RectF(screen.left + s * 0.05f, screen.top + s * 0.05f, screen.right - s * 0.05f, screen.bottom - s * 0.05f);
                c.drawRoundRect(in, s * 0.03f, s * 0.03f, p);
                p.setColor(0xFFFFFFFF);
                Path play = new Path();
                play.moveTo(cx - s * 0.06f, in.centerY() - s * 0.08f);
                play.lineTo(cx + s * 0.09f, in.centerY());
                play.lineTo(cx - s * 0.06f, in.centerY() + s * 0.08f);
                play.close();
                c.drawPath(play, p);
                break;
            }
            default:
                break;
        }
    }
}
