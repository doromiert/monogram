package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppDebugLogTest {
    @Test void latestFailureSurvivesXmlFloodButClearsWithCapture() {
        XmppDebugLog log=new XmppDebugLog();log.enabled(true);
        log.record("Request failure","Invalid OMEMO2 content");
        for(int i=0;i<200;i++) log.record("Received XML","<message id='"+i+"'/>");
        assertEquals(100,log.snapshot().size());assertEquals("Invalid OMEMO2 content",log.snapshot().get(0).text);
        log.record("Request failure","OMEMO2 bundle unavailable");
        assertEquals(1,log.snapshot().stream().filter(e->e.phase.equals("Request failure")).count());
        log.enabled(false);assertTrue(log.snapshot().isEmpty());
        log.enabled(true);assertTrue(log.snapshot().isEmpty());
    }
    @Test void captureIsOptInBoundedAndClearedOnDisableWithoutChangingExistingSnapshots() {
        XmppDebugLog log=new XmppDebugLog();log.record("Received XML","private");assertTrue(log.snapshot().isEmpty());
        log.enabled(true);
        for(int i=0;i<150;i++) log.record("Sent XML","<message id='"+i+"'/>");
        assertEquals(100,log.snapshot().size());assertTrue(log.snapshot().get(0).text.contains("50"));
        String huge=new String(new char[XmppDebugLog.MAX_ENTRY*2]).replace('\0','x');
        for(int i=0;i<10;i++) log.record("Received XML",huge);
        assertTrue(log.snapshot().stream().mapToInt(entry->entry.text.length()).sum()<=XmppDebugLog.MAX_TEXT);
        assertTrue(log.snapshot().get(0).text.endsWith("[truncated]"));
        java.util.List<XmppDebugLog.Entry> snapshot=log.snapshot();log.enabled(false);
        assertTrue(log.snapshot().isEmpty());assertFalse(snapshot.isEmpty());
        log.record("Received XML","disabled");assertTrue(log.snapshot().isEmpty());
        log.enabled(true);assertTrue(log.snapshot().isEmpty());log.record("Received XML","new");log.clear();assertTrue(log.snapshot().isEmpty());
    }
}
