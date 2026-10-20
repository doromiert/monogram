package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.chatstates.ChatState;
import org.jivesoftware.smackx.chatstates.packet.ChatStateExtension;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppChatStatesTest {
    @Test void onlyActiveMayAccompanyContent() {
        for (ChatState state : ChatState.values()) {
            Message message = new Message(); message.addExtension(new ChatStateExtension(state));
            assertEquals(state, XmppChatStates.read(message));
            message.setBody("Message content");
            assertEquals(state == ChatState.active ? state : null, XmppChatStates.read(message));
        }
    }
    @Test void malformedAndMultipleStatesCannotPretendToBeTyping() {
        Message message = new Message();
        message.addExtension(new ChatStateExtension(ChatState.composing));
        message.addExtension(new ChatStateExtension(ChatState.active));
        assertNull(XmppChatStates.read(message));
        for (StandardExtensionElement invalid : new StandardExtensionElement[]{
                StandardExtensionElement.builder("composing", XmppChatStates.NAMESPACE).setText("payload").build(),
                StandardExtensionElement.builder("composing", XmppChatStates.NAMESPACE).addAttribute("unexpected", "value").build(),
                StandardExtensionElement.builder("composing", XmppChatStates.NAMESPACE).addElement("nested", "payload").build(),
                StandardExtensionElement.builder("unknown", XmppChatStates.NAMESPACE).build()}) {
            message = new Message(); message.addExtension(invalid); assertNull(XmppChatStates.read(message));
        }
        message = new Message(); message.addExtension(StandardExtensionElement.builder("composing", "wrong:namespace").build());
        assertNull(XmppChatStates.read(message));
    }
    @Test void encryptedContentCannotCarryAComposingNotification() {
        Message message = new Message(); message.addExtension(new ChatStateExtension(ChatState.composing));
        message.addExtension(StandardExtensionElement.builder("encrypted", "eu.siacs.conversations.axolotl").build());
        assertNull(XmppChatStates.read(message));
    }
}
