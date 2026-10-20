// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

/** Compact message-menu action strip ported from Extera's shipped ActionRow. */
@SuppressLint("ViewConstructor")
public final class MonogramMessageActionRow extends FrameLayout {
    public static final class Item {
        public final int option;
        public final int icon;
        public final CharSequence label;
        public final boolean enabled;
        public final View.OnClickListener action;

        public Item(int option, int icon, CharSequence label, boolean enabled, View.OnClickListener action) {
            this.option = option;
            this.icon = icon;
            this.label = label;
            this.enabled = enabled;
            this.action = action;
        }
    }

    private final FrameLayout buttonsView;
    private final ArrayList<Item> displayedItems = new ArrayList<>(4);

    public MonogramMessageActionRow(Context context, Theme.ResourcesProvider resourcesProvider, List<Item> items) {
        super(context);
        buttonsView = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                final int count = getChildCount();
                final int available = (right - left) - AndroidUtilities.dp(count * 40 + 20);
                final int gap = available / Math.max(1, count - 1);
                for (int i = 0; i < count; i++) {
                    final int visualIndex = LocaleController.isRTL ? count - 1 - i : i;
                    final int childLeft = AndroidUtilities.dp(visualIndex * 40 + 10) + gap * visualIndex;
                    final int childTop = AndroidUtilities.dp(8);
                    final View child = getChildAt(i);
                    child.layout(childLeft, childTop, childLeft + child.getMeasuredWidth(), childTop + child.getMeasuredHeight());
                }
            }
        };
        addView(buttonsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateItems(items, resourcesProvider);
    }

    private void updateItems(List<Item> items, Theme.ResourcesProvider resourcesProvider) {
        buttonsView.removeAllViews();
        displayedItems.clear();
        ArrayList<Item> disabledItems = new ArrayList<>();
        for (Item item : items) {
            if (item.enabled && displayedItems.size() < 4) {
                addButton(item, displayedItems.size(), resourcesProvider);
            } else {
                disabledItems.add(item);
            }
        }
        for (Item item : disabledItems) {
            if (displayedItems.size() >= 4) {
                break;
            }
            addButton(item, displayedItems.size(), resourcesProvider);
        }
    }

    private void addButton(Item item, int index, Theme.ResourcesProvider resourcesProvider) {
        ImageView imageView = new ImageView(getContext()) {
            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                setAlpha(enabled ? 1f : .5f);
            }
        };
        ScaleStateListAnimator.apply(imageView, .15f, 1.5f);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setImageDrawable(ContextCompat.getDrawable(getContext(), item.icon).mutate());
        imageView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        imageView.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider), 1, AndroidUtilities.dp(20)));
        imageView.setContentDescription(item.label);
        imageView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        imageView.setOnClickListener(item.action);
        imageView.setEnabled(item.enabled);
        imageView.setAlpha(0f);
        imageView.setTranslationX(AndroidUtilities.dp(LocaleController.isRTL ? -12 : 12));
        buttonsView.addView(imageView, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.LEFT));
        displayedItems.add(item);
        imageView.post(() -> imageView.animate()
                .alpha(item.enabled ? 1f : .5f)
                .translationX(0f)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .setStartDelay(index * 35L + 100L)
                .setDuration(400L)
                .start());
    }

    public boolean containsOption(int option) {
        for (Item item : displayedItems) {
            if (item.option == option) {
                return true;
            }
        }
        return false;
    }
}
