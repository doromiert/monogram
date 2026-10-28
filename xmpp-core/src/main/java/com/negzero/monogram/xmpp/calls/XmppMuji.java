// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp.calls;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jxmpp.jid.impl.JidCreate;
import static com.negzero.monogram.xmpp.calls.JingleCodec.*;

/** XEP-0272 coordination over the existing Jingle engine. No media capture or automatic joining. */
public final class XmppMuji implements AutoCloseable {
    public static final String NS="urn:xmpp:jingle:muji:0";
    public static final int MAX_PARTICIPANTS=8;
    public interface Listener extends XmppCalls.Listener {
        default void conference(String room,List<Participant> participants,String error){}
    }
    public static final class Participant {
        public final String occupant,realJid;public final boolean preparing,video;
        Participant(String occupant,String realJid,boolean preparing,boolean video){this.occupant=occupant;this.realJid=realJid;this.preparing=preparing;this.video=video;}
    }
    private static final class Leg {
        final XmppCalls engine;final String sid;Leg(XmppCalls engine,String sid){this.engine=engine;this.sid=sid;}
    }
    private final XMPPConnection connection;private final XmppCalls calls;private final Predicate<String> blocked;
    private final ScheduledThreadPoolExecutor events=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"Monogram MUJI");t.setDaemon(true);return t;});
    private final Map<String,Participant> participants=new LinkedHashMap<>();
    private final Map<String,Leg> legs=new ConcurrentHashMap<>();
    private final Set<String> waiting=new HashSet<>();
    private final java.util.concurrent.atomic.AtomicInteger queued=new java.util.concurrent.atomic.AtomicInteger();
    private final StanzaListener presence;private final StanzaListener decorate;private final ConnectionListener lifecycle;
    private volatile Listener listener=new Listener(){};private volatile String room;private volatile boolean closed;
    private MultiUserChat chat;private volatile String occupant;private volatile boolean video,ready;private boolean selfEcho;private ScheduledFuture<?> deadline;
    public XmppMuji(XMPPConnection connection,XmppCalls calls,Predicate<String> blocked) {
        this.connection=connection;this.calls=calls;this.blocked=blocked;events.setRemoveOnCancelPolicy(true);
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("muji",NS,org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        calls.setConferenceHandler(this::route);calls.setConferenceBusy(()->room!=null);
        ServiceDiscoveryManager.getInstanceFor(connection).addFeature(NS);
        presence=s->{Presence p=(Presence)s;if(room!=null && p.getFrom()!=null && p.getFrom().asBareJid().toString().equals(room))enqueue(()->observed(p));};
        connection.addAsyncStanzaListener(presence,s->s instanceof Presence);
        decorate=s->{if(room!=null && s.getTo()!=null && s.getTo().toString().equals(occupant) && ((Presence)s).getType()==Presence.Type.available && !s.hasExtension("muji",NS))s.addExtension(advertisement(ready,video));};
        connection.addStanzaInterceptor(decorate,s->s instanceof Presence);
        lifecycle=new ConnectionListener(){public void connectionClosed(){enqueue(()->stop("Disconnected"));}public void connectionClosedOnError(Exception e){connectionClosed();}};
        connection.addConnectionListener(lifecycle);
    }
    public void setListener(Listener listener){this.listener=listener==null?new Listener(){}:listener;}
    public String room(){return room;}
    public XmppCalls leg(String peer){Leg leg=legs.get(peer);return leg==null?null:leg.engine;}
    public CompletableFuture<Void> join(String address,boolean video) {
        CompletableFuture<Void> result=new CompletableFuture<>();
        if(!enqueue(()->{
            if(room!=null) {result.completeExceptionally(new IllegalStateException("A group call is already active"));return;}
            try {
                if(calls.current()!=null || !connection.isAuthenticated())throw new IllegalStateException("Another call is active or XMPP is disconnected");
                org.jxmpp.jid.EntityBareJid jid=JidCreate.entityBareFrom(address);
                MultiUserChat found=MultiUserChatManager.getInstanceFor(connection).getMultiUserChat(jid);
                if(!found.isJoined())throw new IllegalStateException("Join the room before starting a group call");
                RoomInfo info=MultiUserChatManager.getInstanceFor(connection).getRoomInfo(jid);
                if(!info.isNonanonymous() || !info.isMembersOnly())throw new IllegalStateException("Group calls require a private, non-anonymous room");
                chat=found;room=jid.toString();occupant=JidCreate.entityFullFrom(jid,chat.getNickname()).toString();this.video=video;ready=false;selfEcho=false;
                participants.clear();waiting.clear();
                int count=0;
                for(org.jxmpp.jid.EntityFullJid member:chat.getOccupants())if(!member.toString().equals(occupant)){
                    Presence p=chat.getOccupantPresence(member);if(parse(p)!=null)count++;update(p);
                }
                if(count>=MAX_PARTICIPANTS)throw new IllegalStateException("This call already has eight participants");
                announce(false);publish(null);
                deadline=events.schedule(()->stop("Group-call preparation timed out"),30,TimeUnit.SECONDS);
                result.complete(null);
            }catch(Exception failure){if(room!=null)stop(failure.getMessage());result.completeExceptionally(failure);}
        }))result.completeExceptionally(new IllegalStateException("Group-call queue unavailable"));
        return result;
    }
    public void leave(){enqueue(()->stop(null));}
    public void refreshPermissions(){enqueue(()->{for(Participant peer:new ArrayList<>(participants.values()))if(blocked.test(peer.realJid)){participants.remove(peer.occupant);removeLeg(peer.realJid);}publish(null);});}
    private boolean enqueue(Runnable task){
        if(closed)return false;if(queued.incrementAndGet()>128){queued.decrementAndGet();return false;}
        try{events.execute(()->{try{if(!closed)task.run();}catch(RuntimeException failure){stop("Group-call signaling failed");}finally{queued.decrementAndGet();}});return true;}
        catch(RejectedExecutionException done){queued.decrementAndGet();return false;}
    }
    static StandardExtensionElement advertisement(boolean ready,boolean video){
        StandardExtensionElement.Builder muji=StandardExtensionElement.builder("muji",NS);
        if(!ready)return muji.addElement(StandardExtensionElement.builder("preparing",NS).build()).build();
        muji.addElement(content("voice","audio",111,"opus",48000,2));
        if(video)muji.addElement(content("webcam","video",96,"VP8",90000,1));
        return muji.build();
    }
    private static StandardExtensionElement content(String name,String media,int pt,String codec,int rate,int channels){
        StandardExtensionElement.Builder payload=StandardExtensionElement.builder("payload-type",RTP).addAttribute("id",Integer.toString(pt)).addAttribute("name",codec).addAttribute("clockrate",Integer.toString(rate));
        if(channels>1)payload.addAttribute("channels",Integer.toString(channels));
        return StandardExtensionElement.builder("content",NS).addAttribute("creator","initiator").addAttribute("name",name)
            .addElement(StandardExtensionElement.builder("description",RTP).addAttribute("media",media).addElement(payload.build()).build()).build();
    }
    private void announce(boolean ready)throws Exception{Presence p=new Presence(Presence.Type.available);p.setTo(JidCreate.entityFullFrom(occupant));p.addExtension(advertisement(ready,video));connection.sendStanza(p);}
    private void observed(Presence presence){
        if(room==null || presence==null || presence.getFrom()==null || !presence.getFrom().isEntityFullJid() || !presence.getFrom().asBareJid().toString().equals(room))return;
        String from=presence.getFrom().toString();
        if(from.equals(occupant)) {
            if(presence.getType()==Presence.Type.unavailable){stop("Left the room",false);return;}
            if(!selfEcho && presence.hasExtension("muji",NS)) {
                selfEcho=true;for(Participant p:participants.values())if(p.preparing && initiate(occupant,p.occupant))waiting.add(p.occupant);
            }
        }else update(presence);
        waiting.removeIf(who->{Participant p=participants.get(who);return p==null || !p.preparing;});
        if(selfEcho && !ready && waiting.isEmpty()) {
            ready=true;try{announce(true);}catch(Exception failed){stop("Could not announce group call");return;}
            if(deadline!=null)deadline.cancel(false);
        }
        if(ready)connectReady();publish(null);
    }
    static Participant parse(Presence presence)throws Exception{
        if(presence==null || presence.getType()!=Presence.Type.available || presence.getFrom()==null || !presence.getFrom().isEntityFullJid())return null;
        ExtensionElement raw=presence.getExtension("muji",NS);if(!(raw instanceof StandardExtensionElement) || raw.toXML().length()>16384)return null;
        if(presence.getExtensions("x",MUCUser.NAMESPACE).size()!=1)return null;
        MUCUser muc=MUCUser.from(presence);if(muc==null || muc.getItem()==null || muc.getItem().getJid()==null || !muc.getItem().getJid().isEntityFullJid())return null;
        StandardExtensionElement muji=(StandardExtensionElement)raw;boolean preparing=false,audio=false,video=false;
        if(muji.getElements().size()>3)return null;
        for(StandardExtensionElement child:muji.getElements()) {
            if(!NS.equals(child.getNamespace()))return null;
            if("preparing".equals(child.getElementName())){preparing=true;continue;}
            if(!"content".equals(child.getElementName()))return null;
            StandardExtensionElement description=JingleSdp.one(child,"description",RTP);
            for(StandardExtensionElement codec:description.getElements())if(RTP.equals(codec.getNamespace()) && "payload-type".equals(codec.getElementName())) {
                String name=codec.getAttributeValue("name"),id=codec.getAttributeValue("id"),rate=codec.getAttributeValue("clockrate");
                if("audio".equals(description.getAttributeValue("media")) && "opus".equalsIgnoreCase(name) && "111".equals(id) && "48000".equals(rate))audio=true;
                if("video".equals(description.getAttributeValue("media")) && "VP8".equalsIgnoreCase(name) && "96".equals(id) && "90000".equals(rate))video=true;
            }
        }
        if(!preparing && !audio)return null;
        return new Participant(presence.getFrom().toString(),muc.getItem().getJid().toString(),preparing,video);
    }
    private void update(Presence presence){
        if(presence==null || presence.getFrom()==null)return;String who=presence.getFrom().toString();Participant old=participants.remove(who),found=null;
        try{found=parse(presence);}catch(Exception invalid){/* Malformed advertisements cannot authorize media. */}
        if(found!=null && blocked.test(found.realJid))found=null;
        if(found!=null && (old!=null || participants.size()<MAX_PARTICIPANTS-1))participants.put(who,found);
        if(old!=null && (found==null || !old.realJid.equals(found.realJid) || found.preparing))removeLeg(old.realJid);
    }
    private void connectReady(){
        for(Participant peer:participants.values())if(!peer.preparing && !legs.containsKey(peer.realJid) && initiate(occupant,peer.occupant) && authorized(peer.realJid)) {
            XmppCalls leg=calls.conferenceLeg(room);leg.setListener(delegate(leg));
            try{XmppCalls.Call call=leg.startConference(peer.realJid,video && peer.video);legs.put(peer.realJid,new Leg(leg,call.sid));}
            catch(Exception failed){leg.close();}
        }
    }
    static boolean initiate(String own,String other){return own.substring(own.indexOf('/')+1).compareTo(other.substring(other.indexOf('/')+1))>0;}
    private boolean authorized(String real){
        if(room==null || chat==null || !chat.isJoined() || blocked.test(real))return false;
        for(Participant p:participants.values())if(!p.preparing && p.realJid.equals(real)) {
            try{Participant current=parse(chat.getOccupantPresence(JidCreate.entityFullFrom(p.occupant)));return current!=null && !current.preparing && real.equals(current.realJid);}catch(Exception invalid){return false;}
        }
        return false;
    }
    private boolean route(IQ iq){
        if(!(iq instanceof Packet))return false;StandardExtensionElement root=((Packet)iq).element;String sid=root.getAttributeValue("sid"),from=iq.getFrom()==null?"":iq.getFrom().toString();
        Leg leg=legs.get(from);boolean tagged=root.getFirstElement("muji",NS)!=null;
        if(!tagged && (leg==null || !leg.sid.equals(sid)))return false;
        if(!enqueue(()->incoming(iq)))reject(iq,StanzaError.Condition.resource_constraint);return true;
    }
    private void incoming(IQ iq){
        StandardExtensionElement root=((Packet)iq).element;String from=iq.getFrom()==null?"":iq.getFrom().toString(),sid=root.getAttributeValue("sid");
        StandardExtensionElement reference=root.getFirstElement("muji",NS);
        if(reference!=null && !Objects.equals(room,reference.getAttributeValue("room"))){reject(iq,StanzaError.Condition.not_authorized);return;}
        if(!ready || !authorized(from)){reject(iq,StanzaError.Condition.not_authorized);return;}
        if("session-initiate".equals(root.getAttributeValue("action"))) {
            StandardExtensionElement tag=root.getFirstElement("muji",NS);
            if(tag==null || !Objects.equals(room,tag.getAttributeValue("room"))){reject(iq,StanzaError.Condition.conflict);return;}
            Leg existing=legs.get(from);
            if(existing!=null){if(existing.sid.equals(sid))existing.engine.receiveConference(iq);else reject(iq,StanzaError.Condition.conflict);return;}
            if(legs.size()>=MAX_PARTICIPANTS-1){reject(iq,StanzaError.Condition.resource_constraint);return;}
            try{token(sid,128);}catch(RuntimeException malformed){reject(iq,StanzaError.Condition.bad_request);return;}
            XmppCalls engine=calls.conferenceLeg(room);engine.setListener(delegate(engine));legs.put(from,new Leg(engine,sid));engine.receiveConference(iq);
        }else{Leg leg=legs.get(from);if(leg==null || !leg.sid.equals(sid)){reject(iq,StanzaError.Condition.item_not_found);return;}leg.engine.receiveConference(iq);}
    }
    private XmppCalls.Listener delegate(XmppCalls engine){return new XmppCalls.Listener(){
        public void incoming(XmppCalls.Call call){enqueue(()->{if(authorized(call.fullPeer))engine.accept(call.sid);else engine.reject(call.sid);});}
        public void state(XmppCalls.Call call,XmppCalls.State state,String reason){enqueue(()->{
            if(state==XmppCalls.State.ENDED){Leg old=legs.get(call.fullPeer);if(old!=null && old.engine==engine){legs.remove(call.fullPeer);engine.close();}}
            listener.state(call,state,reason);publish(null);
        });}
        public void localDescriptionRequested(XmppCalls.Call call,boolean offer){enqueue(()->listener.localDescriptionRequested(call,offer));}
        public void remoteDescription(XmppCalls.Call call,boolean offer,String sdp){enqueue(()->listener.remoteDescription(call,offer,sdp));}
        public void remoteCandidate(XmppCalls.Call call,String mid,int line,String value){enqueue(()->listener.remoteCandidate(call,mid,line,value));}
        public void remoteMediaState(XmppCalls.Call call,boolean audio,boolean video){enqueue(()->listener.remoteMediaState(call,audio,video));}
    };}
    private void removeLeg(String peer){Leg leg=legs.remove(peer);if(leg!=null)leg.engine.close();}
    private void publish(String error){listener.conference(room,Collections.unmodifiableList(new ArrayList<>(participants.values())),error);}
    private void reject(IQ iq,StanzaError.Condition error){try{connection.sendStanza(IQ.createErrorResponse(iq,StanzaError.getBuilder(error)));}catch(Exception ignored){}}
    private void stop(String error){stop(error,true);}
    private void stop(String error,boolean withdraw){
        String leaving=room;room=null;ready=false;selfEcho=false;if(deadline!=null)deadline.cancel(false);
        if(withdraw && leaving!=null && chat!=null && chat.isJoined() && connection.isAuthenticated())try{Presence clear=new Presence(Presence.Type.available);clear.setTo(JidCreate.entityFullFrom(occupant));connection.sendStanza(clear);}catch(Exception ignored){}
        for(Leg leg:new ArrayList<>(legs.values()))leg.engine.close();legs.clear();participants.clear();waiting.clear();chat=null;publish(error);
    }
    @Override public void close(){
        if(closed)return;CompletableFuture<Void> done=new CompletableFuture<>();
        if(enqueue(()->{stop(null);done.complete(null);}))try{done.get(2,TimeUnit.SECONDS);}catch(Exception ignored){}
        closed=true;calls.setConferenceHandler(null);calls.setConferenceBusy(()->false);connection.removeAsyncStanzaListener(presence);connection.removeStanzaInterceptor(decorate);connection.removeConnectionListener(lifecycle);events.shutdownNow();
        ServiceDiscoveryManager.getInstanceFor(connection).removeFeature(NS);
    }
}
