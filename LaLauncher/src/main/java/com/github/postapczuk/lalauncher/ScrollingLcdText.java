/*
 * ZX1 Retro Interface
 * A single-line LCD title view that glides an over-long title back and forth
 * across the glass - the way a MiniDisc deck scrolled a long title - instead
 * of truncating it with an ellipsis. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.os.SystemClock;
import android.text.Layout;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * The LCD title line. When the text fits the view it draws statically,
 * left-aligned, with no ellipsis. When it overflows it pans: a hold at the
 * head, a smooth glide left until the tail shows, a hold there, then a glide
 * back - looping. It is the one period concession to long classical titles;
 * a deck of the era did exactly this rather than cut a title off.
 *
 * <p>Frames are driven by {@link #postOnAnimation(Runnable)} - the
 * Choreographer's vsync-aligned animation step (API 16) - never by a timed
 * Handler: the first build's 33 ms postDelayed pan updated out of phase with
 * the display and visibly flickered on the device (2026-07-19). The glide
 * moves one pixel per 60 Hz frame, and the offset is derived from elapsed
 * time, so a missed frame advances the pan instead of stalling it. During
 * the end holds nothing moves, so the view sleeps on a plain postDelayed -
 * no per-frame callbacks while the glass is still.
 *
 * <p>Gliding runs only while {@link #setActive(boolean)} is true - the
 * activity ties that to onResume/onPause, so a dark screen never animates -
 * and only while the current text is wider than the view. Identical text set
 * again (the ~1 s now-playing poll re-sends the same title every tick) is
 * ignored, so the pan is not restarted every second.
 */
public final class ScrollingLcdText extends TextView {

    // Deck cadence: a hold at each end, then a one-pixel-per-frame glide.
    private static final long PAUSE_START_MS = 1400L;
    private static final long PAUSE_END_MS = 1000L;
    private static final float SPEED_PX_PER_MS = 0.06f;

    private float scrollAmount;
    private long cycleStartMs;
    private boolean active;
    private boolean ticking;
    private boolean textChanged;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!ticking) {
                return;
            }
            long holdMs = applyCycleOffset();
            if (holdMs > 0L) {
                postDelayed(this, holdMs);
            } else {
                postOnAnimation(this);
            }
        }
    };

    private final Runnable recompute = new Runnable() {
        @Override
        public void run() {
            recomputeExtent();
        }
    };

    public ScrollingLcdText(Context context) {
        super(context);
        init();
    }

    public ScrollingLcdText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ScrollingLcdText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // One line, never an ellipsis (a long title scrolls, it is not cut),
        // and a full-width layout so the true text width is measurable and
        // scrollTo can pan it past the view bounds.
        setSingleLine(true);
        setEllipsize(null);
        setHorizontallyScrolling(true);
    }

    /** Enables gliding; the activity drives this from onResume/onPause. */
    void setActive(boolean value) {
        if (active == value) {
            return;
        }
        active = value;
        if (active) {
            // Coming back from a dark screen starts the title at its head.
            textChanged = true;
        }
        scheduleRecompute();
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        CharSequence current = getText();
        if (text != null && current != null && text.toString().equals(current.toString())) {
            // Same title re-sent by the poll - keep gliding, do not restart.
            return;
        }
        super.setText(text, type);
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int after) {
        super.onTextChanged(text, start, before, after);
        if (recompute == null) {
            // Called from the superclass constructor, before our fields exist.
            return;
        }
        textChanged = true;
        scheduleRecompute();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (recompute != null) {
            scheduleRecompute();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ticking = false;
        removeCallbacks(ticker);
        removeCallbacks(recompute);
        super.onDetachedFromWindow();
    }

    /**
     * Posts the extent recompute rather than running it inline: a new title
     * builds its Layout synchronously inside setText, but onTextChanged fires
     * just before that, so the fresh Layout is only guaranteed ready by the
     * next message.
     */
    private void scheduleRecompute() {
        removeCallbacks(recompute);
        post(recompute);
    }

    private void recomputeExtent() {
        Layout layout = getLayout();
        int available = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (layout == null || available <= 0) {
            // Not laid out yet; keep textChanged pending for the real pass.
            scrollAmount = 0f;
            syncTicker();
            return;
        }
        float extent = layout.getLineWidth(0) - available;
        scrollAmount = extent > 0f ? extent : 0f;
        if (textChanged) {
            textChanged = false;
            cycleStartMs = SystemClock.uptimeMillis();
            scrollTo(0, 0);
        } else if (getScrollX() > scrollAmount) {
            scrollTo(Math.round(scrollAmount), 0);
        }
        syncTicker();
    }

    /**
     * Recompute runs only on real text/size/activation changes, so re-arming
     * the ticker here restarts the pan chain - a fresh title begins with its
     * head hold (the cycle clock was just reset).
     */
    private void syncTicker() {
        boolean shouldRun = active && scrollAmount > 0f;
        removeCallbacks(ticker);
        ticking = shouldRun;
        if (shouldRun) {
            postOnAnimation(ticker);
        }
    }

    /**
     * Positions the text for the current moment of the hold-glide-hold-glide
     * cycle and returns how long the pan will now stay still (0 while it is
     * moving, so the caller keeps riding the animation ticks).
     */
    private long applyCycleOffset() {
        long travelMs = (long) (scrollAmount / SPEED_PX_PER_MS);
        long outHoldEndMs = PAUSE_START_MS + travelMs + PAUSE_END_MS;
        long cycleMs = outHoldEndMs + travelMs;
        long t = (SystemClock.uptimeMillis() - cycleStartMs) % cycleMs;
        float offset;
        long holdMs = 0L;
        if (t < PAUSE_START_MS) {
            offset = 0f;
            holdMs = PAUSE_START_MS - t;
        } else if (t < PAUSE_START_MS + travelMs) {
            offset = (t - PAUSE_START_MS) * SPEED_PX_PER_MS;
        } else if (t < outHoldEndMs) {
            offset = scrollAmount;
            holdMs = outHoldEndMs - t;
        } else {
            offset = scrollAmount - (t - outHoldEndMs) * SPEED_PX_PER_MS;
        }
        offset = offset < 0f ? 0f : offset > scrollAmount ? scrollAmount : offset;
        scrollTo(Math.round(offset), 0);
        return holdMs;
    }
}
