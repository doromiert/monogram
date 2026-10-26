package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramArticlesTest.Utilities.class)
public class MonogramArticlesTest {
    @Implements(org.telegram.messenger.AndroidUtilities.class)
    public static class Utilities {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static int dp(float value) { return (int)Math.ceil(value); }
    }
    @Before public void setup() { ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();ApplicationLoader.applicationLoaderInstance=new ApplicationLoader(); }
    TL_iv.textPlain text(String s) { TL_iv.textPlain t=new TL_iv.textPlain();t.text=s;return t; }
    TL_iv.TL_inputRichMessage article(TL_iv.PageBlock... blocks) { TL_iv.TL_inputRichMessage r=new TL_iv.TL_inputRichMessage();r.blocks.addAll(Arrays.asList(blocks));return r; }
    @Test public void tableSurvivesMarkdownNativeProjectionAndDatabaseReopen() throws Exception {
        TL_iv.pageBlockTable table=new TL_iv.pageBlockTable();table.title=text("Shopping");
        for(String[] cells:new String[][]{{"Item","Count"},{"Apples | pears","😀 3"}}) {
            TL_iv.pageTableRow row=new TL_iv.pageTableRow();for(String cell:cells) { TL_iv.pageTableCell c=new TL_iv.pageTableCell();c.text=text(cell);c.header=table.rows.isEmpty();c.flags|=128;row.cells.add(c); }table.rows.add(row);
        }
        String wire=MonogramArticles.markdown(article(table));assertTrue(wire.contains("| --- | --- |"));
        MessageMarkup markup=MessageMarkup.body(wire).asMarkdown();
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("articles.db");MonogramStore store=new MonogramStore(context,"articles.db");
        try {
            long self=store.peer("alice@example.org");store.peer("bob@example.org");
            int id=store.save(MessageFixture.replyMessage("article","article",null,wire,true,false,100000,"phone",true,null).withMarkup(markup));
            store.close();store=new MonogramStore(context,"articles.db");TLRPC.Message restored=store.getMessage(id,self);
            assertEquals(wire,restored.message);assertNotNull(restored.rich_message);
            TL_iv.pageBlockTable projected=null;for(TL_iv.PageBlock b:restored.rich_message.blocks) if(b instanceof TL_iv.pageBlockTable) projected=(TL_iv.pageBlockTable)b;
            assertNotNull(projected);assertEquals(2,projected.rows.size());assertEquals(2,projected.rows.get(1).cells.size());
            assertEquals("Apples | pears",org.telegram.ui.Components.MarkdownParser.richTextToString(projected.rows.get(1).cells.get(0).text));
        } finally { store.close();context.deleteDatabase("articles.db"); }
    }
    @Test public void unhintedIncomingTableRendersAfterHistoryReopenAndRemainsEditableWhenOutgoing() throws Exception {
        String wire="| Item | Count |\n| --- | --- |\n| Apples | 3 |";
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("plain-tables.db");MonogramStore store=new MonogramStore(context,"plain-tables.db");
        try {
            long self=store.peer("alice@example.org");store.peer("bob@example.org");
            int id=store.save(MessageFixture.replyMessage("plain-table","plain-table",null,wire,true,false,100000,"phone",true,null).withMarkup(MessageMarkup.body(wire)));
            store.close();store=new MonogramStore(context,"plain-tables.db");TLRPC.Message restored=store.getMessage(id,self);
            assertEquals(wire,restored.message);assertNotNull(restored.rich_message);
            assertTrue(restored.rich_message.blocks.stream().anyMatch(block -> block instanceof TL_iv.pageBlockTable));
            restored.out=true;
            assertTrue(org.telegram.messenger.MessageObject.canEditMessage(0,restored,null,false));
            assertTrue(org.telegram.messenger.MessageObject.canEditMessageAnytime(0,restored,null));
            restored.out=false;assertFalse(org.telegram.messenger.MessageObject.canEditMessage(0,restored,null,false));
        } finally {store.close();context.deleteDatabase("plain-tables.db");}
    }
    @Test public void bareUrlsKeepFormattingCharactersAndExactClickTargets() {
        for(String url:new String[]{"https://x.com/_lyraaa_/status/123", "https://example.org/a__b__c?q=~~value~~", "www.example.org/_name_/a*b", "https://example.org/a_(b)/_c_"}) {
            TLRPC.Message message=new TLRPC.TL_message();message.message=url;
            MonogramFormatting.apply(message,MessageMarkup.body(url));
            assertEquals(url,message.message);assertNotNull(message.rich_message);
            TL_iv.RichText text=message.rich_message.blocks.get(0).text;
            assertTrue(text instanceof TL_iv.textUrl);
            assertEquals(url,org.telegram.ui.Components.MarkdownParser.richTextToString(text));
            assertEquals(url.startsWith("www.") ? "https://"+url : url,((TL_iv.textUrl)text).url);
        }
    }
    @Test public void urlParsingPreservesOutsideFormattingCodeAndMarkdownLinks() {
        String url="https://x.com/_lyraaa_/status/123";
        ArrayList<TL_iv.PageBlock> blocks=new ArrayList<>();
        org.telegram.ui.Components.MarkdownParser.parseMessage("*outside* ("+url+"). `"+url+"` [label]("+url+")",blocks);
        TL_iv.textConcat text=(TL_iv.textConcat)blocks.get(0).text;
        assertTrue(text.texts.stream().anyMatch(t->t instanceof TL_iv.textItalic));
        assertTrue(text.texts.stream().anyMatch(t->t instanceof TL_iv.textFixed));
        List<TL_iv.textUrl> links=new ArrayList<>();for(TL_iv.RichText item:text.texts)if(item instanceof TL_iv.textUrl)links.add((TL_iv.textUrl)item);
        assertEquals(2,links.size());assertEquals(url,links.get(0).url);assertEquals(url,links.get(1).url);
        assertEquals("outside ("+url+"). "+url+" label",org.telegram.ui.Components.MarkdownParser.richTextToString(text));
        blocks.clear();org.telegram.ui.Components.MarkdownParser.parseMessage("["+url+"](https://example.org/destination)",blocks);
        assertTrue(blocks.get(0).text instanceof TL_iv.textUrl);
        assertEquals("https://example.org/destination",((TL_iv.textUrl)blocks.get(0).text).url);
        assertEquals(url,org.telegram.ui.Components.MarkdownParser.richTextToString(blocks.get(0).text));
    }
    @Test public void unhintedPlainTextCodeAndSpoilersAreNotReinterpretedAsTables() {
        for(String wire:new String[]{"a | b\nnot a table", "ordinary text"}) {
            TLRPC.Message message=new TLRPC.TL_message();message.message=wire;
            MonogramFormatting.apply(message,MessageMarkup.body(wire));assertNull(message.rich_message);
        }
        String table="| a | b |\n| - | - |\n| c | d |";
        TLRPC.Message message=new TLRPC.TL_message();message.message=table;
        MonogramFormatting.apply(message,MessageMarkup.body(table).withSpoiler(true));assertNull(message.rich_message);
        TLRPC.Message caption=new TLRPC.TL_message();caption.message=table;caption.media=new TLRPC.TL_messageMediaDocument();
        MonogramFormatting.apply(caption,MessageMarkup.body(table));assertNull(caption.rich_message);
    }
    @Test public void headingsListsQuotesCodeAndInlineArticleFormattingRenderWithoutHint() {
        for(String wire:new String[]{"# Article heading", "- First\n- Second", "> Quoted text", "```java\nint value = 1;\n```", "A **bold** word", "A ~~deleted~~ word", "Math $x+1$", "<u>Underlined</u> and <mark>marked</mark>"}) {
            TLRPC.Message message=new TLRPC.TL_message();message.message=wire;
            MonogramFormatting.apply(message,MessageMarkup.body(wire).preservingBody());
            assertNotNull(wire,message.rich_message);assertEquals(wire,message.message);
        }
    }
    @Test public void markdownFallbackDoesNotDiscardExplicitXmppSpoilersOrStyles() {
        String wire="# Heading secret";
        MessageMarkup.Range hidden=new MessageMarkup.Range(10,16,java.util.Collections.singleton(MessageMarkup.Style.SPOILER),null);
        TLRPC.Message message=new TLRPC.TL_message();message.message=wire;
        MonogramFormatting.apply(message,new MessageMarkup(wire,java.util.Collections.singleton(hidden)).asMarkdown());
        assertNull(message.rich_message);assertTrue(message.entities.stream().anyMatch(entity -> entity instanceof TLRPC.TL_messageEntitySpoiler));
        String styled="*bold*";MessageMarkup.Range strong=new MessageMarkup.Range(1,5,java.util.Collections.singleton(MessageMarkup.Style.STRONG),null);
        TLRPC.Message ordinary=new TLRPC.TL_message();ordinary.message=styled;
        MonogramFormatting.apply(ordinary,new MessageMarkup(styled,java.util.Collections.singleton(strong)));
        assertNull(ordinary.rich_message);assertTrue(ordinary.entities.stream().anyMatch(entity -> entity instanceof TLRPC.TL_messageEntityBold));
        TLRPC.Message inferred=new TLRPC.TL_message();inferred.message=styled;
        MonogramFormatting.apply(inferred,MessageMarkup.body(styled));
        assertNull(inferred.rich_message);assertTrue(inferred.entities.stream().anyMatch(entity -> entity instanceof TLRPC.TL_messageEntityBold));
    }
    @Test public void literalMarkersCodeLinksAndFormattingSurviveConversion() {
        TL_iv.pageBlockParagraph paragraph=new TL_iv.pageBlockParagraph();TL_iv.textConcat concat=new TL_iv.textConcat();paragraph.text=concat;
        concat.texts.add(text(">greentext * literal | "));
        TL_iv.textBold bold=new TL_iv.textBold();bold.text=text("bold");concat.texts.add(bold);
        TL_iv.textUrl link=new TL_iv.textUrl();link.text=text("site");link.url="https://example.org/a_(b)";concat.texts.add(link);
        TL_iv.pageBlockPreformatted code=new TL_iv.pageBlockPreformatted();code.text=text("```\nx < 2 & y");code.language="java";
        String wire=MonogramArticles.markdown(article(paragraph,code));assertTrue(wire.contains("**bold**"));assertTrue(wire.contains("[site](<https://example.org/a_(b)>)"));assertTrue(wire.contains("````java"));
        TLRPC.Message projected=new TLRPC.TL_message();projected.message=wire;MonogramFormatting.apply(projected,MessageMarkup.body(wire).asMarkdown());
        assertNotNull(projected.rich_message);assertEquals(2,projected.rich_message.blocks.size());
        assertTrue(org.telegram.ui.Components.MarkdownParser.richTextToString(projected.rich_message.blocks.get(0).text).startsWith(">greentext * literal | "));
        assertEquals("```\nx < 2 & y\n",org.telegram.ui.Components.MarkdownParser.richTextToString(projected.rich_message.blocks.get(1).text));
    }
    @Test public void unsupportedAttachmentsAndExcessiveArticlesFailInsteadOfLosingContent() {
        assertThrows(IllegalArgumentException.class,() -> MonogramArticles.markdown(article(new TL_iv.pageBlockPhoto())));
        TL_iv.pageBlockParagraph p=new TL_iv.pageBlockParagraph();p.text=text(String.join("",Collections.nCopies(32769,"x")));
        assertThrows(IllegalArgumentException.class,() -> MonogramArticles.markdown(article(p)));
        TLRPC.Message message=new TLRPC.TL_message();message.message="still readable";
        MonogramArticles.apply(message,String.join("",Collections.nCopies(32769,"x")));assertNull(message.rich_message);assertEquals("still readable",message.message);
    }
}
