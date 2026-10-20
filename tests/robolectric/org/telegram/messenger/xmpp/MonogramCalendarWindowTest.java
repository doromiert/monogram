package org.telegram.messenger.xmpp;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonogramCalendarWindowTest {
    private MonogramCalendarWindow.Page page(int id,boolean complete) {
        return new MonogramCalendarWindow.Page("first-"+id,"last-"+id,Collections.singleton(id),complete);
    }
    @Test public void scrollingRetainsThreePagesAndReopensTheEvictedDirection() throws Exception {
        MonogramCalendarWindow window=new MonogramCalendarWindow();window.start(page(2,true),page(1,false));
        assertTrue(window.end(true));assertFalse(window.end(false));
        window.add(page(0,false),false);window.add(page(-1,true),false);
        assertEquals(new LinkedHashSet<>(Arrays.asList(-1,0,1)),window.ids());
        assertEquals("first--1",window.cursor(false));assertEquals("last-1",window.cursor(true));
        assertTrue(window.end(false));assertFalse(window.end(true));
        window.add(page(2,true),true);
        assertEquals(new LinkedHashSet<>(Arrays.asList(0,1,2)),window.ids());
        assertFalse(window.end(false));assertTrue(window.end(true));
    }
    @Test public void controlOnlyPagesAdvanceWithoutTreatingMissingOrRepeatedCursorsAsExhaustion() throws Exception {
        MonogramCalendarWindow window=new MonogramCalendarWindow();window.start(page(2,false),page(1,false));
        window.add(new MonogramCalendarWindow.Page("control-first","control-last",Collections.emptySet(),false),true);
        assertEquals("control-last",window.cursor(true));assertFalse(window.end(true));
        assertThrows(java.io.IOException.class,()->window.add(new MonogramCalendarWindow.Page("control-first","control-last",Collections.emptySet(),false),true));
        assertThrows(java.io.IOException.class,()->window.add(new MonogramCalendarWindow.Page(null,null,Collections.emptySet(),false),true));
        assertFalse(window.end(true));
        window.add(new MonogramCalendarWindow.Page(null,null,Collections.emptySet(),true),true);
        assertTrue(window.end(true));
    }
    @Test public void messageAnchorOwnsItsAdjacentCursorsAndCachedFallbackCannotBorrowThem() throws Exception {
        MonogramCalendarWindow window=new MonogramCalendarWindow();
        window.startAround(page(0,false),new MonogramCalendarWindow.Page("target","target",Collections.singleton(50),false),page(2,false));
        assertEquals(new LinkedHashSet<>(Arrays.asList(0,50,2)),window.ids());assertEquals("first-0",window.cursor(false));assertEquals("last-2",window.cursor(true));
        window.add(page(3,true),true);assertFalse(window.ids().contains(0));assertTrue(window.ids().contains(50));assertFalse(window.end(false));
        window.cached(Arrays.asList(80,81));assertTrue(window.cachedOnly());assertEquals(new LinkedHashSet<>(Arrays.asList(80,81)),window.ids());
        assertNull(window.cursor(false));assertNull(window.cursor(true));assertFalse(window.end(false));assertFalse(window.end(true));
        window.addCached(Collections.singleton(82),true,true);assertTrue(window.end(true));assertFalse(window.end(false));assertTrue(window.ids().contains(82));
        assertThrows(java.io.IOException.class,()->window.add(page(4,false),true));
        List<Integer> initial=new ArrayList<>(),next=new ArrayList<>(),last=new ArrayList<>();
        for(int id=100;id>0;id--) initial.add(id);for(int id=150;id>100;id--) next.add(id);for(int id=200;id>150;id--) last.add(id);
        window.cached(initial);window.addCached(next,true,false);window.addCached(last,true,true);
        assertEquals(150,window.ids().size());assertFalse(window.ids().contains(1));assertTrue(window.ids().contains(51));assertTrue(window.ids().contains(200));assertFalse(window.end(false));
        window.start(page(2,false),page(1,false));assertFalse(window.cachedOnly());assertFalse(window.ids().contains(80));
    }

}
