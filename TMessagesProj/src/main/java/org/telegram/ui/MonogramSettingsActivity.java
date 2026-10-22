// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Build;
import android.os.Parcelable;
import android.view.View;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.SharedConfig;
import java.util.ArrayList;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

/** Uses the same section renderer and adaptive navigation as native Chat Settings. */
public abstract class MonogramSettingsActivity extends UniversalFragment {
    private final BlurredBackgroundSourceColor headerColor=new BlurredBackgroundSourceColor();
    private final BlurredBackgroundSourceRenderNode headerGlass=Build.VERSION.SDK_INT>=31?new BlurredBackgroundSourceRenderNode(headerColor):null;
    private final DownscaleScrollableNoiseSuppressor headerNoise=Build.VERSION.SDK_INT>=31?new DownscaleScrollableNoiseSuppressor():null;
    private final RectF headerBounds=new RectF();
    private final ArrayList<RectF> headerRegions=new ArrayList<>();
    private IBlur3Capture headerCapture;
    private ChatActivityFadeView headerFade;
    private ValueAnimator fadeAnimator;
    private boolean faded;
    private float fadeAlpha;
    private Parcelable scrollState;

    @Override public boolean isSupportEdgeToEdge() {return true;}
    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getTitle());actionBar.setCastShadows(false);actionBar.setAddToContainer(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {if(id==-1) finishFragment();}
        });
        HeaderContent content=new HeaderContent(context);content.updateColors();
        actionBar.createMenu();
        BlurredBackgroundDrawableViewFactory factory=new BlurredBackgroundDrawableViewFactory(headerGlass==null?headerColor:headerGlass);
        factory.setSourceRootView(new ViewPositionWatcher(content),content);
        factory.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        actionBar.setGlassPadding(AndroidUtilities.dp(12));actionBar.setGlassTitleTextSize(20);
        actionBar.setupGlass(factory,BlurredBackgroundProviderImpl.topPanelChatActivity(resourceProvider));
        actionBar.setDrawGlassTitle(false);actionBar.setGlassShadowAlpha(0);
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText),false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector),false);
        listView=new UniversalRecyclerView(this,this::fillItems,this::onClick,this::onLongClick);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        listView.setPadding(0,AndroidUtilities.statusBarHeight+ActionBar.getCurrentActionBarHeight(),0,0);
        listView.setVerticalScrollBarEnabled(false);
        if(scrollState!=null) {listView.getLayoutManager().onRestoreInstanceState(scrollState);scrollState=null;}
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view,int dx,int dy) {updateFade(true);content.invalidate();}
        });
        headerCapture=new ViewGroupPartRenderer(listView,content,listView::drawChild);
        listView.addEdgeEffectListener(() -> content.postInvalidateOnAnimation());
        content.addView(listView,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.MATCH_PARENT));
        headerFade=new ChatActivityFadeView(context);headerFade.setupColorKey(Theme.key_windowBackgroundGray);headerFade.setFadeTopAlpha(0);
        content.addView(headerFade,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.MATCH_PARENT));
        content.addView(actionBar,LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT));
        return fragmentView=content;
    }

    private void updateTopFade() {
        if(headerFade==null) return;
        headerFade.setFadeZoneTop(actionBar.getMeasuredHeight()+AndroidUtilities.dp(42));
        headerFade.setFadeHeightTop(AndroidUtilities.dp(78));
        headerFade.setTopFadeColor(getThemedColor(Theme.key_windowBackgroundGray));
        headerFade.setFadeTopAlpha(Math.round(fadeAlpha*255));
    }
    private void updateFade(boolean animate) {
        if(headerFade==null || listView==null) return;
        boolean show=listView.canScrollVertically(-1);
        if(show==faded) return;
        faded=show;if(fadeAnimator!=null) fadeAnimator.cancel();
        if(!animate) {fadeAlpha=show?1:0;updateTopFade();return;}
        fadeAnimator=ValueAnimator.ofFloat(fadeAlpha,show?1:0);fadeAnimator.setDuration(320);
        fadeAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        fadeAnimator.addUpdateListener(a -> {fadeAlpha=(float)a.getAnimatedValue();headerFade.setFadeTopAlpha(Math.round(fadeAlpha*255));});
        fadeAnimator.start();
    }
    private final class HeaderContent extends FrameLayout implements Theme.Colorable {
        HeaderContent(Context context) {super(context);}
        @Override protected void onMeasure(int width,int height) {super.onMeasure(width,height);updateTopFade();}
        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {super.onLayout(changed,left,top,right,bottom);updateFade(false);}
        @Override protected void dispatchDraw(Canvas canvas) {
            if(headerGlass!=null && headerNoise!=null && headerCapture!=null && !headerGlass.inRecording()) {
                if(SharedConfig.chatBlurEnabled()) {
                    int padding=AndroidUtilities.dp(48);
                    headerBounds.set(0,-padding,getWidth(),actionBar.getMeasuredHeight()+padding);
                    if(headerRegions.isEmpty()) headerRegions.add(headerBounds);
                    headerNoise.setupRenderNodes(headerRegions,1);
                    headerNoise.invalidateResultRenderNodes(headerCapture,getWidth(),getHeight());
                }
                Canvas recording=headerGlass.beginRecording(getWidth(),getHeight());
                recording.drawColor(getThemedColor(Theme.key_windowBackgroundGray));
                if(SharedConfig.chatBlurEnabled()) headerNoise.draw(recording,DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                headerGlass.endRecording();
            }
            super.dispatchDraw(canvas);
        }
        @Override public void updateColors() {
            int color=getThemedColor(Theme.key_windowBackgroundGray);setBackgroundColor(color);headerColor.setColor(color);updateTopFade();
        }
    }
    @Override public void onInsets(int left,int top,int right,int bottom) {
        if(listView!=null) listView.setPadding(0,top+ActionBar.getCurrentActionBarHeight(),0,bottom);
    }
    @Override public boolean isLightStatusBar() {return AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundGray))>.721f;}
    @Override public void clearViews() {
        if(listView!=null && listView.getLayoutManager()!=null) scrollState=listView.getLayoutManager().onSaveInstanceState();
        if(fadeAnimator!=null) {fadeAnimator.cancel();fadeAnimator=null;}
        headerFade=null;headerCapture=null;super.clearViews();
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> colors=new ArrayList<>();
        colors.add(new ThemeDescription(fragmentView,ThemeDescription.FLAG_BACKGROUND,null,null,null,null,Theme.key_windowBackgroundGray));
        colors.add(new ThemeDescription(actionBar,ThemeDescription.FLAG_AB_ITEMSCOLOR,null,null,null,null,Theme.key_windowBackgroundWhiteBlackText));
        colors.add(new ThemeDescription(actionBar,ThemeDescription.FLAG_AB_TITLECOLOR,null,null,null,null,Theme.key_windowBackgroundWhiteBlackText));
        colors.add(new ThemeDescription(actionBar,ThemeDescription.FLAG_AB_SELECTORCOLOR,null,null,null,null,Theme.key_listSelector));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_SELECTOR,null,null,null,null,Theme.key_settings_listSelector));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_LISTGLOWCOLOR,null,null,null,null,Theme.key_actionBarDefault));
        colors.add(new ThemeDescription(listView,0,new Class[]{View.class},Theme.dividerPaint,null,null,Theme.key_divider));
        colors.add(new ThemeDescription(listView,0,new Class[]{HeaderCell.class},new String[]{"textView"},null,null,null,Theme.key_windowBackgroundWhiteBlueHeader));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextCheckCell.class,TextSettingsCell.class},new String[]{"textView","textView"},null,null,null,Theme.key_windowBackgroundWhiteBlackText));
        for(int key:new int[]{Theme.key_windowBackgroundWhiteBlackText,Theme.key_windowBackgroundWhiteBlueText4,Theme.key_text_RedRegular}) {
            colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_CHECKTAG,new Class[]{TextCell.class},new String[]{"textView"},null,null,null,key));
        }
        for(int key:new int[]{Theme.key_windowBackgroundWhiteGrayIcon,Theme.key_windowBackgroundWhiteBlueText4,Theme.key_text_RedBold}) {
            colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_CHECKTAG,new Class[]{TextCell.class},new String[]{"imageView"},null,null,null,key));
        }
        colors.add(new ThemeDescription(listView,0,new Class[]{TextCell.class},new String[]{"valueTextView"},null,null,null,Theme.key_windowBackgroundWhiteValueText));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextSettingsCell.class},new String[]{"valueTextView"},null,null,null,Theme.key_windowBackgroundWhiteValueText));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextCheckCell.class},new String[]{"checkBox"},null,null,null,Theme.key_switchTrack));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextCheckCell.class},new String[]{"checkBox"},null,null,null,Theme.key_switchTrackChecked));
        colors.add(new ThemeDescription(listView,0,new Class[]{TextInfoPrivacyCell.class},new String[]{"textView"},null,null,null,Theme.key_windowBackgroundWhiteGrayText4));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_LINKCOLOR,new Class[]{TextInfoPrivacyCell.class},new String[]{"textView"},null,null,null,Theme.key_windowBackgroundWhiteLinkText));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_TEXTCOLOR,new Class[]{EditTextCell.class},new String[]{"editText"},null,null,null,Theme.key_windowBackgroundWhiteBlackText));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_HINTTEXTCOLOR,new Class[]{EditTextCell.class},new String[]{"editText"},null,null,null,Theme.key_windowBackgroundWhiteHintText));
        colors.add(new ThemeDescription(listView,ThemeDescription.FLAG_CURSORCOLOR,new Class[]{EditTextCell.class},new String[]{"editText"},null,null,null,Theme.key_windowBackgroundWhiteBlackText));
        return colors;
    }
}
