/*
 * ZX1 Retro Interface
 * Launcher settings: three persisted choices (interface style, artwork
 * processing, info-window content) behind plain SharedPreferences.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The launcher's own settings, edited on the SETTINGS screen (reached from
 * the program index). Values are small ints so a corrupt or future value
 * degrades to the default rather than crashing an activity mid-inflate.
 */
final class LauncherSettings {

    /** Interface style: the STN-LCD default or the amber-on-black tube amp. */
    static final int STYLE_LCD = 0;
    static final int STYLE_TUBE_AMP = 1;

    /** Artwork processing for the LCD art panel. */
    static final int ART_GREYSCALE = 0;
    static final int ART_GAMEBOY = 1;
    static final int ART_FULL_COLOR = 2;

    /** What the chassis info window (top-right) shows. */
    static final int INFO_SPECTRUM = 0;
    static final int INFO_CLOCK = 1;

    private static final String PREFS_NAME = "zx1_settings";
    private static final String KEY_STYLE = "style";
    private static final String KEY_ARTWORK = "artwork_style";
    private static final String KEY_INFO = "info_screen";

    private LauncherSettings() {
    }

    static int getStyle(Context context) {
        return clamp(prefs(context).getInt(KEY_STYLE, STYLE_LCD), STYLE_TUBE_AMP);
    }

    static void setStyle(Context context, int value) {
        prefs(context).edit().putInt(KEY_STYLE, clamp(value, STYLE_TUBE_AMP)).apply();
    }

    static int getArtworkStyle(Context context) {
        return clamp(prefs(context).getInt(KEY_ARTWORK, ART_GREYSCALE), ART_FULL_COLOR);
    }

    static void setArtworkStyle(Context context, int value) {
        prefs(context).edit().putInt(KEY_ARTWORK, clamp(value, ART_FULL_COLOR)).apply();
    }

    static int getInfoScreen(Context context) {
        return clamp(prefs(context).getInt(KEY_INFO, INFO_SPECTRUM), INFO_CLOCK);
    }

    static void setInfoScreen(Context context, int value) {
        prefs(context).edit().putInt(KEY_INFO, clamp(value, INFO_CLOCK)).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static int clamp(int value, int max) {
        return value < 0 || value > max ? 0 : value;
    }
}
