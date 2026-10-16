// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import com.negzero.monogram.xmpp.Account;
import com.negzero.monogram.xmpp.XmppSession;
import com.negzero.monogram.xmpp.XmppMessage;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.jivesoftware.smack.android.AndroidSmackInitializer;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;

public final class MonogramController {
    private static final MonogramController[] instances = new MonogramController[UserConfig.MAX_ACCOUNT_COUNT];
    public static synchronized MonogramController get(int account) {
        if (instances[account] == null) instances[account] = new MonogramController(account);
        return instances[account];
    }
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final int account;
    private final SharedPreferences preferences;
    private final MonogramStore store;
    private final AccountVault vault;
    private volatile XmppSession session;
    private volatile long self;
    private volatile int state = ConnectionsManager.ConnectionStateConnecting;
    private boolean restoring;
    private long sessionEpoch;
    private volatile boolean archiveLoading;
    private boolean archiveResetAttempted;

    private MonogramController(int account) {
        this.account = account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("monogram-" + account, Context.MODE_PRIVATE);
        store = new MonogramStore(account); vault = new AccountVault(account);
    }
    public int state() { return state; }
    private void state(int value) {
        state = value;
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didUpdateConnectionState));
    }
    public synchronized void restore() {
        if (restoring || session != null || !preferences.contains("jid")) return;
        restoring = true;
        worker.execute(() -> {
            try { connect(new Account(preferences.getString("jid", ""), preferences.getString("host", ""), preferences.getInt("port", 5222), "Monogram"),
                    vault.decrypt(preferences.getString("credential", ""))); }
            catch (Exception e) { state(ConnectionsManager.ConnectionStateWaitingForNetwork); }
            finally { synchronized (this) { restoring = false; } }
        });
    }
    private void connect(Account details, String password) throws Exception {
        if (session != null) { session.close(); session = null; }
        final long epoch = ++sessionEpoch;
        state(ConnectionsManager.ConnectionStateConnecting);
        AndroidSmackInitializer.initialize(ApplicationLoader.applicationContext);
        XmppSession.initializeOmemo(new File(ApplicationLoader.getFilesDirFixed(), "monogram-omemo"));
        int device = preferences.getInt("device", 0);
        if (device == 0) {
            device = org.jivesoftware.smackx.omemo.OmemoManager.randomDeviceId();
            if (!preferences.edit().putInt("device", device).commit()) throw new java.io.IOException("Cannot save device identity");
        }
        self = store.peer(details.jid.toString());
        XmppSession connecting = new XmppSession(details, password, device,
                new File(ApplicationLoader.getFilesDirFixed(), "monogram-trust-" + account),
                new XmppSession.Listener() {
                    @Override public void message(org.jxmpp.jid.EntityBareJid sender, String id, String body, boolean encrypted) {}
                    @Override public void message(XmppMessage message) { worker.execute(() -> { if (sessionEpoch == epoch && session != null) incoming(message); }); }
                });
        connecting.connect();
        session = connecting;
        for (RosterEntry entry : Roster.getInstanceFor(connecting.connection()).getEntries()) store.peer(entry.getJid().toString());
        state(ConnectionsManager.ConnectionStateConnected);
        archiveLoading = true; archiveResetAttempted = false;
        worker.execute(() -> syncArchive(connecting, false));
    }
    private void syncArchive(XmppSession expected, boolean backwards) {
        if (session != expected || !expected.connection().isAuthenticated()) return;
        try {
            if (!expected.hasArchive()) { archiveLoading = false; return; }
            String cursor = store.archiveCursor(backwards);
            if (backwards && store.archiveComplete()) { archiveLoading = false; return; }
            XmppSession.ArchivePage page = !backwards && cursor != null
                    ? expected.archiveAfter(cursor, 100) : expected.archiveBefore(null, cursor, 100);
            if (cursor != null && cursor.equals(backwards ? page.firstId : page.lastId) && !page.complete) {
                throw new java.io.IOException("Archive cursor did not advance");
            }
            boolean initial = !backwards && cursor == null;
            if (store.saveArchive(page, backwards || initial) && UserConfig.getInstance(account).isClientActivated()) {
                TLRPC.TL_messages_dialogs dialogs = dialogs();
                AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).processLoadedDialogs(
                        dialogs, null, null, 0, 0, dialogs.dialogs.size(), 3, false, false, false));
            }
            boolean older = backwards || initial || page.complete;
            if (older ? !store.archiveComplete() : page.lastId != null) {
                worker.execute(() -> syncArchive(expected, older));
            } else { archiveLoading = false; }
        } catch (Exception e) {
            if (!archiveResetAttempted && e instanceof org.jivesoftware.smack.XMPPException.XMPPErrorException
                    && ((org.jivesoftware.smack.XMPPException.XMPPErrorException) e).getStanzaError().getCondition()
                    == org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found) {
                archiveResetAttempted = true;
                store.resetArchiveCursor();
                worker.execute(() -> syncArchive(expected, false));
                return;
            }
            archiveLoading = false;
            // Keep local chats usable if the provider has no usable archive. A later connection retries.
            if (BuildVars.LOGS_ENABLED) FileLog.e("Monogram archive sync: " + e.getClass().getSimpleName());
        }
    }
    private TLRPC.TL_messages_dialogs dialogs() {
        TLRPC.TL_messages_dialogs result = new TLRPC.TL_messages_dialogs();
        for (long peer : store.peers()) {
            if (peer == self) continue;
            ArrayList<TLRPC.Message> history = store.history(peer, self, 0, 1);
            if (history.isEmpty()) continue;
            TLRPC.TL_dialog dialog = new TLRPC.TL_dialog(); dialog.id = peer; dialog.peer = new TLRPC.TL_peerUser(); dialog.peer.user_id = peer;
            dialog.top_message = history.get(0).id; dialog.last_message_date = history.get(0).date;
            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
            result.dialogs.add(dialog); result.messages.addAll(history); result.users.add(store.user(peer, self));
        }
        result.dialogs.sort((a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
        result.users.add(ownUser());
        return result;
    }
    public void loadHistory(long peer, int offset, int count, int loadType, java.util.function.BiConsumer<TLRPC.messages_Messages, Boolean> completed) {
        worker.execute(() -> {
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            result.messages.addAll(store.history(peer, self, offset, count, loadType == 1));
            result.users.add(store.user(peer, self)); result.users.add(ownUser());
            boolean end = !archiveLoading;
            Utilities.stageQueue.postRunnable(() -> completed.accept(result, end));
        });
    }
    public void login(String jid, String password, String host, int port, Consumer<Exception> result) {
        worker.execute(() -> {
            Exception error = null;
            try {
                Account details = new Account(jid, host, port, "Monogram");
                connect(details, password);
                if (!preferences.edit().putString("jid", details.jid.toString()).putString("host", host)
                        .putInt("port", port).putString("credential", vault.encrypt(password)).commit()) throw new java.io.IOException("Cannot save account");
            } catch (Exception e) {
                error = e;
                if (session != null) { session.close(); session = null; }
                state(ConnectionsManager.ConnectionStateWaitingForNetwork);
            }
            final Exception completed = error;
            AndroidUtilities.runOnUIThread(() -> result.accept(completed));
        });
    }
    public void logout() {
        if (!preferences.edit().remove("jid").remove("credential").commit()) throw new IllegalStateException("Cannot remove saved credential");
        worker.execute(() -> {
            if (session != null) { session.close(); session = null; }
            sessionEpoch++; archiveLoading = false;
            store.clear(); self = 0;
            state(ConnectionsManager.ConnectionStateWaitingForNetwork);
        });
    }
    public TLRPC.User ownUser() { return store.user(self, self); }
    public String jid(long peer) { return store.jid(peer); }
    public XmppSession session() {
        XmppSession current = session;
        if (current == null || !current.connection().isAuthenticated()) throw new IllegalStateException("XMPP is disconnected");
        return current;
    }
    public boolean encrypted(long peer) { return preferences.getBoolean("omemo-" + peer, true); }
    public void setEncrypted(long peer, boolean encrypted) {
        if (!preferences.edit().putBoolean("omemo-" + peer, encrypted).commit()) throw new IllegalStateException("Cannot save encryption preference");
    }
    private int now() { return (int) (System.currentTimeMillis() / 1000); }
    private void incoming(XmppMessage message) {
        long peer = store.peer(message.peer.toString());
        int date = (int) (message.timestamp / 1000);
        int id = store.save(message);
        if (id == 0) return;
        if (!UserConfig.getInstance(account).isClientActivated()) return;
        ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(store.user(peer, self)); users.add(ownUser());
        TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage();
        update.message = store.message(id, peer, self, message.body, message.outgoing, date);
        ArrayList<TLRPC.Update> updates = new ArrayList<>(); updates.add(update);
        Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).processUpdateArray(updates, users, new ArrayList<>(), false, date));
    }
    public void openPeer(String address, RequestDelegate callback) {
        worker.execute(() -> {
            try {
                org.jxmpp.jid.EntityBareJid jid = org.jxmpp.jid.impl.JidCreate.entityBareFrom(address);
                long peer = store.peer(jid.toString());
                Roster.getInstanceFor(session().connection()).createItemAndRequestSubscription(jid, jid.toString(), null);
                TLRPC.User user = store.user(peer, self);
                ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(user);
                MessagesStorage.getInstance(account).putUsersAndChats(users, null, true, true);
                AndroidUtilities.runOnUIThread(() -> {
                    MessagesController.getInstance(account).putUser(user, false);
                    callback.run(user, null);
                });
            } catch (Exception e) {
                TLRPC.TL_error error = new TLRPC.TL_error(); error.code = 400; error.text = "XMPP_CONTACT_FAILED";
                AndroidUtilities.runOnUIThread(() -> callback.run(null, error));
            }
        });
    }
    public void request(TLObject request, RequestDelegate callback) {
        worker.execute(() -> {
            TLObject response = null; TLRPC.TL_error error = null;
            try { response = handle(request); }
            catch (Exception e) {
                error = new TLRPC.TL_error(); error.code = 400;
                if (e instanceof org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException) error.text = "MONOGRAM_OMEMO_TRUST_REQUIRED";
                else if (e instanceof com.negzero.monogram.xmpp.OmemoSendException) error.text = "MONOGRAM_OMEMO_" + ((com.negzero.monogram.xmpp.OmemoSendException) e).reason.name();
                else if (e instanceof UnsupportedOperationException) error.text = "MONOGRAM_FEATURE_UNAVAILABLE";
                else if (session == null || !session.connection().isAuthenticated()) error.text = "MONOGRAM_XMPP_DISCONNECTED";
                else error.text = "MONOGRAM_XMPP_FAILED: " + e.getClass().getSimpleName();
            }
            final TLObject completed = response; final TLRPC.TL_error failed = error;
            if (callback != null) Utilities.stageQueue.postRunnable(() -> callback.run(completed, failed));
        });
    }
    private TLObject handle(TLObject request) throws Exception {
        if (request instanceof TLRPC.TL_updates_getState) {
            TLRPC.TL_updates_state result = new TLRPC.TL_updates_state(); result.date = now(); return result;
        }
        if (request instanceof TLRPC.TL_updates_getDifference) {
            TLRPC.TL_updates_differenceEmpty result = new TLRPC.TL_updates_differenceEmpty(); result.date = now(); return result;
        }
        if (request instanceof TLRPC.TL_contacts_getContacts) {
            session();
            TLRPC.TL_contacts_contacts result = new TLRPC.TL_contacts_contacts();
            for (RosterEntry entry : Roster.getInstanceFor(session().connection()).getEntries()) {
                long id = store.peer(entry.getJid().toString()); result.users.add(store.user(id, self));
                TLRPC.TL_contact contact = new TLRPC.TL_contact(); contact.user_id = id; contact.mutual = entry.canSeeMyPresence(); result.contacts.add(contact);
            }
            return result;
        }
        if (request instanceof TLRPC.TL_messages_getDialogs) return dialogs();
        if (request instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory query = (TLRPC.TL_messages_getHistory) request;
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            result.messages.addAll(store.history(query.peer.user_id, self, query.offset_id, query.limit));
            result.users.add(store.user(query.peer.user_id, self)); result.users.add(ownUser()); return result;
        }
        if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage send = (TLRPC.TL_messages_sendMessage) request;
            if (send.schedule_date != 0 || send.reply_to != null || send.peer.user_id == 0) throw new UnsupportedOperationException();
            long peer = send.peer.user_id; boolean encrypted = encrypted(peer);
            String stanza = session().send(store.jid(peer), send.message, encrypted);
            XmppMessage message = XmppMessage.sent(session().connection().getUser(), store.jid(peer), stanza, send.message, encrypted);
            int date = (int) (message.timestamp / 1000); int id = store.save(message);
            TLRPC.TL_updateShortSentMessage result = new TLRPC.TL_updateShortSentMessage();
            result.id = id; result.date = date; result.out = true; result.pts_count = 1;
            result.pts = MessagesStorage.getInstance(account).getLastPtsValue() + 1;
            return result;
        }
        if (request instanceof TL_account.updateStatus) {
            boolean offline = ((TL_account.updateStatus) request).offline;
            session().connection().sendStanza(new org.jivesoftware.smack.packet.Presence(offline ? org.jivesoftware.smack.packet.Presence.Type.unavailable : org.jivesoftware.smack.packet.Presence.Type.available));
            return new TLRPC.TL_boolTrue();
        }
        throw new UnsupportedOperationException(request.getClass().getSimpleName());
    }
}
