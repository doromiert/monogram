// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import com.negzero.monogram.xmpp.Account;
import com.negzero.monogram.xmpp.XmppSession;
import com.negzero.monogram.xmpp.XmppMessage;
import com.negzero.monogram.xmpp.ReplyReference;
import com.negzero.monogram.xmpp.FileLink;
import com.negzero.monogram.xmpp.FileTransfer;
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
    /** Native update payloads originating from XMPP have no Telegram PTS sequence. */
    public static final class Updates extends TLRPC.TL_updates {}
    private static final MonogramController[] instances = new MonogramController[UserConfig.MAX_ACCOUNT_COUNT];
    public static synchronized MonogramController get(int account) {
        if (instances[account] == null) instances[account] = new MonogramController(account);
        return instances[account];
    }
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService cacheWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService archiveWorker = Executors.newSingleThreadExecutor();
    private final int account;
    private final ExecutorService fileWorker = Executors.newFixedThreadPool(2);
    private final ExecutorService profileWorker = Executors.newFixedThreadPool(2);
    private final ExecutorService autoDownloadWorker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ConcurrentHashMap<Integer, FileTransfer.Job> fileJobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, FileTransfer.Job> fileNames = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, FileTransfer.Job> automaticJobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> autoAttempted = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());
    private final SharedPreferences preferences;
    private final MonogramStore store;
    private final AccountVault vault;
    private volatile XmppSession session;
    private volatile long self;
    private volatile long accountEpoch;
    private volatile int state = ConnectionsManager.ConnectionStateConnecting;
    private boolean restoring;
    private volatile long sessionEpoch;
    private volatile ArchiveSync archiveSync;
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> profileVersions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Long> profileLoads = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private final java.util.Set<Long> typingPeers = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private final java.util.concurrent.ConcurrentHashMap<Long, String> typingAddresses = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long mediaHistoryPeer;
    private int retrySeconds = 3;
    private final Runnable retryConnection = this::restore;

    private MonogramController(int account) {
        this.account = account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("monogram-" + account, Context.MODE_PRIVATE);
        store = new MonogramStore(account); vault = new AccountVault(account);
        if (preferences.contains("jid")) self = store.knownPeer(accountJid());
        preferences.edit().remove("media-auto-enabled").remove("media-auto-limit").apply();
    }
    public int state() { return state; }
    public String accountJid() { return preferences.getString("jid", ""); }
    public boolean typingEnabled() { return preferences.getBoolean("typing-enabled", true); }
    public boolean setTypingEnabled(boolean enabled) {
        if (!preferences.edit().putBoolean("typing-enabled", enabled).commit()) return false;
        XmppSession current = session;
        if (current != null) current.chatStates.setEnabled(enabled);
        return true;
    }
    public void chatOpened(long peer) { chatState(peer, 0); }
    public void typingInput(long peer, boolean composing) { chatState(peer, composing ? 1 : 0); }
    public void chatInactive(long peer) { chatState(peer, 2); }
    public void chatGone(long peer) { chatState(peer, 3); }
    private void chatState(long peer, int action) {
        XmppSession current = session;
        if (peer <= 0 || peer == self || current == null || !current.connection().isAuthenticated() || !typingEnabled()) return;
        try {
            String jid = typingAddresses.computeIfAbsent(peer, store::jid);
            if (action == 1) current.chatStates.input(jid, true);
            else if (action == 2) current.chatStates.inactive(jid);
            else if (action == 3) current.chatStates.gone(jid);
            else current.chatStates.open(jid);
        } catch (IllegalArgumentException unknown) { /* A deleted/local-only conversation has no remote state. */ }
    }
    private void publishTyping(long peer, boolean composing) {
        TL_update.TL_updateUserTyping update = new TL_update.TL_updateUserTyping();
        update.user_id = peer;
        update.action = composing ? new TLRPC.TL_sendMessageTypingAction() : new TLRPC.TL_sendMessageCancelAction();
        ArrayList<TLRPC.Update> updates = new ArrayList<>(); updates.add(update);
        ArrayList<TLRPC.User> users = new ArrayList<>();
        if (composing) users.add(store.user(peer, self));
        Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).processUpdateArray(updates, users, new ArrayList<>(), false, now()));
    }
    private void state(int value) {
        state = value;
        if (value != ConnectionsManager.ConnectionStateConnected) {
            for (long peer : new ArrayList<>(typingPeers)) if (typingPeers.remove(peer)) publishTyping(peer, false);
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.didUpdateConnectionState));
    }
    public void restore() { restore(false); }
    public void reconnect() { restore(true); }
    private synchronized void restore(boolean force) {
        if (restoring || !preferences.contains("jid")) return;
        if (!force && session != null && (session.connection().isAuthenticated() || session.connection().isConnected())) return;
        restoring = true;
        AndroidUtilities.cancelRunOnUIThread(retryConnection);
        state(ConnectionsManager.ConnectionStateConnecting);
        worker.execute(() -> {
            try { connect(new Account(preferences.getString("jid", ""), preferences.getString("host", ""), preferences.getInt("port", 5222), "Monogram"),
                    vault.decrypt(preferences.getString("credential", ""))); }
            catch (Exception e) { state(ConnectionsManager.ConnectionStateWaitingForNetwork); retryConnection(); }
            finally { synchronized (this) { restoring = false; } }
        });
    }
    private void retryConnection() {
        if (!preferences.contains("jid")) return;
        AndroidUtilities.cancelRunOnUIThread(retryConnection);
        AndroidUtilities.runOnUIThread(retryConnection, retrySeconds * 1000L);
        retrySeconds = Math.min(60, retrySeconds * 2);
    }
    private void connect(Account details, String password) throws Exception {
        if (session != null) { session.close(); session = null; }
        final long epoch = ++sessionEpoch;
        autoAttempted.clear();
        state(ConnectionsManager.ConnectionStateConnecting);
        AndroidSmackInitializer.initialize(ApplicationLoader.applicationContext);
        XmppSession.initializeOmemo(new File(ApplicationLoader.getFilesDirFixed(), "monogram-omemo"));
        int device = preferences.getInt("device", 0);
        if (device == 0) {
            device = org.jivesoftware.smackx.omemo.OmemoManager.randomDeviceId();
            if (!preferences.edit().putInt("device", device).commit()) throw new java.io.IOException("Cannot save device identity");
        }
        self = store.peer(details.jid.toString());
        if (UserConfig.getInstance(account).isClientActivated() && UserConfig.getInstance(account).getClientUserId() == self
                && details.jid.toString().equals(accountJid())) publishProfile(self, epoch);
        XmppSession connecting = new XmppSession(details, password, device,
                new File(ApplicationLoader.getFilesDirFixed(), "monogram-trust-" + account),
                new XmppSession.Listener() {
                    @Override public void message(org.jxmpp.jid.EntityBareJid sender, String id, String body, boolean encrypted) {}
                    @Override public void message(XmppMessage message) { worker.execute(() -> { if (sessionEpoch == epoch && session != null) incoming(message); }); }
                    @Override public void profileChanged(org.jxmpp.jid.EntityBareJid jid) {
                        worker.execute(() -> { if (sessionEpoch == epoch && session != null) { long peer = store.knownPeer(jid.toString()); if (peer != 0) refreshProfile(peer); } });
                    }
                    @Override public void typing(org.jxmpp.jid.EntityBareJid jid, boolean composing) {
                        worker.execute(() -> {
                            if (sessionEpoch != epoch || session == null || !preferences.contains("jid") || composing && state != ConnectionsManager.ConnectionStateConnected) return;
                            long peer = store.knownPeer(jid.toString());
                            if (peer == 0 || peer == self) return;
                            if (composing) typingPeers.add(peer); else typingPeers.remove(peer);
                            publishTyping(peer, composing);
                        });
                    }
                    @Override public void disconnected() {
                        worker.execute(() -> {
                            if (sessionEpoch != epoch || session == null) return;
                            archiveSync = null;
                            state(ConnectionsManager.ConnectionStateWaitingForNetwork); retryConnection();
                        });
                    }
                    @Override public void reconnected(boolean resumed) {
                        worker.execute(() -> {
                            if (sessionEpoch != epoch || session == null) return;
                            AndroidUtilities.cancelRunOnUIThread(retryConnection); retrySeconds = 3;
                            state(ConnectionsManager.ConnectionStateConnected); startArchiveSync(session);
                        });
                    }
                });
        Roster roster = Roster.getInstanceFor(connecting.connection());
        roster.addRosterListener(new org.jivesoftware.smack.roster.RosterListener() {
            private void changed(java.util.Collection<org.jxmpp.jid.Jid> addresses) {
                worker.execute(() -> {
                    if (sessionEpoch != epoch || session != connecting) return;
                    for (org.jxmpp.jid.Jid jid : addresses) {
                        RosterEntry entry = roster.getEntry(jid.asBareJid());
                        long peer = store.knownPeer(jid.asBareJid().toString());
                        if (peer == 0 && entry != null) peer = store.peer(jid.asBareJid().toString());
                        if (peer != 0) { store.rosterName(peer, entry == null ? "" : entry.getName()); publishProfile(peer, epoch); refreshProfile(peer); }
                    }
                });
            }
            @Override public void entriesAdded(java.util.Collection<org.jxmpp.jid.Jid> addresses) { changed(addresses); }
            @Override public void entriesUpdated(java.util.Collection<org.jxmpp.jid.Jid> addresses) { changed(addresses); }
            @Override public void entriesDeleted(java.util.Collection<org.jxmpp.jid.Jid> addresses) { changed(addresses); }
            @Override public void presenceChanged(org.jivesoftware.smack.packet.Presence presence) {}
        });
        connecting.chatStates.setEnabled(typingEnabled());
        connecting.connect();
        session = connecting;
        for (RosterEntry entry : Roster.getInstanceFor(connecting.connection()).getEntries()) {
            long peer = store.peer(entry.getJid().toString()); store.rosterName(peer, entry.getName());
        }
        refreshProfile(self);
        for (long peer : store.peers()) if (peer != self) refreshProfile(peer);
        state(ConnectionsManager.ConnectionStateConnected);
        AndroidUtilities.cancelRunOnUIThread(retryConnection); retrySeconds = 3;
        startArchiveSync(connecting);
    }
    private static final class ArchiveTask {
        final long peer;
        boolean backwards, resetAttempted;
        ArchiveTask(long peer) { this.peer = peer; }
    }
    private static final class ArchiveSync {
        final XmppSession session;
        final long epoch;
        final java.util.ArrayDeque<ArchiveTask> tasks = new java.util.ArrayDeque<>();
        final java.util.Set<Long> peers = new java.util.HashSet<>();
        final java.util.Set<Long> pending = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
        boolean supported;
        ArchiveSync(XmppSession session, long epoch) { this.session = session; this.epoch = epoch; }
        boolean add(long peer) {
            if (!peers.add(peer)) return false;
            pending.add(peer); tasks.addLast(new ArchiveTask(peer)); return true;
        }
    }
    private boolean currentArchive(ArchiveSync sync) {
        return archiveSync == sync && accountEpoch == sync.epoch && session == sync.session && sync.session.connection().isAuthenticated();
    }
    private void startArchiveSync(XmppSession expected) {
        ArchiveSync sync = new ArchiveSync(expected, accountEpoch); archiveSync = sync;
        for (long peer : store.peers()) if (peer != self) sync.add(peer);
        // Keep discovery for conversations that exist only in the server archive.
        sync.add(0);
        nextArchivePage(sync);
    }
    private void nextArchivePage(ArchiveSync sync) {
        if (!currentArchive(sync)) return;
        ArchiveTask task;
        while ((task = sync.tasks.pollFirst()) != null) {
            if (task.backwards && store.archiveComplete(task.peer)) { sync.pending.remove(task.peer); continue; }
            final ArchiveTask next = task;
            archiveWorker.execute(() -> syncArchive(sync, next));
            return;
        }
        archiveSync = null;
    }
    private void syncArchive(ArchiveSync sync, ArchiveTask task) {
        if (!currentArchive(sync)) return;
        try {
            if (!sync.supported) {
                if (!sync.session.hasArchive()) { worker.execute(() -> { if (currentArchive(sync)) archiveSync = null; }); return; }
                sync.supported = true;
            }
            String cursor = store.archiveCursor(task.peer, task.backwards);
            String jid = task.peer == 0 ? null : store.jid(task.peer);
            XmppSession.ArchivePage page = !task.backwards && cursor != null
                    ? sync.session.archiveAfter(jid, cursor, 100) : sync.session.archiveBefore(jid, cursor, 100);
            if (cursor != null && cursor.equals(task.backwards ? page.firstId : page.lastId) && !page.complete) {
                throw new java.io.IOException("Archive cursor did not advance");
            }
            boolean initial = !task.backwards && cursor == null;
            worker.execute(() -> {
                if (!currentArchive(sync)) return;
                try { archivePage(sync, task, page, initial); }
                catch (Exception error) { archiveFailed(sync, task, error); }
            });
        } catch (Exception error) { worker.execute(() -> { if (currentArchive(sync)) archiveFailed(sync, task, error); }); }
    }
    private void archivePage(ArchiveSync sync, ArchiveTask task, XmppSession.ArchivePage page, boolean initial) {
        java.util.Set<Integer> updated = new java.util.HashSet<>();
        boolean changed = store.saveArchive(task.peer, page, task.backwards || initial, updated);
        java.util.Set<Long> replyPeers = new java.util.HashSet<>();
        for (XmppMessage message : page.messages) replyPeers.add(store.peer(message.peer.toString()));
        for (long peer : replyPeers) {
            MonogramStore.CorrectionChanges corrections = store.reconcileCorrections(peer, updated);
            updated.addAll(corrections.edited);
            publishCorrections(peer, correctionUpdates(corrections, 0));
            refreshReplies(peer, updated);
            if (peer != self && sync.add(peer)) refreshProfile(peer);
        }
        if (changed && UserConfig.getInstance(account).isClientActivated()) {
            TLRPC.TL_messages_dialogs dialogs = dialogs();
            AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).processLoadedDialogs(
                    dialogs, null, null, 0, 0, dialogs.dialogs.size(), 3, false, false, false));
        }
        boolean older = task.backwards || initial || page.complete;
        if (older ? !store.archiveComplete(task.peer) : page.lastId != null) {
            task.backwards = older;
            // One page per chat per turn, including account-wide discovery.
            sync.tasks.addLast(task);
        } else sync.pending.remove(task.peer);
        nextArchivePage(sync);
    }
    private void archiveFailed(ArchiveSync sync, ArchiveTask task, Exception error) {
        if (!task.resetAttempted && error instanceof org.jivesoftware.smack.XMPPException.XMPPErrorException
                && ((org.jivesoftware.smack.XMPPException.XMPPErrorException) error).getStanzaError().getCondition()
                == org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found) {
            task.resetAttempted = true; task.backwards = false;
            store.resetArchiveCursor(task.peer); sync.tasks.addLast(task);
        } else {
            sync.pending.remove(task.peer);
            // A failed chat must not prevent other chats from syncing or erase cached history.
            if (BuildVars.LOGS_ENABLED) FileLog.e("Monogram archive sync: " + error.getClass().getSimpleName());
        }
        nextArchivePage(sync);
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
        long epoch = accountEpoch;
        cacheWorker.execute(() -> {
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            mediaHistoryPeer = peer;
            ArchiveSync sync = archiveSync;
            boolean end = sync == null || !sync.pending.contains(peer);
            try {
                result.messages.addAll(loadType == 3 && offset > 0 ? store.historyAround(peer, self, offset, count) : store.history(peer, self, offset, count, loadType == 1));
                result.users.add(store.user(peer, self)); if (self > 0) result.users.add(ownUser());
                ArrayList<TLRPC.Message> automatic = new ArrayList<>(result.messages);
                worker.execute(() -> { if (epoch == accountEpoch) for (TLRPC.Message message : automatic) maybeAutoDownload(message); });
            } catch (RuntimeException failed) {
                if (BuildVars.LOGS_ENABLED) FileLog.e("Monogram cached history: " + failed.getClass().getSimpleName());
                result.messages.clear(); result.users.clear(); end = true;
            }
            final boolean archiveEnd = end;
            Utilities.stageQueue.postRunnable(() -> completed.accept(epoch == accountEpoch ? result : new TLRPC.TL_messages_messages(), archiveEnd));
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
        accountEpoch++;
        AndroidUtilities.cancelRunOnUIThread(retryConnection);
        worker.execute(() -> {
            for (long peer : new ArrayList<>(typingPeers)) if (typingPeers.remove(peer)) publishTyping(peer, false);
            typingAddresses.clear();
            if (session != null) { session.close(); session = null; }
            sessionEpoch++; archiveSync = null;
            for (FileTransfer.Job job : fileJobs.values()) job.cancel();
            fileJobs.clear(); fileNames.clear();
            automaticJobs.clear(); autoAttempted.clear();
            store.clear(); self = 0;
            state(ConnectionsManager.ConnectionStateWaitingForNetwork);
        });
    }
    public java.io.File avatarPath(long peer) { return store.avatar(peer); }
    public java.util.Map<String, String> profileDetails(long peer) {
        try { return MonogramProfiles.details(store.profile(peer), store.jid(peer)); }
        catch (IllegalArgumentException unknown) { return java.util.Collections.emptyMap(); }
    }
    public com.negzero.monogram.xmpp.XmppProfile cachedProfile(long peer) { return store.profile(peer); }
    public void refreshProfile(long peer) {
        if (peer <= 0 || !profileLoads.add(peer)) return;
        final XmppSession expected = session;
        final long epoch = sessionEpoch;
        final String jid;
        try { jid = store.jid(peer); }
        catch (IllegalArgumentException unknown) { profileLoads.remove(peer); return; }
        final long version = profileVersions.getOrDefault(peer, 0L);
        profileWorker.execute(() -> {
            try {
                if (sessionEpoch != epoch || expected == null || session != expected) return;
                com.negzero.monogram.xmpp.XmppProfile profile = expected.profiles.load(jid);
                worker.execute(() -> {
                    if (sessionEpoch != epoch || session != expected || !preferences.contains("jid") || profileVersions.getOrDefault(peer, 0L) != version) return;
                    try { store.profile(peer, profile); if (peer == self) expected.profiles.useOwnProfile(profile); publishProfile(peer, epoch); }
                    catch (Exception failed) { if (BuildVars.LOGS_ENABLED) FileLog.e("Monogram profile cache: " + failed.getClass().getSimpleName()); }
                });
            } catch (Exception failed) {
                if (BuildVars.LOGS_ENABLED) FileLog.e("Monogram profile: " + failed.getClass().getSimpleName());
            } finally { profileLoads.remove(peer); }
        });
    }
    private void publishProfile(long peer, long epoch) {
        TLRPC.User user = store.user(peer, self);
        ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(user);
        MessagesStorage.getInstance(account).putUsersAndChats(users, null, false, true);
        AndroidUtilities.runOnUIThread(() -> {
            if (sessionEpoch != epoch) return;
            MessagesController.getInstance(account).putUser(user, false);
            if (peer == self) {
                UserConfig.getInstance(account).setCurrentUser(user); UserConfig.getInstance(account).saveConfig(true);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
            }
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
            MessagesController.getInstance(account).loadUserInfo(user, true, 0);
        });
    }
    private TLRPC.TL_users_userFull profileResult(long peer) {
        TLRPC.TL_users_userFull result = new TLRPC.TL_users_userFull();
        result.full_user = store.fullUser(peer, self); result.users.add(result.full_user.user); return result;
    }
    public TLRPC.User ownUser() { return store.user(self, self); }
    public String jid(long peer) { return store.jid(peer); }
    public XmppSession session() {
        XmppSession current = session;
        if (current == null || !current.connection().isAuthenticated()) throw new IllegalStateException("XMPP is disconnected");
        return current;
    }
    public boolean encrypted(long peer) { return preferences.getBoolean("omemo-" + peer, true); }
    public void autoDownloadSettingsChanged() {
        for (java.util.Map.Entry<Integer, FileTransfer.Job> entry : automaticJobs.entrySet()) {
            entry.getValue().cancel(); fileJobs.remove(entry.getKey(), entry.getValue());
            for (java.util.Map.Entry<String, FileTransfer.Job> name : fileNames.entrySet())
                if (name.getValue() == entry.getValue()) fileNames.remove(name.getKey(), name.getValue());
        }
        automaticJobs.clear(); autoAttempted.clear();
        worker.execute(() -> { if (mediaHistoryPeer > 0 && self > 0) for (TLRPC.Message message : store.history(mediaHistoryPeer, self, 0, 100)) maybeAutoDownload(message); });
    }
    public void setEncrypted(long peer, boolean encrypted) {
        if (!preferences.edit().putBoolean("omemo-" + peer, encrypted).commit()) throw new IllegalStateException("Cannot save encryption preference");
    }
    private int now() { return (int) (System.currentTimeMillis() / 1000); }
    private void incoming(XmppMessage message) {
        long peer = store.peer(message.peer.toString());
        if (store.profile(peer) == null) refreshProfile(peer);
        int date = (int) (message.timestamp / 1000);
        int id = store.save(message);
        java.util.Set<Integer> updated = new java.util.HashSet<>(); if (id != 0) updated.add(id);
        MonogramStore.CorrectionChanges corrections = store.reconcileCorrections(peer, updated);
        updated.addAll(corrections.edited); refreshReplies(peer, updated);
        ArrayList<TLRPC.Update> updates = correctionUpdates(corrections, id);
        if (id != 0 && !store.hiddenCorrection(id)) {
            TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage(); update.message = store.getMessage(id, self); updates.add(update);
        }
        if (updates.isEmpty()) return;
        if (!UserConfig.getInstance(account).isClientActivated()) return;
        ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(store.user(peer, self)); users.add(ownUser());
        Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).processUpdateArray(updates, users, new ArrayList<>(), false, date));
        if (typingPeers.contains(peer)) publishTyping(peer, true);
        if (id != 0 && !store.hiddenCorrection(id)) maybeAutoDownload(store.getMessage(id, self));
    }
    private ArrayList<TLRPC.Update> correctionUpdates(MonogramStore.CorrectionChanges changes, int arriving) {
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        for (int id : changes.edited) {
            TL_update.TL_updateEditMessage edit = new TL_update.TL_updateEditMessage(); edit.message = store.getMessage(id, self); updates.add(edit);
        }
        TL_update.TL_updateDeleteMessages hidden = new TL_update.TL_updateDeleteMessages();
        for (int id : changes.hidden) if (id != arriving) hidden.messages.add(id);
        if (!hidden.messages.isEmpty()) updates.add(hidden);
        for (int id : changes.shown) if (id != arriving) {
            TL_update.TL_updateNewMessage shown = new TL_update.TL_updateNewMessage(); shown.message = store.getMessage(id, self); updates.add(shown);
        }
        return updates;
    }
    private void publishCorrections(long peer, ArrayList<TLRPC.Update> updates) {
        if (updates.isEmpty() || !UserConfig.getInstance(account).isClientActivated()) return;
        ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(store.user(peer, self)); users.add(ownUser());
        Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account).processUpdateArray(updates, users, new ArrayList<>(), false, now()));
    }
    private void refreshReplies(long peer) { refreshReplies(peer, java.util.Collections.emptySet()); }
    private void refreshReplies(long peer, java.util.Set<Integer> updated) {
        ArrayList<TLRPC.Message> changed = store.changedReplies(peer, self, updated);
        if (changed.isEmpty() || !UserConfig.getInstance(account).isClientActivated()) return;
        long epoch = sessionEpoch;
        MessagesStorage.getInstance(account).putMessages(changed, false, true, false, 0, 0, 0);
        AndroidUtilities.runOnUIThread(() -> {
            if (sessionEpoch != epoch) return;
            ArrayList<MessageObject> objects = new ArrayList<>();
            for (TLRPC.Message message : changed) objects.add(new MessageObject(account, message, true, true));
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, peer, objects);
        });
    }
    private static final class ReplyUnavailable extends IllegalArgumentException {
        ReplyUnavailable(Exception cause) { super("Original reply message is unavailable", cause); }
    }
    private ReplyReference replyTo(int target, long peer, boolean encrypted, String quote, int offset) {
        try { return store.replyTo(target, peer, self, encrypted, quote, offset); }
        catch (IllegalArgumentException invalid) { throw new ReplyUnavailable(invalid); }
    }
    private ReplyReference outgoingReply(TLRPC.InputReplyTo reply, long peer, boolean encrypted) {
        if (reply == null) return null;
        if (!(reply instanceof TLRPC.TL_inputReplyToMessage) || reply.top_msg_id != 0 || reply.todo_item_id != 0 || reply.poll_option != null
                || reply.monoforum_peer_id != null || reply.reply_to_peer_id != null && reply.reply_to_peer_id.user_id != peer) throw new UnsupportedOperationException();
        return replyTo(reply.reply_to_msg_id, peer, encrypted, reply.quote_text, reply.quote_offset);
    }
    private void maybeAutoDownload(TLRPC.Message message) {
        if (DownloadController.getInstance(account).getCurrentDownloadMask() == 0 || !MonogramAttachments.has(message) || new File(message.attachPath).isFile() || !autoAttempted.add(message.id)) return;
        download(new MessageObject(account, message, true, true), true);
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
    public void reserveFile(MonogramFileRequest request) { fileJobs.put(request.localId, request.job); }
    public File attachmentPath(int id) { return store.attachmentPath(id); }
    public boolean isFileLoading(String name) { return name != null && fileNames.containsKey(name); }
    public void cancelFile(int message) {
        FileTransfer.Job job = fileJobs.remove(message);
        if (job != null) { job.cancel(); for (java.util.Map.Entry<String, FileTransfer.Job> entry : fileNames.entrySet()) if (entry.getValue() == job) fileNames.remove(entry.getKey(), job); }
    }
    public void cancelFile(String name) {
        FileTransfer.Job job = name == null ? null : fileNames.remove(name);
        if (job != null) job.cancel();
    }
    private void progress(String name, long bytes, long total, boolean upload, boolean encrypted) {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(
                upload ? NotificationCenter.fileUploadProgressChanged : NotificationCenter.fileLoadProgressChanged,
                name, bytes, total <= 0 ? Math.max(bytes, 1) : total, encrypted));
    }
    private TLRPC.TL_error fileError(Exception e) {
        TLRPC.TL_error error = new TLRPC.TL_error(); error.code = 400;
        if (e instanceof ReplyUnavailable) error.text = "MONOGRAM_REPLY_UNAVAILABLE";
        else if (e instanceof FileTransfer.Failure) error.text = "MONOGRAM_FILE_" + ((FileTransfer.Failure) e).reason;
        else if (e instanceof org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException) error.text = "MONOGRAM_OMEMO_TRUST_REQUIRED";
        else if (e instanceof com.negzero.monogram.xmpp.OmemoSendException) error.text = "MONOGRAM_OMEMO_" + ((com.negzero.monogram.xmpp.OmemoSendException) e).reason.name();
        else if (e instanceof UnsupportedOperationException) error.text = "MONOGRAM_FEATURE_UNAVAILABLE";
        else if (session == null || !session.connection().isAuthenticated()) error.text = "MONOGRAM_XMPP_DISCONNECTED";
        else error.text = "MONOGRAM_FILE_TRANSFER_FAILED";
        return error;
    }
    private void upload(MonogramFileRequest request, RequestDelegate callback) {
        final XmppSession expected;
        try {
            request.job.check();
            expected = session();
            if (!request.supported || request.message.dialog_id <= 0)
                throw new UnsupportedOperationException();
            if (request.file == null || !request.file.isFile()) throw new java.io.FileNotFoundException();
        } catch (Exception e) {
            fileJobs.remove(request.localId, request.job);
            TLRPC.TL_error error = fileError(e);
            if (!request.job.cancelled()) Utilities.stageQueue.postRunnable(() -> callback.run(null, error));
            return;
        }
        boolean encrypted = encrypted(request.message.dialog_id);
        String peer = store.jid(request.message.dialog_id);
        final ReplyReference reply;
        try {
            TLRPC.MessageReplyHeader header = request.message.reply_to;
            if (header != null && (header.reply_to_peer_id != null && header.reply_to_peer_id.user_id != request.message.dialog_id || header.reply_to_top_id != 0)) throw new UnsupportedOperationException();
            reply = header == null ? null : replyTo(header.reply_to_msg_id, request.message.dialog_id, encrypted, header.quote_text, header.quote_offset);
        } catch (Exception failed) {
            fileJobs.remove(request.localId, request.job);
            Utilities.stageQueue.postRunnable(() -> callback.run(null, fileError(failed))); return;
        }
        FileTransfer.Job job = request.job;
        fileJobs.put(request.localId, job); fileNames.put(request.message.attachPath, job);
        String progressName = request.message.attachPath;
        fileWorker.execute(() -> {
            try {
                FileLink link = FileTransfer.upload(expected.connection(), request.file, request.mime, encrypted, job,
                        (bytes, total) -> progress(progressName, bytes, total, true, encrypted));
                File cached = store.attachmentFile(link.wireUrl);
                if (!cached.getParentFile().isDirectory() && !cached.getParentFile().mkdirs()) throw new java.io.IOException("Cannot create attachment cache");
                if (!AndroidUtilities.copyFile(request.file, cached)) throw new java.io.IOException("Cannot cache attachment");
                worker.execute(() -> {
                    try {
                        job.check();
                        if (session != expected || encrypted(request.message.dialog_id) != encrypted) throw new IllegalStateException("Sending mode or account changed");
                        String stanza = expected.sendFile(peer, link, encrypted, reply);
                        XmppMessage message = XmppMessage.sentFile(expected.connection().getUser(), peer, stanza, link, encrypted, reply);
                        int id = store.save(message);
                        TLRPC.MessageMedia media = request.message.media.photo == null ? request.message.media
                                : MonogramAttachments.media(id, (int) (message.timestamp / 1000), cached, link.fileName());
                        store.attach(id, link.wireUrl, cached, media);
                        refreshReplies(request.message.dialog_id);
                        MonogramAttachments.apply(request.message, link, cached, media);
                        TLRPC.TL_updateShortSentMessage response = new TLRPC.TL_updateShortSentMessage();
                        response.id = id; response.date = (int) (message.timestamp / 1000); response.out = true; response.pts_count = 1;
                        response.pts = MessagesStorage.getInstance(account).getLastPtsValue() + 1;
                        Utilities.stageQueue.postRunnable(() -> {
                            callback.run(response, null);
                            if (request.caption != null && !request.caption.isEmpty()) AndroidUtilities.runOnUIThread(() ->
                                    SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                                            request.caption, request.message.dialog_id, null, null, null, false, null, null, null, true, 0, 0, null, false)));
                        });
                    } catch (Exception e) {
                        TLRPC.TL_error error = fileError(e);
                        if (!job.cancelled()) Utilities.stageQueue.postRunnable(() -> callback.run(null, error));
                    } finally { fileJobs.remove(request.localId, job); fileNames.remove(progressName, job); }
                });
            } catch (Exception e) {
                TLRPC.TL_error error = fileError(e);
                if (!job.cancelled()) Utilities.stageQueue.postRunnable(() -> callback.run(null, error));
                fileJobs.remove(request.localId, job); fileNames.remove(progressName, job);
            }
        });
    }
    public void download(MessageObject object) {
        download(object, false);
    }
    private void finishDownload(int id, String name, FileTransfer.Job job) {
        fileJobs.remove(id, job); fileNames.remove(name, job); automaticJobs.remove(id, job);
    }
    private void download(MessageObject object, boolean automatic) {
        if (!MonogramAttachments.has(object.messageOwner)) return;
        int id = object.getId();
        FileTransfer.Job job = new FileTransfer.Job();
        if (fileJobs.putIfAbsent(id, job) != null) return;
        if (automatic) automaticJobs.put(id, job);
        String name = object.getFileName(); fileNames.put(name, job);
        String address = object.messageOwner.params.get(MonogramAttachments.PARAM);
        long epoch = sessionEpoch;
        (automatic ? autoDownloadWorker : fileWorker).execute(() -> {
            try {
                job.check();
                FileLink link = FileLink.parse(address); File path = store.attachmentFile(address);
                FileTransfer.Progress onProgress = (bytes, total) -> progress(name, bytes, total, false, link.encrypted());
                if (automatic && !FileTransfer.download(link, path, job, onProgress, (mime, size) ->
                        DownloadController.getInstance(account).getMonogramDownloadLimit(object.messageOwner, MonogramAttachments.remoteMedia(id, link.fileName(), mime, size), size))) {
                    finishDownload(id, name, job);
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoadFailed, name, 0));
                    return;
                }
                if (!automatic) FileTransfer.download(link, path, job, onProgress);
                worker.execute(() -> {
                    try {
                        job.check();
                        if (sessionEpoch != epoch) return;
                        TLRPC.Message saved = store.getMessage(id, self);
                        store.attach(id, address, path, MonogramAttachments.media(id, saved.date, path, link.fileName()));
                        TLRPC.Message updated = store.getMessage(id, self);
                        MonogramAttachments.apply(object.messageOwner, link, path, updated.media);
                        ArrayList<TLRPC.Message> messages = new ArrayList<>(); messages.add(updated);
                        MessagesStorage.getInstance(account).putMessages(messages, false, true, false, 0, 0, 0);
                        AndroidUtilities.runOnUIThread(() -> {
                            ArrayList<MessageObject> objects = new ArrayList<>(); objects.add(new MessageObject(account, updated, true, true));
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, updated.dialog_id, objects);
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoaded, name, path);
                        });
                    } catch (Exception e) { downloadError(name, object, job, e, automatic); }
                    finally { finishDownload(id, name, job); }
                });
            } catch (Exception e) {
                downloadError(name, object, job, e, automatic); finishDownload(id, name, job);
            }
        });
    }
    private void downloadError(String name, MessageObject object, FileTransfer.Job job, Exception error, boolean automatic) {
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.fileLoadFailed, name, 0);
            if (!automatic && !job.cancelled()) org.telegram.ui.Components.AlertsCreator.processError(account, fileError(error), null,
                    new MonogramFileRequest(object.messageOwner, null, "application/octet-stream", ""));
        });
    }
    public void request(TLObject request, RequestDelegate callback) {
        if (request instanceof MonogramFileRequest) { worker.execute(() -> upload((MonogramFileRequest) request, callback)); return; }
        boolean cached = request instanceof TLRPC.TL_messages_getDialogs || request instanceof TLRPC.TL_messages_getHistory
                || request instanceof TLRPC.TL_messages_getMessages || request instanceof TLRPC.TL_users_getFullUser
                || request instanceof TLRPC.TL_users_getUsers || request instanceof TLRPC.TL_photos_getUserPhotos
                || request instanceof TLRPC.TL_messages_getMessageEditData;
        long epoch = accountEpoch;
        (cached ? cacheWorker : worker).execute(() -> {
            TLObject response = null; TLRPC.TL_error error = null;
            try { response = handle(request); }
            catch (Exception e) {
                error = new TLRPC.TL_error(); error.code = 400;
                if (e instanceof ReplyUnavailable) error.text = "MONOGRAM_REPLY_UNAVAILABLE";
                else if ((request instanceof TLRPC.TL_messages_editMessage || request instanceof TLRPC.TL_messages_getMessageEditData)
                        && e instanceof IllegalArgumentException) error.text = "MONOGRAM_EDIT_UNAVAILABLE";
                else if (e instanceof org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException) error.text = "MONOGRAM_OMEMO_TRUST_REQUIRED";
                else if (e instanceof com.negzero.monogram.xmpp.OmemoSendException) error.text = "MONOGRAM_OMEMO_" + ((com.negzero.monogram.xmpp.OmemoSendException) e).reason.name();
                else if (e instanceof UnsupportedOperationException) error.text = "MONOGRAM_FEATURE_UNAVAILABLE";
                else if (session == null || !session.connection().isAuthenticated()) error.text = "MONOGRAM_XMPP_DISCONNECTED";
                else error.text = "MONOGRAM_XMPP_FAILED: " + e.getClass().getSimpleName();
            }
            final TLObject completed = response; final TLRPC.TL_error failed = error;
            if (callback != null) Utilities.stageQueue.postRunnable(() -> {
                if (epoch != accountEpoch) {
                    TLRPC.TL_error changed = new TLRPC.TL_error(); changed.code = 400; changed.text = "MONOGRAM_ACCOUNT_CHANGED";
                    callback.run(null, changed);
                } else callback.run(completed, failed);
            });
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
                long id = store.peer(entry.getJid().toString()); store.rosterName(id, entry.getName()); result.users.add(store.user(id, self));
                TLRPC.TL_contact contact = new TLRPC.TL_contact(); contact.user_id = id; contact.mutual = entry.canSeeMyPresence(); result.contacts.add(contact);
            }
            return result;
        }
        if (request instanceof TLRPC.TL_users_getUsers) {
            org.telegram.tgnet.Vector<TLRPC.User> result = new org.telegram.tgnet.Vector<>(TLRPC.User::TLdeserialize);
            for (TLRPC.InputUser input : ((TLRPC.TL_users_getUsers) request).id) {
                long peer = input instanceof TLRPC.TL_inputUserSelf ? self : input.user_id;
                result.objects.add(store.user(peer, self));
            }
            return result;
        }
        if (request instanceof TLRPC.TL_users_getFullUser) {
            TLRPC.InputUser input = ((TLRPC.TL_users_getFullUser) request).id;
            long peer = input instanceof TLRPC.TL_inputUserSelf ? self : input.user_id;
            // Respond from the offline cache; refresh separately without blocking the profile screen.
            if (store.profile(peer) == null) refreshProfile(peer);
            return profileResult(peer);
        }
        if (request instanceof TL_account.updateProfile) {
            TL_account.updateProfile edit = (TL_account.updateProfile) request;
            String first = (edit.flags & 1) != 0 ? edit.first_name : null;
            String last = (edit.flags & 2) != 0 ? edit.last_name : null;
            String about = (edit.flags & 4) != 0 ? edit.about : null;
            profileVersions.merge(self, 1L, Long::sum);
            com.negzero.monogram.xmpp.XmppProfile changed = session().profiles.edit(first, last, about);
            profileVersions.merge(self, 1L, Long::sum);
            store.profile(self, changed); publishProfile(self, sessionEpoch);
            return ownUser();
        }
        if (request instanceof TLRPC.TL_photos_uploadProfilePhoto) {
            TLRPC.TL_photos_uploadProfilePhoto upload = (TLRPC.TL_photos_uploadProfilePhoto) request;
            if (!(upload.file instanceof MonogramAvatarFile) || upload.video != null || upload.video_emoji_markup != null) throw new UnsupportedOperationException();
            File source = ((MonogramAvatarFile) upload.file).source;
            if (source.length() > com.negzero.monogram.xmpp.XmppProfile.MAX_AVATAR_BYTES) throw new IllegalArgumentException("Avatar too large");
            byte[] bytes = new byte[(int) source.length()];
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(source))) { in.readFully(bytes); }
            profileVersions.merge(self, 1L, Long::sum);
            com.negzero.monogram.xmpp.XmppProfile changed = session().profiles.avatar(MonogramProfiles.png(bytes));
            profileVersions.merge(self, 1L, Long::sum);
            store.profile(self, changed); publishProfile(self, sessionEpoch);
            TLRPC.TL_photos_photo result = new TLRPC.TL_photos_photo(); result.photo = store.fullUser(self, self).profile_photo;
            result.users.add(ownUser()); return result;
        }
        if (request instanceof TLRPC.TL_photos_updateProfilePhoto) {
            if (!(((TLRPC.TL_photos_updateProfilePhoto) request).id instanceof TLRPC.TL_inputPhotoEmpty)) throw new UnsupportedOperationException();
            profileVersions.merge(self, 1L, Long::sum);
            com.negzero.monogram.xmpp.XmppProfile changed = session().profiles.avatar(new byte[0]);
            profileVersions.merge(self, 1L, Long::sum);
            store.profile(self, changed); publishProfile(self, sessionEpoch);
            TLRPC.TL_photos_photo result = new TLRPC.TL_photos_photo(); result.photo = new TLRPC.TL_photoEmpty(); result.users.add(ownUser()); return result;
        }
        if (request instanceof TLRPC.TL_photos_getUserPhotos) {
            TLRPC.TL_photos_getUserPhotos query = (TLRPC.TL_photos_getUserPhotos) request;
            long peer = query.user_id instanceof TLRPC.TL_inputUserSelf ? self : query.user_id.user_id;
            TLRPC.TL_photos_photos result = new TLRPC.TL_photos_photos();
            TLRPC.Photo photo = store.fullUser(peer, self).profile_photo;
            if (photo instanceof TLRPC.TL_photo && query.offset == 0 && query.max_id == 0 && query.limit > 0) result.photos.add(photo);
            result.users.add(store.user(peer, self)); result.count = photo instanceof TLRPC.TL_photo ? 1 : 0; return result;
        }
        if (request instanceof TLRPC.TL_messages_getDialogs) return dialogs();
        if (request instanceof TLRPC.TL_messages_getMessages) {
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            java.util.Set<Long> peers = new java.util.HashSet<>();
            for (int input : ((TLRPC.TL_messages_getMessages) request).id) {
                try { TLRPC.Message message = store.getMessage(input, self); result.messages.add(message); peers.add(message.dialog_id); }
                catch (IllegalArgumentException missing) { /* The requested local message is unavailable. */ }
            }
            for (long peer : peers) result.users.add(store.user(peer, self)); result.users.add(ownUser()); return result;
        }
        if (request instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.TL_messages_getHistory query = (TLRPC.TL_messages_getHistory) request;
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            result.messages.addAll(store.history(query.peer.user_id, self, query.offset_id, query.limit));
            result.users.add(store.user(query.peer.user_id, self)); result.users.add(ownUser()); return result;
        }
        if (request instanceof TLRPC.TL_messages_getMessageEditData) {
            TLRPC.TL_messages_getMessageEditData query = (TLRPC.TL_messages_getMessageEditData) request;
            if (query.peer == null || query.peer.user_id <= 0) throw new IllegalArgumentException("Direct chat required");
            store.editTarget(query.id, query.peer.user_id, self);
            return new TLRPC.TL_messages_messageEditData();
        }
        if (request instanceof TLRPC.TL_messages_editMessage) {
            TLRPC.TL_messages_editMessage edit = (TLRPC.TL_messages_editMessage) request;
            if (edit.peer == null || edit.peer.user_id == 0 || edit.media != null || edit.schedule_date != 0 || edit.quick_reply_shortcut_id != 0
                    || (edit.flags & 2048) == 0) throw new UnsupportedOperationException();
            long peer = edit.peer.user_id;
            MonogramStore.EditTarget target = store.editTarget(edit.id, peer, self);
            boolean encrypted = target.encrypted || encrypted(peer);
            String stanza = session().correct(store.jid(peer), edit.message, encrypted, target.reply, target.correction);
            int saved = store.save(XmppMessage.sent(session().connection().getUser(), store.jid(peer), stanza, edit.message,
                    encrypted, target.reply, target.correction));
            MonogramStore.CorrectionChanges corrections = store.reconcileCorrections(peer, java.util.Collections.singleton(saved));
            refreshReplies(peer, corrections.edited);
            corrections.edited.remove(edit.id); publishCorrections(peer, correctionUpdates(corrections, saved));
            Updates result = new Updates(); result.date = now();
            TL_update.TL_updateEditMessage update = new TL_update.TL_updateEditMessage(); update.message = store.getMessage(edit.id, self); result.updates.add(update);
            result.users.add(store.user(peer, self)); result.users.add(ownUser());
            return result;
        }
        if (request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage send = (TLRPC.TL_messages_sendMessage) request;
            if (send.schedule_date != 0 || send.peer.user_id == 0) throw new UnsupportedOperationException();
            long peer = send.peer.user_id; boolean encrypted = encrypted(peer);
            ReplyReference reply = outgoingReply(send.reply_to, peer, encrypted);
            String stanza = session().send(store.jid(peer), send.message, encrypted, reply);
            XmppMessage message = XmppMessage.sent(session().connection().getUser(), store.jid(peer), stanza, send.message, encrypted, reply);
            int date = (int) (message.timestamp / 1000); int id = store.save(message);
            refreshReplies(peer);
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
