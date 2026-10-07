package com.electrotamp.kishids.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/** The desktop app's dark and light palettes (Theme/Palette.Dark.xaml and Palette.Light.xaml), by the same names. */
public final class Theme {
    private Theme() {}

    public static boolean dark = true;

    public static int bg, panel, card, cardHi, field, stroke, strokeHi, text, textDim, textFaint, accent, accentHi, accentLo, accentSoft, violet,
            good, warn, bad, hover, press, controlHover, strokeHover, thumbOff, toggleOffTrack, toggleOffBorder, navSelBg, navSelText, navSelIcon,
            brand, iconTileBg, iconTileStroke, iconTileFg, warnSoft, goodSoft, dotIdle, panelInset, sidebar, heroA, heroB;
    public static int tile1a, tile1b, tile1Stroke, tile1Eyebrow, tile1Icon;
    public static int tile2a, tile2b, tile2Stroke, tile2Eyebrow, tile2Icon;
    public static int tile3a, tile3b, tile3Stroke, tile3Eyebrow, tile3Icon;
    public static int glowBlue, glowViolet;

    private static int c(String hex) { return Color.parseColor(hex); }

    public static void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE);
        apply(p.getBoolean("dark", true));
    }

    public static void save(Context ctx, boolean isDark) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("dark", isDark).apply();
        apply(isDark);
    }

    public static void apply(boolean isDark) {
        dark = isDark;
        if (isDark) {
            bg = c("#080A11"); panel = c("#0C0F18"); card = c("#111623"); cardHi = c("#171D2C"); field = c("#0B0E16");
            stroke = c("#1F2638"); strokeHi = c("#2F3850");
            text = c("#EEF1F6"); textDim = c("#98A3B8"); textFaint = c("#626D83");
            accent = c("#3B82F6"); accentHi = c("#5B9AFF"); accentLo = c("#2F6FDB"); accentSoft = c("#17224A"); violet = c("#8B5CF6");
            good = c("#34D399"); warn = c("#FBBF24"); bad = c("#F87171");
            hover = c("#1E232D"); press = c("#1A1F29"); controlHover = c("#262C39"); strokeHover = c("#3D4659");
            thumbOff = c("#C9D1DF"); toggleOffTrack = c("#262C39"); toggleOffBorder = c("#323949");
            navSelBg = c("#1A2650"); navSelText = c("#EEF1F6"); navSelIcon = c("#5B9AFF"); brand = c("#6E7BFF");
            iconTileBg = c("#1B2750"); iconTileStroke = c("#2A3A70"); iconTileFg = c("#6FA2FF");
            warnSoft = c("#2A2412"); goodSoft = c("#10281F"); dotIdle = c("#2E3544"); panelInset = c("#11141A");
            sidebar = c("#D90B0E17"); heroA = c("#E6141926"); heroB = c("#E60F131E");
            tile1a = c("#2C2356"); tile1b = c("#171A34"); tile1Stroke = c("#3A2F72"); tile1Eyebrow = c("#9A93C8"); tile1Icon = c("#A78BFA");
            tile2a = c("#182C55"); tile2b = c("#121D38"); tile2Stroke = c("#25427A"); tile2Eyebrow = c("#8499C4"); tile2Icon = c("#60A5FA");
            tile3a = c("#143149"); tile3b = c("#111F31"); tile3Stroke = c("#1F4A66"); tile3Eyebrow = c("#7FA6BF"); tile3Icon = c("#38BDF8");
            glowBlue = c("#2E3B82F6"); glowViolet = c("#228B5CF6");
        } else {
            bg = c("#E8EDFA"); panel = c("#F5F8FE"); card = c("#FFFFFF"); cardHi = c("#F2F6FE"); field = c("#F1F5FC");
            stroke = c("#DAE2F3"); strokeHi = c("#C3CFE8");
            text = c("#17203B"); textDim = c("#54617F"); textFaint = c("#8793AD");
            accent = c("#3B82F6"); accentHi = c("#2F6FE6"); accentLo = c("#2559C7"); accentSoft = c("#DDE9FF"); violet = c("#7C4DEB");
            good = c("#12A672"); warn = c("#D68A00"); bad = c("#E04A4A");
            hover = c("#E7EEFC"); press = c("#DBE5F8"); controlHover = c("#E9F0FD"); strokeHover = c("#AEBDDC");
            thumbOff = c("#FFFFFF"); toggleOffTrack = c("#C5D0E8"); toggleOffBorder = c("#B4C1DE");
            navSelBg = c("#4287F5"); navSelText = c("#FFFFFF"); navSelIcon = c("#FFFFFF"); brand = c("#5B6CFF");
            iconTileBg = c("#E3EDFF"); iconTileStroke = c("#C7D9FA"); iconTileFg = c("#3B82F6");
            warnSoft = c("#FFF3D6"); goodSoft = c("#DDF5EA"); dotIdle = c("#C3CEE6"); panelInset = c("#F4F7FD");
            sidebar = c("#B8FFFFFF"); heroA = c("#E6FFFFFF"); heroB = c("#CCFFFFFF");
            tile1a = c("#F1ECFE"); tile1b = c("#E3E2FB"); tile1Stroke = c("#D3CCF7"); tile1Eyebrow = c("#7A71B0"); tile1Icon = c("#7C5CE6");
            tile2a = c("#E6F0FF"); tile2b = c("#DAE8FC"); tile2Stroke = c("#C2D6F7"); tile2Eyebrow = c("#6D86B8"); tile2Icon = c("#2F7BEA");
            tile3a = c("#E2F4FB"); tile3b = c("#D7EAF6"); tile3Stroke = c("#BDE0EF"); tile3Eyebrow = c("#5F8DA6"); tile3Icon = c("#1AA3D6");
            glowBlue = c("#66FFFFFF"); glowViolet = c("#40B7A6FF");
        }
    }

    /** A colour with its alpha replaced (0..255). */
    public static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    /** Linear blend of two colours, t = 0 gives a. */
    public static int mix(int a, int b, float t) {
        return Color.argb((int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t), (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t), (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
