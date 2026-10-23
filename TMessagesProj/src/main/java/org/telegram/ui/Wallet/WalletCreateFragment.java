// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import android.content.*;import android.view.*;import android.widget.*;import com.negzero.monogram.wallet.*;import org.telegram.ui.ActionBar.*;import org.telegram.ui.Components.*;import org.telegram.ui.MonogramWalletAuth;import org.telegram.messenger.*;import java.util.*;
/** Fresh SDK wallet creation and system-authenticated, local-only recovery backup. */
public final class WalletCreateFragment extends BaseFragment {
    private final String suggestedNode;private MonogramWallet wallet;private MonogramWalletAuth auth;private TextView explanation;private LinearLayout phrase;private EditTextBoldCursor node;private ButtonWithCounterView action,done;private boolean dead,resumed,secureWasSet;private char[] words;
    public WalletCreateFragment(String node){suggestedNode=node;}
    @Override public boolean onFragmentCreate(){try{wallet=GramWallet.getInstance(currentAccount).backend();wallet.acquire();auth=new MonogramWalletAuth(this,wallet,9640);return super.onFragmentCreate();}catch(Exception e){return false;}}
    @Override public View createView(Context context){
        actionBar.setTitle(wallet.exists() ? "Recovery phrase" : "Create wallet");actionBar.setBackButtonImage(R.drawable.ic_ab_back);actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(AndroidUtilities.dp(24),AndroidUtilities.dp(24),AndroidUtilities.dp(24),AndroidUtilities.dp(24));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));
        explanation=new TextView(context);explanation.setText("Save the recovery phrase somewhere private. It restores this wallet if you lose this device.");explanation.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));explanation.setTextSize(16);body.addView(explanation,new LinearLayout.LayoutParams(-1,-2));
        node=new EditTextBoldCursor(context);node.setTextSize(16);node.setSingleLine(true);node.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));node.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));node.setHint("HTTPS Monero node");String selected=suggestedNode;if(selected==null || selected.isEmpty()){java.util.List<WalletNodes.Node> nodes=WalletNodes.forNetwork(wallet.network);if(!nodes.isEmpty())selected=nodes.get(0).url;}node.setText(selected);body.addView(node,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(64)));node.setVisibility(wallet.exists() ? View.GONE : View.VISIBLE);
        phrase=new LinearLayout(context);phrase.setOrientation(LinearLayout.VERTICAL);phrase.setPadding(0,AndroidUtilities.dp(24),0,AndroidUtilities.dp(24));if(android.os.Build.VERSION.SDK_INT>=26)phrase.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);body.addView(phrase,new LinearLayout.LayoutParams(-1,-2));
        action=new ButtonWithCounterView(context,getResourceProvider()).setRound();action.setText(wallet.exists() ? "Show recovery phrase" : "Create wallet");action.setOnClickListener(v->open());body.addView(action,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(48)));
        done=new ButtonWithCounterView(context,getResourceProvider()).setRound();done.setText("I saved my recovery phrase");done.setVisibility(View.GONE);done.setOnClickListener(v->{hidePhrase();GramWallet.getInstance(currentAccount).invalidateState();finishFragment();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(48));lp.topMargin=AndroidUtilities.dp(16);body.addView(done,lp);fragmentView=scroll;return fragmentView;
    }
    private void open(){if(action.isLoading())return;action.setLoading(true);auth.authenticate(wallet.exists() ? "View recovery phrase" : "Create Monero wallet","Use your device unlock",false,(approved,error)->{
        if(!approved){action.setLoading(false);if(error!=null)error(error);return;}
        try{if(wallet.exists()){
            if(wallet.unlocked())reveal();else wallet.unlock(wallet.authorizeUnlock(),(unused,failure)->{if(failure!=null){action.setLoading(false);error(failure);}else reveal();});
        }else{
            WalletVault.Authorization authorization=wallet.authorizeRestore();wallet.create(authorization,node.getText().toString().trim(),(unused,failure)->{if(dead)return;if(failure!=null){action.setLoading(false);error(failure);}else reveal();});
        }}catch(Exception failure){action.setLoading(false);error(failure);}
    });}
    private void reveal(){try{wallet.recoveryPhrase((seed,error)->{if(dead || !resumed){if(seed!=null)Arrays.fill(seed,'\0');if(action!=null)action.setLoading(false);return;}action.setLoading(false);if(error!=null){error(error);return;}hidePhrase();actionBar.setTitle("Recovery phrase");words=seed;String[] parts=new String(seed).trim().split("\\s+");
        for(int i=0;i<parts.length;i+=2){
            LinearLayout row=new LinearLayout(getContext());row.setOrientation(LinearLayout.HORIZONTAL);
            for(int column=0;column<2;column++){
                int index=i+column;TextView word=new TextView(getContext());
                word.setText(index<parts.length ? (index+1)+". "+parts[index] : "");
                word.setTextSize(16);word.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                word.setTypeface(android.graphics.Typeface.MONOSPACE);
                word.setPadding(AndroidUtilities.dp(8),AndroidUtilities.dp(12),AndroidUtilities.dp(8),AndroidUtilities.dp(12));
                row.addView(word,new LinearLayout.LayoutParams(0,-2,1f));
            }
            phrase.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
        Arrays.fill(parts,null);node.setVisibility(View.GONE);action.setText("Show recovery phrase");done.setVisibility(View.VISIBLE);});}catch(Exception e){action.setLoading(false);error(e);}}
    private void hidePhrase(){if(words!=null){Arrays.fill(words,'\0');words=null;}if(phrase!=null)phrase.removeAllViews();if(done!=null)done.setVisibility(View.GONE);}
    private void error(Exception e){BulletinFactory.of(this).showForError(e.getMessage()==null ? "Wallet operation failed." : e.getMessage());}
    @Override public void onResume(){super.onResume();resumed=true;auth.resume();if(getParentActivity()!=null){secureWasSet=(getParentActivity().getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0;getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}}
    @Override public void onPause(){resumed=false;hidePhrase();auth.pause();if(getParentActivity()!=null && !secureWasSet)getParentActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);super.onPause();}
    @Override public void onActivityResultFragment(int code,int result,Intent data){if(auth.activityResult(code,result))return;super.onActivityResultFragment(code,result,data);}
    @Override public void onFragmentDestroy(){dead=true;hidePhrase();auth.destroy();wallet.release();super.onFragmentDestroy();}
}
