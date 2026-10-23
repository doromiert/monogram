// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;
import android.content.*;import android.view.*;import android.widget.*;
import com.negzero.monogram.wallet.*;import org.telegram.ui.ActionBar.*;import org.telegram.ui.Cells.*;import org.telegram.ui.Components.*;import org.telegram.ui.MonogramWalletAuth;import org.telegram.messenger.*;
/** Native wallet selector. Every row represents an independent local vault. */
public final class WalletManagementActivity extends BaseFragment {
    private LinearLayout rows;private MonogramWalletAuth auth;private MonogramWallet held;
    @Override public View createView(Context context){
        actionBar.setTitle("Wallets");actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));fragmentView=scroll;refresh();return fragmentView;
    }
    private TextSettingsCell row(String label,String value,Runnable click){TextSettingsCell cell=new TextSettingsCell(getContext(),getResourceProvider());cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));cell.setTextAndValue(label,value,true);cell.setOnClickListener(v->click.run());rows.addView(cell,new LinearLayout.LayoutParams(-1,-2));return cell;}
    private void refresh(){if(rows==null)return;rows.removeAllViews();try{
        MonogramWallet current=GramWallet.getInstance(currentAccount).backend();HeaderCell header=new HeaderCell(getContext(),getResourceProvider());header.setText(com.negzero.monogram.xmpp.MoneroPayment.networkName(current.network));rows.addView(header);
        for(MonogramWallet wallet:current.wallets()){
            TextSettingsCell cell=row(wallet.name(),wallet.walletId.equals(current.walletId) ? "Selected" : wallet.exists() ? "" : "Not connected",()->select(wallet));
            cell.setOnLongClickListener(v->{options(wallet);return true;});
        }
        HeaderCell manage=new HeaderCell(getContext(),getResourceProvider());manage.setText("Manage wallets");rows.addView(manage);
        row("Create wallet","",()->add(true));row("Restore wallet","",()->add(false));
        row("Rename selected wallet","",()->rename(current));
        TextSettingsCell remove=row("Remove selected wallet","",()->remove(current));remove.setTextColor(getThemedColor(Theme.key_text_RedBold));
    }catch(Exception e){error(e);}}
    private void error(Exception e){BulletinFactory.of(this).showForError(e.getMessage()==null ? "Wallet operation failed." : e.getMessage());}
    private void select(MonogramWallet wallet){try{MonogramWallet current=GramWallet.getInstance(currentAccount).backend();if(!current.walletId.equals(wallet.walletId)){current.select(wallet.walletId);GramWallet.cleanup(currentAccount);}finishFragment();}catch(Exception e){error(e);}}
    private void options(MonogramWallet wallet){new AlertDialog.Builder(getContext(),getResourceProvider()).setTitle(wallet.name()).setItems(new CharSequence[]{"Open wallet","Rename","Remove from this device"},(d,i)->{if(i==0)select(wallet);else if(i==1)rename(wallet);else remove(wallet);}).show();}
    private void input(String title,String initial,java.util.function.Consumer<String> done){EditTextBoldCursor input=new EditTextBoldCursor(getContext());input.setSingleLine(true);input.setTextSize(16);input.setTextColor(getThemedColor(Theme.key_dialogTextBlack));input.setText(initial);input.setPadding(AndroidUtilities.dp(24),AndroidUtilities.dp(12),AndroidUtilities.dp(24),AndroidUtilities.dp(12));new AlertDialog.Builder(getContext(),getResourceProvider()).setTitle(title).setView(input).setPositiveButton("Save",(d,w)->done.accept(input.getText().toString())).setNegativeButton("Cancel",null).show();}
    private void rename(MonogramWallet wallet){input("Wallet name",wallet.name(),name->{try{wallet.rename(name);refresh();}catch(Exception e){error(e);}});}
    private void add(boolean create){input("Wallet name","Wallet",name->{try{
        MonogramWallet current=GramWallet.getInstance(currentAccount).backend();String node=current.node();String id=current.add(name);current.select(id);GramWallet.cleanup(currentAccount);
        BaseFragment screen=create ? new WalletCreateFragment(node) : new WalletImportFragment();screen.setCurrentAccount(currentAccount);presentFragment(screen);
    }catch(Exception e){error(e);}});}
    private void remove(MonogramWallet wallet){
        new AlertDialog.Builder(getContext(),getResourceProvider()).setTitle("Remove "+wallet.name()+"?")
            .setMessage("This deletes this wallet’s local files and device unlock key. Make sure you have its recovery phrase before continuing.")
            .setNegativeButton("Cancel",null).setPositiveButton("Remove from this device",(d,w)->{try{
                MonogramWallet current=wallet;
                final boolean removingSelected=current.walletId.equals(GramWallet.getInstance(currentAccount).backend().walletId);
                if(current.busy)throw new IllegalStateException("Stop syncing or finish the current wallet operation first.");
                if(auth!=null){auth.destroy();auth=null;}release();
                held=current;held.acquireForManagement();auth=new MonogramWalletAuth(this,current,9641,true);auth.resume();
                auth.authenticate("Remove local wallet","Confirm with your device unlock",false,(approved,failure)->{
                    if(!approved){if(failure!=null)error(failure);release();return;}
                    try{current.remove((unused,error)->{release();if(error!=null)error(error);else{if(removingSelected)GramWallet.cleanup(currentAccount);refresh();}});}catch(Exception e){release();error(e);}
                });
            }catch(Exception e){release();error(e);}}).show();
    }
    private void release(){if(held!=null){held.release();held=null;}}
    @Override public void onResume(){super.onResume();if(auth!=null)auth.resume();refresh();}
    @Override public void onPause(){if(auth!=null)auth.pause();super.onPause();}
    @Override public void onActivityResultFragment(int code,int result,Intent data){if(auth!=null && auth.activityResult(code,result))return;super.onActivityResultFragment(code,result,data);}
    @Override public void onFragmentDestroy(){if(auth!=null)auth.destroy();release();super.onFragmentDestroy();}
}
