// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.xmpp.MonogramPacks;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.StickersAlert;

/** File-manager and chat attachments open the same native sticker/emoji pack sheet. */
public final class MonogramPackFiles {
    private static final ExecutorService FILES=Executors.newSingleThreadExecutor();
    private MonogramPackFiles() {}
    public static boolean isPack(String name,String mime) {
        if("application/vnd.monogram.sticker-pack".equals(mime) || "application/x-monogram-pack".equals(mime))return true;
        String lower=name==null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pack") || lower.endsWith(".nzsp") || lower.endsWith(".xmsp");
    }
    public static boolean handleIntent(Activity activity,int account,Intent intent,boolean restore) {
        if(restore || intent==null || !Intent.ACTION_VIEW.equals(intent.getAction()))return false;
        Uri uri=intent.getData();
        if(uri==null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme())))return false;
        String mime=intent.getType();
        if(!isPack(uri.getLastPathSegment(),mime) && !"application/octet-stream".equals(mime) && !"application/zip".equals(mime))return false;
        intent.setAction(null);
        AlertDialog loading=new AlertDialog(activity,3);loading.setMessage("Checking pack…");loading.show();
        FILES.execute(() -> {
            String name=uri.getLastPathSegment();String error=null;
            try {
                if(AndroidUtilities.isInternalUri(uri))throw new IllegalArgumentException();
                if("content".equals(uri.getScheme())) {
                    try(Cursor cursor=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                        if(cursor!=null && cursor.moveToFirst())name=cursor.getString(0);
                    }
                }
                if(!isPack(name,mime))error="Choose a .pack, .nzsp or .xmsp sticker or emoji pack.";
            } catch(Exception failure){error="Could not read this pack. Save it to Files, then open it with Monogram again.";}
            final String failure=error;
            AndroidUtilities.runOnUIThread(() -> {
                boolean visible=loading.isShowing();loading.dismiss();
                if(!visible || activity.isFinishing() || activity.isDestroyed())return;
                if(failure!=null)error(activity,failure);else open(activity,account,uri);
            });
        });
        return true;
    }
    public static void open(Activity activity,int account,Uri uri) {
        if(activity==null || activity.isFinishing())return;
        if(!UserConfig.getInstance(account).isClientActivated()) {
            error(activity,"Sign in to Monogram, then open this pack again.");return;
        }
        final String owner=MonogramPacks.owner(account);
        AlertDialog loading=new AlertDialog(activity,3);loading.setMessage("Opening pack…");loading.show();
        try {
            MonogramPacks.Request request=MonogramPacks.openArchive(account,uri,false,(pack,failure)->{
                boolean visible=loading.isShowing();loading.dismiss();
                if(!visible || activity.isFinishing() || activity.isDestroyed() || !owner.equals(MonogramPacks.owner(account)))return;
                if(failure!=null){error(activity,failure);return;}
                BaseFragment fragment=LaunchActivity.getLastFragment();
                if(fragment==null || fragment.getCurrentAccount()!=account){error(activity,"Switch to the account that opened this pack, then open it again.");return;}
                TLRPC.TL_inputStickerSetID input=new TLRPC.TL_inputStickerSetID();input.id=pack.id;
                fragment.showDialog(new StickersAlert(activity,fragment,input,MonogramPacks.find(account,input),null,false));
            });
            loading.setOnCancelListener(dialog->request.cancel());
        } catch(Exception failure){loading.dismiss();error(activity,failure.getMessage());}
    }
    private static void error(Activity activity,String message) {
        new AlertDialog.Builder(activity).setTitle("Could not open pack").setMessage(message)
                .setPositiveButton("Close",null).show();
    }
}
