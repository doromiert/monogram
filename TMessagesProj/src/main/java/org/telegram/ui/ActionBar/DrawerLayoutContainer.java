/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

public class DrawerLayoutContainer extends FrameLayout {

    private INavigationLayout parentActionBarLayout;
    private ActionBarLayout actionBarLayout;
    private boolean inLayout;
    private org.telegram.ui.MonogramDrawer navigationDrawer;

    private org.telegram.ui.DialogsActivity navigationDrawerHost() {
        if (parentActionBarLayout == null || parentActionBarLayout.checkTransitionAnimation()
                || parentActionBarLayout.isInPreviewMode()) return null;
        BaseFragment top = parentActionBarLayout.getLastFragment();
        if (top instanceof org.telegram.ui.MainTabsActivity) top = ((org.telegram.ui.MainTabsActivity) top).getCurrentVisibleFragment();
        return top instanceof org.telegram.ui.DialogsActivity && ((org.telegram.ui.DialogsActivity) top).canOpenNavigationDrawer()
                ? (org.telegram.ui.DialogsActivity) top : null;
    }

    private void ensureNavigationDrawer() {
        if (navigationDrawer == null) {
            navigationDrawer = new org.telegram.ui.MonogramDrawer(this);
            addView(navigationDrawer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    public void setNavigationDrawerOffset(float offset) {
        if (parentActionBarLayout == null) return;
        View target = parentActionBarLayout.getView();
        if (target == null) return;
        while (target.getParent() instanceof View && target.getParent() != this) target = (View) target.getParent();
        if (target.getParent() == this) target.setTranslationX(offset);
    }

    public void openNavigationDrawer(org.telegram.ui.DialogsActivity host) {
        if (navigationDrawerHost() != host) return;
        ensureNavigationDrawer();
        navigationDrawer.open(host);
    }

    public boolean closeNavigationDrawer(BaseFragment host, boolean close) {
        if (navigationDrawer == null || !navigationDrawer.belongsTo(host)) return false;
        if (close) navigationDrawer.close(true);
        return true;
    }

    @Override public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // Keep observing a possible edge drag above RecyclerView and the main-tab ViewPager.
        // Vertical movement relinquishes this reservation immediately.
        if (disallowIntercept && navigationDrawer != null && navigationDrawer.observingEdge()) return;
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }


    public DrawerLayoutContainer(Context context) {
        super(context);

        ViewCompat.setOnApplyWindowInsetsListener(this, this::onApplyWindowInsets);
        setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    public void setParentActionBarLayout(INavigationLayout layout) {
        parentActionBarLayout = layout;
    }

    public void setActionBarLayout(ActionBarLayout actionBarLayout) {
        this.actionBarLayout = actionBarLayout;
    }

    public boolean isDrawCurrentPreviewFragmentAbove() {
        return false;
    }

    public boolean onTouchEvent(MotionEvent ev) {
        return navigationDrawer != null && navigationDrawer.touch(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        org.telegram.ui.DialogsActivity host = navigationDrawerHost();
        if (navigationDrawer == null && ev.getActionMasked() == MotionEvent.ACTION_DOWN && host != null) ensureNavigationDrawer();
        if (navigationDrawer != null && navigationDrawer.intercept(ev, host)) return true;
        return parentActionBarLayout != null && parentActionBarLayout.checkTransitionAnimation();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        inLayout = true;
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);

            if (child.getVisibility() == GONE) {
                continue;
            }

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            try {
                child.layout(lp.leftMargin, lp.topMargin + getPaddingTop(), lp.leftMargin + child.getMeasuredWidth(), lp.topMargin + child.getMeasuredHeight() + getPaddingTop());
            } catch (Exception e) {
                FileLog.e(e);
                if (BuildVars.DEBUG_VERSION) {
                    throw e;
                }
            }
        }
        inLayout = false;
    }

    @Override
    public void requestLayout() {
        if (!inLayout) {
            super.requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        setMeasuredDimension(widthSize, heightSize);

        final int newDisplayWidth = widthSize
            - systemAndCutoutInsets.left
            - systemAndCutoutInsets.right;

        final int newDisplayHeight = heightSize
            - systemAndCutoutInsets.top
            - systemAndCutoutInsets.bottom;

        AndroidUtilities.displaySize.x = newDisplayWidth;
        AndroidUtilities.displaySize.y = newDisplayHeight;

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);

            if (child.getVisibility() == GONE) {
                continue;
            }

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            final int contentWidthSpec = MeasureSpec.makeMeasureSpec(widthSize - lp.leftMargin - lp.rightMargin, MeasureSpec.EXACTLY);
            final int contentHeightSpec;
            if (lp.height > 0) {
                contentHeightSpec = MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY);
            } else {
                contentHeightSpec = MeasureSpec.makeMeasureSpec(heightSize - lp.topMargin - lp.bottomMargin, MeasureSpec.EXACTLY);
            }
            if (child instanceof ActionBarLayout) {
                ActionBarLayout actionBarLayout = (ActionBarLayout) child;
                //fix keyboard measuring
                if (actionBarLayout.storyViewerAttached()) {
                    child.forceLayout();
                }
            }
            child.measure(contentWidthSpec, contentHeightSpec);
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (actionBarLayout != null && actionBarLayout.getParent() == this) {
            actionBarLayout.parentDraw(this, canvas);
        }

        super.dispatchDraw(canvas);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private final Paint internalNavbarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Paint getInternalNavbarPaint() {
        return internalNavbarPaint;
    }

    public void setInternalNavigationBarColor(int color) {
        if (internalNavbarPaint.getColor() != color) {
            internalNavbarPaint.setColor(color);
            invalidate();

            for (int a = 0, N = getChildCount(); a < N; a++) {
                getChildAt(a).invalidate();
            }
        }
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        if (lastWindowInsetsCompat != null) {
            dispatchApplyWindowInsetsInternal(child, lastWindowInsetsCompat);
        }
    }

    private @Nullable WindowInsetsCompat lastWindowInsetsCompat;
    private @NonNull Insets systemAndCutoutInsets = Insets.NONE;
    private @NonNull Insets systemAndCutoutAndImeInsets = Insets.NONE;

    private void dispatchApplyWindowInsetsInternal(View child, WindowInsetsCompat insets) {
        boolean canApplyInsets = child instanceof ActionBarLayout || child.getTag() == null;
        if (canApplyInsets) {
            ViewCompat.dispatchApplyWindowInsets(child, insets);
        }
    }

    @NonNull
    private WindowInsetsCompat onApplyWindowInsets(@NonNull View ignoredV, @NonNull WindowInsetsCompat insets) {
        lastWindowInsetsCompat = insets;

        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);
        final Insets systemAndImeInsets = AndroidUtilities.getDefaultWindowInsets(insets, true);

        if (!systemAndCutoutInsets.equals(systemInsets) || !systemAndCutoutAndImeInsets.equals(systemAndImeInsets)) {
            AndroidUtilities.statusBarHeight = systemInsets.top;
            AndroidUtilities.navigationBarHeight = systemInsets.bottom;

            systemAndCutoutInsets = systemInsets;
            systemAndCutoutAndImeInsets = systemAndImeInsets;
            requestLayout();
        }

        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            dispatchApplyWindowInsetsInternal(child, insets);
        }

        invalidate();
        return WindowInsetsCompat.CONSUMED;
    }
}
