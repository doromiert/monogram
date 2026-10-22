package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override public boolean isCustomUpdate() { return true; }
    @Override public BetaUpdate getUpdate() { return org.telegram.messenger.xmpp.MonogramUpdater.get().update(); }
    @Override public void checkUpdate(boolean force, Runnable done) { org.telegram.messenger.xmpp.MonogramUpdater.get().check(force,done); }
    @Override public void downloadUpdate() { org.telegram.messenger.xmpp.MonogramUpdater.get().download(); }
    @Override public void cancelDownloadingUpdate() { org.telegram.messenger.xmpp.MonogramUpdater.get().cancel(); }
    @Override public boolean isDownloadingUpdate() { return org.telegram.messenger.xmpp.MonogramUpdater.get().downloading(); }
    @Override public float getDownloadingUpdateProgress() { return org.telegram.messenger.xmpp.MonogramUpdater.get().progress(); }
    @Override public java.io.File getDownloadedUpdateFile() { return org.telegram.messenger.xmpp.MonogramUpdater.get().file(); }
    @Override public org.telegram.ui.IUpdateLayout takeUpdateLayout(android.app.Activity activity, android.view.ViewGroup parent) {
        return new org.telegram.ui.Components.UpdateLayout(activity,parent);
    }
    @Override public boolean showCustomUpdateAppPopup(android.content.Context context, BetaUpdate update, int account) {
        new org.telegram.ui.Components.UpdateAppAlertDialog(context,update,account).show();return true;
    }
}
