// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.omemo.*;
import org.jivesoftware.smackx.omemo.element.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

/** Legacy authenticated body and unchanged Signal record mutations share one durable transaction. */
public final class LegacyIncomingJournal {
    public static final int MAX_ENTRIES=128,MAX_ENTRY_BYTES=2*1024*1024,MAX_BYTES=16*1024*1024;
    private static final int HEADER=0x4c494831,ENTRY=0x4c494a32;
    private final MonogramSignalStore store;
    public final OmemoDevice own;
    private final byte[] epoch;
    private volatile String localIdentity;
    public static final class FullException extends IOException {
        public FullException(String message) { super(message); }
    }
    public static final class Token {
        private final byte[] epoch,digest;
        private final int slot;
        private Token(byte[] epoch,byte[] digest,int slot) { this.epoch=epoch.clone();this.digest=digest.clone();this.slot=slot; }
    }
    /** The live route is captured before waiting; recovery never re-resolves a historical room nickname. */
    public static final class Context {
        public final Message outer;
        public final String author;
        public final boolean live;
        final String room,nickname,realSender,occupantId,markable;
        final boolean verified,outgoing,archived;
        final long timestamp;
        final Map<String,String> ids;
        final DeliveryAcknowledgement receipt;
        private Context(Message outer,String author,boolean live,String room,String nickname,String realSender,String occupantId,
                        boolean verified,boolean outgoing,boolean archived,long timestamp,Map<String,String> ids,String markable,DeliveryAcknowledgement receipt) {
            this.outer=copy(outer);this.author=author;this.live=live;this.room=room;this.nickname=nickname;this.realSender=realSender;this.occupantId=occupantId;
            this.verified=verified;this.outgoing=outgoing;this.archived=archived;this.timestamp=timestamp;this.ids=Collections.unmodifiableMap(new LinkedHashMap<>(ids));
            this.markable=markable;this.receipt=receipt;
        }
        public static Context direct(Message outer,EntityBareJid own,boolean live,String archiveId,Date time) throws IOException {
            XmppMessage model=XmppMessage.from(outer,own,null,true,false,archiveId,time);
            if(model==null) throw new IOException("Invalid legacy direct-message route");
            return new Context(outer,outer.getFrom().asBareJid().toString(),live,null,null,null,null,false,model.outgoing,model.archived,
                    model.timestamp,model.ids,model.markableId,live ? DeliveryAcknowledgement.from(outer,own) : null);
        }
        static Context room(Message outer,String author,String nickname,XmppRoomMessage snapshot,boolean live) throws IOException {
            if(snapshot==null || author==null) throw new IOException("Legacy room sender is unavailable");
            return new Context(outer,author,live,snapshot.room.toString(),nickname,snapshot.realSender,snapshot.occupantId,snapshot.realSenderVerified,
                    snapshot.outgoing,snapshot.archived,snapshot.timestamp,snapshot.ids,null,null);
        }
        private static Message copy(Message outer) {
            try {
                String xml=XmppXml.string(outer);if(xml.length()>MAX_ENTRY_BYTES || xml.contains("<!")) throw new IOException("Invalid legacy incoming envelope");
                return PacketParserUtils.parseStanza(xml);
            } catch(Exception invalid) { throw new IllegalArgumentException("Cannot snapshot legacy incoming envelope",invalid); }
        }
        public long retainedBytes() { return 2L*outer.toXML().length(); }
    }
    public static final class Received {
        public final XmppMessage message;
        public final XmppRoomMessage roomMessage;
        public final Token token;
        public final Context context;
        public final OmemoMessage.Received decrypted;
        public final Message completion;
        public final boolean replenish;
        Received(XmppMessage message,XmppRoomMessage roomMessage,Token token,Context context,OmemoMessage.Received decrypted,Message completion,boolean replenish) {
            this.message=message;this.roomMessage=roomMessage;this.token=token;this.context=context;this.decrypted=decrypted;this.completion=completion;this.replenish=replenish;
        }
        public boolean receiptEligible() { return context!=null && context.receipt!=null && context.receipt.accepts(message); }
    }
    public LegacyIncomingJournal(MonogramSignalStore store,OmemoDevice own) throws Exception {
        this.store=store;this.own=own;
        try(LegacyOmemoRecords tx=store.begin(own)) {
            byte[] value=tx.load(LegacyOmemoRecords.INCOMING,"",0);
            if(value==null) { rotate(tx);value=tx.load(LegacyOmemoRecords.INCOMING,"",0);tx.commit(); }
            epoch=header(value);
        }
        org.jivesoftware.smackx.omemo.trust.OmemoFingerprint identity=store.getFingerprint(own);
        localIdentity=identity==null ? null : identity.toString();
    }
    public static void rotate(LegacyOmemoRecords tx) throws Exception {
        for(int id:tx.ids(LegacyOmemoRecords.INCOMING,"")) tx.save(LegacyOmemoRecords.INCOMING,"",id,null);
        byte[] value=new byte[20];new SecureRandom().nextBytes(value);
        java.nio.ByteBuffer.wrap(value).putInt(HEADER);tx.save(LegacyOmemoRecords.INCOMING,"",0,value);
    }
    private static byte[] header(byte[] value) throws IOException {
        if(value==null || value.length!=20 || java.nio.ByteBuffer.wrap(value).getInt()!=HEADER) throw new IOException("Invalid legacy incoming journal header");
        return Arrays.copyOfRange(value,4,20);
    }
    private boolean current(LegacyOmemoRecords tx) throws Exception {
        byte[] value=tx.load(LegacyOmemoRecords.INCOMING,"",0);return value!=null && MessageDigest.isEqual(epoch,header(value));
    }
    private void check(LegacyOmemoRecords tx) throws Exception {
        if(!current(tx)) throw new GeneralSecurityException("Legacy incoming journal was reset");
    }
    private String identity() throws Exception {
        org.jivesoftware.smackx.omemo.trust.OmemoFingerprint identity=store.getFingerprint(own);
        if(identity==null) throw new GeneralSecurityException("Legacy incoming local identity is unavailable");
        String current=identity.toString();
        if(localIdentity==null) localIdentity=current;
        else if(!localIdentity.equals(current)) throw new GeneralSecurityException("Legacy incoming local identity changed");
        return current;
    }
    public byte[] digest(Context context) throws Exception {
        OmemoElement element=context.outer.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
        if(element==null || context.outer.getExtensions("encrypted",OmemoElement_VAxolotl.NAMESPACE).size()!=1 || context.retainedBytes()>MAX_ENTRY_BYTES) throw new IOException("Invalid legacy incoming envelope");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        string(out,own.getJid().toString());out.writeInt(own.getDeviceId());string(out,context.author);string(out,context.room);
        string(out,context.room==null ? context.outgoing ? context.outer.getTo().asBareJid().toString() : context.outer.getFrom().asBareJid().toString() : context.room);
        string(out,context.outer.getType().toString());out.writeInt(element.getHeader().getSid());
        blob(out,element.getHeader().getIv());
        List<String> keys=new ArrayList<>();
        for(OmemoKeyElement key:element.getHeader().getKeys()) keys.add(key.getId()+":"+key.isPreKey()+":"+Base64.getEncoder().encodeToString(key.getData()));
        Collections.sort(keys);out.writeInt(keys.size());for(String key:keys) string(out,key);
        blob(out,element.getPayload());out.flush();return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
    }
    /** Unreadable fallbacks may share a private dedup alias only with a trustworthy captured route. */
    Map<String,String> aliases(Context context) throws Exception {
        Map<String,String> ids=new LinkedHashMap<>(context.ids);
        if(context.room==null || context.verified) ids.put("legacy-cipher",XmppSession.hex(digest(context)));
        return ids;
    }
    private static final class Saved {
        Token token;String identity,fingerprint,body,author,completionXml;int senderDevice;
        boolean accepted,replenish;Context context;
    }
    private byte[] encode(Saved saved) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);Context c=saved.context;
        out.writeInt(ENTRY);out.write(saved.token.epoch);out.write(saved.token.digest);string(out,own.getJid().toString());out.writeInt(own.getDeviceId());
        string(out,saved.identity);string(out,saved.fingerprint);string(out,saved.author);out.writeInt(saved.senderDevice);
        out.writeBoolean(saved.accepted);out.writeBoolean(saved.replenish);string(out,saved.completionXml);
        if(!saved.accepted) {
        string(out,XmppXml.string(c.outer));string(out,saved.body);
        string(out,c.room);string(out,c.nickname);string(out,c.realSender);string(out,c.occupantId);string(out,c.markable);
        out.writeBoolean(c.verified);out.writeBoolean(c.outgoing);out.writeBoolean(c.archived);out.writeLong(c.timestamp);
        if(c.ids.size()>16) throw new IOException("Too many legacy incoming aliases");out.writeInt(c.ids.size());
        for(Map.Entry<String,String> id:c.ids.entrySet()) { string(out,id.getKey());string(out,id.getValue()); }
        }
        out.flush();byte[] result=bytes.toByteArray();if(result.length>MAX_ENTRY_BYTES) throw new IOException("Legacy incoming message exceeds journal entry capacity");return result;
    }
    private Saved decode(byte[] bytes,int slot) throws Exception {
        if(bytes==null || bytes.length>MAX_ENTRY_BYTES || slot<1 || slot>MAX_ENTRIES) throw new IOException("Invalid legacy incoming entry");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=ENTRY) throw new IOException("Unknown legacy incoming entry version");
        byte[] scope=new byte[16],digest=new byte[32];in.readFully(scope);in.readFully(digest);
        if(!MessageDigest.isEqual(scope,epoch) || !own.getJid().toString().equals(string(in,4096)) || own.getDeviceId()!=in.readInt()) throw new GeneralSecurityException("Legacy incoming owner changed");
        Saved saved=new Saved();saved.token=new Token(scope,digest,slot);saved.identity=string(in,256);saved.fingerprint=string(in,256);
        saved.author=string(in,4096);saved.senderDevice=in.readInt();saved.accepted=in.readBoolean();saved.replenish=in.readBoolean();saved.completionXml=string(in,65536);
        if(!saved.accepted) {
            String xml=string(in,MAX_ENTRY_BYTES);Message outer=parse(xml);
            saved.body=string(in,MAX_ENTRY_BYTES);
            String room=string(in,4096),nickname=string(in,4096),real=string(in,4096),occupant=string(in,4096),markable=string(in,16384);
            boolean verified=in.readBoolean(),outgoing=in.readBoolean(),archived=in.readBoolean();long time=in.readLong();
            int count=in.readInt();if(count<1 || count>16) throw new IOException("Invalid legacy incoming aliases");Map<String,String> ids=new LinkedHashMap<>();
            for(int i=0;i<count;i++) { String kind=string(in,32),id=string(in,16384);if(kind==null || id==null || ids.put(kind,id)!=null) throw new IOException("Invalid legacy incoming alias"); }
            if(saved.body==null) throw new IOException("Missing authenticated legacy body");
            saved.context=new Context(outer,saved.author,false,room,nickname,real,occupant,verified,outgoing,archived,time,ids,markable,null);
        }
        if(in.read()!=-1 || saved.identity==null || !saved.identity.equals(identity()) || saved.fingerprint==null || saved.author==null
                || !JidCreate.bareFrom(saved.author).toString().equals(saved.author) || saved.senderDevice<=0)
            throw new GeneralSecurityException("Legacy incoming identity/content mismatch");
        completion(saved);return saved;
    }
    private static Message parse(String xml) throws Exception {
        if(xml==null || xml.contains("<!")) throw new IOException("Invalid legacy incoming XML");
        ReplyReference.initialize();MessageCorrection.initialize();MessageReactions.initialize();MessageRetraction.initialize();MessageMarkup.initialize();FileShare.initialize();
        return PacketParserUtils.parseStanza(xml);
    }
    private Message completion(Saved saved) throws Exception {
        if(saved.completionXml==null) return null;Message result=parse(saved.completionXml);
        OmemoElement encrypted=result.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
        if(result.getBody()!=null || !MessageReceipt.valid(result.getStanzaId()) || result.getTo()==null || !saved.author.equals(result.getTo().toString())
                || result.getExtensions().size()!=1 || encrypted==null || !encrypted.isKeyTransportElement() || encrypted.getHeader().getSid()!=own.getDeviceId()
                || encrypted.getHeader().getKeys().size()!=1 || encrypted.getHeader().getKeys().get(0).getId()!=saved.senderDevice)
            throw new IOException("Invalid persisted legacy completion");
        return result;
    }
    private Received received(Saved saved,OmemoMessage.Received decrypted,Context current) throws Exception {
        if(saved.accepted) return new Received(null,null,saved.token,null,null,completion(saved),saved.replenish);
        Context c=saved.context;EntityBareJid owner=own.getJid().asEntityBareJidOrThrow();
        Map<String,String> ids=new LinkedHashMap<>(c.ids);ids.put("legacy-cipher",XmppSession.hex(saved.token.digest));
        long timestamp=c.timestamp;boolean archived=c.archived;
        if(current!=null && current.archived) {
            for(String kind:new String[]{"archive","mam"}) { String alias=current.ids.get(kind);if(alias!=null) ids.put(kind,alias); }
            timestamp=current.timestamp;archived=true;
        }
        String cipher=ids.remove("legacy-cipher");ids.put("legacy-cipher",cipher);
        XmppMessage message=null;XmppRoomMessage roomMessage=null;
        if(c.room==null) {
            message=XmppMessage.from(c.outer,owner,saved.body,true,true,null,null);
            if(message!=null) message=message.canonical(ids,timestamp,archived,c.markable).withSenderFingerprint(saved.fingerprint);
        } else {
            roomMessage=XmppRoomMessage.archived(c.outer,JidCreate.entityBareFrom(c.room),owner,c.nickname,saved.body,true,true,null,c.archived ? new Date(c.timestamp) : null);
            if(roomMessage!=null) roomMessage=roomMessage.canonical(ids,timestamp,archived,c.outgoing,c.realSender,c.verified,c.occupantId).withSenderFingerprint(saved.fingerprint);
        }
        if(message==null && roomMessage==null) throw new IOException("Invalid authenticated legacy incoming model");
        return new Received(message,roomMessage,saved.token,current==null ? c : current,decrypted,completion(saved),saved.replenish);
    }
    public Received find(LegacyOmemoRecords tx,Context context,byte[] digest) throws Exception {
        check(tx);identity();int count=0;long total=0;
        for(int slot:tx.ids(LegacyOmemoRecords.INCOMING,"")) if(slot!=0) {
            byte[] bytes=tx.load(LegacyOmemoRecords.INCOMING,"",slot);total+=bytes.length;
            if(slot<1 || slot>MAX_ENTRIES || ++count>MAX_ENTRIES || bytes.length>MAX_ENTRY_BYTES || total>MAX_BYTES || bytes.length<52)
                throw new IOException("Legacy incoming journal exceeds capacity");
            java.nio.ByteBuffer prefix=java.nio.ByteBuffer.wrap(bytes);
            if(prefix.getInt()!=ENTRY) throw new IOException("Unknown legacy incoming entry version");
            byte[] scope=new byte[16],storedDigest=new byte[32];prefix.get(scope);prefix.get(storedDigest);
            if(!MessageDigest.isEqual(scope,epoch)) throw new GeneralSecurityException("Legacy incoming epoch mismatch");
            if(MessageDigest.isEqual(storedDigest,digest)) return received(decode(bytes,slot),null,context);
        }return null;
    }
    public Received append(LegacyOmemoRecords tx,Context context,byte[] digest,OmemoMessage.Received decrypted) throws Exception {
        return append(tx,context,digest,decrypted,null,false);
    }
    public Received append(LegacyOmemoRecords tx,Context context,byte[] digest,OmemoMessage.Received decrypted,Message completion,boolean replenish) throws Exception {
        check(tx);identity();
        if(decrypted.isKeyTransportMessage() && completion==null && !replenish) return new Received(null,null,null,context,decrypted,null,false);
        boolean[] used=new boolean[MAX_ENTRIES+1];long total=0;int count=0;
        for(int slot:tx.ids(LegacyOmemoRecords.INCOMING,"")) if(slot!=0) {
            if(slot<1 || slot>MAX_ENTRIES) throw new IOException("Invalid legacy incoming slot");used[slot]=true;count++;total+=tx.load(LegacyOmemoRecords.INCOMING,"",slot).length;
        }
        if(count>=MAX_ENTRIES) throw new FullException("Legacy incoming journal is full; store pending messages before retrying");
        int slot=1;while(used[slot]) slot++;
        Saved saved=new Saved();saved.context=context;saved.body=decrypted.getBody();saved.fingerprint=decrypted.getSendersFingerprint().toString();saved.identity=identity();saved.token=new Token(epoch,digest,slot);
        saved.author=decrypted.getSenderDevice().getJid().toString();saved.senderDevice=decrypted.getSenderDevice().getDeviceId();
        saved.accepted=decrypted.isKeyTransportMessage();saved.completionXml=completion==null ? null : XmppXml.string(completion);saved.replenish=replenish;
        Received result=received(saved,decrypted,context);byte[] bytes=encode(saved);
        if(total+bytes.length>MAX_BYTES) throw new FullException("Legacy incoming journal byte capacity exceeded");
        tx.save(LegacyOmemoRecords.INCOMING,"",slot,bytes);return result;
    }
    public List<Received> pending() throws Exception {
        try(LegacyOmemoRecords tx=store.begin(own)) {
            if(!current(tx)) return Collections.emptyList();
            return store.withTransaction(own,tx,() -> {
                List<Received> result=new ArrayList<>();long bytes=0;
                for(int slot:tx.ids(LegacyOmemoRecords.INCOMING,"")) if(slot!=0) {
                    byte[] entry=tx.load(LegacyOmemoRecords.INCOMING,"",slot);bytes+=entry.length;
                    if(result.size()>=MAX_ENTRIES || bytes>MAX_BYTES) throw new IOException("Legacy incoming journal exceeds capacity");
                    result.add(received(decode(entry,slot),null,null));
                }return result;
            });
        }
    }
    private Saved matching(LegacyOmemoRecords tx,Received received) throws Exception {
        if(received==null || received.token==null || !current(tx) || !MessageDigest.isEqual(epoch,received.token.epoch)) return null;
        byte[] bytes=tx.load(LegacyOmemoRecords.INCOMING,"",received.token.slot);if(bytes==null) return null;
        Saved saved=decode(bytes,received.token.slot);return MessageDigest.isEqual(saved.token.digest,received.token.digest) ? saved : null;
    }
    public boolean accept(Received received) throws Exception {
        if(received==null || received.token==null) return true;
        try(LegacyOmemoRecords tx=store.begin(own)) {
            boolean accepted=store.withTransaction(own,tx,() -> {
                Saved saved=matching(tx,received);if(saved==null) return false;if(saved.accepted) return true;
                saved.accepted=true;saved.body=null;saved.context=null;
                tx.save(LegacyOmemoRecords.INCOMING,"",saved.token.slot,saved.completionXml==null && !saved.replenish ? null : encode(saved));return true;
            });if(accepted) tx.commit();return accepted;
        }
    }
    /** Local epoch/identity validation immediately before sending a stored protocol obligation. */
    public Received protocol(Received received) throws Exception {
        try(LegacyOmemoRecords tx=store.begin(own)) {
            return store.withTransaction(own,tx,() -> {
                Saved saved=matching(tx,received);return saved==null || !saved.accepted ? null : received(saved,null,null);
            });
        }
    }
    public boolean completed(Received received,boolean acknowledgement,boolean replenished) throws Exception {
        try(LegacyOmemoRecords tx=store.begin(own)) {
            boolean changed=store.withTransaction(own,tx,() -> {
                Saved saved=matching(tx,received);if(saved==null || !saved.accepted) return false;
                if(acknowledgement) saved.completionXml=null;if(replenished) saved.replenish=false;
                tx.save(LegacyOmemoRecords.INCOMING,"",saved.token.slot,saved.completionXml==null && !saved.replenish ? null : encode(saved));return true;
            });if(changed) tx.commit();return changed;
        }
    }
    /** A confirmed publication fulfills only obligations captured before that publication began. */
    public boolean replenished(List<Received> snapshot) throws Exception {
        try(LegacyOmemoRecords tx=store.begin(own)) {
            boolean changed=store.withTransaction(own,tx,() -> {
                boolean any=false;
                for(Received received:snapshot) {
                    Saved saved=matching(tx,received);if(saved==null || !saved.replenish) continue;
                    saved.replenish=false;
                    tx.save(LegacyOmemoRecords.INCOMING,"",saved.token.slot,saved.accepted && saved.completionXml==null ? null : encode(saved));any=true;
                }return any;
            });if(changed) tx.commit();return changed;
        }
    }
    private static void string(DataOutputStream out,String value) throws IOException { if(value==null) out.writeInt(-1);else { byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes); } }
    private static String string(DataInputStream in,int maximum) throws IOException {
        int length=in.readInt();if(length==-1) return null;if(length<0 || length>maximum || length>in.available()) throw new IOException("Invalid legacy incoming field");
        byte[] value=new byte[length];in.readFully(value);return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(value)).toString();
    }
    private static void blob(DataOutputStream out,byte[] value) throws IOException { if(value==null) out.writeInt(-1);else { out.writeInt(value.length);out.write(value); } }
}
