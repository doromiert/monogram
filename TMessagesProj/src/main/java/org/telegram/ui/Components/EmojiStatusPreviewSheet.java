package org.telegram.ui.Components;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import org.telegram.ui.Components.Premium.StarParticlesView;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;


public class EmojiStatusPreviewSheet extends BottomSheetWithRecyclerListView {

    protected TLRPC.User user;
    StarParticlesView starParticlesView;
    ViewGroup iconContainer;
    BaseFragment fragment;

    public Integer accentColor;
    public float startEnterFromX;
    public float startEnterFromY;
    public float startEnterFromX1;
    public float startEnterFromY1;
    public float startEnterFromScale;
    public View startEnterFromView;
    public View overrideTitleIcon;

    int[] coords = new int[2];
    float enterTransitionProgress = 0;
    boolean enterTransitionInProgress;
    ValueAnimator enterAnimator;


    public EmojiStatusPreviewSheet(BaseFragment fragment, int currentAccount, TLRPC.User user, Theme.ResourcesProvider resourcesProvider) {
        super(fragment, false, false, false, resourcesProvider);
        fixNavigationBar();
        this.fragment = fragment;
        this.user = user;
        topPadding = 0.26f;
        recyclerListView.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
    }

    @Override
    protected void onPreMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onPreMeasure(widthMeasureSpec, heightMeasureSpec);
        container.getLocationOnScreen(coords);
    }

    private FrameLayout titleViewContainer;
    protected LinkSpanDrawable.LinksTextView titleView[];

    private void titleLoaded(CharSequence newText, boolean animated) {
        if (titleView == null) {
            return;
        }
        titleView[1].setText(newText);
        if (titleView[1].getVisibility() != View.VISIBLE) {
            if (animated) {
                titleView[1].setAlpha(0);
                titleView[1].setVisibility(View.VISIBLE);
                titleView[1].animate().alpha(1).setInterpolator(CubicBezierInterpolator.DEFAULT).setDuration(200).start();
                titleView[0].animate().alpha(0).setInterpolator(CubicBezierInterpolator.DEFAULT).setDuration(200).withEndAction(() -> {
                    titleView[0].setVisibility(View.GONE);
                }).start();

                ValueAnimator heightUpdate = ValueAnimator.ofFloat(0, 1);
                heightUpdate.addUpdateListener(anm -> {
                    float t = (float) anm.getAnimatedValue();
                    titleViewContainer.getLayoutParams().height = AndroidUtilities.lerp(titleView[0].getHeight(), titleView[1].getHeight(), t);
                    titleViewContainer.requestLayout();
                });
                heightUpdate.setInterpolator(CubicBezierInterpolator.DEFAULT);
                heightUpdate.setDuration(200);
                heightUpdate.start();
            } else {
                titleView[1].setAlpha(1);
                titleView[1].setVisibility(View.VISIBLE);
                titleView[0].setAlpha(0);
                titleView[0].setVisibility(View.GONE);
            }
        }
    }

    protected LinkSpanDrawable.LinksTextView subtitleView;

    public void setTitle(boolean animated) {
        if (titleView == null) return;
        String name = user == null ? "" : UserObject.getFirstName(user);
        titleLoaded(TextUtils.isEmpty(name) ? getString(R.string.MonogramEmojiStatus)
                : LocaleController.formatString(R.string.MonogramEmojiStatusFor, name), animated);
        subtitleView.setVisibility(View.GONE);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.MonogramEmojiStatus);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return new Adapter();
    }

    protected void attachIconContainer(LinearLayout container) {
        container.addView(overrideTitleIcon, LayoutHelper.createLinear(140, 140, Gravity.CENTER_HORIZONTAL, Gravity.CENTER, 10, 10, 10, 10));
    }



    private class Adapter extends RecyclerListView.SelectionAdapter {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                default:
                case 0:
                    LinearLayout linearLayout = new LinearLayout(context) {
                        @Override
                        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {

                            return super.drawChild(canvas, child, drawingTime);
                        }
                    };
                    iconContainer = linearLayout;
                    linearLayout.setOrientation(LinearLayout.VERTICAL);
                    if (overrideTitleIcon == null) {
                        android.widget.ImageView icon = new android.widget.ImageView(context);
                        icon.setImageResource(R.drawable.msg_emoji_smiles);
                        icon.setScaleType(android.widget.ImageView.ScaleType.CENTER);
                        overrideTitleIcon = icon;
                    }
                    if (overrideTitleIcon.getParent() != null) ((ViewGroup) overrideTitleIcon.getParent()).removeView(overrideTitleIcon);
                    attachIconContainer(linearLayout);


                    if (titleViewContainer == null) {
                        titleViewContainer = new FrameLayout(context);
                        titleViewContainer.setClipChildren(false);

                        final ColorFilter colorFilter = new PorterDuffColorFilter(accentColor == null ? getThemedColor(Theme.key_windowBackgroundWhiteBlueIcon) : accentColor, PorterDuff.Mode.SRC_IN);
                        titleView = new LinkSpanDrawable.LinksTextView[2];
                        for (int a = 0; a < 2; ++a) {
                            titleView[a] = new LinkSpanDrawable.LinksTextView(context, resourcesProvider) {
                                @Override
                                protected int emojiCacheType() {
                                    return AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW;
                                }
                            };
                            titleView[a].setVisibility(a == 0 ? View.VISIBLE : View.GONE);
                            titleView[a].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                            titleView[a].setTypeface(AndroidUtilities.bold());
                            titleView[a].setGravity(Gravity.CENTER_HORIZONTAL);
                            titleView[a].setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                            titleView[a].setLinkTextColor(getThemedColor(Theme.key_windowBackgroundWhiteLinkText));
                            titleViewContainer.addView(titleView[a], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                        }
                    }
                    if (titleViewContainer.getParent() != null) {
                        ((ViewGroup) titleViewContainer.getParent()).removeView(titleViewContainer);
                    }
                    linearLayout.addView(titleViewContainer, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.CENTER_HORIZONTAL, 40, 0, 40, 0));

                    if (subtitleView == null) {
                        subtitleView = new LinkSpanDrawable.LinksTextView(getContext(), resourcesProvider);
                        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
                        subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        subtitleView.setLinkTextColor(getThemedColor(Theme.key_windowBackgroundWhiteLinkText));
                    }
                    if (subtitleView.getParent() != null) {
                        ((ViewGroup) subtitleView.getParent()).removeView(subtitleView);
                    }
                    linearLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 24, 9, 24, 20));

                    setTitle(false);

                    starParticlesView = new StarParticlesView(context) {
                        @Override
                        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                            drawable.rect2.set(0, 0, getMeasuredWidth(), getMeasuredHeight() - AndroidUtilities.dp(52));
                        }

                        @Override
                        protected void configure() {
                            super.configure();
                            drawable.useGradient = true;
                            drawable.useBlur = false;
                            drawable.forceMaxAlpha = true;
                            drawable.checkBounds = true;
                            drawable.init();
                        }
                    };
                    FrameLayout frameLayout = new FrameLayout(context) {
                        @Override
                        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                            float y = 0;
                            if (overrideTitleIcon != null) {
                                y = overrideTitleIcon.getTop() + overrideTitleIcon.getMeasuredHeight() / 2f;
                            }
                            starParticlesView.setTranslationY(y - starParticlesView.getMeasuredHeight() / 2f);
                        }
                    };
                    frameLayout.setClipChildren(false);
                    frameLayout.addView(starParticlesView);
                    frameLayout.addView(linearLayout);



                    view = frameLayout;
                    break;

            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}

        @Override
        public int getItemCount() {
            return 1;
        }

        @Override
        public int getItemViewType(int position) { return 0; }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }
    }











    @Override
    public void show() {
        super.show();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 4);

    }

    @Override
    public void dismiss() {
        super.dismiss();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 4);
        if (enterAnimator != null) {
            enterAnimator.cancel();
        }


    }

    @Override
    protected void mainContainerDispatchDraw(Canvas canvas) {
        if (overrideTitleIcon != null) {
            overrideTitleIcon.setVisibility(enterTransitionInProgress ? View.INVISIBLE : View.VISIBLE);
        }
        super.mainContainerDispatchDraw(canvas);
        if (startEnterFromView != null && enterTransitionInProgress) {
            View titleIcon = overrideTitleIcon;
            if (titleIcon == overrideTitleIcon) {
                overrideTitleIcon.setVisibility(View.VISIBLE);
            }

            canvas.save();

            float[] points = new float[]{startEnterFromX, startEnterFromY};
            startEnterFromView.getMatrix().mapPoints(points);
            Drawable startEnterFromDrawable = null;
            if (startEnterFromView instanceof SimpleTextView) {
                startEnterFromDrawable = ((SimpleTextView) startEnterFromView).getRightDrawable();
            } else if (startEnterFromView instanceof ChatMessageCell) {
                startEnterFromDrawable = ((ChatMessageCell) startEnterFromView).currentNameStatusDrawable;
            }
            if (startEnterFromDrawable == null) {
                canvas.restore();
                return;
            }
            float cxFrom = -coords[0] + startEnterFromX1 + points[0];
            float cyFrom = -coords[1] + startEnterFromY1 + points[1];

            if (AndroidUtilities.isTablet()) {
                View v = fragment.getParentLayout().getView();
                cxFrom += v.getX() + v.getPaddingLeft();
                cyFrom += v.getY() + v.getPaddingTop();
            }

            float fromSize = startEnterFromScale * startEnterFromDrawable.getIntrinsicWidth();
            float toSize = titleIcon.getMeasuredHeight() * 0.8f;
            float toSclale = toSize / fromSize;
            float bigIconFromScale = fromSize / toSize;

            float cxTo = titleIcon.getMeasuredWidth() / 2f;
            View view = titleIcon;
            while (view != container) {
                if (view == null) {
                    break;
                }
                cxTo += view.getX();
                view = (View) view.getParent();
            }
            float cy = 0;
            float cyTo = cy + titleIcon.getY() + ((View) titleIcon.getParent()).getY() + ((View) titleIcon.getParent().getParent()).getY() + titleIcon.getMeasuredHeight() / 2f;

            float x = AndroidUtilities.lerp(cxFrom, cxTo, CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(enterTransitionProgress));
            float y = AndroidUtilities.lerp(cyFrom, cyTo, enterTransitionProgress);

            if (startEnterFromDrawable != null) {
                float s = startEnterFromScale * (1f - enterTransitionProgress) + toSclale * enterTransitionProgress;
                canvas.save();
                canvas.scale(s, s, x, y);
                startEnterFromDrawable.setBounds(
                        (int) x - startEnterFromDrawable.getIntrinsicWidth() / 2,
                        (int) y - startEnterFromDrawable.getIntrinsicHeight() / 2,
                        (int) x + startEnterFromDrawable.getIntrinsicWidth() / 2,
                        (int) y + startEnterFromDrawable.getIntrinsicHeight() / 2);
                startEnterFromDrawable.setAlpha((int) (255 * (1f - Utilities.clamp(enterTransitionProgress, 1, 0))));
                startEnterFromDrawable.draw(canvas);
                startEnterFromDrawable.setAlpha(0);
                canvas.restore();

                s = AndroidUtilities.lerp(bigIconFromScale, 1, enterTransitionProgress);
                canvas.scale(s, s, x, y);
                canvas.translate(x - titleIcon.getMeasuredWidth() / 2f, y - titleIcon.getMeasuredHeight() / 2f);
              //  canvas.saveLayerAlpha(0, 0, titleIcon.getMeasuredWidth(), titleIcon.getMeasuredHeight(), (int) (255 * enterTransitionProgress), Canvas.ALL_SAVE_FLAG);
                titleIcon.draw(canvas);
               // canvas.restore();
            }
            canvas.restore();
        }
    }

    @Override
    protected boolean onCustomOpenAnimation() {
        if (startEnterFromView == null) {
            return false;
        }
        enterAnimator = ValueAnimator.ofFloat(0, 1f);
        enterTransitionProgress = 0f;
        enterTransitionInProgress = true;
        iconContainer.invalidate();
        final Drawable startEnterFromDrawable;
        if (startEnterFromView instanceof SimpleTextView) {
            startEnterFromDrawable = ((SimpleTextView) startEnterFromView).getRightDrawable();
        } else if (startEnterFromView instanceof ChatMessageCell) {
            startEnterFromDrawable = ((ChatMessageCell) startEnterFromView).currentNameStatusDrawable;
            ((ChatMessageCell) startEnterFromView).invalidateOutbounds();
        } else {
            startEnterFromDrawable = null;
        }
        if (startEnterFromDrawable != null) {
            startEnterFromDrawable.setAlpha(0);
        }
        if (startEnterFromView instanceof ChatMessageCell) {
            ((ChatMessageCell) startEnterFromView).invalidateOutbounds();
        } else {
            startEnterFromView.invalidate();
        }

        enterAnimator.addUpdateListener(animation -> {
            enterTransitionProgress = (float) animation.getAnimatedValue();
            container.invalidate();
        });
        enterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                enterTransitionInProgress = false;
                enterTransitionProgress = 1f;
                iconContainer.invalidate();
                if (startEnterFromDrawable != null) {
                    ValueAnimator iconAlphaBack = ValueAnimator.ofInt(0, 255);
                    iconAlphaBack.addUpdateListener(animation1 -> {
                        startEnterFromDrawable.setAlpha((Integer) animation1.getAnimatedValue());
                        if (startEnterFromView instanceof ChatMessageCell) {
                            ((ChatMessageCell) startEnterFromView).invalidateOutbounds();
                        } else {
                            startEnterFromView.invalidate();
                        }
                    });
                    iconAlphaBack.start();
                }
                super.onAnimationEnd(animation);
            }
        });
        enterAnimator.setDuration(600);
        enterAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        enterAnimator.start();
        return super.onCustomOpenAnimation();
    }

}
