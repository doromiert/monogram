// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import org.telegram.tgnet.TLRPC;

/** Free local/XMPP capabilities, independent of a Telegram account's subscription flag. */
public final class MonogramFreeFeatures {
    private MonogramFreeFeatures() {}
    public static boolean local(int account) {
        // Limit and purchase checks run while native controllers are still being constructed.
        android.content.Context context=org.telegram.messenger.ApplicationLoader.applicationContext;
        return context!=null && !context.getSharedPreferences("monogram-"+account,android.content.Context.MODE_PRIVATE).getString("jid","").isEmpty();
    }
    public static boolean purchasesUnavailable(int account) { return org.telegram.messenger.BuildVars.IS_BILLING_UNAVAILABLE || local(account); }
    public static boolean document(int account,TLRPC.Document document) {
        return document!=null && emoji(account,document.id);
    }
    public static boolean emoji(int account,long id) {
        return (MonogramPacks.isLocalId(id) || MonogramEmojis.isLocalId(id)) && local(account);
    }
    public static boolean localPack(int account,long id) {
        return MonogramPacks.isLocalId(id) && local(account);
    }
}
