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
    public static final String NAME="monogram-forward-name",DATE="monogram-forward-date";
    private MonogramForwarding() {}
    public static SendMessageParams copy(TLRPC.Message source,long destination,boolean hideCaption,int account,boolean hideAuthor) {
        SendMessageParams send=copy(source,destination,hideCaption);
        if(hideAuthor) return send;
        String name=source.fwd_from==null ? null : source.fwd_from.from_name;
        if((name==null || name.isEmpty()) && source.fwd_from!=null && source.fwd_from.from_id!=null)
            name=org.telegram.messenger.UserObject.getUserName(org.telegram.messenger.MessagesController.getInstance(account).getUser(source.fwd_from.from_id.user_id));
        if(name==null || name.isEmpty()) {
            TLRPC.User user=source.from_id==null ? null : org.telegram.messenger.MessagesController.getInstance(account).getUser(source.from_id.user_id);
            name=org.telegram.messenger.UserObject.getUserName(user);
        }
        if(name==null || name.isEmpty()) name="Unknown sender";
        name=com.negzero.monogram.xmpp.ForwardedContent.label(name);
        int date=source.fwd_from!=null && source.fwd_from.date>0 ? source.fwd_from.date : source.date;
        send.params.put(NAME,name);send.params.put(DATE,Integer.toString(Math.max(0,date)));
        return send;
    }
    public static com.negzero.monogram.xmpp.ForwardedContent attribution(java.util.Map<String,String> params,String body) {
        if(params==null || !params.containsKey(NAME)) return null;
        try { return com.negzero.monogram.xmpp.ForwardedContent.of(params.get(NAME),Integer.parseInt(params.get(DATE)),body); }
        catch(IllegalArgumentException invalid) { return null; }
    }
    public static void prepare(TLObject request,TLRPC.Message local) {
        com.negzero.monogram.xmpp.ForwardedContent author=attribution(local.params,"");if(author==null) return;
        if(request instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage send=(TLRPC.TL_messages_sendMessage)request;
            if(com.negzero.monogram.xmpp.ForwardedContent.parse(send.message)!=null)return;
            send.message=author.prefix()+(send.message==null ? "" : send.message);
            ArrayList<TLRPC.MessageEntity> entities=new ArrayList<>();
            for(TLRPC.MessageEntity old:send.entities) {
                TLRPC.MessageEntity entity=TLObject.deepCopy(old,TLRPC.MessageEntity::TLdeserialize);entity.offset+=author.prefix().length();entities.add(entity);
            }
            send.entities=entities;
        } else if(MonogramLocation.supports(request)) {
            TLRPC.TL_messages_sendMedia send=(TLRPC.TL_messages_sendMedia)request;
            send.message=author.prefix();
        }
    }
    public static void pending(TLRPC.Message message) {
        com.negzero.monogram.xmpp.ForwardedContent forward=attribution(message.params,"");
        if(forward!=null) header(message,forward);
        decorate(message,message.message);
    }
    private static void header(TLRPC.Message message,com.negzero.monogram.xmpp.ForwardedContent forward) {
        message.fwd_from=new TLRPC.TL_messageFwdHeader();message.fwd_from.flags=32;
        message.fwd_from.from_name=forward.name;message.fwd_from.date=forward.date;
        message.flags|=TLRPC.MESSAGE_FLAG_FWD;
        if(message.params==null)message.params=new HashMap<>();
        message.params.put(NAME,forward.name);message.params.put(DATE,Integer.toString(forward.date));
    }
    public static void decorate(TLRPC.Message message,String rawBody) {
        com.negzero.monogram.xmpp.ForwardedContent visible=com.negzero.monogram.xmpp.ForwardedContent.parse(message.message);
        com.negzero.monogram.xmpp.ForwardedContent forward=visible==null ? com.negzero.monogram.xmpp.ForwardedContent.parse(rawBody) : visible;
        if(forward==null) return;
        header(message,forward);
        if(visible!=null) {
            int removed=visible.prefix().length();message.message=visible.body;
            ArrayList<TLRPC.MessageEntity> entities=new ArrayList<>();
            for(TLRPC.MessageEntity old:message.entities) if(old.offset>=removed) {
                TLRPC.MessageEntity entity=TLObject.deepCopy(old,TLRPC.MessageEntity::TLdeserialize);
                entity.offset-=removed;entities.add(entity);
            }
            message.entities=entities;
            if(entities.isEmpty())message.flags&=~TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
    }

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
        pending(pending);
    }

    public static SendMessageParams copy(TLRPC.Message source, long destination, boolean hideCaption) {
        SendMessageParams send = new SendMessageParams();
        send.peer = destination;
        send.notify = true;
        send.entities = new ArrayList<>();
        for(TLRPC.MessageEntity entity:source.entities) send.entities.add(TLObject.deepCopy(entity,TLRPC.MessageEntity::TLdeserialize));
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
        } else if(media instanceof TLRPC.TL_messageMediaPoll && MonogramPolls.parse(MonogramPolls.body(source))!=null) {
            send.message=MonogramPolls.freshBody(source);send.entities.clear(); // A forwarded poll starts a fresh vote in its destination.
        } else if(media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaVenue) {
            send.location=TLObject.deepCopy(media,TLRPC.MessageMedia::TLdeserialize);send.entities.clear();
        } else if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage) {
            send.message = text;
        } else throw new IllegalArgumentException("This message type cannot be forwarded over XMPP yet.");
        return send;
    }
}
