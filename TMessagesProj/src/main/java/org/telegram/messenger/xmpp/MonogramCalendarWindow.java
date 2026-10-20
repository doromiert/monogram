// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.io.IOException;
import java.util.*;

/** An independent, bounded MAM window; recent-history cursors never describe a date jump. */
final class MonogramCalendarWindow {
    static final class Page {
        final String first,last;
        final Set<Integer> ids;
        final boolean complete;
        Page(String first,String last,Collection<Integer> ids,boolean complete) {
            this.first=first;this.last=last;this.ids=Collections.unmodifiableSet(new LinkedHashSet<>(ids));this.complete=complete;
        }
    }
    private final ArrayDeque<Page> pages=new ArrayDeque<>();
    private boolean olderEnd,newerEnd;
    private ArrayDeque<Set<Integer>> cachedPages;
    synchronized void start(Page after,Page before) throws IOException {
        validate(after);validate(before);
        pages.clear();cachedPages=null;
        if(before.first!=null && before.last!=null) pages.add(before);
        if(after.first!=null && after.last!=null) pages.add(after);
        olderEnd=before.complete || before.first==null;
        newerEnd=after.complete || after.last==null;
    }
    synchronized void startAround(Page before,Page anchor,Page after) throws IOException {
        validate(before);validate(anchor);validate(after);
        if(anchor.first==null || !anchor.first.equals(anchor.last) || anchor.ids.size()!=1) throw new IOException("Invalid message history anchor");
        pages.clear();cachedPages=null;
        if(before.first!=null) pages.add(before);
        pages.add(anchor);
        if(after.first!=null) pages.add(after);
        olderEnd=before.complete;newerEnd=after.complete;
    }
    /** Cached neighbors have no proven adjacent server cursor; never page them through another window. */
    // The native projection supplies IDs newest first; keep cached pages oldest first.
    synchronized void cached(Collection<Integer> ids) {
        if(ids.size()>100) throw new IllegalArgumentException("Cached navigation window exceeds its row budget");
        pages.clear();cachedPages=new ArrayDeque<>();
        ArrayList<Integer> ordered=new ArrayList<>(ids);
        for(int start=0;start<ordered.size();start+=50) cachedPages.addFirst(new LinkedHashSet<>(ordered.subList(start,Math.min(start+50,ordered.size()))));
        olderEnd=ids.isEmpty();newerEnd=ids.isEmpty();
    }
    synchronized void addCached(Collection<Integer> ids,boolean forward,boolean complete) throws IOException {
        if(cachedPages==null || ids.size()>50 || ids.isEmpty() && !complete) throw new IOException("Invalid cached continuation");
        if(!ids.isEmpty()) { if(forward) cachedPages.addLast(new LinkedHashSet<>(ids));else cachedPages.addFirst(new LinkedHashSet<>(ids)); }
        if(forward) newerEnd=complete;else olderEnd=complete;
        while(cachedPages.size()>3) {
            if(forward) { cachedPages.removeFirst();olderEnd=false; }else { cachedPages.removeLast();newerEnd=false; }
        }
    }
    synchronized boolean cachedOnly() { return cachedPages!=null; }
    synchronized String cursor(boolean forward) {
        if(pages.isEmpty()) return null;
        return forward ? pages.getLast().last : pages.getFirst().first;
    }
    synchronized boolean end(boolean forward) { return forward ? newerEnd : olderEnd; }
    synchronized Set<Integer> ids() {
        if(cachedPages!=null) { Set<Integer> ids=new LinkedHashSet<>();for(Set<Integer> page:cachedPages) ids.addAll(page);return ids; }
        Set<Integer> result=new LinkedHashSet<>();for(Page page:pages) result.addAll(page.ids);return result;
    }
    synchronized void add(Page page,boolean forward) throws IOException {
        if(cachedPages!=null) throw new IOException("Cached navigation needs a new archive seed");
        validate(page);
        String previous=cursor(forward),next=forward ? page.last : page.first;
        if(next==null) {
            if(!page.complete) throw new IOException("Archive page has no continuation cursor.");
            if(forward) newerEnd=true;else olderEnd=true;
            return;
        }
        if(next.equals(previous)) throw new IOException("Archive did not advance its continuation cursor.");
        if(forward) { pages.addLast(page);newerEnd=page.complete; }
        else { pages.addFirst(page);olderEnd=page.complete; }
        while(pages.size()>3) {
            if(forward) { pages.removeFirst();olderEnd=false; }
            else { pages.removeLast();newerEnd=false; }
        }
    }
    private static void validate(Page page) throws IOException {
        if((page.first==null)!=(page.last==null) || page.first==null && (!page.complete || !page.ids.isEmpty())
                || page.first!=null && (page.first.isEmpty() || page.last.isEmpty()))
            throw new IOException("Archive page has inconsistent continuation cursors.");
    }
}
