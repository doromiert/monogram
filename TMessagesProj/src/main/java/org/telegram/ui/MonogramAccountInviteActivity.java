// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

/** Native settings components; invitation tokens stay in this screen's memory only. */
public final class MonogramAccountInviteActivity extends MonogramSettingsActivity {
    private MonogramController.AccountInvitation invitation;
    private EditTextCell username;
    private boolean busy,destroyed,subscribe;
    private String error;
    private java.util.function.Consumer<com.negzero.monogram.xmpp.RegistrationInvitation> useInvitation;
    public MonogramAccountInviteActivity(int account) { currentAccount=account; }
    public MonogramAccountInviteActivity(int account,java.util.function.Consumer<com.negzero.monogram.xmpp.RegistrationInvitation> useInvitation) {
        this(account);this.useInvitation=useInvitation;
    }
    @Override protected CharSequence getTitle() { return "Invite to your server"; }
    @Override public View createView(Context context) {
        username=new EditTextCell(context,"Username",false);
        username.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        username.editText.setSingleLine(true);
        username.editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1023)});
        username.whenHitEnter(this::generate);
        return super.createView(context);
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asShadow("Create a link that lets someone register on your XMPP server. Your server decides whether your account can issue invitations."));
        items.add(UItem.asHeader("New XMPP account"));
        if(invitation!=null && invitation.result!=null) {
            if(useInvitation!=null) items.add(UItem.asButton(6,R.drawable.msg_add,"Use to create account").accent());
            items.add(UItem.asButton(2,R.drawable.msg_copy,"Copy invitation link"));
            items.add(UItem.asButton(3,R.drawable.msg_share,"Share invitation link").accent());
            items.add(UItem.asShadow(invitation.result.link));
            if(!invitation.result.expires.isEmpty()) items.add(UItem.asShadow("Expires: "+invitation.result.expires));
            items.add(UItem.asShadow("Anyone with this link may be able to register. Share it only with the person you want to invite."));
            items.add(UItem.asButton(4,"Create another invitation"));
        } else {
            if(invitation!=null) {
                items.add(UItem.asHeader(invitation.usernameRequired ? "Required username" : "Username (optional)"));
                items.add(UItem.asCustom(username));
                items.add(UItem.asShadow("Enter the new account's username without @server. "+(invitation.usernameRequired ? "Your server requires a username." : "Leave empty to let the invitee choose.")));
                if(invitation.rosterSubscription) items.add(UItem.asCheck(5,"Add each other as contacts").setChecked(subscribe).setEnabled(!busy));
            }
            items.add(UItem.asButton(1,busy ? "Creating invitation…" : "Create invitation").accent().setEnabled(!busy));
            items.add(UItem.asShadow("The link is generated only when you press Create invitation. It is not sent automatically."));
        }
        if(error!=null) items.add(UItem.asShadow(error));
    }
    private void generate() {
        if(busy || destroyed || invitation!=null && invitation.result!=null) return;
        if(invitation!=null && invitation.usernameRequired && username.getText().toString().trim().isEmpty()) {
            error="Enter a username for the new account.";listView.adapter.update(true);return;
        }
        busy=true;error=null;username.setEnabled(false);listView.adapter.update(true);
        MonogramController.get(currentAccount).accountInvitation(invitation,username.getText().toString(),subscribe,(value,failure) -> {
            if(destroyed) { MonogramController.get(currentAccount).cancelAccountInvitation(value);return; }
            busy=false;username.setEnabled(true);invitation=value;error=failure;
            if(value!=null && value.result!=null) AndroidUtilities.hideKeyboard(username.editText);
            if(listView!=null) listView.adapter.update(true);
        });
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==6 && useInvitation!=null && invitation!=null && invitation.result!=null) {
            try {
                com.negzero.monogram.xmpp.RegistrationInvitation value=com.negzero.monogram.xmpp.RegistrationInvitation.parse(invitation.result.uri);
                useInvitation.accept(value);finishFragment();
            } catch(Exception invalid) { error="This invitation cannot be used for in-app registration. Use the server’s invitation page.";listView.adapter.update(true); }
            return;
        }

        if(item.id==1) generate();
        else if(item.id==2 && invitation!=null && invitation.result!=null) {
            AndroidUtilities.addToClipboard(invitation.result.link);BulletinFactory.of(this).createCopyLinkBulletin().show();
        } else if(item.id==3 && invitation!=null && invitation.result!=null && getParentActivity()!=null) {
            Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,invitation.result.link);
            try { getParentActivity().startActivity(Intent.createChooser(share,"Share invitation link")); }
            catch(android.content.ActivityNotFoundException unavailable) { error="No sharing app is available. Copy the link instead.";listView.adapter.update(true); }
        } else if(item.id==4) { invitation=null;error=null;username.editText.setText("");listView.adapter.update(true); }
        else if(item.id==5 && !busy) { subscribe=!subscribe;listView.adapter.update(true); }
    }
    @Override public void onFragmentDestroy() {
        destroyed=true;MonogramController.get(currentAccount).cancelAccountInvitation(invitation);invitation=null;
        super.onFragmentDestroy();
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
}
