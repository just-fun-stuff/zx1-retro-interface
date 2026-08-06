/*
 * ZX1 Retro Interface
 * The LCD transport glyph. PLAY and STOP are the proven font glyphs; PAUSE
 * is drawn as two solid rectangles - the firmware's 4.1 fonts have no
 * reliable double-bar character, and letters ("II") are not a pause icon.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * The single transport glyph at the end of the LCD title line. It stays a
 * TextView so PLAY ({@code ▶}) and STOP ({@code ■}) render exactly as they
 * always have; PAUSE keeps {@code ■} as an invisible metrics donor and
 * paints two ink rectangles in its measured box instead - same baseline,
 * same advance as STOP, no font-coverage gamble, no anti-aliasing (LCD
 * pixels are square).
 */
public final class TransportGlyphView extends TextView {

    private static final String PLAY = "▶";
    private static final String STOP = "■";
    /** Each bar's share of the square's width; the rest is the centre gap. */
    private static final float BAR_WIDTH_FRACTION = 0.34f;

    private final Paint barPaint = new Paint();
    private final Rect squareBounds = new Rect();
    private boolean pauseBars;

    public TransportGlyphView(Context context) {
        super(context);
    }

    public TransportGlyphView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TransportGlyphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void showPlay() {
        pauseBars = false;
        setText(PLAY);
        invalidate();
    }

    void showStop() {
        pauseBars = false;
        setText(STOP);
        invalidate();
    }

    void showPause() {
        pauseBars = true;
        setText(STOP);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!pauseBars) {
            super.onDraw(canvas);
            return;
        }
        Paint textPaint = getPaint();
        textPaint.getTextBounds(STOP, 0, STOP.length(), squareBounds);
        int baseline = getBaseline();
        float left = getCompoundPaddingLeft() + squareBounds.left;
        float top = baseline + squareBounds.top;
        float bottom = baseline + squareBounds.bottom;
        float width = squareBounds.width();
        float bar = Math.max(2f, width * BAR_WIDTH_FRACTION);
        barPaint.setColor(getCurrentTextColor());
        canvas.drawRect(left, top, left + bar, bottom, barPaint);
        canvas.drawRect(left + width - bar, top, left + width, bottom, barPaint);
    }
}
