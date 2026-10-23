// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;

import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.math.BigInteger;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.Future;
import com.negzero.monogram.wallet.*;
import com.negzero.monogram.xmpp.MoneroPayment;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/** Upfront, explicit fee approval with real public chain samples and a wallet-engine suggestion. */
final class DepositFeePicker {
    static final class Choice {
        final BigInteger limit;final int priority;final boolean watch;
        Choice(BigInteger limit,int priority,boolean watch){this.limit=limit;this.priority=priority;this.watch=watch;}
    }
    static void show(BaseFragment host,MonogramWallet wallet,BigInteger linkAmount,String[] estimates,Utilities.Callback2<Choice,String> done){
        BigInteger normal=null,fast=null,suggestion=null;
        try{normal=new BigInteger(estimates[0]);fast=new BigInteger(estimates[1]);suggestion=WalletFeeMath.suggestedLimit(normal);}catch(Exception unavailable){}
        final BigInteger suggested=suggestion,asapEstimate=fast;
        android.content.Context context=host.getContext();Theme.ResourcesProvider theme=host.getResourceProvider();
        int text=Theme.getColor(Theme.key_dialogTextBlack,theme),muted=Theme.getColor(Theme.key_dialogTextGray,theme),accent=Theme.getColor(Theme.key_dialogTextLink,theme);
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),0,dp(24),dp(8));
        TextView estimate=label(context,normal==null ? "Current estimate unavailable" : "Estimated deposit fee: "+fee(host,normal),14,text);body.addView(estimate);
        TextView history=label(context,"Last 24h · loading sampled paid fees…",12,muted);body.addView(history,LayoutHelper.createLinear(-1,-2,0,10,0,0));
        WalletFeeChartView chart=new WalletFeeChartView(context,theme);body.addView(chart,LayoutHelper.createLinear(-1,130));
        RadioGroup modes=new RadioGroup(context);modes.setOrientation(LinearLayout.VERTICAL);body.addView(modes,LayoutHelper.createLinear(-1,-2));
        RadioButton recommended=option(context,modes,1,suggested==null ? "Suggested limit unavailable" : "Suggested limit: "+fee(host,suggested),text,accent);recommended.setEnabled(suggested!=null);
        option(context,modes,2,"Choose from chart or enter a limit",text,accent);
        option(context,modes,3,"ASAP · higher priority, no custom cap"+(fast==null ? "" : "\nEstimated: "+fee(host,fast)),text,accent);
        EditTextBoldCursor input=new EditTextBoldCursor(context);input.setSingleLine(true);input.setTextSize(17);input.setTextColor(text);input.setHintTextColor(muted);input.setHint("Maximum fee in XMR");
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setPadding(dp(8),dp(8),dp(8),dp(8));
        body.addView(input,LayoutHelper.createLinear(-1,48));
        TextView detail=label(context,"",12,muted);body.addView(detail,LayoutHelper.createLinear(-1,-2,0,6,0,0));
        modes.setOnCheckedChangeListener((group,id)->{
            input.setVisibility(id==3 ? View.GONE : View.VISIBLE);input.setEnabled(id==2);
            if(id==1 && suggested!=null)input.setText(MoneroPayment.format(suggested));
            detail.setText(id==3 ? "ASAP uses a higher-priority network fee with no custom ceiling. It cannot shorten the confirmation lock."
                : "Suggested adds 2× headroom to the current estimate. You pay the actual fee, not the limit. Historical fees are sampled payments of different sizes; your deposit may cost more or less.");
        });
        chart.setOnSelection(point->{modes.check(2);input.setText(MoneroPayment.format(point.fee));history.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(point.time*1000))+" · median of "+point.samples+" sampled payments: "+fee(host,point.fee));});
        modes.check(suggested==null ? 2 : 1);
        boolean[] answered={false},dismissed={false};Future<?>[] pending={null};
        new AlertDialog.Builder(context,theme).setTitle("Approve background deposit")
            .setMessage("Deposit into “"+wallet.name()+"” automatically when the link unlocks, using the fee policy below.")
            .setView(body).setPositiveButton("Approve and queue",(dialog,which)->{
                answered[0]=true;
                try{
                    if(modes.getCheckedRadioButtonId()==3){
                        if(asapEstimate!=null && asapEstimate.compareTo(linkAmount)>=0)throw new IllegalArgumentException("The estimated ASAP fee exceeds this link’s amount. Try the suggested option.");
                        done.run(new Choice(WalletFeeMath.MAX,3,false),null);
                    }else done.run(new Choice(MoneroPayment.amount(input.getText().toString().trim().replace(',','.')),0,false),null);
                }catch(Exception invalid){done.run(null,invalid.getMessage()==null ? "Choose a fee limit in XMR." : invalid.getMessage());}
            }).setNeutralButton("Only check status",(dialog,which)->{answered[0]=true;done.run(new Choice(null,0,true),null);})
            .setNegativeButton("Cancel",(dialog,which)->{answered[0]=true;done.run(null,"Deposit not queued.");})
            .setOnDismissListener(dialog->{dismissed[0]=true;if(pending[0]!=null)pending[0].cancel(true);if(!answered[0])done.run(null,"Deposit not queued.");}).show();
        pending[0]=WalletFeeHistory.load(wallet.node(),wallet.network,value->{if(!dismissed[0]){chart.setHistory(value);history.setText(value.points.size()+"/24 hourly samples · tap or drag to choose");}},error->{if(!dismissed[0]){chart.setUnavailable();history.setText("24-hour history unavailable from this node. Suggested still uses the current wallet estimate.");}});
    }
    private static int dp(float value){return AndroidUtilities.dp(value);}
    private static TextView label(android.content.Context context,String text,int size,int color){TextView view=new TextView(context);view.setText(text);view.setTextSize(size);view.setTextColor(color);return view;}
    private static RadioButton option(android.content.Context context,RadioGroup group,int id,String label,int color,int accent){
        RadioButton option=new RadioButton(context);option.setId(id);option.setText(label);option.setTextSize(14);option.setTextColor(color);option.setMinHeight(dp(44));
        option.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,color}));group.addView(option,new RadioGroup.LayoutParams(-1,-2));return option;
    }
    private static String fee(BaseFragment host,BigInteger atomic){
        String value=MoneroPayment.format(atomic)+" XMR";
        try{WalletFiat.Rate rate=GramWallet.getInstance(host.getCurrentAccount()).currencies.exactRate();if(rate!=null)value+=" (≈ "+new WalletFiat.Format(rate.currency,org.telegram.messenger.LocaleController.getInstance().getCurrentLocale()).format(rate.fiatFor(atomic))+")";}catch(Exception unavailable){}
        return value;
    }
}
