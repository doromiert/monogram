package org.telegram.messenger.xmpp;

import java.util.ArrayList;
import java.util.HashMap;
import org.telegram.messenger.SendMessagesHelper.SendMessageParams;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** XMPP forwards are new sends, never references to another chat's encrypted download URL. */
public final class MonogramForwarding {
    public static final String SOURCE = "monogram-forward-source";
    public static final String ADDRESS = "monogram-forward-address";
    private MonogramForwarding() {}

    public static void retry(TLRPC.Message pending, TLRPC.Message source) {
        if (pending.params == null) pending.params = new HashMap<>();
        if (source != null && MonogramAttachments.has(source)) {
            pending.params.put(SOURCE, Integer.toString(source.id));
            pending.params.put(ADDRESS, source.params.get(MonogramAttachments.PARAM));
        }
        pending.params.remove("fwd_id");
        pending.params.remove("fwd_peer");
        pending.fwd_from = null;
        pending.fwd_msg_id = 0;
        pending.flags &= ~TLRPC.MESSAGE_FLAG_FWD;
    }

    public static SendMessageParams copy(TLRPC.Message source, long destination, boolean hideCaption) {
        SendMessageParams send = new SendMessageParams();
        send.peer = destination;
        send.notify = true;
        send.entities = new ArrayList<>(source.entities);
        send.params = new HashMap<>();
        TLRPC.MessageMedia media = source.media;
        boolean file = media != null && (media.photo instanceof TLRPC.TL_photo || media.document instanceof TLRPC.TL_document);
        String text = source.message == null ? "" : source.message;
        if (file) {
            send.caption = hideCaption ? "" : text;
            if (hideCaption) send.entities.clear();
            send.path = source.attachPath;
            send.hasMediaSpoilers = media.spoiler;
            send.invert_media = source.invert_media;
            if (media.photo instanceof TLRPC.TL_photo)
                send.photo = (TLRPC.TL_photo) TLObject.deepCopy(media.photo, TLRPC.Photo::TLdeserialize);
            else send.document = (TLRPC.TL_document) TLObject.deepCopy(media.document, TLRPC.Document::TLdeserialize);
            if (MonogramAttachments.has(source)) {
                send.params.put(SOURCE, Integer.toString(source.id));
                send.params.put(ADDRESS, source.params.get(MonogramAttachments.PARAM));
            }
        } else if(media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaVenue) {
            send.location=TLObject.deepCopy(media,TLRPC.MessageMedia::TLdeserialize);send.entities.clear();
        } else if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage) {
            send.message = text;
        } else throw new IllegalArgumentException("This message type cannot be forwarded over XMPP yet.");
        return send;
    }
}
