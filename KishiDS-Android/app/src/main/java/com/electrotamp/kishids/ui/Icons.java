package com.electrotamp.kishids.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Line icons drawn on a 24-unit grid, in the same quiet outline style as the desktop app's icon font. */
public final class Icons {
    private Icons() {}

    public static final String HOME = "home", BUTTONS = "buttons", STICK = "stick", TRIGGERS = "triggers", DPAD = "dpad", SUN = "sun", MOON = "moon",
            PULSE = "pulse", PERSON = "person", DOWNLOAD = "download", CHEVRON = "chevron", CHEVRON_DOWN = "chevron_down", CHECK = "check",
            WARNING = "warning", LINK = "link", LAYERS = "layers", FOLDER = "folder", RESET = "reset", HELP = "help", MORE = "more", CLOSE = "close",
            ARROW = "arrow", LOCK = "lock", SEARCH = "search";

    private static final Path P = new Path();
    private static final RectF R = new RectF();

    /** Draws {@code name} centred at (cx, cy) in a box of {@code size} pixels.  {@code p} should be a STROKE paint; its width is set here. */
    public static void draw(Canvas c, String name, float cx, float cy, float size, Paint p) {
        float s = size / 24f;
        c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(s, s);
        Paint.Style old = p.getStyle();
        float oldW = p.getStrokeWidth();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        P.reset();
        switch (name) {
            case HOME:
                P.moveTo(3, 11); P.lineTo(12, 3); P.lineTo(21, 11);
                P.moveTo(5.5f, 9.5f); P.lineTo(5.5f, 20); P.lineTo(10, 20); P.lineTo(10, 14); P.lineTo(14, 14); P.lineTo(14, 20); P.lineTo(18.5f, 20); P.lineTo(18.5f, 9.5f);
                c.drawPath(P, p);
                break;
            case BUTTONS:
                R.set(2.5f, 6.5f, 21.5f, 17.5f);
                c.drawRoundRect(R, 3, 3, p);
                for (int i = 0; i < 4; i++) c.drawPoint(6 + i * 4, 10.5f, dot(p));
                P.moveTo(7, 14); P.lineTo(17, 14);
                c.drawPath(P, p);
                break;
            case STICK:
                c.drawCircle(12, 12, 9, p);
                c.drawCircle(12, 12, 3.2f, p);
                break;
            case TRIGGERS:
                P.moveTo(5, 9); P.cubicTo(5, 5.5f, 8, 4, 12, 4); P.cubicTo(16, 4, 19, 5.5f, 19, 9);
                P.moveTo(12, 9); P.lineTo(12, 20);
                P.moveTo(8, 16.5f); P.lineTo(12, 20.5f); P.lineTo(16, 16.5f);
                c.drawPath(P, p);
                break;
            case DPAD:
                P.moveTo(9, 3); P.lineTo(15, 3); P.lineTo(15, 9); P.lineTo(21, 9); P.lineTo(21, 15); P.lineTo(15, 15); P.lineTo(15, 21); P.lineTo(9, 21);
                P.lineTo(9, 15); P.lineTo(3, 15); P.lineTo(3, 9); P.lineTo(9, 9); P.close();
                c.drawPath(P, p);
                break;
            case SUN:
                c.drawCircle(12, 12, 4, p);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    c.drawLine(12 + (float) Math.cos(a) * 7, 12 + (float) Math.sin(a) * 7, 12 + (float) Math.cos(a) * 9.5f, 12 + (float) Math.sin(a) * 9.5f, p);
                }
                break;
            case MOON:
                P.moveTo(20, 14.5f); P.cubicTo(18.8f, 15, 17.5f, 15.3f, 16, 15.3f); P.cubicTo(11.4f, 15.3f, 8, 12, 8, 7.5f);
                P.cubicTo(8, 6, 8.3f, 4.8f, 8.8f, 3.6f); P.cubicTo(5.7f, 4.8f, 3.5f, 7.8f, 3.5f, 11.3f); P.cubicTo(3.5f, 16, 7.3f, 19.8f, 12, 19.8f);
                P.cubicTo(15.5f, 19.8f, 18.6f, 17.6f, 20, 14.5f); P.close();
                c.drawPath(P, p);
                break;
            case PULSE:
                P.moveTo(2, 12); P.lineTo(7, 12); P.lineTo(10, 5); P.lineTo(14, 19); P.lineTo(17, 12); P.lineTo(22, 12);
                c.drawPath(P, p);
                break;
            case PERSON:
                c.drawCircle(12, 8, 4, p);
                P.moveTo(4, 21); P.cubicTo(4, 16.5f, 8, 14, 12, 14); P.cubicTo(16, 14, 20, 16.5f, 20, 21);
                c.drawPath(P, p);
                break;
            case DOWNLOAD:
                P.moveTo(12, 3.5f); P.lineTo(12, 15); P.moveTo(7, 10.5f); P.lineTo(12, 15.5f); P.lineTo(17, 10.5f); P.moveTo(4.5f, 20); P.lineTo(19.5f, 20);
                c.drawPath(P, p);
                break;
            case CHEVRON:
                P.moveTo(9, 5); P.lineTo(16, 12); P.lineTo(9, 19);
                c.drawPath(P, p);
                break;
            case CHEVRON_DOWN:
                P.moveTo(5, 9); P.lineTo(12, 16); P.lineTo(19, 9);
                c.drawPath(P, p);
                break;
            case CHECK:
                P.moveTo(4.5f, 12.5f); P.lineTo(10, 18); P.lineTo(19.5f, 7);
                c.drawPath(P, p);
                break;
            case WARNING:
                P.moveTo(12, 3.5f); P.lineTo(22, 20.5f); P.lineTo(2, 20.5f); P.close();
                P.moveTo(12, 10); P.lineTo(12, 14.5f);
                c.drawPath(P, p);
                c.drawPoint(12, 17.5f, dot(p));
                break;
            case LINK:
                c.save(); c.rotate(-45, 12, 12);
                R.set(1.5f, 8.5f, 14, 15.5f); c.drawRoundRect(R, 3.5f, 3.5f, p);
                R.set(10, 8.5f, 22.5f, 15.5f); c.drawRoundRect(R, 3.5f, 3.5f, p);
                c.restore();
                break;
            case LAYERS:
                P.moveTo(12, 3); P.lineTo(21.5f, 8); P.lineTo(12, 13); P.lineTo(2.5f, 8); P.close();
                P.moveTo(2.5f, 12.5f); P.lineTo(12, 17.5f); P.lineTo(21.5f, 12.5f);
                P.moveTo(2.5f, 17); P.lineTo(12, 22); P.lineTo(21.5f, 17);
                c.drawPath(P, p);
                break;
            case FOLDER:
                P.moveTo(3, 6.5f); P.lineTo(9.5f, 6.5f); P.lineTo(11.5f, 9); P.lineTo(21, 9); P.lineTo(21, 19); P.lineTo(3, 19); P.close();
                c.drawPath(P, p);
                break;
            case RESET:
                R.set(4.5f, 4.5f, 19.5f, 19.5f);
                c.drawArc(R, -60, 300, false, p);
                P.moveTo(4, 3.5f); P.lineTo(4.5f, 9); P.lineTo(10, 8.5f);
                c.drawPath(P, p);
                break;
            case HELP:
                c.drawCircle(12, 12, 9.5f, p);
                P.moveTo(9.2f, 9.5f); P.cubicTo(9.2f, 6.2f, 14.8f, 6.2f, 14.8f, 9.6f); P.cubicTo(14.8f, 12, 12, 12, 12, 14.5f);
                c.drawPath(P, p);
                c.drawPoint(12, 17.7f, dot(p));
                break;
            case MORE:
                c.drawPoint(5, 12, dot(p)); c.drawPoint(12, 12, dot(p)); c.drawPoint(19, 12, dot(p));
                break;
            case CLOSE:
                P.moveTo(6, 6); P.lineTo(18, 18); P.moveTo(18, 6); P.lineTo(6, 18);
                c.drawPath(P, p);
                break;
            case ARROW:
                P.moveTo(4, 12); P.lineTo(20, 12); P.moveTo(14, 6); P.lineTo(20, 12); P.lineTo(14, 18);
                c.drawPath(P, p);
                break;
            case LOCK:
                R.set(5, 10.5f, 19, 20.5f); c.drawRoundRect(R, 2.5f, 2.5f, p);
                R.set(7.5f, 3.5f, 16.5f, 15.5f); c.drawArc(R, 180, 180, false, p);
                break;
            case SEARCH:
                c.drawCircle(10.5f, 10.5f, 6.5f, p);
                c.drawLine(15.5f, 15.5f, 21, 21, p);
                break;
            default:
                break;
        }
        p.setStyle(old);
        p.setStrokeWidth(oldW);
        c.restore();
    }

    /** Paint used for a round dot: same colour, drawn as a thick point (the point size is in grid units, scaled with the canvas). */
    private static Paint dotPaint;

    private static Paint dot(Paint like) {
        if (dotPaint == null) { dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG); dotPaint.setStrokeCap(Paint.Cap.ROUND); }
        dotPaint.setColor(like.getColor());
        dotPaint.setStyle(Paint.Style.STROKE);
        dotPaint.setStrokeWidth(2.8f);
        return dotPaint;
    }
}
