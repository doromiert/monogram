// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.view.View;
import com.negzero.monogram.xmpp.*;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import org.telegram.messenger.xmpp.MonogramCertificates;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;

public final class MonogramCertificatesActivity extends MonogramSettingsActivity {
    private final Account server;
    private final Runnable changed;
    private X509Certificate pending,trusted;
    private String error;
    public MonogramCertificatesActivity(int account,Account server,Runnable changed){currentAccount=account;this.server=server;this.changed=changed;}
    @Override protected CharSequence getTitle(){return "Server certificate";}
    private static String details(X509Certificate certificate)throws Exception {
        return "Subject: "+certificate.getSubjectX500Principal()+"\nIssuer: "+certificate.getIssuerX500Principal()
            +"\nValid from: "+certificate.getNotBefore()+"\nValid until: "+certificate.getNotAfter()+"\n\nSHA-256\n"+ServerCertificateTrust.fingerprint(certificate);
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asShadow(MonogramCertificates.endpoint(server)));
        try {
            pending=MonogramCertificates.pending(server);trusted=MonogramCertificates.trusted(server);
            if(pending!=null) {items.add(UItem.asHeader("Last rejected self-signed certificate"));items.add(UItem.asShadow(details(pending)));items.add(UItem.asButton(1,"Trust this certificate").accent());}
            if(trusted!=null) {items.add(UItem.asHeader("Approved certificate"));items.add(UItem.asShadow(details(trusted)));items.add(UItem.asButton(2,"Forget certificate approval"));}
            if(pending!=null || trusted!=null)items.add(UItem.asButton(3,"Copy certificate fingerprint"));
            if(pending==null && trusted==null)items.add(UItem.asShadow("No certificate exception saved. Try connecting first; a rejected, valid self-signed certificate will appear here for review."));
        }catch(Exception failed){error="Could not read the server certificate: "+failed.getMessage();}
        if(error!=null)items.add(UItem.asShadow(error));
        items.add(UItem.asShadow("Compare the SHA-256 fingerprint with your server administrator through another trusted channel. Approval applies only to this service and configured endpoint, including other accounts using that endpoint. Certificate expiry and hostname verification remain required. It does not change OMEMO trust."));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(item.id==3) {
            try{org.telegram.messenger.AndroidUtilities.addToClipboard(ServerCertificateTrust.fingerprint(pending!=null ? pending : trusted));}catch(Exception failed){error=failed.getMessage();listView.adapter.update(true);}
        }else if(item.id==1 && pending!=null) {
            X509Certificate selected=pending;
            showDialog(new AlertDialog.Builder(getContext()).setTitle("Trust this self-signed certificate?")
                .setMessage("Approve this exact certificate for "+server.jid.getDomain()+" only after verifying its fingerprint. An incorrect approval can expose your login and server traffic. OMEMO message encryption is separate.")
                .setPositiveButton("Trust certificate",(dialog,which)->{
                    try{MonogramCertificates.approve(server,selected);error=null;if(changed!=null)changed.run();else error="Approval saved. Return to sign-in and try connecting again.";}
                    catch(Exception failed){error=failed.getMessage();}
                    listView.adapter.update(true);
                }).setNegativeButton("Cancel",null).create());
        }else if(item.id==2)showDialog(new AlertDialog.Builder(getContext()).setTitle("Forget certificate approval?")
            .setMessage("The next connection will require normal certificate verification or a new explicit approval.")
            .setPositiveButton("Forget",(dialog,which)->{try{MonogramCertificates.forget(server);error=null;if(changed!=null)changed.run();}catch(Exception failed){error=failed.getMessage();}listView.adapter.update(true);})
            .setNegativeButton("Cancel",null).create());
    }
    @Override public void onResume(){super.onResume();if(listView!=null)listView.adapter.update(true);}
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y){return false;}
}
