package com.negzero.monogram.xmpp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.jivesoftware.smack.packet.StandardExtensionElement;

class XmppStoriesTest {
    @Test void monoclesAtomShapeRoundTripsAndExpires() {
        long now=System.currentTimeMillis();
        XmppStories.Story story=new XmppStories.Story("one","alice@example.org","https://example.org/story.jpg","image/jpeg","A <caption> & text",now);
        XmppStories.Story parsed=XmppStories.parse("alice@example.org","one",story.entry());
        assertNotNull(parsed);assertEquals(story.title,parsed.title);assertEquals(story.url,parsed.url);
        assertFalse(parsed.expired(now+86399999));assertTrue(parsed.expired(now+86400000));
        assertNull(XmppStories.parse("mallory@example.org","one",story.entry()));
        XmppStories.Story old=new XmppStories.Story("old","alice@example.org",story.url,story.mime,"old",now-XmppStories.LIFETIME-1);
        assertNull(XmppStories.parse("alice@example.org","old",old.entry()));
    }
    @Test void ambiguousEnclosuresAndUnsafeMediaDoNotBecomeStories() {
        StandardExtensionElement link=StandardExtensionElement.builder("link",XmppStories.ATOM).addAttribute("rel","enclosure")
                .addAttribute("href","https://example.org/a.jpg").addAttribute("type","image/jpeg").build();
        StandardExtensionElement entry=StandardExtensionElement.builder("entry",XmppStories.ATOM)
                .addElement("published",java.time.Instant.now().toString()).addElement(link).addElement(link).build();
        assertNull(XmppStories.parse("alice@example.org","id",entry));
        assertThrows(IllegalArgumentException.class,()->new XmppStories.Story("id","alice@example.org","file:///secret","image/jpeg","",1));
        assertThrows(IllegalArgumentException.class,()->new XmppStories.Story("id","alice@example.org","https://example.org/a","text/html","",1));
    }
    @Test void onlyObservedSmackRecoveryAssertionIsConverted() {
        AssertionError error=new AssertionError();
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.jivesoftware.smack.util.XmlStringBuilder","halfOpenElement","XmlStringBuilder.java",214),
                new StackTraceElement("org.jivesoftware.smack.util.PacketParserUtils","parseContentDepthWithoutRoundtrip","PacketParserUtils.java",326)});
        assertTrue(XmppSession.stanzaRecoveryAssertion(error));
        error.setStackTrace(new StackTraceElement[]{new StackTraceElement("example.Other","run","Other.java",1)});
        assertFalse(XmppSession.stanzaRecoveryAssertion(error));
    }
}
