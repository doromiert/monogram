/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Cells;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

public class HeaderCell extends FrameLayout {
    private boolean appliedSeparatedHeaders;
    private boolean sectionStyleInitialized;
    private int originalPadding, originalBottomMargin;
    private android.graphics.drawable.Drawable sectionBackground;

    @Override public void setBackground(android.graphics.drawable.Drawable background) {
        sectionBackground=background;
        super.setBackground(org.telegram.utils.settings.MonogramAppearance.separatedHeaders()?null:background);
    }
    @Override public void setBackgroundColor(int color) {
        setBackground(new android.graphics.drawable.ColorDrawable(color));
    }

    private void updateSectionStyle() {
        boolean separated=org.telegram.utils.settings.MonogramAppearance.separatedHeaders();
        if(sectionStyleInitialized && separated==appliedSeparatedHeaders) return;
        appliedSeparatedHeaders=separated;sectionStyleInitialized=true;
        padding=separated?24:originalPadding;
        bottomMargin=separated?3:originalBottomMargin;
        View primary=animated?animatedTextView:textView;
        if(primary!=null) {
            LayoutParams lp=(LayoutParams)primary.getLayoutParams();
            lp.leftMargin=lp.rightMargin=AndroidUtilities.dp(padding);
            lp.bottomMargin=textView2==null?AndroidUtilities.dp(bottomMargin):0;
            primary.setLayoutParams(lp);
        }
        if(textView2!=null) {
            LayoutParams lp=(LayoutParams)textView2.getLayoutParams();
            lp.leftMargin=lp.rightMargin=AndroidUtilities.dp(padding);
            lp.bottomMargin=AndroidUtilities.dp(bottomMargin);textView2.setLayoutParams(lp);
        }
        super.setBackground(separated?null:sectionBackground);
    }


    public int id;

    protected int padding;
    protected int bottomMargin;

    private TextView textView;
    private AnimatedTextView animatedTextView;
    private SimpleTextView textView2;
    private int height = 40;
    private final Theme.ResourcesProvider resourcesProvider;
    private final boolean animated;

    public HeaderCell(Context context) {
        this(context, Theme.key_windowBackgroundWhiteBlueHeader, 20, 7, false, null);
    }

    public HeaderCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        this(context, Theme.key_windowBackgroundWhiteBlueHeader, 20, 7, false, resourcesProvider);
    }

    public HeaderCell(Context context, int padding) {
        this(context, Theme.key_windowBackgroundWhiteBlueHeader, padding, 7, false, null);
    }

    public HeaderCell(Context context, int padding, Theme.ResourcesProvider resourcesProvider) {
        this(context, Theme.key_windowBackgroundWhiteBlueHeader, padding, 7, false, resourcesProvider);
    }

    public HeaderCell(Context context, int textColorKey, int padding, int topMargin, boolean text2) {
        this(context, textColorKey, padding, topMargin, text2, null);
    }

    public HeaderCell(Context context, int textColorKey, int padding, int topMargin, boolean text2, Theme.ResourcesProvider resourcesProvider) {
        this(context, textColorKey, padding, topMargin, 0, text2, resourcesProvider);
    }

    public HeaderCell(Context context, int textColorKey, int padding, int topMargin, int bottomMargin, boolean text2, Theme.ResourcesProvider resourcesProvider) {
        this(context, textColorKey, padding, topMargin, bottomMargin, text2, false, resourcesProvider);
    }

    public HeaderCell(Context context, int textColorKey, int padding, int topMargin, int bottomMargin, boolean text2, boolean animated, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        originalPadding=padding;originalBottomMargin=bottomMargin;
        if(org.telegram.utils.settings.MonogramAppearance.separatedHeaders()) { padding=24;bottomMargin=3; }
        this.padding = padding;
        this.bottomMargin = bottomMargin;
        this.animated = animated;

        if (animated) {
            animatedTextView = new AnimatedTextView(getContext());
            animatedTextView.setTextSize(AndroidUtilities.dp(14));
            animatedTextView.setTypeface(AndroidUtilities.bold());
            animatedTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            animatedTextView.setTextColor(getThemedColor(textColorKey));
            animatedTextView.setTag(textColorKey);
            animatedTextView.getDrawable().setHacks(true, true, false);
            addView(animatedTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, height - topMargin, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, padding, topMargin, padding, text2 ? 0 : bottomMargin));
        } else {
            textView = new TextView(getContext());
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            textView.setMinHeight(AndroidUtilities.dp(height - topMargin));
            textView.setTextColor(getThemedColor(textColorKey));
            textView.setTag(textColorKey);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, padding, topMargin, padding, text2 ? 0 : bottomMargin));
        }

        if (text2) {
            textView2 = new SimpleTextView(getContext());
            textView2.setTextSize(13);
            textView2.setGravity((LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP);
            addView(textView2, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, padding, 21, padding, bottomMargin));
        }

        ViewCompat.setAccessibilityHeading(this, true);
    }

    public void setOnWidthUpdateListener(Runnable listener) {
        animatedTextView.setOnWidthUpdatedListener(listener);
    }

    public float getAnimatedWidth() {
        return animatedTextView.getDrawable().getCurrentWidth();
    }

    public void setHeight(int value) {
        if(org.telegram.utils.settings.MonogramAppearance.separatedHeaders()) return;
        int newMinHeight = AndroidUtilities.dp(height = value) - ((LayoutParams) textView.getLayoutParams()).topMargin;
        if (textView.getMinHeight() != newMinHeight) {
            textView.setMinHeight(newMinHeight);
            requestLayout();
        }
    }

    public void setTopMargin(int topMargin) {
        if(org.telegram.utils.settings.MonogramAppearance.separatedHeaders()) return;
        ((LayoutParams) textView.getLayoutParams()).topMargin = AndroidUtilities.dp(topMargin);
        setHeight(height);
    }

    public void setBottomMargin(int bottomMargin) {
        if(org.telegram.utils.settings.MonogramAppearance.separatedHeaders()) return;
        originalBottomMargin=bottomMargin;
        ((LayoutParams) textView.getLayoutParams()).bottomMargin = AndroidUtilities.dp(bottomMargin);
        if (textView2 != null) {
            ((LayoutParams) textView2.getLayoutParams()).bottomMargin = AndroidUtilities.dp(bottomMargin);
        }
    }

    public void setEnabled(boolean value, boolean animated) {
        super.setEnabled(value);
        if (animated) {
            textView.animate().alpha(value ? 1.0f : 0.5f).start();
        } else {
            textView.setAlpha(value ? 1.0f : 0.5f);
        }
    }

    public void setEnabled(boolean value, ArrayList<Animator> animators) {
        if (animators != null) {
            animators.add(ObjectAnimator.ofFloat(textView, View.ALPHA, value ? 1.0f : 0.5f));
        } else {
            textView.setAlpha(value ? 1.0f : 0.5f);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        updateSectionStyle();
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    public void setTextSize(float dip) {
        if (animated) {
            animatedTextView.setTextSize(AndroidUtilities.dp(dip));
        } else {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dip);
        }
    }

    public void setTextColor(int color) {
        if (textView != null) {
            textView.setTextColor(color);
        }
        if (animatedTextView != null) {
            animatedTextView.setTextColor(color);
        }
    }

    public void setText(CharSequence text) {
        setText(text, false);
    }

    public void setText(CharSequence text, boolean animate) {
        updateSectionStyle();
        if (this.animated) {
            animatedTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            animatedTextView.setText(text, animate);
        } else {
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            textView.setText(text);
        }
    }

    public void setText2(CharSequence text) {
        if (textView2 == null) {
            return;
        }
        textView2.setText(text);
    }

    public TextView getTextView() {
        return textView;
    }

    public SimpleTextView getTextView2() {
        return textView2;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.setHeading(true);
        } else {
            AccessibilityNodeInfo.CollectionItemInfo collection = info.getCollectionItemInfo();
            if (collection != null) {
                info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(collection.getRowIndex(), collection.getRowSpan(), collection.getColumnIndex(), collection.getColumnSpan(), true));
            }
        }
        info.setEnabled(true);
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }
}
