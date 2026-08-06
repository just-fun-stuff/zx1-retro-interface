/*
 * ZX1 Retro Interface
 * The settings gear on the program index header - drawn, not a font glyph:
 * U+2699 is unreliable in the firmware's 4.1 fonts. The one sanctioned
 * icon in the interface.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * A minimal machined gear: an open ring with eight square-cut teeth, drawn
 * in the chassis lettering tone. Pressed feedback comes from the control's
 * background drawable, like every other chassis control.
 */
public final class GearView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int inkColor = 0xFF858C87;

    public GearView(Context context) {
        super(context);
    }

    public GearView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GearView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void setInk(int color) {
        inkColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float unit = Math.min(getWidth(), getHeight());
        float ringRadius = unit * 0.20f;
        float stroke = Math.max(2f, unit * 0.09f);
        paint.setColor(inkColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        canvas.drawCircle(centerX, centerY, ringRadius, paint);
        float toothInner = ringRadius + stroke * 0.5f;
        float toothOuter = ringRadius + stroke * 1.6f;
        for (int tooth = 0; tooth < 8; tooth++) {
            double angle = Math.PI * tooth / 4d;
            float directionX = (float) Math.cos(angle);
            float directionY = (float) Math.sin(angle);
            canvas.drawLine(
                    centerX + directionX * toothInner, centerY + directionY * toothInner,
                    centerX + directionX * toothOuter, centerY + directionY * toothOuter,
                    paint);
        }
    }
}
