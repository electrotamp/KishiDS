package com.electrotamp.kishids.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Kishi V1 vector artwork (assets/kishi_art.txt, generated from the SVG halves by tools/make_art.py).  Shapes are kept in the
 * artwork's own coordinates (each half is 630 x 890), so a Canvas transform scales everything, gradients included.
 */
final class Art {
    private static Art instance;

    static final class Shape {
        final Path path = new Path();
        Paint fill, stroke;

        void draw(Canvas c) {
            if (fill != null) c.drawPath(path, fill);
            if (stroke != null) c.drawPath(path, stroke);
        }
    }

    final Map<String, List<Shape>> groups = new HashMap<>();
    final Map<String, Path> geos = new HashMap<>();

    static synchronized Art get(Context c) {
        if (instance == null) {
            try { instance = new Art(c); } catch (IOException e) { throw new IllegalStateException("artwork missing", e); }
        }
        return instance;
    }

    private Art(Context c) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("kishi_art.txt"), StandardCharsets.UTF_8))) {
            String line;
            List<Shape> cur = null;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("group ")) { cur = new ArrayList<>(); groups.put(line.substring(6).trim(), cur); }
                else if (line.startsWith("shape ") && cur != null) cur.add(parseShape(line.substring(6)));
                else if (line.startsWith("geo ")) {
                    String rest = line.substring(4);
                    int sp = rest.indexOf(' ');
                    geos.put(rest.substring(0, sp), parsePath(rest.substring(sp + 1)));
                }
            }
        }
    }

    private static Shape parseShape(String s) {
        String[] part = s.split(" \\| ", 3);
        Shape sh = new Shape();
        sh.fill = fillPaint(part[0].trim());
        sh.stroke = strokePaint(part[1].trim());
        String g = part[2].trim();
        if (g.startsWith("O ")) {
            String[] n = g.substring(2).split(" ");
            sh.path.addCircle(Float.parseFloat(n[0]), Float.parseFloat(n[1]), Float.parseFloat(n[2]), Path.Direction.CW);
        } else sh.path.set(parsePath(g));
        return sh;
    }

    private static Paint fillPaint(String f) {
        if (f.equals("-")) return null;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        if (f.startsWith("#")) { p.setColor(Color.parseColor(f)); return p; }
        String[] t = f.split(" ");
        boolean linear = t[0].equals("L");
        int first = linear ? 5 : 5;
        int n = t.length - first;
        int[] colors = new int[n];
        float[] pos = new float[n];
        for (int i = 0; i < n; i++) {
            String[] sp = t[first + i].split(":");
            pos[i] = Float.parseFloat(sp[0]);
            colors[i] = Color.parseColor(sp[1]);
        }
        float a = Float.parseFloat(t[1]), b = Float.parseFloat(t[2]), c = Float.parseFloat(t[3]), d = Float.parseFloat(t[4]);
        if (linear) p.setShader(new LinearGradient(a, b, c, d, colors, pos, Shader.TileMode.CLAMP));
        else {
            // R cx cy rx ry: a unit radial gradient stretched to the ellipse
            RadialGradient g = new RadialGradient(0, 0, 1, colors, pos, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(c, d);
            m.postTranslate(a, b);
            g.setLocalMatrix(m);
            p.setShader(g);
        }
        return p;
    }

    private static Paint strokePaint(String s) {
        if (s.equals("-")) return null;
        String[] t = s.split(" ");
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Color.parseColor(t[0]));
        p.setStrokeWidth(Float.parseFloat(t[1]));
        p.setStrokeJoin(t[2].equals("round") ? Paint.Join.ROUND : Paint.Join.MITER);
        if (t[2].equals("round")) p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    /** Absolute M / L / C / Z path data. */
    static Path parsePath(String d) {
        Path p = new Path();
        int i = 0, len = d.length();
        while (i < len) {
            char cmd = d.charAt(i++);
            int j = i;
            while (j < len && Character.isLetter(d.charAt(j)) == false) j++;
            String args = d.substring(i, j).trim();
            i = j;
            if (cmd == 'Z') { p.close(); continue; }
            String[] n = args.isEmpty() ? new String[0] : args.split("[ ,]+");
            float[] v = new float[n.length];
            for (int k = 0; k < n.length; k++) v[k] = Float.parseFloat(n[k]);
            if (cmd == 'M') p.moveTo(v[0], v[1]);
            else if (cmd == 'L') p.lineTo(v[0], v[1]);
            else if (cmd == 'C') p.cubicTo(v[0], v[1], v[2], v[3], v[4], v[5]);
        }
        return p;
    }

    /** Each half is clipped to its own 630 x 890 artboard: the SVG carries mirrored overflow outside it that must not show. */
    void drawGroup(Canvas c, String name) {
        List<Shape> g = groups.get(name);
        if (g == null) return;
        c.save();
        c.clipRect(0, 0, 630, 890);
        for (Shape s : g) s.draw(c);
        c.restore();
    }
}
