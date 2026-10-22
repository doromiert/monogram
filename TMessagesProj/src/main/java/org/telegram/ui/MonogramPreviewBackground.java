// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import org.telegram.ui.ActionBar.Theme;
import static org.telegram.messenger.AndroidUtilities.dp;

/** Reference preview surface: 12dp corners, theme-derived fill and selection outline. */
final class MonogramPreviewBackground extends Drawable {
    private final Paint fill=new Paint(3),stroke=new Paint(3);
    private final RectF rect=new RectF();
    private float selected;
    MonogramPreviewBackground() {stroke.setStyle(Paint.Style.STROKE);}
    void setSelection(float selected) {this.selected=selected;invalidateSelf();}
    static int mock(boolean bright) {return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2),bright?.4f:.2f);}
    @Override public void draw(Canvas canvas) {
        float alpha=Theme.isCurrentThemeDark()?.05f:.035f;
        fill.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),alpha));
        stroke.setColor(androidx.core.graphics.ColorUtils.blendARGB(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),alpha+.085f),Theme.getColor(Theme.key_windowBackgroundWhiteValueText),selected));
        stroke.setStrokeWidth(dp(.5f+1.5f*selected));float inset=stroke.getStrokeWidth()/2;
        rect.set(getBounds());rect.inset(inset,inset);canvas.drawRoundRect(rect,dp(12),dp(12),fill);canvas.drawRoundRect(rect,dp(12),dp(12),stroke);
    }
    @Override public void setAlpha(int value) {fill.setAlpha(value);stroke.setAlpha(value);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter) {fill.setColorFilter(filter);stroke.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity() {return PixelFormat.TRANSLUCENT;}
}
