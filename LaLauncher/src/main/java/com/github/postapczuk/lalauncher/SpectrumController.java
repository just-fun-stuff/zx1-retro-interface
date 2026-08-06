/*
 * ZX1 Retro Interface
 * Chassis spectrum window renderer (B7b): replays a track's stored
 * envelope against the player's clock. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

/**
 * Drives the chassis window's eight plain-View bars from the current
 * track's stored {@link SpectrumStore} envelope - offline analysis
 * replayed at the playhead, the same architecture Sony's own visualizer
 * uses, because nothing on this hardware can observe playback PCM live
 * (findings "Live spectrum capture is impossible"). No audio is tapped;
 * the window shows only levels the analyzer really measured.
 *
 * Honesty rule: the bars move only while the player itself reports PLAY,
 * home is resumed with the window in SPECTRUM mode, and the envelope
 * frame for the current position exists. Every other state - PAUSE, STOP,
 * the binder-less fallback, a track without (or not yet reaching) an
 * envelope - holds the designed flat baseline. On a first play the bars
 * may light mid-track once analysis passes the playhead: that is real
 * data arriving, never a backfill. The position is the last poll's
 * playTime extrapolated with uptimeMillis while PLAY (the sanctioned
 * clock), so a seek is corrected within one poll.
 *
 * Main thread only. Bar heights move via LayoutParams - the bars stay the
 * layout's plain Views, nothing custom-drawn - at the envelope's native
 * 12 fps.
 */
final class SpectrumController {

    /** Bar travel in dp: the designed 2 dp baseline up to 18 dp full scale. */
    private static final int BASELINE_DP = 2;
    private static final int FULL_SCALE_DP = 18;
    private static final long TICK_MS = 1000L / SpectrumStore.FPS;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View[] bars;
    private final int baselinePx;
    private final int fullScalePx;
    private final int[] shownHeights;

    // The current track and clock, fed by home's ~1 s poll.
    private long genieId;
    private int durationMs;
    private boolean playing;
    private int playTimeMs;
    private long playTimeAtUptime;
    /** Resumed, and the window is in SPECTRUM mode. */
    private boolean active;
    private SpectrumStore.Envelope envelope;
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!shouldRun()) {
                ticking = false;
                baseline();
                return;
            }
            renderFrame();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** The window's children are the bars, left to right. */
    SpectrumController(Context context, ViewGroup window, float density) {
        this.context = context.getApplicationContext();
        bars = new View[window.getChildCount()];
        shownHeights = new int[bars.length];
        baselinePx = Math.round(BASELINE_DP * density);
        fullScalePx = Math.round(FULL_SCALE_DP * density);
        for (int i = 0; i < bars.length; i++) {
            bars[i] = window.getChildAt(i);
            shownHeights[i] = baselinePx;
        }
    }

    /** Resumed-and-SPECTRUM gate; false drops straight to the baseline. */
    void setActive(boolean active) {
        this.active = active;
        reconcile();
    }

    /**
     * One poll result naming a real track. playTimeMs is the player's own
     * counter at (approximately) this call's uptime; it anchors the
     * extrapolation until the next poll.
     */
    void onTrack(long genieId, int durationMs, boolean playing, int playTimeMs) {
        if (this.genieId != genieId || this.durationMs != durationMs) {
            this.genieId = genieId;
            this.durationMs = durationMs;
            envelope = SpectrumStore.peek(genieId, durationMs);
            if (envelope == null) {
                final long wantedId = genieId;
                final int wantedDuration = durationMs;
                SpectrumStore.requestLoad(context, genieId, durationMs, new Runnable() {
                    @Override
                    public void run() {
                        if (wantedId == SpectrumController.this.genieId
                                && wantedDuration == SpectrumController.this.durationMs) {
                            envelope = SpectrumStore.peek(wantedId, wantedDuration);
                            reconcile();
                        }
                    }
                });
            }
        }
        this.playing = playing;
        this.playTimeMs = playTimeMs;
        this.playTimeAtUptime = SystemClock.uptimeMillis();
        reconcile();
    }

    /** A poll with no readable track (or no binder at all). */
    void onNoTrack() {
        genieId = 0;
        durationMs = 0;
        envelope = null;
        playing = false;
        reconcile();
    }

    private boolean shouldRun() {
        return active && playing && envelope != null;
    }

    /** Starts or stops the 12 fps tick to match the current state. */
    private void reconcile() {
        if (shouldRun()) {
            if (!ticking) {
                ticking = true;
                handler.removeCallbacks(tick);
                handler.post(tick);
            }
        } else if (ticking) {
            ticking = false;
            handler.removeCallbacks(tick);
            baseline();
        } else {
            baseline();
        }
    }

    private void renderFrame() {
        long position = playTimeMs + (SystemClock.uptimeMillis() - playTimeAtUptime);
        int frame = SpectrumStore.frameFor(envelope, position);
        if (frame < 0) {
            // Analysis has not reached this position (or the track ended):
            // the honest baseline, never a guess.
            baseline();
            return;
        }
        int span = fullScalePx - baselinePx;
        for (int band = 0; band < bars.length; band++) {
            int level = envelope.level(frame, band);
            setBarHeight(band, baselinePx + level * span / SpectrumStore.LEVEL_MAX);
        }
    }

    private void baseline() {
        for (int band = 0; band < bars.length; band++) {
            setBarHeight(band, baselinePx);
        }
    }

    private void setBarHeight(int band, int heightPx) {
        if (shownHeights[band] == heightPx) {
            return;
        }
        shownHeights[band] = heightPx;
        ViewGroup.LayoutParams params = bars[band].getLayoutParams();
        params.height = heightPx;
        bars[band].setLayoutParams(params);
    }
}
