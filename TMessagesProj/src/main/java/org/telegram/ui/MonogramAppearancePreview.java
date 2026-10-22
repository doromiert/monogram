// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramUiPreferences;
import org.telegram.ui.ActionBar.Theme;
import static org.telegram.messenger.AndroidUtilities.dp;
/** Small native palette preview; it has no account/network side effects. */
final class MonogramAppearancePreview extends View {
    private final int account;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    MonogramAppearancePreview(Context context,int account){super(context);this.account=account;setContentDescription("Preview of avatar corners, home title and floating button shape");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),dp(164));}
    private void fill(int key){paint.setColor(Theme.getColor(key));}
    @Override protected void onDraw(Canvas canvas){
        int width=getWidth();fill(Theme.key_windowBackgroundWhite);canvas.drawRect(0,0,width,getHeight(),paint);
        fill(Theme.key_windowBackgroundWhiteBlackText);paint.setTextSize(dp(20));paint.setTypeface(AndroidUtilities.bold());String title="Monogram";
        int mode=org.telegram.utils.settings.MonogramAppearance.title.get();if(mode==1)title="user@example.org";else if(mode==2)title="Your name";else if(mode==3)title="Chats";
        float x=MonogramUiPreferences.centerHomeTitle(account)?(width-paint.measureText(title))/2:dp(20);canvas.drawText(title,x,dp(34),paint);
        float radius=dp(MonogramUiPreferences.avatarCorners(account));fill(Theme.key_featuredStickers_addButton);canvas.drawRoundRect(dp(20),dp(58),dp(76),dp(114),radius,radius,paint);
        fill(Theme.key_chats_actionIcon);paint.setTextSize(dp(23));canvas.drawText("M",dp(37),dp(95),paint);
        fill(Theme.key_windowBackgroundWhiteBlackText);paint.setTextSize(dp(16));canvas.drawText("Monogram",dp(90),dp(79),paint);
        fill(Theme.key_windowBackgroundWhiteGrayText);paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(dp(13));canvas.drawText("Make it yours",dp(90),dp(103),paint);
        if(!MonogramUiPreferences.hideComposeButton(account)){
            boolean square=org.telegram.utils.settings.MonogramAppearance.squareFab.get()||org.telegram.utils.settings.MonogramAppearance.fabStyle.get();
            float right=width-dp(18),left=right-dp(48),top=dp(106);fill(Theme.key_featuredStickers_addButton);canvas.drawRoundRect(left,top,right,top+dp(48),dp(square?16:24),dp(square?16:24),paint);
            fill(Theme.key_chats_actionIcon);paint.setStrokeWidth(dp(2));canvas.drawLine(left+dp(16),top+dp(32),left+dp(32),top+dp(16),paint);
        }
    }
}
