// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;
import java.util.function.Consumer;
import org.telegram.messenger.AndroidUtilities;
/** Deliver a scan after the camera sheet closes, so pairing feedback is visible. */
final class MonogramPairingScan implements CameraScanActivity.CameraScanActivityDelegate {
    private final Consumer<String> join,error;
    private String code;
    private boolean dismissed;
    MonogramPairingScan(Consumer<String> join,Consumer<String> error){this.join=join;this.error=error;}
    @Override public boolean processQr(String text,Runnable complete){AndroidUtilities.runOnUIThread(complete);return true;}
    @Override public void didFindQr(String text){if(!dismissed)code=text==null?null:text.trim();}
    @Override public void onDismiss(){
        if(dismissed)return;dismissed=true;String found=code;code=null;
        if(found!=null)AndroidUtilities.runOnUIThread(()->{
            if(found.startsWith("monogram-sync:")&&found.length()<=8192)join.accept(found);
            else error.accept("This is not a Monogram pairing code. Open Device Sync on your other device.");
        },200);
    }
    @Override public String getSubtitleText(){return "Scan the pairing code displayed in Monogram on your other device.";}
}
