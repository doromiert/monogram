// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.View;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramDoubleTap;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;

/** Exteragram's two-bubble action illustration, using native Monogram theme colors. */
public final class MonogramDoubleTapPreview extends View {
    private final int account;
    private final MessageDrawable[] bubbles={new MessageDrawable(0,false,false),new MessageDrawable(0,true,false)};
    private final Drawable[] icons=new Drawable[2];
    private final AnimatorSet[] animations=new AnimatorSet[2];
    private final float[] iconAlpha={1,1};
    private final float[][] radius=new float[2][2],opacity=new float[2][2];
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),outline=new Paint(Paint.ANTI_ALIAS_FLAG),ring=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds=new RectF();
    private PorterDuffColorFilter iconFilter;
    private int iconColor;

    public MonogramDoubleTapPreview(Context context,int account) {
        super(context);this.account=account;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        outline.setStyle(Paint.Style.STROKE);outline.setStrokeWidth(dp(.5f));
        ring.setStyle(Paint.Style.STROKE);
        update(false,false);update(true,false);
    }
    private float dp(float value) { return AndroidUtilities.dpf2(value); }
    private float ease(float t) { return t<.5f ? 2*t*t : 1-(float)Math.pow(-2*t+2,2)/2; }
    private Drawable icon(boolean own) {
        int[] resources={R.drawable.msg_block,R.drawable.msg_reactions2,R.drawable.menu_reply,R.drawable.msg_copy,R.drawable.msg_forward,
                R.drawable.msg_edit,R.drawable.msg_saved,R.drawable.monogram_double_tap_repeat,R.drawable.msg_delete,R.drawable.msg_translate};
        return ContextCompat.getDrawable(getContext(),resources[MonogramDoubleTap.action(account,own)]).mutate();
    }
    public void update(boolean own,boolean animated) {
        int side=own ? 1 : 0;
        if(animations[side]!=null) { animations[side].cancel();animations[side]=null; }
        if(!animated || !isAttachedToWindow()) { icons[side]=icon(own);iconAlpha[side]=1;invalidate();return; }
        ArrayList<Animator> parts=new ArrayList<>();
        for(int i=0;i<2;i++) {
            final int pulse=i;
            radius[side][pulse]=opacity[side][pulse]=0;
            ValueAnimator size=ValueAnimator.ofFloat(0,1);size.setDuration(1300);size.setStartDelay(60L*i);
            size.setInterpolator(this::ease);
            size.addUpdateListener(a -> { radius[side][pulse]=(float)a.getAnimatedValue();invalidate(); });parts.add(size);
            ValueAnimator alpha=ValueAnimator.ofFloat(0,1,0);alpha.setDuration(1400);alpha.setStartDelay(150L+80L*i);
            alpha.setInterpolator(t -> t<.5f ? ease(t*2)/2 : .5f+ease((t-.5f)*2)/2);
            alpha.addUpdateListener(a -> { opacity[side][pulse]=(float)a.getAnimatedValue();invalidate(); });parts.add(alpha);
        }
        ValueAnimator fadeOut=ValueAnimator.ofFloat(iconAlpha[side],0);fadeOut.setDuration(250);fadeOut.setInterpolator(this::ease);
        fadeOut.addUpdateListener(a -> { iconAlpha[side]=(float)a.getAnimatedValue();invalidate(); });
        ValueAnimator fadeIn=ValueAnimator.ofFloat(0,1);fadeIn.setDuration(250);fadeIn.setInterpolator(this::ease);
        fadeIn.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationStart(Animator a) { icons[side]=icon(own); } });
        fadeIn.addUpdateListener(a -> { iconAlpha[side]=(float)a.getAnimatedValue();invalidate(); });
        AnimatorSet iconChange=new AnimatorSet();iconChange.playSequentially(fadeOut,fadeIn);parts.add(iconChange);
        AnimatorSet animation=new AnimatorSet();animation.playTogether(parts);animations[side]=animation;
        animation.start();performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),AndroidUtilities.dp(170));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width=getWidth()-dp(26),left=dp(13);
        int foreground=Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        fill.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        outline.setColor(ColorUtils.setAlphaComponent(foreground,40));
        int accent=Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        int currentIconColor=Theme.getColor(Theme.key_chats_menuItemIcon);
        if(iconFilter==null || iconColor!=currentIconColor) { iconColor=currentIconColor;iconFilter=new PorterDuffColorFilter(iconColor,PorterDuff.Mode.SRC_IN); }
        for(int side=0;side<2;side++) {
            float top=dp(side==0 ? 10 : 85),centerX=left+width*(side==0 ? .25f : .75f),centerY=top+dp(32.5f);
            bounds.set(left+(side==0 ? dp(8) : width/2+dp(8)),top,left+(side==0 ? width/2-dp(8) : width-dp(8)),top+dp(65));
            bubbles[side].setBounds((int)bounds.left,(int)bounds.top,(int)bounds.right,(int)bounds.bottom);
            bubbles[side].draw(canvas,fill);bubbles[side].draw(canvas,outline);
            for(int pulse=0;pulse<2;pulse++) {
                float alpha=opacity[side][pulse];ring.setColor(ColorUtils.setAlphaComponent(accent,Math.round(255*alpha)));
                ring.setStrokeWidth(dp(1.5f)*alpha*alpha);
                canvas.drawCircle(centerX,centerY,dp(25-pulse*6)*radius[side][pulse],ring);
            }
            Drawable icon=icons[side];float half=dp(12+4*(1-iconAlpha[side]));
            icon.setBounds((int)(centerX-half),(int)(centerY-half),(int)(centerX+half),(int)(centerY+half));
            icon.setColorFilter(iconFilter);
            icon.setAlpha(Math.round(255*iconAlpha[side]));icon.draw(canvas);
        }
    }
    private void stopAnimations() {
        for(int side=0;side<2;side++) {
            if(animations[side]!=null) { animations[side].cancel();animations[side]=null; }
            icons[side]=icon(side==1);iconAlpha[side]=1;
            for(int pulse=0;pulse<2;pulse++) radius[side][pulse]=opacity[side][pulse]=0;
        }
    }
    @Override protected void onDetachedFromWindow() { stopAnimations();super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE) stopAnimations(); }
}
