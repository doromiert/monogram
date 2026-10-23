// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.negzero.monogram.wallet.MonogramWallet;
import org.telegram.messenger.AndroidUtilities;
import java.util.*;
import java.util.function.Consumer;

/** Compact wallet cards for the composer footer. No add-wallet page or outer background. */
public final class WalletBalanceStrip extends ViewPager {
    private final ArrayList<MonogramWallet> wallets=new ArrayList<>();
    private final Map<Integer,TextView> cards=new HashMap<>();
    private final MonogramWallet.Observer observer=this::refreshCards;
    private Consumer<MonogramWallet> selected;
    private boolean binding,observing,ownsGesture;
    private int scrollState;
    private float downX,downY;
    public WalletBalanceStrip(Context context){
        super(context);
        setPadding(dp(12),0,dp(12),0);setClipToPadding(false);setPageMargin(dp(8));
        setOffscreenPageLimit(1);
        addOnPageChangeListener(new SimpleOnPageChangeListener(){
            @Override public void onPageSelected(int position){if(scrollState==SCROLL_STATE_IDLE)choose(position);}
            @Override public void onPageScrollStateChanged(int state){scrollState=state;if(state==SCROLL_STATE_IDLE)choose(getCurrentItem());}
        });
    }
    public void setOnWalletSelected(Consumer<MonogramWallet> listener){selected=listener;}
    private int dp(float value){return AndroidUtilities.dp(value);}
    private void choose(int position){if(!binding && selected!=null && position>=0 && position<wallets.size())selected.accept(wallets.get(position));}
    public void bind(List<MonogramWallet> next,String activeId){
        boolean changed=!wallets.equals(next);binding=true;
        try{
            if(changed){
                observe(false);wallets.clear();wallets.addAll(next);cards.clear();
                setAdapter(new PagerAdapter(){
                    @Override public int getCount(){return wallets.size();}
                    @Override public boolean isViewFromObject(View view,Object item){return view==item;}
                    @Override public Object instantiateItem(ViewGroup parent,int position){
                        TextView card=new TextView(getContext());card.setSingleLine(true);card.setGravity(Gravity.CENTER);
                        card.setTextColor(Color.WHITE);card.setTextSize(TypedValue.COMPLEX_UNIT_DIP,16);
                        card.setTypeface(AndroidUtilities.bold());card.setPadding(dp(16),0,dp(16),0);
                        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(card,10,16,1,TypedValue.COMPLEX_UNIT_DIP);
                        GradientDrawable face=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFFCF5300,0xFFA83200});face.setCornerRadius(dp(12));card.setBackground(face);
                        if(Build.VERSION.SDK_INT>=26)card.setTooltipText(wallets.get(position).name());
                        card.setOnClickListener(view->setCurrentItem(position,true));
                        cards.put(position,card);updateCard(position,card);parent.addView(card);return card;
                    }
                    @Override public void destroyItem(ViewGroup parent,int position,Object item){parent.removeView((View)item);if(cards.get(position)==item)cards.remove(position);}
                });
                if(isAttachedToWindow())observe(true);
            }
            for(int i=0;i<wallets.size();i++)if(wallets.get(i).walletId.equals(activeId)){if(getCurrentItem()!=i)setCurrentItem(i,false);break;}
            refreshCards();
        }finally{binding=false;}
    }
    private void updateCard(int position,TextView card){
        MonogramWallet wallet=wallets.get(position);String[] snapshot=wallet.balance;
        String amount=wallet.unlocked() && snapshot!=null && snapshot.length>1
            ? com.negzero.monogram.xmpp.MoneroPayment.format(new java.math.BigInteger(snapshot[1]))+" XMR" : "— XMR";
        card.setText(amount);card.setContentDescription(wallet.name()+", "+amount+(wallet.unlocked() ? "" : ", locked"));
    }
    private void refreshCards(){for(Map.Entry<Integer,TextView> entry:cards.entrySet())if(entry.getKey()<wallets.size())updateCard(entry.getKey(),entry.getValue());}
    private void observe(boolean enable){if(observing==enable)return;observing=enable;for(MonogramWallet wallet:wallets){if(enable)wallet.observe(observer);else wallet.unobserve(observer);}}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();observe(true);refreshCards();}
    @Override protected void onDetachedFromWindow(){observe(false);super.onDetachedFromWindow();}
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);if(Build.VERSION.SDK_INT>=29)setSystemGestureExclusionRects(Collections.singletonList(new Rect(0,0,w,h)));}
    @Override public void requestDisallowInterceptTouchEvent(boolean disallow){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(disallow || ownsGesture);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){ownsGesture=true;downX=event.getRawX();downY=event.getRawY();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);}
        if(action==MotionEvent.ACTION_MOVE && Math.abs(event.getRawY()-downY)>dp(16) && Math.abs(event.getRawY()-downY)>Math.abs(event.getRawX()-downX)*1.5f){ownsGesture=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
        boolean result=super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL){ownsGesture=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
        return result;
    }
}
