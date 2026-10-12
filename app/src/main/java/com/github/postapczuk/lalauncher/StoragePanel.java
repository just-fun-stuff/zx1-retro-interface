/*
 * ZX1 Retro Interface
 * The storage panel: one readout for the stretches the music is out of reach.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Renders one {@link StorageState.Snapshot} into {@code storage_panel.xml}
 * - the readout home, the library and the playlists all show while the
 * music volume is out of reach. Home cuts it into its glass; the browsers
 * set it in their overlay panel. Where it shows, and when, is each
 * screen's own business; what it says is decided here only.
 *
 * Static type only: no phase animates, and the UPDATING bar moves only
 * with a rebuild's real done/total (its count shows only once the total
 * is known). USB_STORAGE carries the one key, USB STORAGE OFF
 * ({@link StorageState#requestUsbStorageOff}): dim and dead while the
 * request is in flight, and reading NOT AVAILABLE - still dead - once the
 * firmware refused it, because there is no other route to offer.
 */
final class StoragePanel {

    private final TextView title;
    private final TextView line1;
    private final TextView line2;
    private final ProgressBar bar;
    private final TextView count;
    private final TextView key;
    /** The key's live lettering (inverts while pressed), and its dead one. */
    private final ColorStateList keyText;
    private final int keyTextDead;

    /** Binds the inflated panel and applies the style's tints once. */
    StoragePanel(View panel, Skin skin) {
        title = (TextView) panel.findViewById(R.id.storage_title);
        line1 = (TextView) panel.findViewById(R.id.storage_line1);
        line2 = (TextView) panel.findViewById(R.id.storage_line2);
        bar = (ProgressBar) panel.findViewById(R.id.storage_bar);
        count = (TextView) panel.findViewById(R.id.storage_count);
        key = (TextView) panel.findViewById(R.id.storage_key);
        key.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                StorageState.requestUsbStorageOff(view.getContext());
            }
        });
        if (skin.isLcd()) {
            Resources res = panel.getResources();
            keyText = res.getColorStateList(R.color.lcd_menu_text);
            keyTextDead = res.getColor(R.color.lcd_ink_dim);
            return;
        }
        keyText = skin.softKeyText();
        keyTextDead = skin.inkDim;
        title.setTextColor(skin.ink);
        line1.setTextColor(skin.ink);
        line2.setTextColor(skin.ink);
        count.setTextColor(skin.inkDim);
        skin.tintProgressBar(bar);
        key.setBackground(skin.softKeyBackground());
    }

    /** Main thread. READY has nothing to say; callers hide the panel then. */
    void render(StorageState.Snapshot snapshot) {
        switch (snapshot.phase) {
            case USB_STORAGE:
                show(title, "USB STORAGE");
                show(line1, "CONNECTED TO COMPUTER");
                show(line2, "MUSIC UNAVAILABLE");
                bar.setVisibility(View.GONE);
                count.setVisibility(View.GONE);
                renderKey(snapshot);
                break;
            case READING:
                show(title, "READING STORAGE");
                show(line1, "SONY DATABASE UPDATING");
                line2.setVisibility(View.GONE);
                bar.setVisibility(View.GONE);
                count.setVisibility(View.GONE);
                key.setVisibility(View.GONE);
                break;
            case UPDATING:
                show(title, "UPDATING LIBRARY");
                line1.setVisibility(View.GONE);
                line2.setVisibility(View.GONE);
                bar.setMax(snapshot.total);
                bar.setProgress(snapshot.done);
                bar.setVisibility(View.VISIBLE);
                renderCount(snapshot);
                key.setVisibility(View.GONE);
                break;
            default:
                break;
        }
    }

    /** Live only while nothing is in flight and nothing was refused. */
    private void renderKey(StorageState.Snapshot snapshot) {
        show(key, snapshot.usbOffRefused ? "NOT AVAILABLE" : "USB STORAGE OFF");
        boolean live = !snapshot.usbOffPending && !snapshot.usbOffRefused;
        key.setEnabled(live);
        if (live) {
            key.setTextColor(keyText);
        } else {
            key.setTextColor(keyTextDead);
        }
    }

    /**
     * The count line keeps its room while the total is still unknown, so
     * the panel does not jump when the first real count arrives.
     */
    private void renderCount(StorageState.Snapshot snapshot) {
        if (snapshot.total <= 0) {
            count.setVisibility(View.INVISIBLE);
            return;
        }
        show(count, String.format(Locale.US, "%d / %d", snapshot.done, snapshot.total));
    }

    private static void show(TextView view, String text) {
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }
}
