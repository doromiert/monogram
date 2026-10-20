/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.core.app.RemoteInput;

import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.xmpp.MonogramNotificationActions;

import java.util.ArrayList;

public class WearReplyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        ApplicationLoader.postInitApplication();
        Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
        if (remoteInput == null) {
            return;
        }
        CharSequence text = remoteInput.getCharSequence(NotificationsController.EXTRA_VOICE_REPLY);
        if (TextUtils.isEmpty(text)) {
            return;
        }
        long dialogId = intent.getLongExtra("dialog_id", 0);
        int maxId = intent.getIntExtra("max_id", 0);
        long topicId = intent.getLongExtra("topic_id", 0);
        int currentAccount = intent.getIntExtra("currentAccount", 0);
        int[] voiceMsgIds = intent.getIntArrayExtra("voice_msg_ids");
        if (dialogId == 0 || maxId == 0 || !UserConfig.isValidAccount(currentAccount)) {
            return;
        }
        final boolean xmpp = MonogramNotificationActions.isXmpp(intent, currentAccount);
        if (xmpp && (!MonogramNotificationActions.valid(intent, currentAccount)
                || AndroidUtilities.needShowPasscode() || SharedConfig.isWaitingForPasscodeEnter)) return;
        final Runnable completion = xmpp ? MonogramNotificationActions.keepAlive(this) : () -> {};
        AccountInstance accountInstance = AccountInstance.getInstance(currentAccount);
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = accountInstance.getMessagesController().getUser(dialogId);
            if (user == null) {
                Utilities.globalQueue.postRunnable(() -> {
                    if (xmpp && !MonogramNotificationActions.valid(intent, currentAccount)) { completion.run(); return; }
                    TLRPC.User user1 = accountInstance.getMessagesStorage().getUserSync(dialogId);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (xmpp && !MonogramNotificationActions.valid(intent, currentAccount)) { completion.run(); return; }
                        accountInstance.getMessagesController().putUser(user1, true);
                        sendMessage(accountInstance, text, dialogId, topicId, maxId, voiceMsgIds, intent, xmpp, completion);
                    });
                });
                return;
            }
        } else if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = accountInstance.getMessagesController().getChat(-dialogId);
            if (chat == null) {
                Utilities.globalQueue.postRunnable(() -> {
                    if (xmpp && !MonogramNotificationActions.valid(intent, currentAccount)) { completion.run(); return; }
                    TLRPC.Chat chat1 = accountInstance.getMessagesStorage().getChatSync(-dialogId);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (xmpp && !MonogramNotificationActions.valid(intent, currentAccount)) { completion.run(); return; }
                        accountInstance.getMessagesController().putChat(chat1, true);
                        sendMessage(accountInstance, text, dialogId, topicId, maxId, voiceMsgIds, intent, xmpp, completion);
                    });
                });
                return;
            }
        }
        sendMessage(accountInstance, text, dialogId, topicId, maxId, voiceMsgIds, intent, xmpp, completion);
    }

    private void sendMessage(AccountInstance accountInstance, CharSequence text, long dialog_id, long topicId, int max_id,
            int[] voiceMsgIds, Intent action, boolean xmpp, Runnable completion) {
        int account = accountInstance.getCurrentAccount();
        if (xmpp && (!MonogramNotificationActions.valid(action, account) || AndroidUtilities.needShowPasscode()
                || SharedConfig.isWaitingForPasscodeEnter
                || dialog_id > 0 && accountInstance.getMessagesController().getUser(dialog_id) == null
                || dialog_id < 0 && accountInstance.getMessagesController().getChat(-dialog_id) == null)) {
            completion.run(); return;
        }
        MessageObject replyToMsgId = null;
        MessageObject replyToTopMsgId = null;
        if (max_id != 0) {
            TLRPC.TL_message replyMessage = new TLRPC.TL_message();
            replyMessage.message = "";
            replyMessage.id = max_id;
            replyMessage.peer_id = accountInstance.getMessagesController().getPeer(dialog_id);
            replyToMsgId = new MessageObject(accountInstance.getCurrentAccount(), replyMessage, false, false);
        }
        if (topicId != 0) {
            TLRPC.TL_message topicStartMessage = new TLRPC.TL_message();
            topicStartMessage.message = "";
            topicStartMessage.id = (int) topicId;
            topicStartMessage.peer_id = accountInstance.getMessagesController().getPeer(dialog_id);
            topicStartMessage.action = new TLRPC.TL_messageActionTopicCreate();
            topicStartMessage.action.title = "";
            replyToTopMsgId = new MessageObject(accountInstance.getCurrentAccount(), topicStartMessage, false, false);
        }

        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text.toString(), dialog_id, replyToMsgId, replyToTopMsgId, null, true, null, null, null, true, 0, 0, null, false);
        if (xmpp) params.onLocalMessageStored = stored -> {
            if (!stored || !MonogramNotificationActions.valid(action, account)) { completion.run(); return; }
            try {
                markVoiceRead(accountInstance, dialog_id, voiceMsgIds);
                accountInstance.getMessagesController().markMonogramMessagesAsRead(dialog_id,
                        java.util.Collections.singleton(max_id), read -> completion.run());
            } catch (RuntimeException failure) { completion.run(); throw failure; }
        };
        try { accountInstance.getSendMessagesHelper().sendMessage(params); }
        catch (RuntimeException failure) { completion.run(); throw failure; }

        if (xmpp) return; // Delivery/reconnect is independent; the local commit callback completes this action.

        markVoiceRead(accountInstance, dialog_id, voiceMsgIds);
        //TODO handle topics
        if (topicId == 0) {
            accountInstance.getMessagesController().markDialogAsRead(dialog_id, max_id, max_id, 0, false, topicId, 0, true, 0);
        }
    }

    private void markVoiceRead(AccountInstance accountInstance, long dialog_id, int[] voiceMsgIds) {
        if (voiceMsgIds != null && voiceMsgIds.length > 0) {
            ArrayList<Integer> ids = new ArrayList<>(voiceMsgIds.length);
            for (int id : voiceMsgIds) ids.add(id);
            accountInstance.getMessagesStorage().markVoiceMessageContentAsRead(dialog_id, ids);
        }

    }
}
