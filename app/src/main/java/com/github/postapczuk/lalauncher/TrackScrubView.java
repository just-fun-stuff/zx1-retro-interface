/*
 * ZX1 Retro Interface
 * The track scrub over the home art panel: a hold raises a position strip
 * along the panel's foot, the finger sets a target, and lifting it issues
 * one seek. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/**
 * A hidden overlay on exactly the art panel's bounds. Holding the panel for
 * the long-press timeout raises a strip across its foot - elapsed time, a
 * track with an elapsed bar and a marker, the total - and dragging sideways
 * moves a target: one pixel of finger is one pixel of marker, measured from
 * where the drag began, so nothing jumps. Lifting the finger issues one seek.
 *
 * <p>Seek on release, never live: every write is a full registered-client
 * pass, a write the service does not answer wedges Sony's write path until
 * reboot (findings "The open() wedge"), and repeated seeks would make the
 * S-Master pipeline stutter rather than cue. One gesture, one write - and
 * never a second write while one is still out. The player keeps playing
 * from where it was while the finger moves.
 *
 * <p>Honesty: before the drag the marker is the last polled position and
 * moves only when a poll lands - nothing is extrapolated. The strip shows
 * the user's request (the target) only while the seek is in flight; once
 * it is confirmed the first poll puts the player's own position back on the
 * strip before it hides, and a refused seek reads NOT AVAILABLE. The time
 * segment above never stops showing the player's truth.
 *
 * <p>The strip appears and hides instantly and nothing on it animates - the
 * marker moves only with the finger or a poll - so it is not a fourth
 * animation; the only timers are the hold and the hide. Not focusable and
 * not clickable, so D-pad navigation on home never lands here; when a scrub
 * is not possible (the activity decides, see {@link #setScrubAllowed}) the
 * gesture stays invisible. Main thread only.
 */
public final class TrackScrubView extends View {

    interface Listener {
        /** Main thread: the finger lifted on a target worth seeking to. */
        void onScrubCommit(long trackId, int targetMs);
    }

    private static final int IDLE = 0;
    /** Finger down, hold timer running; nothing drawn yet. */
    private static final int ARMED = 1;
    /** Strip up at the polled position, no drag yet. */
    private static final int HELD = 2;
    /** The marker follows the finger as the target. */
    private static final int DRAGGING = 3;
    /** Released on a target; the strip holds it until the seek reports. */
    private static final int SEEKING = 4;
    /** Seek confirmed; the strip shows the player's polled position. */
    private static final int SETTLING = 5;
    /** Seek refused; NOT AVAILABLE, then gone. */
    private static final int FAILED = 6;

    /** A target closer than this to where the drag began is not worth a write. */
    private static final int MIN_SEEK_MS = 1000;
    /** The target stops this short of the end, so a seek never cues the boundary. */
    private static final int END_MARGIN_MS = 1000;
    private static final long SETTLE_SHOW_MS = 1200L;
    /** Confirmed but no poll came: the strip hides anyway after this. */
    private static final long SETTLE_TIMEOUT_MS = 1500L;
    private static final long FAILED_SHOW_MS = 1500L;
    private static final String NOT_AVAILABLE = "NOT AVAILABLE";

    private static final float STRIP_HEIGHT_DP = 22f;
    private static final float RULE_DP = 1f;
    private static final float READOUT_INSET_DP = 6f;
    private static final float TRACK_GAP_DP = 6f;
    private static final float LINE_DP = 1f;
    private static final float BAR_DP = 3f;
    private static final float MARKER_WIDTH_DP = 4f;
    private static final float MARKER_HEIGHT_DP = 12f;
    private static final float READOUT_TEXT_SP = 10f;

    // Rects without anti-aliasing (LCD pixels are square); the readouts
    // anti-alias like the TextViews on the glass do.
    private final Paint glassPaint = new Paint();
    private final Paint inkPaint = new Paint();
    private final Paint inkDimPaint = new Paint();
    private final Paint readoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint totalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int stripHeightPx;
    private final int rulePx;
    private final int readoutInsetPx;
    private final int trackGapPx;
    private final int lineHeightPx;
    private final int barHeightPx;
    private final int markerWidthPx;
    private final int markerHeightPx;
    private final int fontAscent;
    private final int fontDescent;
    private final int notAvailableWidthPx;
    private final int touchSlopPx;

    private Listener listener;
    private int state = IDLE;
    private boolean scrubAllowed;
    /**
     * A committed seek whose outcome has not arrived. It outlives a cancel
     * (the outcome is then swallowed) and no new scrub starts until it
     * clears, so a late outcome can never be taken for a newer seek's.
     */
    private boolean seekInFlight;

    // The player's latest report, as polled.
    private long playheadTrackId;
    private int playheadMs = -1;
    private int playheadDurationMs;

    // The scrub under way: its track, the position the drag started from,
    // and what the marker and left readout show - the polled position in
    // HELD and SETTLING, the target in DRAGGING and SEEKING.
    private long scrubTrackId;
    private int scrubDurationMs;
    private int baseMs;
    private int markerMs;

    // Readout text is formatted only when its whole second changes.
    private int markerSecond = -1;
    private String markerText = "";
    private String totalText = "";
    private int readoutWidthPx;

    // The gesture: one pointer, the point it went down at, the point the
    // hold fired at, and the x where the drag crossed the slop.
    private int pointerId;
    private float downX;
    private float downY;
    private float touchX;
    private float holdX;
    private float originX;

    // Strip geometry in whole pixels, from the view size and the readout width.
    private int stripLeft;
    private int stripTop;
    private int stripRight;
    private int stripBottom;
    private int readoutLeftX;
    private int totalRightX;
    private int trackLeft;
    private int trackRight;
    private int textBaseline;
    private int lineTop;
    private int barTop;
    private int markerTop;
    private int notAvailableX;

    private final Runnable holdRunnable = new Runnable() {
        @Override
        public void run() {
            onHoldFired();
        }
    };

    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    public TrackScrubView(Context context) {
        this(context, null);
    }

    public TrackScrubView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TrackScrubView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        stripHeightPx = Math.round(STRIP_HEIGHT_DP * density);
        rulePx = Math.round(RULE_DP * density);
        readoutInsetPx = Math.round(READOUT_INSET_DP * density);
        trackGapPx = Math.round(TRACK_GAP_DP * density);
        lineHeightPx = Math.round(LINE_DP * density);
        barHeightPx = Math.round(BAR_DP * density);
        markerWidthPx = Math.round(MARKER_WIDTH_DP * density);
        markerHeightPx = Math.round(MARKER_HEIGHT_DP * density);
        touchSlopPx = ViewConfiguration.get(context).getScaledTouchSlop();

        // The glass's monospace bold, made the way TextView makes it: the
        // firmware's monospace has no bold face, so it is synthesized.
        Typeface typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        boolean fakeBold = (typeface.getStyle() & Typeface.BOLD) == 0;
        float textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                READOUT_TEXT_SP, getResources().getDisplayMetrics());
        Paint[] textPaints = {readoutPaint, totalPaint};
        for (Paint paint : textPaints) {
            paint.setTypeface(typeface);
            paint.setFakeBoldText(fakeBold);
            paint.setTextSize(textSizePx);
        }
        totalPaint.setTextAlign(Paint.Align.RIGHT);
        Paint.FontMetricsInt metrics = readoutPaint.getFontMetricsInt();
        fontAscent = metrics.ascent;
        fontDescent = metrics.descent;
        notAvailableWidthPx = (int) Math.ceil(readoutPaint.measureText(NOT_AVAILABLE));
    }

    void setListener(Listener value) {
        listener = value;
    }

    /** The active style's glass, ink and dim ink (LCD green, or TUBE AMP amber). */
    void setColors(int glass, int ink, int inkDim) {
        glassPaint.setColor(glass);
        inkPaint.setColor(ink);
        inkDimPaint.setColor(inkDim);
        readoutPaint.setColor(ink);
        totalPaint.setColor(inkDim);
        invalidate();
    }

    /**
     * Every poll: the player's own report (trackId <= 0 / durationMs <= 0 =
     * nothing scrubbable). Another track abandons any scrub under way,
     * without a seek; the same track moves the marker only where the strip
     * shows the player's position.
     */
    void setPlayhead(long trackId, int playTimeMs, int durationMs) {
        playheadTrackId = trackId;
        playheadMs = playTimeMs;
        playheadDurationMs = durationMs;
        if (state == IDLE || state == ARMED || state == FAILED) {
            return;
        }
        if (trackId != scrubTrackId) {
            hide();
            return;
        }
        if (playTimeMs < 0) {
            return;
        }
        if (state == HELD || state == SETTLING) {
            showPosition(playTimeMs);
        } else if (state == SEEKING && !seekInFlight) {
            // Confirmed, and this is the first poll since: the player's own
            // position replaces the target for a moment, then the strip goes.
            state = SETTLING;
            removeCallbacks(hideRunnable);
            postDelayed(hideRunnable, SETTLE_SHOW_MS);
            showPosition(playTimeMs);
        }
    }

    /** Whether a scrub may start; false abandons a hold or drag (never a seek in flight). */
    void setScrubAllowed(boolean allowed) {
        scrubAllowed = allowed;
        if (!allowed && (state == ARMED || state == HELD || state == DRAGGING)) {
            hide();
        }
    }

    /** The seek's outcome; ignored unless the strip is still waiting on it. */
    void onSeekFinished(boolean confirmed) {
        if (!seekInFlight) {
            return;
        }
        seekInFlight = false;
        if (state != SEEKING) {
            // Cancelled or abandoned while the seek was out.
            return;
        }
        if (confirmed) {
            // The target holds until the first poll; if none comes, go anyway.
            postDelayed(hideRunnable, SETTLE_TIMEOUT_MS);
        } else {
            state = FAILED;
            postDelayed(hideRunnable, FAILED_SHOW_MS);
            invalidate();
        }
    }

    /** Hide now, abandon any hold/drag, ignore a later onSeekFinished. */
    void cancelScrub() {
        hide();
    }

    /*
     * A hold-and-drag gesture with no click to perform, so performClick is
     * not called; the view is deliberately outside the accessibility and
     * focus paths (the time segment carries the same information).
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (listener == null || state != IDLE || seekInFlight || !scrubAllowed) {
                    return false;
                }
                pointerId = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                touchX = downX;
                state = ARMED;
                postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointerId);
                if (index >= 0) {
                    onMove(event.getX(index), event.getY(index));
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointerId) {
                    onRelease();
                }
                return true;
            case MotionEvent.ACTION_UP:
                onRelease();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (state == ARMED || state == HELD || state == DRAGGING) {
                    hide();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        layoutStrip();
    }

    @Override
    protected void onDetachedFromWindow() {
        hide();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (state == IDLE || state == ARMED) {
            return;
        }
        canvas.drawRect(stripLeft, stripTop, stripRight, stripBottom, glassPaint);
        canvas.drawRect(stripLeft, stripTop, stripRight, stripTop + rulePx, inkPaint);
        if (state == FAILED) {
            canvas.drawText(NOT_AVAILABLE, notAvailableX, textBaseline, readoutPaint);
            return;
        }
        canvas.drawText(markerText, readoutLeftX, textBaseline, readoutPaint);
        canvas.drawText(totalText, totalRightX, textBaseline, totalPaint);
        int clampedMs = Math.max(0, Math.min(markerMs, scrubDurationMs));
        int markerX = trackLeft + (int) (((long) clampedMs * (trackRight - trackLeft)
                + scrubDurationMs / 2) / scrubDurationMs);
        canvas.drawRect(markerX, lineTop, trackRight, lineTop + lineHeightPx, inkDimPaint);
        canvas.drawRect(trackLeft, barTop, markerX, barTop + barHeightPx, inkPaint);
        int markerLeft = Math.max(trackLeft,
                Math.min(markerX - markerWidthPx / 2, trackRight - markerWidthPx));
        canvas.drawRect(markerLeft, markerTop, markerLeft + markerWidthPx,
                markerTop + markerHeightPx, inkPaint);
    }

    private void onMove(float x, float y) {
        switch (state) {
            case ARMED:
                touchX = x;
                float dx = x - downX;
                float dy = y - downY;
                if (dx * dx + dy * dy > touchSlopPx * touchSlopPx) {
                    // Moved before the hold fired: a swipe, not a hold.
                    hide();
                }
                break;
            case HELD:
                if (Math.abs(x - holdX) > touchSlopPx) {
                    // The drag starts from where the marker is and where the
                    // finger is now, so the marker does not jump.
                    state = DRAGGING;
                    baseMs = markerMs;
                    originX = x;
                }
                break;
            case DRAGGING:
                showPosition(targetFor(x));
                break;
            default:
                break;
        }
    }

    private void onRelease() {
        switch (state) {
            case ARMED:
            case HELD:
                hide();
                break;
            case DRAGGING:
                if (Math.abs(markerMs - baseMs) < MIN_SEEK_MS) {
                    hide();
                    break;
                }
                state = SEEKING;
                seekInFlight = true;
                invalidate();
                listener.onScrubCommit(scrubTrackId, markerMs);
                break;
            default:
                break;
        }
    }

    /**
     * The hold completed. When a scrub is not possible nothing happens at
     * all - no strip, no message - and the rest of the gesture is ignored.
     */
    private void onHoldFired() {
        if (state != ARMED) {
            return;
        }
        if (!scrubAllowed || playheadTrackId <= 0 || playheadDurationMs <= 0 || playheadMs < 0) {
            state = IDLE;
            return;
        }
        scrubTrackId = playheadTrackId;
        setScrubDuration(playheadDurationMs);
        holdX = touchX;
        state = HELD;
        showPosition(playheadMs);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    /** Relative drag: one pixel of finger moves the marker one pixel of track. */
    private int targetFor(float x) {
        int trackWidth = trackRight - trackLeft;
        if (trackWidth <= 0) {
            return markerMs;
        }
        long target = baseMs + Math.round((x - originX) * (double) scrubDurationMs / trackWidth);
        long last = Math.max(0, scrubDurationMs - END_MARGIN_MS);
        return (int) Math.max(0L, Math.min(target, last));
    }

    private void showPosition(int positionMs) {
        markerMs = positionMs;
        int second = positionMs / 1000;
        if (second != markerSecond) {
            markerSecond = second;
            markerText = FavouriteAppsActivity.formatTime(positionMs);
        }
        invalidate();
    }

    /** Both readouts reserve the total's width, so the track never shifts. */
    private void setScrubDuration(int durationMs) {
        if (durationMs == scrubDurationMs) {
            return;
        }
        scrubDurationMs = durationMs;
        totalText = FavouriteAppsActivity.formatTime(durationMs);
        readoutWidthPx = (int) Math.ceil(readoutPaint.measureText(totalText));
        layoutStrip();
    }

    /** All strip geometry, in whole pixels, inside the view's padding. */
    private void layoutStrip() {
        stripLeft = getPaddingLeft();
        stripRight = getWidth() - getPaddingRight();
        stripBottom = getHeight() - getPaddingBottom();
        stripTop = stripBottom - stripHeightPx;
        int bodyTop = stripTop + rulePx;
        int bodyHeight = stripBottom - bodyTop;
        textBaseline = bodyTop + (bodyHeight - fontAscent - fontDescent) / 2;
        lineTop = bodyTop + (bodyHeight - lineHeightPx) / 2;
        barTop = bodyTop + (bodyHeight - barHeightPx) / 2;
        markerTop = bodyTop + (bodyHeight - markerHeightPx) / 2;
        readoutLeftX = stripLeft + readoutInsetPx;
        totalRightX = stripRight - readoutInsetPx;
        trackLeft = readoutLeftX + readoutWidthPx + trackGapPx;
        trackRight = totalRightX - readoutWidthPx - trackGapPx;
        notAvailableX = stripLeft + (stripRight - stripLeft - notAvailableWidthPx) / 2;
    }

    /** Back to IDLE at once: timers dropped, nothing drawn. */
    private void hide() {
        removeCallbacks(holdRunnable);
        removeCallbacks(hideRunnable);
        state = IDLE;
        invalidate();
    }
}
