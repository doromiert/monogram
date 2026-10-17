package com.negzero.monogram.xmpp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class XmppPresenceTest {
    private org.jivesoftware.smack.packet.Presence resource(org.jivesoftware.smack.packet.Presence.Mode mode,Long idle) {
        org.jivesoftware.smack.packet.Presence presence=new org.jivesoftware.smack.packet.Presence(org.jivesoftware.smack.packet.Presence.Type.available);
        presence.setMode(mode);
        if(idle!=null) presence.addExtension(new org.jivesoftware.smackx.last_interaction.element.IdleElement(new java.util.Date(idle*1000)));
        return presence;
    }
    @Test void availableDoesNotMeanActive() {
        for(org.jivesoftware.smack.packet.Presence.Mode mode:new org.jivesoftware.smack.packet.Presence.Mode[]{
                org.jivesoftware.smack.packet.Presence.Mode.away,org.jivesoftware.smack.packet.Presence.Mode.xa,org.jivesoftware.smack.packet.Presence.Mode.dnd}) {
            XmppContacts.PresenceState state=XmppContacts.aggregate(java.util.Collections.singletonList(resource(mode,null)),1000);
            assertTrue(state.available); assertFalse(state.online); assertEquals(mode,state.mode); assertEquals(0,state.lastSeen);
        }
    }
    @Test void idleTimesAggregateWithoutHidingAnActiveResource() {
        org.jivesoftware.smack.packet.Presence.Mode normal=org.jivesoftware.smack.packet.Presence.Mode.available;
        java.util.List<org.jivesoftware.smack.packet.Presence> resources=new java.util.ArrayList<>();
        resources.add(resource(normal,500L)); resources.add(resource(normal,700L));
        XmppContacts.PresenceState idle=XmppContacts.aggregate(resources,1000);
        assertFalse(idle.online); assertTrue(idle.available); assertEquals(700,idle.lastSeen);
        resources.add(resource(normal,null));
        assertTrue(XmppContacts.aggregate(resources,1000).online);
    }
    @Test void unknownOrFutureIdleDoesNotInventLastSeen() {
        org.jivesoftware.smack.packet.Presence.Mode away=org.jivesoftware.smack.packet.Presence.Mode.away;
        for(Long idle:new Long[]{null,0L,-1L,1001L}) {
            XmppContacts.PresenceState state=XmppContacts.aggregate(java.util.Arrays.asList(resource(away,500L),resource(away,idle)),1000);
            assertFalse(state.online); assertEquals(0,state.lastSeen);
        }
    }
    @Test void offlineTimestampRequiresValidNonzeroServerDuration() {
        assertEquals(970,XmppContacts.lastSeenTimestamp(1000,30));
        for(long seconds:new long[]{0,-1,1000,1001,Long.MAX_VALUE,Long.MIN_VALUE}) assertEquals(0,XmppContacts.lastSeenTimestamp(1000,seconds));
    }
}
