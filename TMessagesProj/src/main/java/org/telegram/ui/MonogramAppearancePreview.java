// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.FrameLayout;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramFolderIcons;
import org.telegram.messenger.xmpp.MonogramUiPreferences;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.*;
import static org.telegram.messenger.AndroidUtilities.dp;

/** Native reference previews; sample data stays on this device and never opens a remote channel. */
final class MonogramAppearancePreview extends FrameLayout {
    enum Kind {AVATAR,CHAT_LIST,FOLDERS,FAB}
    private final Kind kind;
    private final int account;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private ActionBar bar;
    private FilterTabsView folders;
    private MonogramAppearanceSlider avatarSlider;
    private final MonogramPreviewBackground specimenBackground=new MonogramPreviewBackground();
    private final java.util.HashMap<Integer,Integer> counters=new java.util.HashMap<>();
    private final int counterSeed=Utilities.random.nextInt(40)+20;
    private org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor folderBackground;
    MonogramAppearancePreview(Context context,int account,Kind kind,Runnable changed) {
        super(context);this.account=account;this.kind=kind;setWillNotDraw(false);
        if(kind==Kind.AVATAR) {
            avatarSlider=new MonogramAppearanceSlider(context,"Avatar Corners","Square","Circle",28,MonogramUiPreferences.avatarCorners(account),v -> {
                MonogramUiPreferences.setAvatarCorners(account,v);invalidate();changed.run();
            });
            addView(avatarSlider,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,112));
        } else if(kind==Kind.CHAT_LIST) {
            bar=new ActionBar(context);bar.setOccupyStatusBar(false);bar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),false);
            bar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));bar.createMenu().addItem(0,R.drawable.ic_ab_other);
            bar.setBackground(new MonogramPreviewBackground());bar.setSupportsHolidayImage(true);
            addView(bar,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT,Gravity.CENTER,21,21,21,21));
        } else if(kind==Kind.FOLDERS) {
            folders=new FilterTabsView(context,null);folders.setPadding(0,dp(7),0,dp(7));
            folders.setColors(Theme.key_actionBarTabLine,Theme.key_actionBarTabActiveText,Theme.key_actionBarTabUnactiveText,Theme.key_actionBarTabSelector,Theme.key_actionBarDefault);
            folders.setDelegate(new FilterTabsView.FilterTabsViewDelegate() {
                public void onPageSelected(FilterTabsView.Tab tab,boolean forward) {}
                public void onPageScrolled(float progress) {} public void onSamePageSelected() {}
                public int getTabCounter(int id) {return org.telegram.utils.settings.MonogramAppearance.folderCounters.get()==2?0:counters.getOrDefault(id,0);}
                public boolean didSelectTab(FilterTabsView.TabView view,boolean selected) {return false;}
                public boolean isTabMenuVisible() {return false;}public void onDeletePressed(int id) {}public void onPageReorder(int from,int to) {}public boolean canPerformActions() {return false;}
            });
            folderBackground=new org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor();
            folderBackground.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable background=
                    new org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory(folderBackground).create(folders,
                    org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.topPanel(null));
            background.setRadius(dp(18));background.setPadding(dp(6.666f));folders.setBlurredBackground(background);
            addView(folders,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,50,Gravity.CENTER,12,0,12,0));
        } else {
            for(int index=0;index<2;index++) {
                final boolean square=index==1;
                View option=new View(context) {
                    private final MonogramPreviewBackground surface=new MonogramPreviewBackground();
                    private final Drawable icon=getResources().getDrawable(R.drawable.filled_fab_compose_32).mutate();
                    @Override protected void onDraw(Canvas canvas) {
                        surface.setBounds(0,0,getWidth(),getHeight());surface.setSelection(org.telegram.utils.settings.MonogramAppearance.squareFab.get()==square?1:0);surface.draw(canvas);
                        for(int row=0;row<2;row++) {
                            float y=dp(21+row*32);paint.setColor(MonogramPreviewBackground.mock(false));
                            float radius=dp(MonogramUiPreferences.avatarCorners(account)*22f/56f);
                            canvas.drawRoundRect(dp(11),y-dp(11),dp(33),y+dp(11),radius,radius,paint);
                            for(int line=0;line<2;line++) {paint.setColor(MonogramPreviewBackground.mock(line==0));canvas.drawRoundRect(dp(41),y-dp(7-line*10),getWidth()-dp(line==0?70:55),y-dp(3-line*10),dp(2),dp(2),paint);}
                        }
                        paint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));rect.set(getWidth()-dp(42),getHeight()-dp(42),getWidth()-dp(12),getHeight()-dp(12));
                        canvas.drawRoundRect(rect,dp(square?9:100),dp(square?9:100),paint);
                        icon.setColorFilter(Theme.getColor(Theme.key_chats_actionIcon),PorterDuff.Mode.SRC_IN);
                        int size=Math.round(icon.getIntrinsicWidth()*30f/MonogramFabStyle.getFabSizeDp());icon.setBounds((int)rect.centerX()-size/2,(int)rect.centerY()-size/2,(int)rect.centerX()+size/2,(int)rect.centerY()+size/2);icon.draw(canvas);
                    }
                };
                option.setContentDescription(square?"Rounded square floating button":"Round floating button");option.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
                ScaleStateListAnimator.apply(option);addView(option,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,74,Gravity.TOP,21,15,21,21));
                option.setOnClickListener(v -> {org.telegram.utils.settings.MonogramAppearance.squareFab.set(square);invalidate();changed.run();});
            }
        }
        refresh();
    }
    void refresh() {
        if(avatarSlider!=null) avatarSlider.refreshStyle();
        if(bar!=null) {
            String title="Monogram";int mode=org.telegram.utils.settings.MonogramAppearance.title.get();
            if(mode==1) title=org.telegram.messenger.xmpp.MonogramController.get(account).accountJid();
            else if(mode==2) title=UserObject.getUserName(UserConfig.getInstance(account).getCurrentUser());else if(mode==3) title=LocaleController.getString(R.string.FilterChats);
            bar.setTitle(title);bar.setCenteredTitle(MonogramUiPreferences.centerHomeTitle(account));bar.invalidate();
        }
        if(folders!=null) {
            folderBackground.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            folders.resetTabId();folders.removeTabs();counters.clear();
            int mode=MonogramFolderIcons.displayMode(account),first=-1,count=0;
            java.util.ArrayList<MessagesController.DialogFilter> configured=MessagesController.getInstance(account).getDialogFilters();
            int customCount=0;for(MessagesController.DialogFilter f:configured) if(!f.isDefault()) customCount++;
            boolean hideDefault=customCount>1 && org.telegram.utils.settings.MonogramAppearance.hideAllChats.get();
            for(int index=0;index<configured.size();index++) {
                MessagesController.DialogFilter filter=configured.get(index);
                if(filter.isDefault() && hideDefault) continue;
                if(index==0 || index==1 || index==3) counters.put(filter.id,counterSeed/(index+1));
                if(first<0) first=filter.id;
                if(filter.isDefault()) folders.addTab(filter.id,0,LocaleController.getString(R.string.FilterAllChats),null,false,true);
                else {
                    int icon=MonogramFolderIcons.get(account,filter.id);
                    folders.addTab(filter.id,filter.localId,mode==1?"":filter.name,mode==1?null:filter.entities,
                            filter.title_noanimate,false,mode==0?0:icon==0?R.drawable.filter_custom:icon);
                    folders.setLastTabAccessibilityTitle(filter.name);
                }
                count++;
            }
            String[] names={"Contacts","Groups","Unread","Bots"};
            int[] icons={R.drawable.filter_private,R.drawable.filter_group,R.drawable.filter_unread,R.drawable.filter_bots};
            for(int i=0;i<names.length && count<6;i++,count++) {
                int id=Integer.MAX_VALUE-i;
                if(first<0) first=id;
                int position=configured.size()+i;
                if(position==0 || position==1 || position==3) counters.put(id,counterSeed/(position+1));
                folders.addTab(id,id,mode==1?"":names[i],null,false,false,mode==0?0:icons[i]);
                folders.setLastTabAccessibilityTitle(names[i]);
            }
            folders.finishAddingTabs(false);if(first>=0) folders.selectTabWithId(first,1);
        }
        for(int i=0;i<getChildCount();i++) getChildAt(i).invalidate();invalidate();
    }
    @Override protected void onMeasure(int w,int h) {
        int height=kind==Kind.AVATAR?218:kind==Kind.CHAT_LIST?98:kind==Kind.FOLDERS?74:110;
        super.onMeasure(w,MeasureSpec.makeMeasureSpec(dp(height),MeasureSpec.EXACTLY));
        if(kind==Kind.FAB) for(int i=0;i<getChildCount();i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec((getMeasuredWidth()-dp(58))/2,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(74),MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        super.onLayout(changed,l,t,r,b);
        if(kind==Kind.FAB) for(int i=0;i<2;i++) {View v=getChildAt(i);int x=dp(21)+i*(v.getMeasuredWidth()+dp(16));v.layout(x,dp(15),x+v.getMeasuredWidth(),dp(89));}
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(kind==Kind.AVATAR) {
            specimenBackground.setBounds(dp(21),dp(114),getWidth()-dp(21),dp(197));specimenBackground.draw(canvas);
            float radius=dp(MonogramUiPreferences.avatarCorners(account));paint.setColor(MonogramPreviewBackground.mock(true));
            float square=Math.max(0,Math.min(1,1-MonogramUiPreferences.avatarCorners(account)/28f));
            float outer=AndroidUtilities.dpf2(2*square+7),inner=AndroidUtilities.dpf2(square+5);
            float dotX=dp(92)-(dp(8)+(outer/(float)Math.sqrt(2)-dp(8))*square);
            float dotY=dp(183)-(AndroidUtilities.dpf2(7.5f)+(outer/(float)Math.sqrt(2)-AndroidUtilities.dpf2(7.5f))*square);
            canvas.save();
            Path dot=new Path();dot.addCircle(dotX,dotY,outer,Path.Direction.CW);canvas.clipPath(dot,Region.Op.DIFFERENCE);
            canvas.drawRoundRect(dp(36),dp(127),dp(92),dp(183),radius,radius,paint);canvas.restore();
            paint.setColor(Theme.getColor(Theme.key_chats_onlineCircle));canvas.drawCircle(dotX,dotY,inner,paint);
            paint.setColor(MonogramPreviewBackground.mock(false));
            canvas.drawRoundRect(getWidth()-dp(80),dp(131),getWidth()-dp(38),dp(139.33f),dp(4),dp(4),paint);
            float start=dp(110),available=getWidth()-dp(42);
            for(int i=0;i<3;i++) {paint.setColor(MonogramPreviewBackground.mock(i==0));float y=dp(131+i*20);canvas.drawRoundRect(start,y,Math.min(getWidth()-dp(38),start+available*(i==0?.4f:i==1?.5f:.36f)),y+dp(8.33f),dp(4),dp(4),paint);}
        }
    }
}
