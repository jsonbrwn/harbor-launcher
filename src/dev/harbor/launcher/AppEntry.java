package dev.harbor.launcher;

import android.graphics.drawable.Drawable;

/** One launchable thing: an installed app or one of Harbor's built-in shortcuts. */
final class AppEntry {
    static final String APPS = "@apps", INPUTS = "@inputs", SETTINGS = "@settings", LIVETV = "@livetv";

    final String key;      // package name, or one of the @ keys above
    final String label;
    final Drawable icon;
    final int glyph;

    AppEntry(String key, String label, Drawable icon, int glyph) {
        this.key = key;
        this.label = label;
        this.icon = icon;
        this.glyph = glyph;
    }

    boolean isBuiltIn() { return key.startsWith("@"); }
}
