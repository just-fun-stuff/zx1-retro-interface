/*
 * ZX1 Retro Interface
 * Runtime style engine for the Settings "Style" choice. The LCD style is
 * the resource default; TUBE AMP is applied over the inflated views in
 * code, because API 16 drawables and color state lists cannot reference
 * theme attributes. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * One resolved interface style. {@link #of(Context)} reads the persisted
 * Settings choice and returns the matching palette:
 *
 * <ul>
 * <li><b>LCD</b> - graphite chassis, STN-green glass, dark ink. This is
 * exactly the resource palette; the tint helpers are no-ops so the proven
 * XML look is never re-built in code.</li>
 * <li><b>TUBE AMP</b> - pure black chassis and glass, amber glow for every
 * printed and lit element (dark-on-light becomes light-on-dark: the ink is
 * the glow).</li>
 * </ul>
 *
 * The drawable factories replicate the LCD res/drawable shapes with the
 * skin's colors, so pressed/focused behaviour is identical in both styles.
 */
final class Skin {

    final int style;
    /** Chassis body, control fill, and bezel tones. */
    final int chassis;
    final int chassisMid;
    final int chassisEdge;
    /** Printed chassis lettering (bright / secondary) and the one accent. */
    final int panelText;
    final int panelDim;
    final int accent;
    /** The glass: background, pressed flash, ink, and dim ink. */
    final int glass;
    final int glassPressed;
    final int ink;
    final int inkDim;
    final int searchField;
    /**
     * The art/plasma tone endpoints by luminance, not by role: artDark
     * renders a black pixel, artLight a white one. On LCD dark is the ink;
     * on TUBE AMP dark is the black glass and light is the amber glow.
     */
    final int artDark;
    final int artLight;

    private final float density;

    static Skin of(Context context) {
        return new Skin(context, LauncherSettings.getStyle(context));
    }

    private Skin(Context context, int style) {
        this.style = style;
        this.density = context.getResources().getDisplayMetrics().density;
        android.content.res.Resources res = context.getResources();
        if (style == LauncherSettings.STYLE_TUBE_AMP) {
            chassis = res.getColor(R.color.tube_black);
            chassisMid = res.getColor(R.color.tube_chassis_mid);
            chassisEdge = res.getColor(R.color.tube_chassis_edge);
            panelText = res.getColor(R.color.tube_glow);
            panelDim = res.getColor(R.color.tube_glow_dim);
            accent = res.getColor(R.color.tube_glow_bright);
            glass = res.getColor(R.color.tube_black);
            glassPressed = res.getColor(R.color.tube_glow_press);
            ink = res.getColor(R.color.tube_glow);
            inkDim = res.getColor(R.color.tube_glow_dim);
            searchField = res.getColor(R.color.tube_field);
            artDark = res.getColor(R.color.tube_black);
            artLight = res.getColor(R.color.tube_glow);
        } else {
            chassis = res.getColor(R.color.chassis_black);
            chassisMid = res.getColor(R.color.chassis_mid);
            chassisEdge = res.getColor(R.color.chassis_edge);
            panelText = res.getColor(R.color.panel_white);
            panelDim = res.getColor(R.color.panel_dim);
            accent = res.getColor(R.color.accent_orange);
            glass = res.getColor(R.color.lcd_surface);
            glassPressed = res.getColor(R.color.lcd_surface_pressed);
            ink = res.getColor(R.color.lcd_ink);
            inkDim = res.getColor(R.color.lcd_ink_dim);
            searchField = res.getColor(R.color.lcd_search_field);
            artDark = res.getColor(R.color.lcd_ink);
            artLight = res.getColor(R.color.lcd_surface);
        }
    }

    boolean isLcd() {
        return style != LauncherSettings.STYLE_TUBE_AMP;
    }

    // ------------------------------------------------------------- drawables

    /** res/drawable/lcd_frame.xml with skin colors (bezel + edge-to-edge glass). */
    Drawable frameBackground() {
        GradientDrawable bezel = new GradientDrawable();
        bezel.setColor(chassisEdge);
        GradientDrawable glassPane = new GradientDrawable();
        glassPane.setColor(glass);
        glassPane.setStroke(dp(1), 0xFF050706);
        LayerDrawable frame = new LayerDrawable(new Drawable[]{bezel, glassPane});
        frame.setLayerInset(0, -dp(4), 0, -dp(4), 0);
        frame.setLayerInset(1, -dp(1), dp(3), -dp(1), dp(3));
        return frame;
    }

    /** res/drawable/lcd_soft_key.xml: stroked key, ink-solid when pressed. */
    Drawable softKeyBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, solid(ink, 0, 0));
        states.addState(new int[]{android.R.attr.state_focused},
                solid(glassPressed, dp(1), inkDim));
        states.addState(new int[]{}, solid(0, dp(1), inkDim));
        return states;
    }

    /** res/color/lcd_menu_text.xml: soft-key text inverts while pressed. */
    ColorStateList softKeyText() {
        return pressedStates(glass, ink);
    }

    /** res/drawable/lcd_latch.xml: transparent latch, ink-solid when pressed. */
    Drawable latchBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, solid(ink, 0, 0));
        states.addState(new int[]{}, new ColorDrawable(0));
        return states;
    }

    /** res/color/lcd_latch_text.xml: dim latch text inverts while pressed. */
    ColorStateList latchText() {
        return pressedStates(glass, inkDim);
    }

    /** res/drawable/lcd_row_selected.xml: the list-row press flash. */
    Drawable rowSelector() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(glassPressed));
        states.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(glassPressed));
        states.addState(new int[]{}, new ColorDrawable(0));
        return states;
    }

    /** res/drawable/lcd_search.xml: the recessed search field. */
    Drawable searchFieldBackground() {
        return solid(searchField, dp(1), inkDim);
    }

    /** res/drawable/drawer_control.xml: the stroked chassis control. */
    Drawable drawerControlBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                solid(chassisMid, dp(1), panelDim));
        states.addState(new int[]{}, solid(0, dp(1), chassisEdge));
        return states;
    }

    /** res/drawable/lcd_panel_frame.xml: the art panel's ink hairline. */
    Drawable panelFrame() {
        return solid(0, dp(1), ink);
    }

    /** res/drawable/spectrum_window.xml: the chassis info-window recess. */
    Drawable infoWindowBackground() {
        GradientDrawable window = new GradientDrawable();
        window.setColor(isLcd() ? ink : chassisMid);
        window.setStroke(dp(1), chassisEdge);
        window.setCornerRadius(dp(1));
        return window;
    }

    /**
     * The info window in CLOCK mode: the same rectangle re-cut as a piece
     * of the screen - glass fill, ink digits (both styles use this; it is a
     * new element, not a re-skin of an existing XML drawable).
     */
    Drawable clockWindowBackground() {
        GradientDrawable window = new GradientDrawable();
        window.setColor(glass);
        window.setStroke(dp(1), chassisEdge);
        window.setCornerRadius(dp(1));
        return window;
    }

    /** res/drawable/lcd_progress.xml: glass track, ink fill. */
    Drawable progressDrawable() {
        GradientDrawable track = new GradientDrawable();
        track.setColor(glass);
        track.setStroke(1, ink);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(ink);
        LayerDrawable bar = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
        bar.setId(0, android.R.id.background);
        bar.setId(1, android.R.id.progress);
        bar.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
        return bar;
    }

    // ---------------------------------------------------- shared view tints

    /** Re-skins a full-screen list (divider, press flash, scroll cache). */
    void tintList(ListView list) {
        if (isLcd()) {
            return;
        }
        list.setCacheColorHint(glass);
        list.setDivider(new ColorDrawable(inkDim));
        list.setDividerHeight(dp(1));
        list.setSelector(rowSelector());
    }

    /** Re-skins a glass search field. */
    void tintSearch(EditText search) {
        if (isLcd()) {
            return;
        }
        search.setBackground(searchFieldBackground());
        search.setTextColor(ink);
        search.setHintTextColor(inkDim);
    }

    /** Re-skins a chassis drawer control (the header back buttons). */
    void tintDrawerControl(TextView control) {
        if (isLcd()) {
            return;
        }
        control.setBackground(drawerControlBackground());
        control.setTextColor(panelDim);
    }

    /** Re-skins the rebuild overlay's progress bar in place. */
    void tintProgressBar(ProgressBar bar) {
        if (isLcd()) {
            return;
        }
        Drawable fresh = progressDrawable();
        Drawable old = bar.getProgressDrawable();
        if (old != null) {
            fresh.setBounds(old.getBounds());
        }
        bar.setProgressDrawable(fresh);
    }

    // ---------------------------------------------------------------- pieces

    private GradientDrawable solid(int fill, int strokeWidth, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        if (strokeWidth > 0) {
            shape.setStroke(strokeWidth, strokeColor);
        }
        shape.setCornerRadius(dp(1));
        return shape;
    }

    private static ColorStateList pressedStates(int pressed, int normal) {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_pressed}, {}},
                new int[]{pressed, normal});
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
