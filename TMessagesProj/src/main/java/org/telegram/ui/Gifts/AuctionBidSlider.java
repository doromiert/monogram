// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Gifts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.AndroidUtilities.rectTmp;
import static org.telegram.messenger.LocaleController.getString;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import com.google.zxing.common.detector.MathUtils;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.Text;
import me.vkryl.android.animator.BoolAnimator;
import org.telegram.ui.Components.ReactionParticles;
import org.telegram.ui.Stars.StarsIntroActivity;

public class AuctionBidSlider extends View {

    private final Theme.ResourcesProvider resourcesProvider;

    private final Paint sliderInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sliderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sliderCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final ReactionParticles sliderParticles = new ReactionParticles(ReactionParticles.TYPE_RIGHT, 300);
    private final ReactionParticles textParticles = new ReactionParticles(ReactionParticles.TYPE_RADIAL_INSIDE, 30);

    private int gradientColor1 = 0xFFEEAC0D, gradientColor2 = 0xFFF9D316;
    private ValueAnimator gradientAnimator;
    private int toGradientColor1 = gradientColor1, toGradientColor2 = gradientColor2;
    private LinearGradient gradient = new LinearGradient(0, 0, 255, 0, new int[] {gradientColor1, gradientColor2}, new float[] {0, 1}, Shader.TileMode.CLAMP);
    private final Matrix gradientMatrix = new Matrix();

    public boolean drawCounterImage = true;
    private final Drawable counterImage;
    private final AnimatedTextView.AnimatedTextDrawable counterText = new AnimatedTextView.AnimatedTextDrawable(false, true, true);
    private final AnimatedTextView.AnimatedTextDrawable counterSubText = new AnimatedTextView.AnimatedTextDrawable(false, true, true);

    private final ColoredImageSpan[] starRef = new ColoredImageSpan[1];

    private final Paint topPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Text topText = new Text(getString(R.string.StarsReactionTop), 14, AndroidUtilities.getTypeface("fonts/rcondensedbold.ttf"));
    private final AnimatedFloat overTop = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat overTopText = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);

    public boolean drawPlus;

    public AuctionBidSlider(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        counterImage = context.getResources().getDrawable(R.drawable.msg_premium_liststar).mutate();
        counterImage.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));

        counterText.setTextColor(0xFFFFFFFF);
        counterText.setTypeface(AndroidUtilities.getTypeface("fonts/num.otf"));
        counterText.setTextSize(AndroidUtilities.dp(21));
        counterText.setCallback(this);
        counterText.setOverrideFullWidth(AndroidUtilities.displaySize.x);
        counterText.setGravity(Gravity.CENTER);

        counterSubText.setTextColor(0xDDFFFFFF);
        counterSubText.setTextSize(AndroidUtilities.dp(11));
        counterSubText.setCallback(this);
        counterSubText.setOverrideFullWidth(AndroidUtilities.displaySize.x);
        counterSubText.setGravity(Gravity.CENTER);

        topPaint.setColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        topPaint.setStyle(Paint.Style.STROKE);
        topPaint.setStrokeWidth(dp(1));
    }

    private long currentTop = -1;

    public void setStarsTop(long top) {
        currentTop = top;
        invalidate();
    }

    public void setTopText(String text) {
        topText.setText(text);
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == counterText || super.verifyDrawable(who);
    }

    private final RectF sliderInnerRect = new RectF();
    private final RectF sliderRect = new RectF();
    private final RectF sliderCircleRect = new RectF();
    private final RectF arc = new RectF();

    private final Path sliderInnerPath = new Path();
    private final Path sliderPath = new Path();

    private final Path plusPath = new Path();

    private final RectF textRect = new RectF();
    private final Path textPath = new Path();

    public float progress = 0;
    public float aprogress;
    public int steps;
    public int[] stops;

    public void setSteps(int steps, int... stops) {
        this.steps = steps;
        this.stops = stops;
    }

    public void setValue(int value) {
        setValue(value, false);
    }
    public void setValue(int value, boolean byScroll) {
        this.progress = getProgress(value);
        if (!byScroll) {
            this.aprogress = this.progress;
        }
        updateText(true);
    }

    public int getValue() {
        return getValue(progress);
    }

    public float getProgress() {
        return progress;
    }

    public int getValue(float progress) {
        if (progress <= 0f) return stops[0];
        if (progress >= 1f) return stops[stops.length - 1];
        float scaledProgress = progress * (stops.length - 1);
        int index = (int) scaledProgress;
        float localProgress = scaledProgress - index;
        return Math.round(stops[index] + localProgress * (stops[index + 1 >= stops.length ? index : index + 1] - stops[index]));
    }

    public float getProgress(int value) {
        for (int i = 1; i < stops.length; ++i) {
            if (value <= stops[i]) {
                float local = (float) (value - stops[i - 1]) / (stops[i] - stops[i - 1]);
                return (i - 1 + local) / (stops.length - 1);
            }
        }
        return 1f;
    }

    public void setColor(int color1, int color2, boolean animated) {
        if (toGradientColor1 == color1 && toGradientColor2 == color2)
            return;

        if (gradientAnimator != null) {
            gradientAnimator.cancel();
            gradientAnimator = null;
        }

        if (animated) {
            final int fromColor1 = gradientColor1;
            final int fromColor2 = gradientColor2;
            toGradientColor1 = color1;
            toGradientColor2 = color2;
            gradientAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            gradientAnimator.addUpdateListener(a -> {
                final float t = (float) a.getAnimatedValue();
                gradientColor1 = ColorUtils.blendARGB(fromColor1, color1, t);
                gradientColor2 = ColorUtils.blendARGB(fromColor2, color2, t);
                gradient = new LinearGradient(0, 0, 255, 0, new int[] {gradientColor1, gradientColor2}, new float[] {0, 1}, Shader.TileMode.CLAMP);
                invalidate();
            });
            gradientAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    final float t = 1.0F;
                    gradientColor1 = ColorUtils.blendARGB(fromColor1, color1, t);
                    gradientColor2 = ColorUtils.blendARGB(fromColor2, color2, t);
                    gradient = new LinearGradient(0, 0, 255, 0, new int[] {gradientColor1, gradientColor2}, new float[] {0, 1}, Shader.TileMode.CLAMP);
                    invalidate();
                }
            });
            gradientAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            gradientAnimator.setDuration(420);
            gradientAnimator.start();
        } else {
            gradientColor1 = toGradientColor1 = color1;
            gradientColor2 = toGradientColor2 = color2;
            gradient = new LinearGradient(0, 0, 255, 0, new int[] {gradientColor1, gradientColor2}, new float[] {0, 1}, Shader.TileMode.CLAMP);
            invalidate();
        }
    }

    public void updateText(boolean animated) {
        counterText.cancelAnimation();
        counterText.setText(StarsIntroActivity.replaceStars(LocaleController.formatNumber(getValue(), ','), starRef), animated);
    }

    protected void onValueChanged(int value) {}

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(220));
        final int w = getMeasuredWidth();
        final int h = getMeasuredHeight();

        final int pad = dp(14);
        final int top = dp(135);

        sliderInnerRect.set(pad, top, w - pad, top + dp(24));

        sliderPaint.setColor(0xFFEFAD0D);
        sliderCirclePaint.setColor(0xFFFFFFFF);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        gradientMatrix.reset();
        gradientMatrix.postTranslate(sliderInnerRect.left, 0);
        gradientMatrix.postScale(sliderInnerRect.width() / 255f, 1f);
        gradient.setLocalMatrix(gradientMatrix);
        sliderPaint.setShader(gradient);

        final int particlesColor = ColorUtils.blendARGB(gradientColor1, gradientColor2, progress);

        sliderInnerPath.rewind();
        sliderInnerPath.addRoundRect(sliderInnerRect, dp(12), dp(12), Path.Direction.CW);
        sliderInnerPaint.setColor(Theme.multAlpha(gradientColor1, 0.15f));
        canvas.drawPath(sliderInnerPath, sliderInnerPaint);

        sliderRect.set(sliderInnerRect);
        final float roundedValue = getProgress(getValue());
        sliderRect.right = lerp(sliderRect.left + dp(24), sliderRect.right, roundedValue);

        sliderPath.rewind();
        sliderPath.addRoundRect(sliderRect, dp(12), dp(12), Path.Direction.CW);

        sliderParticles.setBounds(sliderInnerRect);
        sliderParticles.setSpeed(1f + progress * 15f);
        sliderParticles.setVisible(.15f + .85f * progress);
        sliderParticles.process();
        canvas.save();
        canvas.clipPath(sliderInnerPath);
        sliderParticles.draw(canvas, particlesColor);
        if (currentTop != -1 && getProgress((int) currentTop) < 1f && getProgress((int) currentTop) > 0) {
            final float topX = sliderInnerRect.left + dp(12) + (sliderInnerRect.width() - dp(24)) * Utilities.clamp01(getProgress((int) currentTop));
            final float isOverTop = overTop.set(Math.abs(sliderRect.right - dp(10) - topX) < dp(14));
            final float textPad = lerp(dp(9), dp(16), overTopText.set(Math.abs(sliderRect.right - dp(10) - topX) < dp(12)));
            final float topTextX = topX + topText.getCurrentWidth() + 2 * dp(16) > sliderInnerRect.right ? topX - textPad - topText.getCurrentWidth() : topX + textPad;
            topPaint.setStrokeWidth(dp(1));
            topPaint.setColor(Theme.multAlpha(particlesColor, .6f));
            canvas.drawLine(topX, lerp(sliderInnerRect.top, sliderInnerRect.centerY(), isOverTop), topX, lerp(sliderInnerRect.bottom, sliderInnerRect.centerY(), isOverTop), topPaint);
            topText.draw(canvas, topTextX, sliderInnerRect.centerY(), particlesColor, .6f);
        }
        canvas.drawPath(sliderPath, sliderPaint);
        canvas.clipPath(sliderPath);
        sliderParticles.draw(canvas, Color.WHITE);
        if (currentTop != -1 && getProgress((int) currentTop) < 1f && getProgress((int) currentTop) > 0) {
            final float topX = sliderInnerRect.left + dp(12) + (sliderInnerRect.width() - dp(24)) * Utilities.clamp01(getProgress((int) currentTop));
            final float isOverTop = overTop.set(Math.abs(sliderRect.right - dp(10) - topX) < dp(14));
            final float textPad = lerp(dp(9), dp(16), overTopText.set(Math.abs(sliderRect.right - dp(10) - topX) < dp(12)));
            final float topTextX = topX + topText.getCurrentWidth() + 2 * dp(16) > sliderInnerRect.right ? topX - textPad - topText.getCurrentWidth() : topX + textPad;
            topPaint.setStrokeWidth(dp(1));
            topPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogBackground, resourcesProvider), .4f));
            canvas.drawLine(topX, lerp(sliderInnerRect.top, sliderInnerRect.centerY(), isOverTop), topX, lerp(sliderInnerRect.bottom, sliderInnerRect.centerY(), isOverTop), topPaint);
            topText.draw(canvas, topTextX, sliderInnerRect.centerY(), Color.WHITE, .75f);
        }
        canvas.restore();
        invalidate();

        if (drawPlus) {
            final float cx = sliderInnerRect.right - sliderInnerRect.height() / 2;
            final float cy = sliderInnerRect.centerY();

            plusPaint.setColor(ColorUtils.blendARGB(sliderInnerPaint.getColor(), gradientColor2, 0.5f));

            plusPath.rewind();
            plusPath.addRoundRect(cx - dp(1), cy - dp(6), cx + dp(1), cy + dp(6), dp(1), dp(1), Path.Direction.CW);
            plusPath.addRoundRect(cx - dp(6), cy - dp(1), cx + dp(6), cy + dp(1), dp(1), dp(1), Path.Direction.CW);

            canvas.drawPath(plusPath, plusPaint);
        }

        sliderCircleRect.set(
                sliderRect.right - dp(16) - dp(16 - 12),
                (sliderRect.centerY() - dp(16) / 2f),
                sliderRect.right - dp(16 - 12),
                (sliderRect.centerY() + dp(16) / 2f)
        );
        canvas.drawRoundRect(sliderCircleRect, dp(12), dp(12), sliderCirclePaint);

        final float slide = dp(9) / sliderInnerRect.width();
        final float pointerX = lerp(
            lerp(sliderCircleRect.left, sliderCircleRect.right, roundedValue),
            lerp(sliderCircleRect.left + dp(9), sliderCircleRect.right - dp(9), roundedValue),
            Math.min(Utilities.clamp01(roundedValue / slide), Utilities.clamp01((1f - roundedValue) / slide))
        ); // slide < dp(12) ? sliderInnerRect.left + dp(12) : slide > (sliderInnerRect.width() - dp(12)) ? sliderInnerRect.right - dp(12) : sliderCircleRect.centerX();
        final float textWidth = Math.max(counterSubText.getCurrentWidth() + dp(20), counterText.getCurrentWidth() + dp(24 + 26));
        final float textHeight = dp(44);
        final float left = Utilities.clamp(pointerX - textWidth / 2f, sliderInnerRect.right - textWidth - dp(4), sliderInnerRect.left + dp(4));
        textRect.set(left, sliderInnerRect.top - dp(21) - textHeight, left + textWidth, sliderInnerRect.top - dp(21));

        float d = textRect.height(), r = d / 2f;

        final float px = Utilities.clamp(pointerX, textRect.right, textRect.left);
        final float lpx = Utilities.clamp(px - dp(9), textRect.right, textRect.left);
        final float rpx = Utilities.clamp(px + dp(9), textRect.right, textRect.left);


        final float rotate = Utilities.clamp(progress - aprogress, 1, -1) * 60;
        final float rotateCx = px, rotateCy = textRect.bottom + dp(8);

        textPath.rewind();
        arc.set(textRect.left, textRect.top, textRect.left + d, textRect.top + d);
        textPath.arcTo(arc, -180, 90);
        arc.set(textRect.right - d, textRect.top, textRect.right, textRect.top + d);
        textPath.arcTo(arc, -90, 90);
        arc.set(textRect.right - d, textRect.bottom - d, textRect.right, textRect.bottom);
        float rr = Utilities.clamp01((rpx - arc.centerX()) / r);
        textPath.arcTo(arc, 0, (float) Utilities.clamp(.85f * Math.acos(rr) / Math.PI * 180, 90, 0));
        if (lpx < textRect.right - d * .7f) {
            textPath.lineTo(rpx, textRect.bottom);
            textPath.lineTo(px + 2, textRect.bottom + dp(8));
        }
        textPath.lineTo(px, textRect.bottom + dp(8) + 1);
        if (rpx > textRect.left + d * .7f) {
            textPath.lineTo(px - 2, textRect.bottom + dp(8));
            textPath.lineTo(lpx, textRect.bottom);
        }
        arc.set(textRect.left, textRect.bottom - d, textRect.left + d, textRect.bottom);
        float lr = Utilities.clamp01((lpx - arc.left) / r);
        float a = 90 + (float) Utilities.clamp(.85f * Math.acos(lr) / Math.PI * 180, 90, 0);
        textPath.arcTo(arc, a, 180 - a);
        textPath.lineTo(textRect.left, textRect.bottom);

        textPath.close();

        AndroidUtilities.rectTmp.set(textRect);
        AndroidUtilities.rectTmp.inset(-dp(12), -dp(12));
        textParticles.setBounds(AndroidUtilities.rectTmp);
        textParticles.setSpeed(1f + progress * 15f);
        textParticles.process();
        canvas.save();
//            canvas.translate(textRect.centerX(), textRect.centerY());
        textParticles.draw(canvas, particlesColor);
        canvas.restore();

        canvas.save();
        canvas.rotate(rotate, rotateCx, rotateCy);
        if (Math.abs(progress - aprogress) > .001f) {
            aprogress = AndroidUtilities.lerp(aprogress, progress, .1f);
            invalidate();
        }

        textBackgroundPaint.setShader(gradient);
        canvas.drawPath(textPath, textBackgroundPaint);

        canvas.save();
        canvas.clipPath(textPath);
        canvas.rotate(-rotate, rotateCx, rotateCy);
//            canvas.translate(textRect.centerX(), textRect.centerY());
        textParticles.draw(canvas, Color.WHITE);
        canvas.restore();

        canvas.save();
        final float s = 1f - subTextVisible.getFloatValue() * 0.15f;
        canvas.scale(s, s, textRect.centerX(), textRect.top - textRect.height() * 0.5f);
        counterImage.setBounds(
            (int) (textRect.centerX() - counterText.getCurrentWidth() / 2 + dp(8 - 20)),
            (int) (textRect.centerY() - dp(10)),
            (int) (textRect.centerX() - counterText.getCurrentWidth() / 2 + dp(8)),
            (int) (textRect.centerY() + dp(10))
        );
        if (drawCounterImage) {
            counterImage.draw(canvas);
        }
        counterText.setBounds(textRect.left + dp(24), textRect.top, textRect.right, textRect.bottom);
        counterText.draw(canvas);
        canvas.restore();

        counterSubText.setBounds(textRect.left, textRect.top + dp(10f), textRect.right, textRect.bottom + dp(10f));
        counterSubText.setAlpha((int)(subTextVisible.getFloatValue() * 255));
        counterSubText.draw(canvas);

        canvas.restore();

    }

    private final BoolAnimator subTextVisible = new BoolAnimator(this, CubicBezierInterpolator.EASE_OUT_QUINT, 320L);

    public void setCounterSubText(@Nullable String text, boolean animated) {
        subTextVisible.setValue(!TextUtils.isEmpty(text), animated);
        counterSubText.cancelAnimation();
        counterSubText.setText(text, animated);
    }

    private float lastX, lastY;
    private long pressTime;
    private int pointerId;
    private boolean tracking;

    public boolean isTracking() {
        return tracking;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            lastX = event.getX();
            lastY = event.getY();
            pointerId = event.getPointerId(0);
            pressTime = System.currentTimeMillis();
            tracking = false;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE && event.getPointerId(0) == pointerId) {
            final float dx = event.getX() - lastX;
            final float dy = event.getY() - lastY;
            if (!tracking && Math.abs(dx) > Math.abs(1.5f * dy) && Math.abs(dx) > AndroidUtilities.touchSlop) {
                getParent().requestDisallowInterceptTouchEvent(true);
                tracking = true;
                if (progressAnimator != null) {
                    progressAnimator.cancel();
                }
            }
            if (tracking) {
                final int pastValue = getValue();
                progress = Utilities.clamp01(progress + dx / (1f * getWidth()));
                if (getValue() != pastValue) {
                    onValueChanged(getValue());
                    updateText(true);
                }
                lastX = event.getX();
            }
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (!tracking && event.getPointerId(0) == pointerId && MathUtils.distance(lastX, lastY, event.getX(), event.getY()) < AndroidUtilities.touchSlop && System.currentTimeMillis() - pressTime <= ViewConfiguration.getTapTimeout() * 1.5f) {
                if (!onTapCustom(event.getX(), event.getY())) {
                    // tap
                    float newProgress = Utilities.clamp01((event.getX() - sliderInnerRect.left) / (float) sliderInnerRect.width());
                    if (currentTop > 0 && Math.abs(getProgress((int) currentTop) - newProgress) < 0.035f) {
                        newProgress = Utilities.clamp01(getProgress((int) currentTop));
                    }
                    animateProgressTo(newProgress);
                }
            }
            tracking = false;
        }
        return true;
    }

    protected boolean onTapCustom(float x, float y) {
        return false;
    }

    public void setValueAnimated(int value) {
        if (value == getValue()) {
            return;
        }
        animateProgressTo(getProgress(value));
    }

    private ValueAnimator progressAnimator;
    private void animateProgressTo(float toProgress) {
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        progressAnimator = ValueAnimator.ofFloat(progress, toProgress);
        progressAnimator.addUpdateListener(anm -> {
            progress = (float) anm.getAnimatedValue();
            invalidate();
        });
        final int pastValue = getValue();
        progressAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                progress = toProgress;
                if (getValue() != pastValue) {
                    onValueChanged(getValue());
                }
                invalidate();
            }
        });
        progressAnimator.setDuration(320);
        progressAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        progressAnimator.start();

        if (getValue(toProgress) != pastValue) {
            onValueChanged(getValue(toProgress));
        }

        counterText.cancelAnimation();
        counterText.setText(StarsIntroActivity.replaceStars(LocaleController.formatNumber(getValue(toProgress), ','), starRef), true);
    }
}
