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
    synchronized void start(Page after,Page before) throws IOException {
        validate(after);validate(before);
        pages.clear();
        if(before.first!=null && before.last!=null) pages.add(before);
        if(after.first!=null && after.last!=null) pages.add(after);
        olderEnd=before.complete || before.first==null;
        newerEnd=after.complete || after.last==null;
    }
    synchronized String cursor(boolean forward) {
        if(pages.isEmpty()) return null;
        return forward ? pages.getLast().last : pages.getFirst().first;
    }
    synchronized boolean end(boolean forward) { return forward ? newerEnd : olderEnd; }
    synchronized Set<Integer> ids() {
        Set<Integer> result=new LinkedHashSet<>();for(Page page:pages) result.addAll(page.ids);return result;
    }
    synchronized void add(Page page,boolean forward) throws IOException {
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
