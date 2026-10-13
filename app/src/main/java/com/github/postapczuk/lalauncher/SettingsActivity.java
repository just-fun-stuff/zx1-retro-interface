/*
 * ZX1 Retro Interface
 * The SETTINGS screen: three numbered function rows; a tap steps the value
 * the way a deck's MENU/ENTER key cycled options. Licensed under GPLv2;
 * see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings live behind the program index's gear. Each row shows its current
 * value; tapping cycles to the next one and persists immediately. A style
 * change re-creates this screen on the spot - controls swap instantly, no
 * transition - and every other screen reconciles its own style on resume.
 */
public class SettingsActivity extends Activity {

    private Skin skin;
    private TextView styleValue;
    private TextView artworkValue;
    private TextView infoValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_settings);
        skin = Skin.of(this);

        styleValue = (TextView) findViewById(R.id.setting_style_value);
        artworkValue = (TextView) findViewById(R.id.setting_artwork_value);
        infoValue = (TextView) findViewById(R.id.setting_info_value);

        findViewById(R.id.setting_style).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int next = LauncherSettings.getStyle(SettingsActivity.this)
                        == LauncherSettings.STYLE_LCD
                        ? LauncherSettings.STYLE_TUBE_AMP : LauncherSettings.STYLE_LCD;
                LauncherSettings.setStyle(SettingsActivity.this, next);
                // Re-inflate under the new style so the change is visible
                // right where it was made.
                recreate();
            }
        });
        findViewById(R.id.setting_artwork).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                LauncherSettings.setArtworkStyle(SettingsActivity.this,
                        (LauncherSettings.getArtworkStyle(SettingsActivity.this) + 1) % 3);
                renderValues();
            }
        });
        findViewById(R.id.setting_info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int next = (LauncherSettings.getInfoScreen(SettingsActivity.this) + 1) % 2;
                LauncherSettings.setInfoScreen(SettingsActivity.this, next);
                if (next == LauncherSettings.INFO_CLOCK) {
                    // The window is the clock now: a running analysis pass
                    // yields at its next block instead of burning on.
                    SpectrumAnalyzerService.yieldRunningPass();
                }
                renderValues();
            }
        });
        findViewById(R.id.settings_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });

        renderValues();
        applySkin();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
    }

    private void renderValues() {
        styleValue.setText(styleLabel(LauncherSettings.getStyle(this)));
        artworkValue.setText(artworkLabel(LauncherSettings.getArtworkStyle(this)));
        infoValue.setText(infoLabel(LauncherSettings.getInfoScreen(this)));
    }

    private static String styleLabel(int value) {
        return value == LauncherSettings.STYLE_TUBE_AMP ? "TUBE AMP" : "LCD";
    }

    private static String artworkLabel(int value) {
        switch (value) {
            case LauncherSettings.ART_GAMEBOY:
                return "GAMEBOY";
            case LauncherSettings.ART_FULL_COLOR:
                return "FULL COLOR";
            default:
                return "GREYSCALE";
        }
    }

    private static String infoLabel(int value) {
        return value == LauncherSettings.INFO_CLOCK ? "CLOCK" : "SPECTRUM";
    }

    /** TUBE AMP re-tint; on LCD the inflated resources already match. */
    private void applySkin() {
        if (skin.isLcd()) {
            return;
        }
        Skin.paint(findViewById(R.id.settings_root), skin.chassis);
        skin.tintDrawerControl((TextView) findViewById(R.id.settings_back));
        ((TextView) findViewById(R.id.settings_title)).setTextColor(skin.panelText);
        ((TextView) findViewById(R.id.settings_subtitle)).setTextColor(skin.panelDim);
        LinearLayout glass = (LinearLayout) findViewById(R.id.settings_glass);
        glass.setBackground(skin.frameBackground());
        for (int i = 0; i < glass.getChildCount(); i++) {
            View child = glass.getChildAt(i);
            if (child instanceof LinearLayout) {
                tintRow((LinearLayout) child);
            } else if (child.getBackground() instanceof ColorDrawable) {
                Skin.paint(child, skin.inkDim);
            }
        }
        ((TextView) findViewById(R.id.settings_hint)).setTextColor(skin.ink);
    }

    /** Row children are [index, label, value]; the index digit stays dim. */
    private void tintRow(LinearLayout row) {
        row.setBackground(skin.rowSelector());
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(i == 0 ? skin.inkDim : skin.ink);
            }
        }
    }
}
