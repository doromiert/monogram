package org.telegram.ui.ActionBar;
import android.graphics.Color;
import androidx.core.graphics.ColorUtils;
public final class MonogramProfileContrast {
    private MonogramProfileContrast() {}
    private static double contrast(int text,int background) {
        int opaque=ColorUtils.setAlphaComponent(background,255);
        return ColorUtils.calculateContrast(ColorUtils.compositeColors(text,opaque),opaque);
    }
    public static int readable(int text,int background1,int background2) {
        if(Math.min(contrast(text,background1),contrast(text,background2))>=4.5) return text;
        double white=Math.min(contrast(Color.WHITE,background1),contrast(Color.WHITE,background2));
        double black=Math.min(contrast(Color.BLACK,background1),contrast(Color.BLACK,background2));
        int target=white>=black ? Color.WHITE : Color.BLACK;
        for(int i=1;i<=20;i++) {
            int candidate=ColorUtils.blendARGB(text,target,i/20f);
            if(Math.min(contrast(candidate,background1),contrast(candidate,background2))>=4.5) return candidate;
        }
        return target;
    }
}
