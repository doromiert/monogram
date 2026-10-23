// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import com.negzero.monogram.wallet.WalletFeeHistory;
import com.negzero.monogram.xmpp.MoneroPayment;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;

/** Native, scrub-selectable chart. Missing hourly samples are drawn as gaps. */
public final class WalletFeeChartView extends View {
    private List<WalletFeeHistory.Point> points=Collections.emptyList();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Theme.ResourcesProvider theme;
    private Consumer<WalletFeeHistory.Point> selection;
    private int selected=-1;private long end;private double maximum=1;
    private boolean unavailable;
    public void setUnavailable(){unavailable=true;invalidate();}
    public WalletFeeChartView(Context context,Theme.ResourcesProvider theme){super(context);this.theme=theme;setFocusable(true);setContentDescription("24-hour sampled paid-fee history. Select a point, or use the fee input below.");}
    public void setOnSelection(Consumer<WalletFeeHistory.Point> listener){selection=listener;}
    public void setHistory(WalletFeeHistory.Snapshot value){
        points=value.points;end=value.fetched/1000;maximum=1;
        for(WalletFeeHistory.Point point:points)maximum=Math.max(maximum,point.fee.doubleValue()*1.15);invalidate();
    }
    private float dp(float value){return AndroidUtilities.dpf2(value);}
    private float x(WalletFeeHistory.Point point){return dp(8)+(getWidth()-dp(16))*(point.time-(end-86400))/86400f;}
    private float y(WalletFeeHistory.Point point){return getHeight()-dp(24)-(float)(point.fee.doubleValue()/maximum)*(getHeight()-dp(50));}
    @Override protected void onDraw(Canvas canvas){
        int color=Theme.getColor(Theme.key_dialogTextLink,theme),muted=Theme.getColor(Theme.key_dialogTextGray,theme);
        paint.setTextSize(dp(11));paint.setTypeface(null);paint.setColor(muted);paint.setStrokeWidth(dp(1));
        canvas.drawLine(dp(8),getHeight()-dp(24),getWidth()-dp(8),getHeight()-dp(24),paint);
        canvas.drawText("24h ago",dp(8),getHeight()-dp(5),paint);String now="Now";canvas.drawText(now,getWidth()-dp(8)-paint.measureText(now),getHeight()-dp(5),paint);
        if(points.isEmpty()){String text=unavailable ? "No history available" : "Loading fee history…";canvas.drawText(text,(getWidth()-paint.measureText(text))/2,getHeight()/2f,paint);return;}
        BigInteger high=BigInteger.ZERO;for(WalletFeeHistory.Point point:points)high=high.max(point.fee);
        canvas.drawText(MoneroPayment.format(high)+" XMR",dp(8),dp(14),paint);
        paint.setColor(color);paint.setStrokeWidth(dp(2));
        WalletFeeHistory.Point previous=null;
        for(int i=0;i<points.size();i++){
            WalletFeeHistory.Point point=points.get(i);
            if(previous!=null && point.slot==previous.slot+1)canvas.drawLine(x(previous),y(previous),x(point),y(point),paint);
            canvas.drawCircle(x(point),y(point),dp(i==selected ? 5 : 2.5f),paint);previous=point;
        }
        if(selected>=0 && selected<points.size()){
            paint.setStrokeWidth(dp(1));paint.setAlpha(100);float x=x(points.get(selected));canvas.drawLine(x,dp(22),x,getHeight()-dp(24),paint);paint.setAlpha(255);
        }
    }
    private void choose(int index){
        selected=Math.max(0,Math.min(points.size()-1,index));if(points.isEmpty())return;
        WalletFeeHistory.Point point=points.get(selected);setContentDescription("Selected historical fee: "+MoneroPayment.format(point.fee)+" XMR");
        if(selection!=null)selection.accept(point);invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(points.isEmpty())return false;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP){
            int nearest=0;float distance=Float.MAX_VALUE;
            for(int i=0;i<points.size();i++){float next=Math.abs(event.getX()-x(points.get(i)));if(next<distance){distance=next;nearest=i;}}
            choose(nearest);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(action!=MotionEvent.ACTION_UP);
            if(action==MotionEvent.ACTION_UP)performClick();return true;
        }
        if(action==MotionEvent.ACTION_CANCEL && getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int code,KeyEvent event){
        if(!points.isEmpty() && (code==KeyEvent.KEYCODE_DPAD_LEFT || code==KeyEvent.KEYCODE_DPAD_RIGHT)){choose((selected<0 ? 0 : selected)+(code==KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1));return true;}
        return super.onKeyDown(code,event);
    }
}
