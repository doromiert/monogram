// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/** Opt-in, bounded, account-owned memory only. Never a disk or network logger. */
public final class XmppDebugLog {
    public static final int MAX_ENTRIES=100,MAX_ENTRY=32768,MAX_TEXT=131072;
    public static final class Entry {
        public final long number,time;
        public final String phase,text;
        Entry(long number,String phase,String text) { this.number=number;this.time=System.currentTimeMillis();this.phase=phase;this.text=text; }
    }
    private final ArrayDeque<Entry> entries=new ArrayDeque<>();
    private volatile boolean enabled;
    private int characters;
    private long sequence;
    public boolean enabled() { return enabled; }
    public synchronized void enabled(boolean value) { enabled=value;if(!value) clear(); }
    public synchronized void clear() { entries.clear();characters=0; }
    public synchronized void record(String phase,String text) {
        if(!enabled || text==null) return;
        if(text.length()>MAX_ENTRY) text=text.substring(0,MAX_ENTRY)+"\n[truncated]";
        entries.addLast(new Entry(++sequence,phase,text));characters+=text.length();
        while(entries.size()>MAX_ENTRIES || characters>MAX_TEXT) characters-=entries.removeFirst().text.length();
    }
    public synchronized List<Entry> snapshot() { return new ArrayList<>(entries); }
}
