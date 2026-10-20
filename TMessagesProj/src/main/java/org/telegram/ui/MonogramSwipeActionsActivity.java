// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramChatBehavior;
import org.telegram.messenger.xmpp.MonogramDoubleTap;
import org.telegram.messenger.xmpp.MonogramSwipeAction;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.MonogramSwipeActionsHelper;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;

/** Native ordered swipe actions. Empty means off; preferences/model are shared with the real gesture. */
public final class MonogramSwipeActionsActivity extends MonogramSettingsActivity {
    private static final int LOOP=1,REVERSED=2,ACTION=100;
    private int orderSection=-1;
    private SwipePreview preview;
    public MonogramSwipeActionsActivity(int account) { currentAccount=account; }
    @Override protected CharSequence getTitle() { return LocaleController.getString(R.string.MonogramSwipeActionsTitle); }
    @Override public View createView(Context context) {
        preview=new SwipePreview(context);
        View view=super.createView(context);
        listView.listenReorder((section,rows) -> {
            if(section!=orderSection) return;
            ArrayList<MonogramSwipeAction> ordered=new ArrayList<>();
            for(UItem row:rows) { MonogramSwipeAction action=MonogramSwipeAction.of(row.id-ACTION);if(action!=null) ordered.add(action); }
            MonogramSwipeAction.setEnabled(currentAccount,ordered);preview.reset();
        });
        listView.allowReorder(true);
        return view;
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        orderSection=-1;
        items.add(UItem.asCustom(preview));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramSwipePreviewInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.MonogramSwipeEnabled)));
        List<MonogramSwipeAction> enabled=MonogramSwipeAction.enabled(currentAccount);
        if(enabled.isEmpty()) items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramSwipeDisabledInfo)));
        else {
            orderSection=adapter.reorderSectionStart();
            for(MonogramSwipeAction action:enabled) items.add(UItem.asCheck(ACTION+action.actionId,MonogramDoubleTap.label(action.actionId)).setChecked(true).setReordering(true).onBind(view -> accessibility(view,action)));
            adapter.reorderSectionEnd();
            items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramSwipeReorderInfo)));
        }
        List<MonogramSwipeAction> disabled=MonogramSwipeAction.disabled(currentAccount);
        if(!disabled.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.MonogramSwipeAvailable)));
            for(MonogramSwipeAction action:disabled) items.add(UItem.asCheck(ACTION+action.actionId,MonogramDoubleTap.label(action.actionId)).setChecked(false).onBind(view -> view.setAccessibilityDelegate(null)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramSwipeAvailableInfo)));
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.MonogramSwipeSelection)));
        items.add(UItem.asCheck(LOOP,LocaleController.getString(R.string.MonogramSwipeLoop)).setChecked(MonogramChatBehavior.swipeLoop(currentAccount)));
        items.add(UItem.asCheck(REVERSED,LocaleController.getString(R.string.MonogramSwipeReverse)).setChecked(MonogramChatBehavior.swipeReversed(currentAccount)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MonogramSwipeSelectionInfo)));
    }
    private void accessibility(View view,MonogramSwipeAction action) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info);List<MonogramSwipeAction> ordered=MonogramSwipeAction.enabled(currentAccount);int at=ordered.indexOf(action);
                if(at>0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.monogram_swipe_move_up,LocaleController.getString(R.string.MonogramSwipeMoveUp)));
                if(at>=0 && at<ordered.size()-1) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.monogram_swipe_move_down,LocaleController.getString(R.string.MonogramSwipeMoveDown)));
            }
            @Override public boolean performAccessibilityAction(View host,int id,Bundle args) {
                if(id!=R.id.monogram_swipe_move_up && id!=R.id.monogram_swipe_move_down) return super.performAccessibilityAction(host,id,args);
                List<MonogramSwipeAction> ordered=new ArrayList<>(MonogramSwipeAction.enabled(currentAccount));int at=ordered.indexOf(action),target=at+(id==R.id.monogram_swipe_move_up?-1:1);
                if(at<0 || target<0 || target>=ordered.size()) return false;
                ordered.remove(at);ordered.add(target,action);MonogramSwipeAction.setEnabled(currentAccount,ordered);refresh();return true;
            }
        });
    }
    private void refresh() { preview.reset();listView.adapter.update(true); }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==LOOP) MonogramChatBehavior.swipeLoop(currentAccount,!MonogramChatBehavior.swipeLoop(currentAccount));
        else if(item.id==REVERSED) MonogramChatBehavior.swipeReversed(currentAccount,!MonogramChatBehavior.swipeReversed(currentAccount));
        else {
            MonogramSwipeAction action=MonogramSwipeAction.of(item.id-ACTION);if(action==null) return;
            action.setEnabled(currentAccount,!MonogramSwipeAction.enabled(currentAccount).contains(action));
        }
        refresh();
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }

    // The preview below is wired to the same helper as ChatActivity and never executes a message action.
    private final class SwipePreview extends View {
        private final MonogramSwipeActionsHelper helper;
        private final MessageDrawable bubble=new MessageDrawable(0,false,false);
        private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint text=new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator returning;
        private float offset,startX,startY;
        private boolean dragging,tracking;
        private String chosen;

        SwipePreview(Context context) {
            super(context);
            Theme.createChatResources(context,false);
            helper=new MonogramSwipeActionsHelper(this,currentAccount,getResourceProvider());
            setContentDescription(LocaleController.getString(R.string.MonogramSwipePreviewInfo));
            setFocusable(true);setClickable(true);
            reset();
        }
        void reset() {
            if(returning!=null) { returning.cancel();returning=null; }
            tracking=dragging=false;offset=0;chosen=null;
            helper.detach();helper.start(MonogramSwipeAction.enabled(currentAccount));
            String reaction=MediaDataController.getInstance(currentAccount).getDoubleTapReaction();
            helper.setReaction(reaction!=null && !reaction.startsWith("animated_") ? reaction : null);
            invalidate();
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec),AndroidUtilities.dp(224));
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int left=AndroidUtilities.dp(18),right=getWidth()-AndroidUtilities.dp(24);
            int top=AndroidUtilities.dp(76),bottom=AndroidUtilities.dp(132);
            fill.setColor(Theme.getColor(Theme.key_chat_inBubble));
            int save=canvas.save();canvas.translate(offset,0);
            bubble.setBounds(left,top,right,bottom);bubble.draw(canvas,fill);
            text.setTextSize(AndroidUtilities.dp(15));text.setColor(Theme.getColor(Theme.key_chat_messageTextIn));
            CharSequence message=TextUtils.ellipsize(LocaleController.getString(R.string.MonogramSwipePreviewMessage),text,Math.max(0,right-left-AndroidUtilities.dp(36)),TextUtils.TruncateAt.END);
            canvas.drawText(message.toString(),left+AndroidUtilities.dp(16),top+AndroidUtilities.dp(34),text);canvas.restoreToCount(save);
            helper.draw(canvas,offset,false,(top+bottom)/2f,right+offset);
            String caption=helper.size()==0 ? LocaleController.getString(R.string.MonogramSwipePreviewDisabled) : chosen;
            if(dragging && helper.getSelected()!=null) caption=MonogramDoubleTap.label(helper.getSelected().actionId);
            if(caption!=null) {
                text.setTextSize(AndroidUtilities.dp(14));text.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                canvas.drawText(caption,(getWidth()-text.measureText(caption))/2,AndroidUtilities.dp(192),text);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if(helper.size()==0) return false;
                    reset();tracking=true;startX=event.getX();startY=event.getY();return true;
                case MotionEvent.ACTION_MOVE:
                    if(!tracking) return false;
                    float dx=event.getX()-startX,dy=event.getY()-startY;
                    if(!dragging) {
                        if(Math.abs(dy)>AndroidUtilities.dp(10) && Math.abs(dy)>Math.abs(dx)) { tracking=false;return false; }
                        if(dx < -AndroidUtilities.dp(8) && Math.abs(dx)>Math.abs(dy)) {
                            dragging=true;getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    if(dragging) {
                        offset=Math.max(-AndroidUtilities.dp(110),Math.min(0,dx));
                        helper.update(offset,event.getX(),event.getY());invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if(!tracking) return false;
                    if(event.getActionMasked()==MotionEvent.ACTION_UP && offset<=-AndroidUtilities.dp(50) && helper.getSelected()!=null) {
                        chosen=MonogramDoubleTap.label(helper.getSelected().actionId);announceForAccessibility(chosen);
                    }
                    tracking=dragging=false;getParent().requestDisallowInterceptTouchEvent(false);
                    returning=ValueAnimator.ofFloat(offset,0);returning.setDuration(220);returning.setInterpolator(CubicBezierInterpolator.EASE_OUT);
                    returning.addUpdateListener(animation -> { offset=(float)animation.getAnimatedValue();invalidate(); });returning.start();
                    if(event.getActionMasked()==MotionEvent.ACTION_UP) performClick();return true;
            }
            return super.onTouchEvent(event);
        }
        @Override public boolean performClick() { super.performClick();return true; }
        private void stop() {
            if(returning!=null) { returning.cancel();returning=null; }
            helper.detach();tracking=dragging=false;offset=0;
        }
        @Override protected void onDetachedFromWindow() { stop();super.onDetachedFromWindow(); }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if(helper!=null) { if(visibility!=VISIBLE) stop();else reset(); }
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow();reset(); }
    }
}
