package org.telegram.ui.Components.blur3.drawable.color;

import androidx.annotation.ColorInt;

public interface BlurredBackgroundColorProvider {
    @ColorInt int getShadowColor();
    @ColorInt int getBackgroundColor();
    @ColorInt int getStrokeColorTop();
    @ColorInt int getStrokeColorBottom();
    default int getStrokeColorFull() {return org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_divider); }
}
