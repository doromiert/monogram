// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp.calls;

import java.util.*;
import java.util.concurrent.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.iqrequest.*;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.carbons.CarbonCopyReceivedListener;
import org.jxmpp.jid.impl.JidCreate;
import static com.negzero.monogram.xmpp.calls.JingleCodec.*;

/** One connection, one bounded Jingle RTP call. State-machine/JMI arbitration adapted from Monocles
 * JingleConnectionManager.java and JingleRtpConnection.java, codeberg.org/monocles/monocles_chat
 * commit 129dbcc7cdc919c73a65e50ee121efecc36c5e7e. No Android or Telegram call protocol. */
public final class XmppCalls implements AutoCloseable {
    public enum State { PROPOSING,RINGING,CONNECTING,ACTIVE,ENDED }
    public static final class Call {
        public final String sid,peer,fullPeer;public final boolean outgoing,video;
        Call(String sid,String peer,String fullPeer,boolean outgoing,boolean video) { this.sid=sid;this.peer=peer;this.fullPeer=fullPeer;this.outgoing=outgoing;this.video=video; }
    }
    public static final class Capabilities {
        public final boolean audio,video,jmi;public final List<String> resources;private final List<String> videoResources;
        Capabilities(boolean audio,boolean video,boolean jmi,List<String> resources) { this(audio,video,jmi,resources,Collections.emptyList()); }
        Capabilities(boolean audio,boolean video,boolean jmi,List<String> resources,List<String> videoResources) { this.audio=audio;this.video=video;this.jmi=jmi;this.resources=Collections.unmodifiableList(new ArrayList<>(resources));this.videoResources=Collections.unmodifiableList(new ArrayList<>(videoResources)); }
    }
    public interface Listener {
        default void incoming(Call call) {}
        default void state(Call call,State state,String reason) {}
        default void localDescriptionRequested(Call call,boolean offer) {}
        default void remoteDescription(Call call,boolean offer,String sdp) {}
        default void remoteCandidate(Call call,String mid,int mLineIndex,String candidate) {}
        default void remoteMediaState(Call call,boolean audioMuted,boolean videoMuted) {}
        default void capabilities(String peer,Capabilities capabilities) {}
    }
    private static final Listener NONE=new Listener() {};
    private static final class Candidate { final String mid,value,ufrag;final int index;Candidate(String mid,int index,String value,String ufrag) { this.mid=mid;this.index=index;this.value=value;this.ufrag=ufrag; } }
    private static final class Entry {
        Call call;State state;boolean accepted,offerRequested,remoteApplied,descriptionAcknowledged,wantedAudioMuted,wantedVideoMuted,remoteAudioMuted,remoteVideoMuted,localAudioMuted,localVideoMuted;CallDescription local,remote;
        final List<Candidate> candidates=new ArrayList<>(),localCandidates=new ArrayList<>();int candidateBytes;ScheduledFuture<?> timeout;
        Entry(Call call,State state) { this.call=call;this.state=state; }
    }
    private final XMPPConnection connection;
    private final ScheduledThreadPoolExecutor events=new ScheduledThreadPoolExecutor(1,r -> { Thread t=new Thread(r,"Monogram Jingle");t.setDaemon(true);return t; });
    private final ThreadPoolExecutor network=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r -> { Thread t=new Thread(r,"Monogram call discovery");t.setDaemon(true);return t; },new ThreadPoolExecutor.AbortPolicy());
    private final Set<CompletableFuture<?>> requests=Collections.newSetFromMap(new ConcurrentHashMap<CompletableFuture<?>,Boolean>());
    private final XmppIceServers ice;
    private volatile Listener listener=NONE;private volatile boolean closed;private Entry active;private long generation;
    private final Map<String,Capabilities> capabilities=new LinkedHashMap<>();
    private final Map<String,Long> discoveredAt=new HashMap<>();
    private final Map<String,CompletableFuture<Capabilities>> discoveries=new HashMap<>();
    private final Map<String,ScheduledFuture<?>> refreshes=new HashMap<>();
    private final LinkedHashSet<String> requestedPeers=new LinkedHashSet<>();
    private final java.util.concurrent.atomic.AtomicInteger queued=new java.util.concurrent.atomic.AtomicInteger();
    private final LinkedHashSet<String> seenIq=new LinkedHashSet<>();
    private final AbstractIqRequestHandler handler;
    private final StanzaListener messages,presences;
    private final ConnectionListener lifecycle;
    private final CarbonCopyReceivedListener carbons;
    public XmppCalls(XMPPConnection connection) {
        this.connection=connection;ice=new XmppIceServers(connection);initialize();events.setRemoveOnCancelPolicy(true);network.allowCoreThreadTimeOut(true);
        ServiceDiscoveryManager disco=ServiceDiscoveryManager.getInstanceFor(connection);
        for(String ns:Arrays.asList(NS,RTP,ICE,DTLS,GROUP,JMI,"urn:xmpp:jingle:apps:rtp:audio","urn:xmpp:jingle:apps:rtp:video")) disco.addFeature(ns);
        handler=new AbstractIqRequestHandler("jingle",NS,IQ.Type.set,IQRequestHandler.Mode.async) {
            @Override public IQ handleIQRequest(IQ request) { if(!execute(() -> incomingIq(request))) return IQ.createErrorResponse(request,StanzaError.getBuilder(StanzaError.Condition.resource_constraint));return null; }
        };connection.registerIQRequestHandler(handler);
        messages=stanza -> { Message m=(Message)stanza;if(m.getType()==Message.Type.chat || m.getType()==Message.Type.normal) execute(() -> incomingMessage(m,false)); };
        connection.addAsyncStanzaListener(messages,stanza -> stanza instanceof Message && stanza.hasExtension(JMI));
        carbons=(direction,forwarded,wrapping) -> {
            if(connection.getUser()==null || wrapping.getFrom()==null || !wrapping.getFrom().isEntityBareJid() || !wrapping.getFrom().equals(connection.getUser().asBareJid()) || forwarded.getFrom()==null) return;
            boolean own=forwarded.getFrom().asBareJid().equals(connection.getUser().asBareJid());
            if((direction==org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction.sent)!=own) return;
            execute(() -> incomingMessage(forwarded,true));
        };CarbonManager.getInstanceFor(connection).addCarbonCopyReceivedListener(carbons);
        presences=stanza -> { Presence p=(Presence)stanza;if(p.getFrom()!=null && p.getFrom().asEntityBareJidIfPossible()!=null) execute(() -> presenceChanged(p.getFrom().asBareJid().toString())); };
        connection.addAsyncStanzaListener(presences,stanza -> stanza instanceof Presence && (((Presence)stanza).getType()==Presence.Type.available || ((Presence)stanza).getType()==Presence.Type.unavailable));
        lifecycle=new ConnectionListener() {
            @Override public void connectionClosed() { disconnected(); }
            @Override public void connectionClosedOnError(Exception error) { disconnected(); }
        };connection.addConnectionListener(lifecycle);
    }
    public void setListener(Listener listener) { this.listener=listener==null?NONE:listener; }
    public synchronized Call current() { return active==null?null:active.call; }
    public synchronized Capabilities cachedCapabilities(String peer) { Capabilities found=capabilities.get(peer);return found==null?new Capabilities(false,false,false,Collections.emptyList()):found; }
    public synchronized CompletableFuture<Capabilities> discover(String peer) {
        final String bare;try { bare=JidCreate.entityBareFrom(peer).toString(); }catch(Exception invalid) { CompletableFuture<Capabilities> failed=new CompletableFuture<>();failed.completeExceptionally(invalid);return failed; }
        if(discoveries.containsKey(bare)) return discoveries.get(bare);
        Long at=discoveredAt.get(bare);if(at!=null && System.currentTimeMillis()-at<60000 && capabilities.containsKey(bare)) return CompletableFuture.completedFuture(capabilities.get(bare));
        if(requestedPeers.size()>=256 && !requestedPeers.contains(bare)) { String first=requestedPeers.iterator().next();requestedPeers.remove(first);capabilities.remove(first);discoveredAt.remove(first); }
        requestedPeers.add(bare);final long fence=generation;
        CompletableFuture<Capabilities> future=submit(() -> {
            boolean audio=false,video=false,jmi=false;List<String> resources=new ArrayList<>(),videoResources=new ArrayList<>();int attempted=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            for(Presence p:Roster.getInstanceFor(connection).getAllPresences(JidCreate.entityBareFrom(bare))) {
                if(!p.isAvailable() || p.getFrom()==null || !p.getFrom().isEntityFullJid()) continue;if(++attempted>16) break;
                long remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(remaining<=0) break;
                DiscoverInfo query=new DiscoverInfo();query.setType(IQ.Type.get);query.setTo(p.getFrom());DiscoverInfo info;
                org.jivesoftware.smack.StanzaCollector collector=connection.createStanzaCollectorAndSend(query);
                try { info=collector.nextResultOrThrow(Math.min(3000,remaining)); }
                catch(org.jivesoftware.smack.SmackException.NoResponseException | org.jivesoftware.smack.XMPPException.XMPPErrorException unavailable) { continue; }
                finally { collector.cancel(); }
                boolean supported=info.containsFeature(NS) && info.containsFeature(RTP) && info.containsFeature(ICE) && info.containsFeature(DTLS);
                boolean a=supported && info.containsFeature("urn:xmpp:jingle:apps:rtp:audio"),v=a && info.containsFeature("urn:xmpp:jingle:apps:rtp:video");
                if(a) { resources.add(p.getFrom().toString());audio=true;video|=v;if(v) videoResources.add(p.getFrom().toString());jmi|=info.containsFeature(JMI); }
            }
            return new Capabilities(audio,video,jmi,resources,videoResources);
        });
        discoveries.put(bare,future);
        future.whenComplete((result,error) -> {
            synchronized(this) { if(discoveries.get(bare)!=future) return;discoveries.remove(bare);if(error!=null || generation!=fence || closed) return;capabilities.put(bare,result);discoveredAt.put(bare,System.currentTimeMillis()); }
            execute(() -> listener.capabilities(bare,result));
        });return future;
    }
    private void presenceChanged(String peer) {
        if(!requestedPeers.contains(peer)) return;
        capabilities.remove(peer);discoveredAt.remove(peer);CompletableFuture<Capabilities> previous=discoveries.remove(peer);if(previous!=null) previous.completeExceptionally(new ConcurrentModificationException("Peer resources changed"));
        listener.capabilities(peer,new Capabilities(false,false,false,Collections.emptyList()));
        ScheduledFuture<?> prior=refreshes.remove(peer);if(prior!=null) prior.cancel(false);
        refreshes.put(peer,events.schedule(() -> { synchronized(this) { refreshes.remove(peer);if(!closed && connection.isAuthenticated()) discover(peer); } },750,TimeUnit.MILLISECONDS));
    }
    public CompletableFuture<List<XmppIceServers.IceServer>> iceServers() { return submit(ice::discover); }
    private <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future=new CompletableFuture<>();long fence;
        synchronized(this) { if(closed || !connection.isAuthenticated()) { future.completeExceptionally(new IllegalStateException("XMPP is disconnected"));return future; }fence=generation; }
        requests.add(future);future.whenComplete((value,error) -> requests.remove(future));
        try { network.execute(() -> { try { T result=task.call();synchronized(this) { if(closed || fence!=generation || !connection.isAuthenticated()) throw new IllegalStateException("XMPP session changed"); }future.complete(result); }catch(Exception error) { future.completeExceptionally(error); } }); }
        catch(RejectedExecutionException busy) { future.completeExceptionally(busy); }return future;
    }
    public synchronized Call start(String peer,boolean video) {
        if(closed || !connection.isAuthenticated()) throw new IllegalStateException("XMPP is disconnected");if(active!=null) throw new IllegalStateException("Another call is active");
        try { peer=JidCreate.entityBareFrom(peer).toString(); }catch(Exception bad) { throw invalid("Invalid peer"); }
        if(peer.equals(connection.getUser().asBareJid().toString())) throw invalid("Cannot call this account");
        Capabilities known=capabilities.get(peer);String resource=null;
        if(known!=null && known.audio && !known.jmi) { List<String> eligible=video?known.videoResources:known.resources;if(eligible.isEmpty()) throw new IllegalStateException("Contact does not advertise this call type");resource=eligible.get(0); }
        Call call=new Call(UUID.randomUUID().toString(),peer,resource,true,video);active=new Entry(call,resource==null?State.PROPOSING:State.CONNECTING);
        if(!execute(() -> { if(!is(call.sid)) return;if(call.fullPeer==null) { emit(State.PROPOSING,null);jmi("propose",call.peer,call); }else { emit(State.CONNECTING,null);requestDescription(active,true); }timeout(call.sid,45000,"timeout"); })) { active=null;throw new IllegalStateException("Call signaling queue is full"); }return call;
    }
    public void accept(String sid) { command(sid,e -> {
        if(e.call.outgoing || e.accepted) return;e.accepted=true;
        jmi("accept",connection.getUser().asBareJid().toString(),e.call);
        if(e.remote==null) jmi("proceed",e.call.fullPeer,e.call);
        else { listener.remoteDescription(e.call,true,JingleSdp.serialize(e.remote));requestDescription(e,false); }
        emit(State.CONNECTING,null);timeout(sid,45000,"timeout");
    }); }
    public void reject(String sid) { command(sid,e -> { if(e.local==null && e.remote==null) { jmi("reject",e.call.fullPeer==null?e.call.peer:e.call.fullPeer,e.call);jmi("reject",connection.getUser().asBareJid().toString(),e.call);finish("decline"); }else terminate("decline"); }); }
    public void end(String sid) { command(sid,e -> { if(e.local==null && e.remote==null) { jmi(e.call.outgoing?"retract":"reject",e.call.fullPeer==null?e.call.peer:e.call.fullPeer,e.call);finish("cancel"); }else terminate("success"); }); }
    public void localDescription(String sid,boolean offer,String sdp) { command(sid,e -> {
        try {
            CallDescription parsed=JingleSdp.parse(sdp);
            if(offer!=e.call.outgoing || e.local!=null || parsed.video()!=e.call.video || !e.offerRequested) throw invalid("Unexpected local description");
            if(!offer && e.remote==null) throw invalid("Missing offer");validateRoles(parsed,offer);
            if(e.remote!=null) matchingContents(e.remote,parsed);e.local=parsed;
            iq(offer?"session-initiate":"session-accept",description(parsed,e.call.outgoing),null);
            emit(State.CONNECTING,null);timeout(sid,45000,"timeout");
        }catch(RuntimeException invalid) { terminate("failed-application"); }
    }); }
    public void candidate(String sid,String mid,int mLineIndex,String candidate) { command(sid,e -> {
        try {
            token(mid,64);line(candidate);if(candidate.length()>2048 || mLineIndex<0 || mLineIndex>1) throw invalid("Invalid candidate");
            Candidate c=new Candidate(mid,mLineIndex,candidate,null);
            if(e.local==null || !e.descriptionAcknowledged) { if(e.localCandidates.size()>=MAX_CANDIDATES) throw invalid("Too many local candidates");e.localCandidates.add(c); }else sendCandidate(e,c);
        }catch(RuntimeException invalid) { terminate("failed-transport"); }
    }); }
    public void remoteDescriptionApplied(String sid) { command(sid,e -> { if(e.remote==null) return;for(Candidate c:e.candidates) { int index=contentIndex(e.remote,c.mid);if(index<0 || !e.remote.contents.get(index).ufrag.equals(c.ufrag)) { terminate("failed-transport");return; } }
        e.remoteApplied=true;for(Candidate c:e.candidates) listener.remoteCandidate(e.call,c.mid,contentIndex(e.remote,c.mid),c.value);e.candidates.clear();e.candidateBytes=0; }); }
    public void mediaMuted(String sid,boolean audioMuted,boolean videoMuted) { command(sid,e -> {
        e.wantedAudioMuted=audioMuted;e.wantedVideoMuted=videoMuted;sendMediaState(e);
    }); }
    private void sendMediaState(Entry e) {
        if(e.local==null || !e.descriptionAcknowledged) return;StandardExtensionElement.Builder info=StandardExtensionElement.builder("jingle",NS);
        for(Content c:e.local.contents) {
            boolean audio="audio".equals(c.media),muted=audio?e.wantedAudioMuted:e.wantedVideoMuted,previous=audio?e.localAudioMuted:e.localVideoMuted;
            if(muted!=previous) info.addElement(StandardExtensionElement.builder(muted?"mute":"unmute","urn:xmpp:jingle:apps:rtp:info:1").addAttribute("creator","initiator").addAttribute("name",c.name).build());
        }
        e.localAudioMuted=e.wantedAudioMuted;e.localVideoMuted=e.wantedVideoMuted;StandardExtensionElement payload=info.build();if(!payload.getElements().isEmpty()) iq("session-info",payload,null);
    }
    public void mediaConnected(String sid) { command(sid,e -> { if(e.local==null || e.remote==null || !e.accepted && !e.call.outgoing) return;if(e.timeout!=null) e.timeout.cancel(false);emit(State.ACTIVE,null); }); }
    public void mediaFailed(String sid) { mediaFailed(sid,"Media connection failed"); }
    public void mediaFailed(String sid,String reason) { command(sid,e -> {
        iq("session-terminate",null,"failed-transport");String detail=reason==null?"":reason.replaceAll("[\\r\\n\\p{Cntrl}]"," ");if(detail.length()>256) detail=detail.substring(0,256);finish(detail.isEmpty()?"failed-transport":"failed-transport: "+detail);
    }); }
    private void sendCandidate(Entry e,Candidate candidate) {
        if(candidate.index>=e.local.contents.size()) throw invalid("Invalid candidate media");Content c=e.local.contents.get(candidate.index);if(!c.name.equals(candidate.mid)) throw invalid("Candidate MID mismatch");
        StandardExtensionElement transport=StandardExtensionElement.builder("transport",ICE).addAttribute("ufrag",c.ufrag).addAttribute("pwd",c.password).addElement(JingleCodec.candidate(candidate.value,c.ufrag)).build();
        StandardExtensionElement content=StandardExtensionElement.builder("content",NS).addAttribute("creator","initiator").addAttribute("name",c.name).addElement(transport).build();iq("transport-info",StandardExtensionElement.builder("jingle",NS).addElement(content).build(),null);
    }
    private synchronized boolean is(String sid) { return !closed && active!=null && active.call.sid.equals(sid) && connection.isAuthenticated(); }
    private void command(String sid,java.util.function.Consumer<Entry> action) { execute(() -> { synchronized(this) { if(is(sid)) action.accept(active); } }); }
    private boolean execute(Runnable work) {
        final long fence; synchronized(this) { fence=generation; }
        if(closed || queued.incrementAndGet()>64) { if(!closed) queued.decrementAndGet();return false; }
        try { events.execute(() -> { try { if(!closed) { try { synchronized(this) { if(generation==fence) work.run(); } }catch(RuntimeException bad) { synchronized(this) { if(active!=null) terminate("failed-application"); } } } }finally { queued.decrementAndGet(); } });return true; }
        catch(RejectedExecutionException stopped) { queued.decrementAndGet();return false; }
    }
    private void requestDescription(Entry e,boolean offer) { if(e.offerRequested) return;e.offerRequested=true;listener.localDescriptionRequested(e.call,offer); }
    private void emit(State state,String reason) { if(active==null) return;active.state=state;listener.state(active.call,state,reason); }
    private void timeout(String sid,long millis,String reason) { if(active==null || !active.call.sid.equals(sid)) return;if(active.timeout!=null) active.timeout.cancel(false);active.timeout=events.schedule(() -> { synchronized(this) { if(is(sid)) { if(active.local==null && active.remote==null) { jmi(active.call.outgoing?"retract":"reject",active.call.fullPeer==null?active.call.peer:active.call.fullPeer,active.call);finish(reason); }else terminate(reason); } } },millis,TimeUnit.MILLISECONDS); }
    private void finish(String reason) { if(active==null) return;Entry ended=active;active=null;if(ended.timeout!=null) ended.timeout.cancel(false);ended.candidates.clear();ended.localCandidates.clear();listener.state(ended.call,State.ENDED,reason); }
    private void terminate(String reason) { if(active==null) return;if(active.call.fullPeer!=null) iq("session-terminate",null,reason);finish(reason); }
    private void jmi(String action,String to,Call call) {
        try {
            StandardExtensionElement.Builder b=StandardExtensionElement.builder(action,JMI).addAttribute("id",call.sid);
            if("propose".equals(action)) { b.addElement(StandardExtensionElement.builder("description",RTP).addAttribute("media","audio").build());if(call.video) b.addElement(StandardExtensionElement.builder("description",RTP).addAttribute("media","video").build()); }
            Message message=new Message(JidCreate.from(to),Message.Type.chat);message.addExtension(b.build());message.addExtension(StandardExtensionElement.builder("store","urn:xmpp:hints").build());connection.sendStanza(message);
        }catch(Exception failure) { finish("disconnected"); }
    }
    private void iq(String action,StandardExtensionElement payload,String reason) {
        if(active==null || active.call.fullPeer==null) return;Call call=active.call;
        StandardExtensionElement.Builder b=StandardExtensionElement.builder("jingle",NS).addAttribute("action",action).addAttribute("sid",call.sid);
        if("session-initiate".equals(action)) b.addAttribute("initiator",connection.getUser().toString());
        if("session-accept".equals(action)) b.addAttribute("responder",connection.getUser().toString());
        if(payload!=null) for(StandardExtensionElement c:payload.getElements()) b.addElement(c);
        if(reason!=null) b.addElement(StandardExtensionElement.builder("reason",NS).addElement(StandardExtensionElement.builder(reason,NS).build()).build());
        Packet packet=new Packet(b.build());packet.setType(IQ.Type.set);
        try { packet.setTo(JidCreate.entityFullFrom(call.fullPeer));connection.sendIqRequestAsync(packet).onSuccess(answer -> {
                if("session-initiate".equals(action) || "session-accept".equals(action)) command(call.sid,e -> {
                    e.descriptionAcknowledged=true;
                    for(Candidate candidate:new ArrayList<>(e.localCandidates)) sendCandidate(e,candidate);e.localCandidates.clear();sendMediaState(e);
                });
            }).onError(error -> {
                if("session-info".equals(action) && error instanceof XMPPException.XMPPErrorException
                        && ((XMPPException.XMPPErrorException)error).getStanzaError().getCondition()==StanzaError.Condition.feature_not_implemented) return;
                command(call.sid,e -> finish("signaling-error"));
            }); }
        catch(Exception failure) { finish("disconnected"); }
    }
    private synchronized void incomingMessage(Message message,boolean carbon) {
        if(message.getType()!=Message.Type.chat && message.getType()!=Message.Type.normal) return;
        if(!connection.isAuthenticated() || message.getFrom()==null || message.getTo()==null) return;
        // Reconnect/offline delivery may be fresh; it must not extend an old proposal's lifetime.
        long age=0;
        if(message.hasExtension("urn:xmpp:delay") || message.hasExtension("jabber:x:delay")) {
            Date stamp=org.jivesoftware.smackx.delay.DelayInformationManager.getDelayTimestamp(message);if(stamp==null) return;
            age=System.currentTimeMillis()-stamp.getTime();if(age < -5000 || age>=45000) return;age=Math.max(0,age);
        }
        String own=connection.getUser().asBareJid().toString(),from=message.getFrom().toString();boolean self=message.getFrom().asBareJid().toString().equals(own);
        if(!self && !message.getTo().asBareJid().toString().equals(own)) return;
        List<StandardExtensionElement> actions=new ArrayList<>();for(ExtensionElement x:message.getExtensions()) if(JMI.equals(x.getNamespace()) && x instanceof StandardExtensionElement) actions.add((StandardExtensionElement)x);
        if(actions.size()!=1) return;StandardExtensionElement action=actions.get(0);String sid=action.getAttributeValue("id");try { token(sid,128); }catch(RuntimeException bad) { return; }
        String name=action.getElementName();
        // Received carbons fan a bare proposal/retraction out to the account's other resources.
        if(carbon && !self && (!message.getTo().isEntityBareJid() || !Arrays.asList("propose","retract").contains(name))) return;
        if(self) { if(active!=null && !active.call.outgoing && !active.accepted && active.call.sid.equals(sid) && !from.equals(connection.getUser().toString()) && Arrays.asList("accept","reject","proceed").contains(name)) finish("answered-elsewhere");return; }
        if(!message.getFrom().isEntityFullJid()) return;String bare=message.getFrom().asBareJid().toString();
        if("propose".equals(name)) {
            boolean audio=false,video=false;Set<String> media=new HashSet<>();if(action.getElements().size()>2) return;
            for(StandardExtensionElement c:action.getElements()) { String kind=c.getAttributeValue("media");if(!RTP.equals(c.getNamespace()) || !"description".equals(c.getElementName()) || !Arrays.asList("audio","video").contains(kind) || !media.add(kind)) return;audio|="audio".equals(kind);video|="video".equals(kind); }if(!audio) return;
            Call incoming=new Call(sid,bare,from,false,video);
            if(active!=null) {
                if(active.call.sid.equals(sid) && active.call.peer.equals(bare)) return;
                if(active.call.outgoing && active.call.peer.equals(bare) && active.state==State.PROPOSING && own.compareTo(bare)>0) { jmi("retract",active.call.peer,active.call);finish("alternative-session"); }
                else { jmi("reject",from,incoming);return; }
            }
            active=new Entry(incoming,State.RINGING);listener.incoming(incoming);emit(State.RINGING,null);timeout(sid,45000-age,"timeout");return;
        }
        if(active==null || !active.call.sid.equals(sid) || !active.call.peer.equals(bare)) return;
        Entry e=active;if(e.call.fullPeer!=null && !e.call.fullPeer.equals(from)) {
            if(e.call.outgoing && "proceed".equals(name)) jmi("reject",from,e.call);return;
        }
        if("proceed".equals(name) && e.call.outgoing && e.state==State.PROPOSING) { e.call=new Call(sid,bare,from,true,e.call.video);emit(State.CONNECTING,null);requestDescription(e,true);timeout(sid,45000,"timeout"); }
        else if("retract".equals(name) && !e.call.outgoing && !e.accepted) finish("cancel");
        else if("reject".equals(name) && e.call.outgoing && e.state==State.PROPOSING) finish("decline");
        else if("reject".equals(name) && !e.call.outgoing && e.accepted && e.remote==null) finish("answered-elsewhere");
    }
    private synchronized void incomingIq(IQ iq) {
        if(!(iq instanceof Packet) || !connection.isAuthenticated() || iq.getFrom()==null || !iq.getFrom().isEntityFullJid() || iq.getTo()!=null && !iq.getTo().equals(connection.getUser())) { reply(iq,StanzaError.Condition.bad_request);return; }
        StandardExtensionElement root=((Packet)iq).element;String sid=root.getAttributeValue("sid"),action=root.getAttributeValue("action");String key=iq.getFrom()+"\n"+sid+"\n"+iq.getStanzaId();
        if(seenIq.contains(key)) { reply(iq,null);return; }
        try {
            token(sid,128);String from=iq.getFrom().toString(),peer=iq.getFrom().asBareJid().toString();
            if("session-initiate".equals(action)) {
                CallDescription remote=JingleSdp.fromJingle(root,true);validateRoles(remote,true);
                if(root.getAttributeValue("initiator")!=null && !from.equals(root.getAttributeValue("initiator"))) throw invalid("Wrong initiator");
                if(active==null) { Call incoming=new Call(sid,peer,from,false,remote.video());active=new Entry(incoming,State.RINGING);active.remote=remote;listener.incoming(incoming);emit(State.RINGING,null);timeout(sid,45000,"timeout"); }
                else {
                    if(!active.call.sid.equals(sid) || active.call.outgoing || !from.equals(active.call.fullPeer) || active.remote!=null || remote.video()!=active.call.video) { reply(iq,StanzaError.Condition.conflict);return; }
                    active.remote=remote;if(active.accepted) { listener.remoteDescription(active.call,true,JingleSdp.serialize(remote));requestDescription(active,false); }
                }
            } else {
                if(active==null || !active.call.sid.equals(sid) || !from.equals(active.call.fullPeer)) { reply(iq,StanzaError.Condition.item_not_found);return; }
                if("session-accept".equals(action)) {
                    if(!active.call.outgoing || active.local==null || active.remote!=null) throw invalid("Unexpected answer");CallDescription remote=JingleSdp.fromJingle(root,false);validateRoles(remote,false);matchingContents(active.local,remote);active.remote=remote;listener.remoteDescription(active.call,false,JingleSdp.serialize(remote));
                } else if("transport-info".equals(action)) incomingCandidates(root);
                else if("session-terminate".equals(action)) finish("remote-ended");
                else if("session-info".equals(action)) incomingMediaState(root);
                else { reply(iq,StanzaError.Condition.feature_not_implemented);return; }
            }
            seenIq.add(key);while(seenIq.size()>128) seenIq.remove(seenIq.iterator().next());reply(iq,null);
        }catch(RuntimeException malformed) { reply(iq,StanzaError.Condition.bad_request); }
    }
    private void incomingMediaState(StandardExtensionElement root) {
        if(root.getElements().isEmpty()) return;
        if(root.getElements().size()>2) throw invalid("Invalid RTP session info");
        boolean audio=active.remoteAudioMuted,video=active.remoteVideoMuted,changed=false;
        for(StandardExtensionElement info:root.getElements()) {
            if(!"urn:xmpp:jingle:apps:rtp:info:1".equals(info.getNamespace())) throw invalid("Invalid RTP info namespace");String name=info.getElementName();
            if(Arrays.asList("ringing","hold","unhold").contains(name)) continue;
            if("active".equals(name)) {
                String mid=info.getAttributeValue("name");if(mid==null) { audio=false;video=false; }
                else { int index=contentIndex(active.remote,mid);if(index<0) throw invalid("Unknown RTP active content");if(active.remote.contents.get(index).media.equals("audio")) audio=false;else video=false; }
                changed=true;continue;
            }
            if(!Arrays.asList("mute","unmute").contains(name) || active.remote==null) throw invalid("Invalid RTP mute");
            String creator=attr(info,"creator");if(!Arrays.asList("initiator","responder").contains(creator)) throw invalid("Invalid RTP mute creator");
            String mid=info.getAttributeValue("name");boolean muted=name.equals("mute");
            if(mid==null) { audio=muted;video=active.call.video && muted; }
            else { int index=contentIndex(active.remote,mid);if(index<0 || !"initiator".equals(creator)) throw invalid("Unknown RTP mute content");if(active.remote.contents.get(index).media.equals("audio")) audio=muted;else video=muted; }
            changed=true;
        }
        if(changed) { active.remoteAudioMuted=audio;active.remoteVideoMuted=video;listener.remoteMediaState(active.call,audio,video); }
    }
    private void incomingCandidates(StandardExtensionElement root) {
        Entry e=active;if(root.getElements().size()>2) throw invalid("Too many candidate contents");
        for(StandardExtensionElement content:root.getElements()) {
            if(!NS.equals(content.getNamespace()) || !"content".equals(content.getElementName()) || !"initiator".equals(attr(content,"creator"))) throw invalid("Invalid candidate content");String mid=attr(content,"name");
            StandardExtensionElement transport=JingleSdp.one(content,"transport",ICE);String ufrag=attr(transport,"ufrag");
            if(transport.getElements().size()>MAX_CANDIDATES) throw invalid("Too many candidates");
            for(StandardExtensionElement c:transport.getElements()) {
                if(!ICE.equals(c.getNamespace()) || !"candidate".equals(c.getElementName())) throw invalid("Unsupported ICE restart");String value=JingleCodec.candidate(c,ufrag);
                int index=contentIndex(e.remote,mid);if(e.remote!=null && (index<0 || !e.remote.contents.get(index).ufrag.equals(ufrag))) throw invalid("Stale ICE generation");
                if(e.candidates.size()>=MAX_CANDIDATES || e.candidateBytes+value.length()>65536) throw invalid("Candidate queue full");
                Candidate candidate=new Candidate(mid,index,value,ufrag);if(e.remoteApplied) listener.remoteCandidate(e.call,mid,index,value);else { e.candidates.add(candidate);e.candidateBytes+=value.length(); }
            }
        }
    }
    private static int contentIndex(CallDescription d,String mid) { if(d==null) return -1;for(int i=0;i<d.contents.size();i++) if(d.contents.get(i).name.equals(mid)) return i;return -1; }
    private static void matchingContents(CallDescription a,CallDescription b) { if(a.contents.size()!=b.contents.size()) throw invalid("Answer changed media");for(int i=0;i<a.contents.size();i++) if(!a.contents.get(i).name.equals(b.contents.get(i).name) || !a.contents.get(i).media.equals(b.contents.get(i).media)) throw invalid("Answer changed media"); }
    private static void validateRoles(CallDescription d,boolean offer) { for(Content c:d.contents) if(offer?!"actpass".equals(c.setup):"actpass".equals(c.setup)) throw invalid("Invalid offer/answer DTLS role"); }
    private void reply(IQ iq,StanzaError.Condition error) { try { connection.sendStanza(error==null?IQ.createResultIQ(iq):IQ.createErrorResponse(iq,StanzaError.getBuilder(error))); }catch(Exception ignored) {} }
    private void disconnected() { synchronized(this) { generation++;capabilities.clear();discoveredAt.clear();discoveries.clear();for(ScheduledFuture<?> pending:refreshes.values()) pending.cancel(false);refreshes.clear();ice.clear();for(CompletableFuture<?> pending:requests) pending.completeExceptionally(new IllegalStateException("XMPP disconnected")); }execute(() -> { synchronized(this) { finish("disconnected"); } }); }
    @Override public void close() {
        synchronized(this) { if(closed) return;
            if(active!=null && connection.isAuthenticated()) {
                if(active.local!=null || active.remote!=null) iq("session-terminate",null,"gone");
                else jmi(active.call.outgoing?"retract":"reject",active.call.fullPeer==null?active.call.peer:active.call.fullPeer,active.call);
            }
            finish("closed");closed=true;generation++;capabilities.clear();discoveredAt.clear();discoveries.clear();requestedPeers.clear();for(ScheduledFuture<?> pending:refreshes.values()) pending.cancel(false);refreshes.clear();for(CompletableFuture<?> pending:requests) pending.completeExceptionally(new IllegalStateException("XMPP session closed")); }
        connection.unregisterIQRequestHandler(handler);connection.removeAsyncStanzaListener(messages);connection.removeAsyncStanzaListener(presences);connection.removeConnectionListener(lifecycle);CarbonManager.getInstanceFor(connection).removeCarbonCopyReceivedListener(carbons);ice.clear();network.shutdownNow();events.shutdownNow();listener=NONE;
    }
}
