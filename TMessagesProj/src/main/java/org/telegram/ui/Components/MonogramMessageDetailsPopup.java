// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.List;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.messenger.xmpp.MonogramMessageInfo;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** exteraGram's native details submenu; metadata extraction belongs to the lazy local loader. */
public final class MonogramMessageDetailsPopup {
    public final LinearLayout layout;
    private final LinearLayout rows;
    private final BaseFragment fragment;
    private final PopupSwipeBackLayout swipeBack;
    private final MessageObject message;
    private final Theme.ResourcesProvider provider;
    private final Runnable closeMenu;
    private final int account;
    private final long generation;
    private boolean loaded,opening;
    private volatile int attachmentEpoch;
    private List<MonogramMessageInfo.Entry> pending;

    public MonogramMessageDetailsPopup(BaseFragment fragment,PopupSwipeBackLayout swipeBack,MessageObject message,
            Theme.ResourcesProvider provider,Runnable closeMenu) {
        this.fragment=fragment;this.swipeBack=swipeBack;this.message=message;this.provider=provider;this.closeMenu=closeMenu;
        account=fragment.getCurrentAccount();generation=MonogramController.get(account).accountGeneration();
        Context context=fragment.getContext();
        layout=new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                int width=Math.max(AndroidUtilities.dp(196),Math.min(AndroidUtilities.dp(320),AndroidUtilities.displaySize.x-AndroidUtilities.dp(32)));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),heightSpec);
            }
            @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
                super.onLayout(changed,left,top,right,bottom);
                if(changed && getParent()!=null) swipeBack.post(swipeBack::invalidateTransforms);
            }
            @Override protected void onDetachedFromWindow() { attachmentEpoch++;pending=null;super.onDetachedFromWindow(); }
            @Override protected void onVisibilityChanged(View changedView,int visibility) {
                super.onVisibilityChanged(changedView,visibility);
                if(visibility==VISIBLE) post(MonogramMessageDetailsPopup.this::renderPending);
            }
        };
        layout.setOrientation(LinearLayout.VERTICAL);layout.setMinimumWidth(AndroidUtilities.dp(240));
        ActionBarMenuSubItem back=new ActionBarMenuSubItem(context,true,false,provider);
        back.setItemHeight(44);back.setTextAndIcon(LocaleController.getString(R.string.Back),R.drawable.msg_arrow_back);
        back.getTextView().setPadding(LocaleController.isRTL ? 0 : AndroidUtilities.dp(40),0,LocaleController.isRTL ? AndroidUtilities.dp(40) : 0,0);
        back.setOnClickListener(view -> { opening=false;swipeBack.closeForeground(); });
        layout.addView(back,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,44));
        ScrollView scroll=new ScrollView(context) {
            private final AnimatedFloat shadowAlpha=new AnimatedFloat(this,350,CubicBezierInterpolator.EASE_OUT_QUINT);
            private Drawable shadow;
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                if(MeasureSpec.getMode(heightSpec)==MeasureSpec.EXACTLY) { super.onMeasure(widthSpec,heightSpec);return; }
                int maxHeight=Math.max(AndroidUtilities.dp(112),Math.min(AndroidUtilities.dp(392),AndroidUtilities.displaySize.y/2));
                if(MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED) maxHeight=Math.min(maxHeight,MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(maxHeight,MeasureSpec.AT_MOST));
            }
            @Override protected void onScrollChanged(int x,int y,int oldX,int oldY) {
                super.onScrollChanged(x,y,oldX,oldY);invalidate();
            }
            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                float alpha=shadowAlpha.set(canScrollVertically(-1) ? 1f : 0f)*.5f;
                if(alpha<=0) return;
                if(shadow==null) shadow=getContext().getDrawable(R.drawable.header_shadow).mutate();
                shadow.setBounds(0,getScrollY(),getWidth(),getScrollY()+shadow.getIntrinsicHeight());
                shadow.setAlpha((int)(255*alpha));shadow.draw(canvas);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows,LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT,android.view.Gravity.TOP));
        int viewport=Math.max(AndroidUtilities.dp(112),Math.min(AndroidUtilities.dp(392),AndroidUtilities.displaySize.y/2));
        layout.addView(scroll,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,viewport));
        addGap();
        ActionBarMenuSubItem loading=new ActionBarMenuSubItem(context,false,true,provider);
        loading.setItemHeight(56);loading.setTextAndIcon(LocaleController.getString(R.string.Loading),0);loading.setEnabled(false);
        rows.addView(loading,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,56));
        swipeBack.addOnSwipeBackProgressListener((parent,target,progress) -> {
            opening=target>0;
            if(opening && pending!=null) layout.post(this::renderPending);
        });
    }

    /** Called by the owning native menu after opening this foreground. No work happens in the constructor. */
    public void load() {
        if(loaded || !ownerMatches()) return;
        loaded=true;opening=true;
        int epoch=attachmentEpoch;
        MonogramMessageInfo.load(account,message,() -> epoch==attachmentEpoch && ownerMatches(),entries -> AndroidUtilities.runOnUIThread(() -> {
            if(epoch!=attachmentEpoch || !ownerMatches() || !layout.isAttachedToWindow()) return;
            pending=entries;renderPending();
        }));
    }
    private boolean ownerMatches() {
        return fragment.getCurrentAccount()==account && message!=null && message.currentAccount==account
                && MonogramController.get(account).accountGeneration()==generation;
    }
    private boolean active() {
        return ownerMatches() && opening && layout.isAttachedToWindow() && layout.isShown() && swipeBack.isForegroundOpen();
    }
    private void renderPending() {
        if(pending==null || !active()) return;
        List<MonogramMessageInfo.Entry> entries=pending;pending=null;rows.removeAllViews();addGap();
        for(MonogramMessageInfo.Entry entry:entries) {
            if(entry==null) { addGap();continue; }
            ActionBarMenuSubItem row=new ActionBarMenuSubItem(layout.getContext(),false,false,provider);
            row.setTextAndIcon(entry.title,entry.icon);row.setSubtext(entry.value);row.setItemHeight(56);
            row.subtextView.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);row.subtextView.setMarqueeRepeatLimit(-1);row.subtextView.setSelected(true);
            row.setContentDescription(entry.title+", "+entry.value+", "+LocaleController.getString(entry.action==MonogramMessageInfo.Entry.COPY ? R.string.Copy : R.string.Open));
            row.setOnClickListener(view -> activate(entry));
            row.setOnLongClickListener(view -> { copy(entry.value);return true; });
            rows.addView(row,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,56));
        }
        addGap();
        ActionBarMenuSubItem raw=new ActionBarMenuSubItem(layout.getContext(),false,true,provider);
        raw.setTextAndIcon("Stored message data",R.drawable.msg_log);
        raw.setSubtext("Body and XMPP metadata");raw.setItemHeight(56);
        raw.setOnClickListener(view -> {
            if(!active()) return;
            final String body=message.messageOwner.message;
            MonogramController.get(account).inspectMessage(message,text -> {
                if(!active() || fragment.getParentActivity()==null) return;
                opening=false;if(closeMenu!=null) closeMenu.run();
                org.telegram.ui.MonogramDebugActivity.showText(fragment,"Stored message data","Body:\n"+(body==null ? "" : body)+"\n\n"+text);
            });
        });
        rows.addView(raw,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,56));
        layout.requestLayout();
    }
    private void addGap() {
        ActionBarPopupWindow.GapView gap=new ActionBarPopupWindow.GapView(layout.getContext(),provider);
        rows.addView(gap,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,8));
    }
    private void activate(MonogramMessageInfo.Entry entry) {
        if(!active()) return;
        if(entry.action==MonogramMessageInfo.Entry.COPY) { copy(entry.value);return; }
        if(fragment.getParentActivity()==null) return;
        opening=false;if(closeMenu!=null) closeMenu.run();
        if(entry.action==MonogramMessageInfo.Entry.OPEN_FILE) {
            java.io.File file=new java.io.File(entry.value);
            AndroidUtilities.openForView(file,file.getName(),entry.mime,fragment.getParentActivity(),provider,false);
        } else if(entry.action==MonogramMessageInfo.Entry.OPEN_LOCATION) {
            try { fragment.getParentActivity().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("geo:"+entry.value.replace(", ",",")))); }
            catch(android.content.ActivityNotFoundException | SecurityException missing) { BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.MonogramNoMapsApp)).show(); }
        }
    }
    private void copy(String value) {
        if(!active() || value==null || !AndroidUtilities.addToClipboard(value)) return;
        opening=false;
        if(closeMenu!=null) closeMenu.run();
        BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied),provider).show();
    }
}
