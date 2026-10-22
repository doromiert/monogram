package org.telegram.ui.Components.blur3;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

public class BlurredBackgroundWithFadeDrawable extends Drawable {
    private final Paint maskFadeGradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BlurredBackgroundDrawable drawable;

    private boolean overrideFadeEnabled;
    private int overrideFadeColor,lastOverrideFadeColor,lastOverrideFadeHeight;
    private Shader overrideFadeShader,overrideMaskShader,overrideComposeShader;
    private final Paint overrideFadePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    public void setOverrideFadeColor(int color) {
        if(overrideFadeColor!=color) {overrideFadeColor=color;overrideFadeShader=null;}
        overrideFadeEnabled=true;invalidateSelf();
    }
    private final Matrix matrix = new Matrix();
    private Shader shader;

    private final Matrix matrixTmp = new Matrix();

    private Shader gradientShader;
    private BitmapShader bitmapShader;
    private Shader composeShader;
    private final Matrix bitmapMatrix = new Matrix();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap lastBitmap;

    private int fadeHeight;
    private boolean opacity;

    public BlurredBackgroundWithFadeDrawable(BlurredBackgroundDrawable drawable) {
        this.drawable = drawable;
        maskFadeGradientPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        bitmapPaint.setFilterBitmap(true);
        setFadeHeight(dp(40), false);
    }

    public void setFadeHeight(int fadeHeight, boolean opacity) {
        if (this.fadeHeight == fadeHeight && this.opacity == opacity) {
            return;
        }

        this.fadeHeight = fadeHeight;
        this.opacity = opacity;
        maskFadeGradientPaint.setShader(shader = createGradient(Color.BLACK, opacity));
        colorStaticPaint.setShader(null);

        matrix.reset();
        matrix.setScale(1, fadeHeight);
        if (fadeHeight < 0) {
            matrix.postTranslate(0, -fadeHeight);
        }
        shader.setLocalMatrix(matrix);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        drawable.setBounds(bounds);
    }

    private final Paint colorStaticPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int colorStaticLast;
    private boolean ignoreFastWay;

    public void setIgnoreFastWay(boolean ignoreFastWay) {
        this.ignoreFastWay = ignoreFastWay;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty() || alpha == 0) {
            return;
        }

        if(overrideFadeEnabled) {drawOverrideFade(canvas,bounds);return;}
        BlurredBackgroundSource source = drawable.getUnwrappedSource();
        if (!ignoreFastWay && source instanceof BlurredBackgroundSourceColor) {
            // fast way - just draw gradient

            final int color = ((BlurredBackgroundSourceColor) source).getColor();
            if (colorStaticLast != color ||gradientShader == null) {
                gradientShader = createGradient(color, opacity);
                colorStaticLast = color;
                colorStaticPaint.setShader(gradientShader);
            }

            int offset = 0;
            if (fadeHeight < 0) {
                offset = bounds.height() + fadeHeight;
            }

            matrixTmp.set(matrix);
            matrixTmp.postTranslate(bounds.left, bounds.top + offset);
            gradientShader.setLocalMatrix(matrixTmp);

            colorStaticPaint.setAlpha(alpha);
            canvas.drawRect(bounds, colorStaticPaint);
            return;
        }

        if (!ignoreFastWay && source instanceof BlurredBackgroundSourceBitmap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // fast way - just draw gradient

            final BlurredBackgroundSourceBitmap s = (BlurredBackgroundSourceBitmap) source;
            final Bitmap bitmap = s.getBitmap();
            if (bitmap == null) {
                return;
            }

            final int color = Color.BLACK;
            boolean changed = false;
            if (colorStaticLast != color || gradientShader == null) {
                gradientShader = createGradient(color, opacity);
                colorStaticLast = color;
                changed = true;
            }

            if (bitmapShader == null || lastBitmap != bitmap) {
                lastBitmap = bitmap;
                bitmapShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bitmapShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                }
                changed = true;
            }

            if (changed || composeShader == null) {
                composeShader = new ComposeShader(bitmapShader, gradientShader, PorterDuff.Mode.DST_IN);
                bitmapPaint.setShader(composeShader);
            }

            int offset = 0;
            if (fadeHeight < 0) {
                offset = bounds.height() + fadeHeight;
            }

            matrixTmp.set(matrix);
            matrixTmp.postTranslate(bounds.left, bounds.top + offset);
            gradientShader.setLocalMatrix(matrixTmp);

            bitmapMatrix.set(s.getMatrix());
            bitmapMatrix.postTranslate(-drawable.getSourceOffsetX(), -drawable.getSourceOffsetY());
            bitmapShader.setLocalMatrix(bitmapMatrix);

            bitmapPaint.setAlpha(alpha);
            canvas.drawRect(bounds, bitmapPaint);
            return;
        }

        //if (source instanceof BlurredBackgroundSourceRenderNode) {
        //    return;
        //}
        final int save = canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha);
        int offset = 0;
        if (fadeHeight < 0) {
            offset = bounds.height() + fadeHeight;
        }

        drawable.draw(canvas);
        canvas.translate(bounds.left, bounds.top + offset);
        canvas.drawRect(0, - offset, bounds.width(), bounds.height() - offset, maskFadeGradientPaint);
        canvas.restoreToCount(save);
    }

    private int alpha = 255;

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        //
    }

    @Override
    public int getOpacity() {
        return PixelFormat.UNKNOWN;
    }

    private static LinearGradient createGradient(int color, boolean opacity) {
        final int alpha = Color.alpha(color);

        if (opacity) {
            return new LinearGradient(0, 0, 0, 1, new int[]{
                ColorUtils.setAlphaComponent(color, 0),
                ColorUtils.setAlphaComponent(color, 0x60 * alpha / 285),
                ColorUtils.setAlphaComponent(color, 0xB0 * alpha / 285),
                ColorUtils.setAlphaComponent(color, 0xE8 * alpha / 285),
            }, null, Shader.TileMode.CLAMP);
        }

        return new LinearGradient(0, 0, 0, 1, new int[]{
            ColorUtils.setAlphaComponent(color, 0),
            ColorUtils.setAlphaComponent(color, 0x60 * alpha / 255),
            ColorUtils.setAlphaComponent(color, 0xB0 * alpha / 255),
            ColorUtils.setAlphaComponent(color, 0xE8 * alpha / 255),
            ColorUtils.setAlphaComponent(color, 0xFF * alpha / 255),
        }, null, Shader.TileMode.CLAMP);
    }




    public static void fillAlphaGradientColors(
        int[] outColors,
        int baseColor,
        int alphaStart,
        int alphaEnd,
        Interpolator interpolator
    ) {
        if (outColors == null || outColors.length == 0) {
            return;
        }

        final int count = outColors.length;
        if (count == 1) {
            outColors[0] = ColorUtils.setAlphaComponent(baseColor, (alphaStart + alphaEnd) / 2);
            return;
        }

        for (int i = 0; i < count; i++) {
            final float t = (float) i / (count - 1);
            final float it = interpolator != null ? interpolator.getInterpolation(t) : t;

            final int alpha = MathUtils.clamp(lerp(alphaStart, alphaEnd, it), 0, 255);

            outColors[i] = ColorUtils.setAlphaComponent(baseColor, alpha);
        }
    }
    private void drawOverrideFade(Canvas canvas, Rect rect) {
        Bitmap bitmap;
        int max = Math.max(1, rect.height());
        int i = this.lastOverrideFadeColor;
        int i2 = this.overrideFadeColor;
        if (i != i2 || this.lastOverrideFadeHeight != max || this.overrideFadeShader == null) {
            this.lastOverrideFadeColor = i2;
            this.lastOverrideFadeHeight = max;
            this.overrideFadeShader = createOverrideFadeGradient(i2, max, 1.0f);
            this.overrideMaskShader = createOverrideFadeGradient(-16777216, max, 0.75f);
            this.overrideComposeShader = null;
            this.overrideFadePaint.setShader(this.overrideFadeShader);
        }
        this.matrixTmp.reset();
        this.matrixTmp.postTranslate(rect.left, rect.top);
        BlurredBackgroundSource unwrappedSource = this.drawable.getUnwrappedSource();
        if (!this.ignoreFastWay && (unwrappedSource instanceof BlurredBackgroundSourceBitmap)) {
            BlurredBackgroundSourceBitmap blurredBackgroundSourceBitmap = (BlurredBackgroundSourceBitmap)unwrappedSource;
            int i3 = Build.VERSION.SDK_INT;
            if (i3 >= 28 && (bitmap = blurredBackgroundSourceBitmap.getBitmap()) != null) {
                if (this.bitmapShader == null || this.lastBitmap != bitmap) {
                    this.lastBitmap = bitmap;
                    Shader.TileMode tileMode = Shader.TileMode.CLAMP;
                    BitmapShader bitmapShader = new BitmapShader(bitmap, tileMode, tileMode);
                    this.bitmapShader = bitmapShader;
                    if (i3 >= 33) {
                        bitmapShader.setFilterMode(2);
                    }
                    this.overrideComposeShader = null;
                }
                if (this.overrideComposeShader == null) {
                    ComposeShader composeShader = new ComposeShader(this.bitmapShader, this.overrideMaskShader, PorterDuff.Mode.DST_IN);
                    this.overrideComposeShader = composeShader;
                    this.bitmapPaint.setShader(composeShader);
                }
                this.overrideMaskShader.setLocalMatrix(this.matrixTmp);
                this.bitmapMatrix.set(blurredBackgroundSourceBitmap.getMatrix());
                this.bitmapMatrix.postTranslate(-this.drawable.getSourceOffsetX(), -this.drawable.getSourceOffsetY());
                this.bitmapShader.setLocalMatrix(this.bitmapMatrix);
                this.bitmapPaint.setAlpha(this.alpha);
                canvas.drawRect(rect, this.bitmapPaint);
                this.overrideFadeShader.setLocalMatrix(this.matrixTmp);
                this.overrideFadePaint.setAlpha(Math.round(this.alpha * 0.8f));
                canvas.drawRect(rect, this.overrideFadePaint);
                return;
            }
        }
        this.overrideFadeShader.setLocalMatrix(this.matrixTmp);
        this.overrideFadePaint.setAlpha(this.alpha);
        canvas.drawRect(rect, this.overrideFadePaint);
    }

    private static LinearGradient createOverrideFadeGradient(int i, int i2, float f) {
        boolean isCurrentThemeDark = Theme.isCurrentThemeDark();
        float[] fArr = {isCurrentThemeDark ? 0.95f : 1.0f, isCurrentThemeDark ? 0.9f : 0.93f, isCurrentThemeDark ? 0.78f : 0.83f, 0.62f, 0.4f, 0.18f, 0.05f, 0.0f};
        int[] iArr = new int[8];
        for (int i3 = 0; i3 < 8; i3++) {
            iArr[i3] = ColorUtils.setAlphaComponent(i, Math.round(fArr[i3] * 255.0f * f));
        }
        return new LinearGradient(0.0f, 0.0f, 0.0f, Math.max(1, i2), iArr, new float[]{0.0f, 0.5f, 0.65f, 0.75f, 0.84f, 0.92f, 0.97f, 1.0f}, Shader.TileMode.CLAMP);
    }
}
