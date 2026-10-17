package org.telegram.messenger.xmpp;

import com.negzero.monogram.xmpp.XmppContacts.PresenceState;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

/** Project live XMPP presence into native Telegram views without inferring activity from messages. */
public final class MonogramPresence {
    private MonogramPresence() {}
    private static boolean lastSeen(PresenceState presence, long now) {
        return presence.visible && presence.lastSeen > 0 && presence.lastSeen <= now && presence.lastSeen <= Integer.MAX_VALUE;
    }
    public static boolean applies(TLRPC.User user) {
        return user != null && user.id > 0 && !user.self && !user.bot && !user.deleted && !(user instanceof TLRPC.TL_userEmpty);
    }
    public static void apply(TLRPC.User user, PresenceState presence, long now) {
        if (!applies(user)) return;
        if (presence.visible && presence.online) {
            user.status = new TLRPC.TL_userStatusOnline();
            user.status.expires = Integer.MAX_VALUE;
        } else if (lastSeen(presence, now)) {
            user.status = new TLRPC.TL_userStatusOffline();
            user.status.expires = (int) presence.lastSeen;
        } else user.status = new TLRPC.TL_userStatusEmpty();
        user.flags |= 64;
    }
    public static String format(PresenceState presence, boolean[] isOnline, boolean[] madeShorter) {
        boolean online = presence.visible && presence.online;
        if (isOnline != null) isOnline[0] = online;
        if (online) return LocaleController.getString(R.string.Online);
        if (lastSeen(presence, System.currentTimeMillis()/1000))
            return LocaleController.formatDateOnline((int) presence.lastSeen, madeShorter);
        if (presence.visible && presence.available)
            return LocaleController.getString(presence.mode == org.jivesoftware.smack.packet.Presence.Mode.dnd
                    ? R.string.MonogramPresenceBusy : R.string.MonogramPresenceAway);
        return LocaleController.getString(presence.visible ? R.string.VoipOfflineTitle : R.string.MonogramPresenceUnavailable);
    }
}
