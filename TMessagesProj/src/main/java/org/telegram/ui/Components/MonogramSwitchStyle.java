package org.telegram.ui.Components;

import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import org.telegram.utils.settings.MonogramAppearance;
import me.vkryl.android.animator.BoolAnimator;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

/** Geometry, spring and theme tones from the supplied exteraGram reference APK. */
public abstract class MonogramSwitchStyle {
    private static final float[] MATERIAL3_ICON_CROSS = {6.0f, 6.0f, 18.0f, 18.0f, 6.0f, 18.0f, 18.0f, 6.0f};
    private static final float[] MATERIAL3_ICON_CHECK = {4.5f, 13.1f, 9.0f, 17.6f, 9.0f, 17.6f, 19.8f, 6.8f};
    private static final Interpolator MATERIAL3_THUMB_INTERPOLATOR = new AccelerateDecelerateInterpolator();
    private static final double[] colorLab = new double[3];

    public static boolean isMaterial3SwitchStyle() {
        return MonogramAppearance.switches.get();
    }

    private static float scaled(float f) {
        return AndroidUtilities.dpf2(f * 0.8125f);
    }

    public static int getOverlayPadding() {
        if (isMaterial3SwitchStyle()) {
            return AndroidUtilities.dp(5.0f);
        }
        return 0;
    }

    public static int getStateLayerRadius() {
        return isMaterial3SwitchStyle() ? (int) scaled(20.0f) : AndroidUtilities.dp(18.0f);
    }

    public static <K> SpringAnimation createThumbSpring(K k, FloatPropertyCompat<K> floatPropertyCompat) {
        return new SpringAnimation(k, floatPropertyCompat).setMinimumVisibleChange(0.001f).setSpring(new SpringForce().setDampingRatio(0.65f).setStiffness(510.0f));
    }

    public static BoolAnimator createThumbPressedAnimator(View view) {
        return new BoolAnimator(view, CubicBezierInterpolator.Standard, 100L);
    }

    public static void setTrackBounds(RectF rectF, int i, int i2) {
        float scaled = scaled(26.0f);
        float scaled2 = scaled(16.0f);
        float f = i / 2.0f;
        float f2 = i2 / 2.0f;
        rectF.set(f - scaled, f2 - scaled2, f + scaled, f2 + scaled2);
    }

    public static float getTrackOutlineWidth() {
        return scaled(2.0f);
    }

    public static int getUnselectedTrackColor(int i, Theme.ResourcesProvider resourcesProvider) {
        if (!isMaterial3SwitchStyle()) {
            return Theme.getColor(i, resourcesProvider);
        }
        return trackAtTone(i, resourcesProvider, 90.0f, 22.0f);
    }

    public static int getUnselectedThumbColor(int i, int i2, Theme.ResourcesProvider resourcesProvider) {
        if (!isMaterial3SwitchStyle()) {
            return Theme.getColor(i2, resourcesProvider);
        }
        int trackAtTone = trackAtTone(i, resourcesProvider, 50.0f, 60.0f);
        if ((Theme.getActiveTheme()!=null && Theme.getActiveTheme().name.startsWith("Monet "))) {
            return trackAtTone;
        }
        return ColorUtils.blendARGB(trackAtTone(i, resourcesProvider, 90.0f, 22.0f), trackAtTone, isLightSurface(resourcesProvider) ? 0.55f : 0.8f);
    }

    public static int getSelectedThumbColor(int i, Theme.ResourcesProvider resourcesProvider) {
        if (isMaterial3SwitchStyle() && Build.VERSION.SDK_INT >= 31 && (Theme.getActiveTheme()!=null && Theme.getActiveTheme().name.startsWith("Monet "))) {
            return Theme.getColor(Theme.key_switchTrackBlueThumbChecked, resourcesProvider);
        }
        return Theme.getColor(i, resourcesProvider);
    }

    private static boolean isLightSurface(Theme.ResourcesProvider resourcesProvider) {
        int color = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        double[] dArr = colorLab;
        ColorUtils.colorToLAB(color, dArr);
        return dArr[0] > 50.0d;
    }

    private static int trackAtTone(int i, Theme.ResourcesProvider resourcesProvider, float f, float f2) {
        if (!isLightSurface(resourcesProvider)) {
            f = f2;
        }
        int color = Theme.getColor(i, resourcesProvider);
        double[] dArr = colorLab;
        ColorUtils.colorToLAB(color, dArr);
        double hypot = Math.hypot(dArr[1], dArr[2]);
        if (hypot > 32.0d) {
            double d = 32.0d / hypot;
            dArr[1] = dArr[1] * d;
            dArr[2] = dArr[2] * d;
        }
        return ColorUtils.LABToColor(f, dArr[1], dArr[2]);
    }

    public static float getThumbCenterX(int i, float f) {
        return (i / 2.0f) + (((f * 2.0f) - 1.0f) * scaled(10.0f));
    }

    public static void setThumbBounds(RectF rectF, float f, float f2, float f3, boolean z, float f4, float f5) {
        float lerp;
        float f6;
        float lerp2 = AndroidUtilities.lerp(8.0f, 12.0f, f4);
        float acos = (float) (Math.acos(1.0f - (Utilities.clamp01(f3) * 2.0f)) / 3.141592653589793d);
        if (!z) {
            acos = 1.0f - acos;
        }
        float f7 = z ? 0.6f : 0.4f;
        float f8 = z ? lerp2 : 12.0f;
        float f9 = z ? 12.0f : lerp2;
        if (acos < f7) {
            f6 = MATERIAL3_THUMB_INTERPOLATOR.getInterpolation(acos / f7);
            lerp = AndroidUtilities.lerp(f8, 11.0f, f6);
        } else {
            float interpolation = MATERIAL3_THUMB_INTERPOLATOR.getInterpolation((acos - f7) / (1.0f - f7));
            lerp = AndroidUtilities.lerp(11.0f, f9, interpolation);
            f6 = 1.0f - interpolation;
        }
        float f10 = f6 * 5.0f;
        if (f5 > 0.0f) {
            lerp = AndroidUtilities.lerp(lerp, 14.0f, f5);
            f10 *= 1.0f - f5;
        }
        float scaled = scaled(lerp);
        float scaled2 = scaled(f10);
        rectF.set((f - scaled2) - scaled, f2 - scaled, f + scaled2 + scaled, f2 + scaled);
    }

    public static float getThumbIconScale(int i, int i2) {
        return scaled(13.333333f) / Math.max(i, i2);
    }

    private static float iconGrid(float f) {
        return scaled((f * 16.0f) / 24.0f);
    }

    public static float getIconStrokeWidth() {
        return iconGrid(2.0f);
    }

    public static void setCheckIconLines(float[] fArr, float f, float f2, float f3) {
        float clamp01 = Utilities.clamp01(f3);
        for (int i = 0; i < fArr.length; i++) {
            fArr[i] = iconGrid(AndroidUtilities.lerp(MATERIAL3_ICON_CROSS[i], MATERIAL3_ICON_CHECK[i], clamp01) - 12.0f) + (i % 2 == 0 ? f : f2);
        }
    }

    public static float getClockHandLength(boolean z) {
        return iconGrid(z ? 8.5f : 6.8f);
    }
}
