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
}
