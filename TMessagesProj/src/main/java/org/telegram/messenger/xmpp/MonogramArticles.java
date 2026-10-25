// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.util.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.Components.MarkdownParser;

/** Article transport uses readable Markdown, never Telegram file references or serialized TL objects. */
public final class MonogramArticles {
    public static final int MAX_LENGTH=32768;
    public static final class Failure extends IllegalArgumentException { private Failure(String message) { super(message); } }
    private static final java.util.regex.Pattern STRUCTURE=java.util.regex.Pattern.compile("(?m)^ {0,3}(?:#{1,6}\\s|>\\s?|[-+*]\\s|[0-9]+[.)]\\s|`{3}|~{3}|\\||[-=_]{3,}\\s*$|\\$\\$)");
    private int nodes;
    private void check(int depth) {
        if(depth>24 || ++nodes>2048) throw new Failure("Article is too complex");
    }
    public static String markdown(TL_iv.TL_inputRichMessage article) {
        String value=new MonogramArticles().blocks(article.blocks,0).trim();
        if(value.isEmpty()) throw new Failure("Article has no text");
        if(value.length()>MAX_LENGTH || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_LENGTH)
            throw new Failure("Article is too long (maximum 32 KiB)");
        return value;
    }
    private String blocks(List<TL_iv.PageBlock> blocks,int depth) {
        check(depth);StringBuilder out=new StringBuilder();
        if(blocks!=null) for(TL_iv.PageBlock block:blocks) {
            String value=block(block,depth+1);
            if(!value.isEmpty()) { if(out.length()>0) out.append("\n\n");out.append(value); }
            if(out.length()>MAX_LENGTH) throw new Failure("Article is too long");
        }
        return out.toString();
    }
    private String block(TL_iv.PageBlock b,int depth) {
        check(depth);if(b==null) return "";
        if(b instanceof TL_iv.pageBlockTable) {
            TL_iv.pageBlockTable table=(TL_iv.pageBlockTable)b;
            StringBuilder out=new StringBuilder();String title=text(table.title,depth+1);
            if(!title.isEmpty()) out.append(title).append("\n\n");
            int columns=0;for(TL_iv.pageTableRow row:table.rows) columns=Math.max(columns,row.cells.size());
            if(columns==0) return title;
            // Markdown requires a header. Use an empty header for tables whose first row is ordinary data.
            boolean header=!table.rows.isEmpty() && table.rows.get(0).cells.stream().anyMatch(c -> c.header);
            if(!header) { for(int i=0;i<columns;i++) out.append("| ");out.append("|\n");separator(out,columns); }
            int rowIndex=0;
            for(TL_iv.pageTableRow row:table.rows) {
                for(int c=0;c<columns;c++) out.append("| ").append(c<row.cells.size() ? text(row.cells.get(c).text,depth+1).replace("\n","<br>") : "").append(' ');
                out.append("|\n");if(header && rowIndex++==0) separator(out,columns);
            }
            return out.toString().trim();
        }
        if(b instanceof TL_iv.pageBlockPreformatted) {
            String value=plain(b.text,depth+1),fence="```";
            while(value.contains(fence)) fence+='`';
            String language=((TL_iv.pageBlockPreformatted)b).language;
            return fence+(language==null ? "" : language.replaceAll("[^a-zA-Z0-9_+.-]",""))+"\n"+value+"\n"+fence;
        }
        if(b instanceof TL_iv.pageBlockBlockquoteBlocks) {
            TL_iv.pageBlockBlockquoteBlocks quote=(TL_iv.pageBlockBlockquoteBlocks)b;
            return quote(blocks(quote.blocks,depth+1))+caption(quote.caption,depth+1);
        }
        if(b instanceof TL_iv.pageBlockBlockquote) return quote(text(b.text,depth+1))+caption(((TL_iv.pageBlockBlockquote)b).caption,depth+1);
        if(b instanceof TL_iv.pageBlockPullquote) return quote(text(b.text,depth+1))+caption(((TL_iv.pageBlockPullquote)b).caption,depth+1);
        if(b instanceof TL_iv.pageBlockDetails) {
            TL_iv.pageBlockDetails details=(TL_iv.pageBlockDetails)b;
            return "**"+text(details.title,depth+1)+"**\n\n"+blocks(details.blocks,depth+1);
        }
        if(b instanceof TL_iv.pageBlockList) {
            StringBuilder out=new StringBuilder();
            for(TL_iv.PageListItem item:((TL_iv.pageBlockList)b).items) {
                check(depth+1);String value;
                if(item instanceof TL_iv.TL_pageListItemText) value=text(((TL_iv.TL_pageListItemText)item).text,depth+2);
                else if(item instanceof TL_iv.TL_pageListItemBlocks) value=blocks(((TL_iv.TL_pageListItemBlocks)item).blocks,depth+2);
                else throw unsupported();
                out.append(item.checkbox ? item.checked ? "- [x] " : "- [ ] " : "- ").append(value.replace("\n","\n  ")).append('\n');
            }
            return out.toString().trim();
        }
        if(b instanceof TL_iv.pageBlockOrderedList) {
            StringBuilder out=new StringBuilder();TL_iv.pageBlockOrderedList list=(TL_iv.pageBlockOrderedList)b;int i=Math.max(1,list.start);
            for(TL_iv.PageListOrderedItem item:list.items) {
                check(depth+1);String value;
                if(item instanceof TL_iv.TL_pageListOrderedItemText) value=text(((TL_iv.TL_pageListOrderedItemText)item).text,depth+2);
                else if(item instanceof TL_iv.TL_pageListOrderedItemBlocks) value=blocks(((TL_iv.TL_pageListOrderedItemBlocks)item).blocks,depth+2);
                else throw unsupported();
                out.append(i++).append(". ").append(value.replace("\n","\n   ")).append('\n');
            }
            return out.toString().trim();
        }
        if(b instanceof TL_iv.pageBlockDivider) return "---";
        if(b instanceof TL_iv.pageBlockMath) return "$$\n"+((TL_iv.pageBlockMath)b).source+"\n$$";
        if(b instanceof TL_iv.pageBlockAuthorDate) return text(((TL_iv.pageBlockAuthorDate)b).author,depth+1);
        if(b instanceof TL_iv.pageBlockAnchor) return "";
        int heading=b instanceof TL_iv.pageBlockTitle || b instanceof TL_iv.pageBlockHeading1 ? 1
                : b instanceof TL_iv.pageBlockHeader || b instanceof TL_iv.pageBlockHeading2 ? 2
                : b instanceof TL_iv.pageBlockSubtitle || b instanceof TL_iv.pageBlockSubheader || b instanceof TL_iv.pageBlockHeading3 ? 3
                : b instanceof TL_iv.pageBlockHeading4 ? 4 : b instanceof TL_iv.pageBlockHeading5 ? 5 : b instanceof TL_iv.pageBlockHeading6 ? 6 : 0;
        if(heading>0) return String.join("",Collections.nCopies(heading,"#"))+" "+text(b.text,depth+1);
        if(b instanceof TL_iv.pageBlockParagraph || b instanceof TL_iv.pageBlockFooter || b instanceof TL_iv.pageBlockKicker || b instanceof TL_iv.pageBlockThinking) return text(b.text,depth+1);
        // Never silently discard an attachment or send a Telegram-only document ID to another client.
        throw unsupported();
    }
    private static Failure unsupported() { return new Failure("Send article attachments or interactive widgets separately; XMPP article transport currently supports text formatting and tables."); }
    private static void separator(StringBuilder out,int columns) { for(int i=0;i<columns;i++) out.append("| --- ");out.append("|\n"); }
    private static String quote(String value) { return "> "+value.replace("\n","\n> "); }
    private String caption(TL_iv.RichText caption,int depth) { String value=text(caption,depth);return value.isEmpty() ? "" : "\n\n"+value; }
    private String plain(TL_iv.RichText t,int depth) {
        check(depth);if(t==null) return "";
        if(t instanceof TL_iv.textPlain) return Objects.toString(((TL_iv.textPlain)t).text,"");
        if(t instanceof TL_iv.textConcat) { StringBuilder out=new StringBuilder();for(TL_iv.RichText child:t.texts) out.append(plain(child,depth+1));return out.toString(); }
        if(t instanceof TL_iv.textMath) return Objects.toString(((TL_iv.textMath)t).source,"");
        return plain(t.text,depth+1);
    }
    private static String escape(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replaceAll("([\\\\`*_{}\\[\\]()#+.!|~$-])","\\\\$1"); }
    private String text(TL_iv.RichText t,int depth) {
        check(depth);if(t==null || t instanceof TL_iv.textEmpty) return "";
        if(t instanceof TL_iv.textPlain) return escape(Objects.toString(((TL_iv.textPlain)t).text,""));
        if(t instanceof TL_iv.textConcat) { StringBuilder out=new StringBuilder();for(TL_iv.RichText child:t.texts) out.append(text(child,depth+1));return out.toString(); }
        if(t instanceof TL_iv.textMath) return "$"+((TL_iv.textMath)t).source+"$";
        if(t instanceof TL_iv.textImage || t instanceof TL_iv.textCustomEmoji || t instanceof TL_iv.textButton) throw unsupported();
        String value=text(t.text,depth+1);
        if(t instanceof TL_iv.textBold) return "**"+value+"**";
        if(t instanceof TL_iv.textItalic) return "*"+value+"*";
        if(t instanceof TL_iv.textStrike) return "~~"+value+"~~";
        if(t instanceof TL_iv.textUnderline) return "<u>"+value+"</u>";
        if(t instanceof TL_iv.textSubscript) return "<sub>"+value+"</sub>";
        if(t instanceof TL_iv.textSuperscript) return "<sup>"+value+"</sup>";
        if(t instanceof TL_iv.textMarked) return "<mark>"+value+"</mark>";
        if(t instanceof TL_iv.textSpoiler) return "<tg-spoiler>"+value+"</tg-spoiler>";
        if(t instanceof TL_iv.textFixed) { String raw=plain(t.text,depth+1),fence="`";while(raw.contains(fence)) fence+='`';return fence+" "+raw.replace('\n',' ')+" "+fence; }
        if(t instanceof TL_iv.textUrl && t.url!=null) return "["+value+"](<"+t.url.replace("<","%3C").replace(">","%3E").replace("\n","").replace("\r","")+">)";
        return value;
    }
    /** Recognize structured Markdown even when a sender has no text/markdown content hint. */
    public static boolean applyMarkdown(TLRPC.Message message,String source) {
        if(MonogramAttachments.has(message) || message.media!=null && !(message.media instanceof TLRPC.TL_messageMediaEmpty) && !(message.media instanceof TLRPC.TL_messageMediaWebPage)) return false;
        if(source==null || source.length()>MAX_LENGTH || !STRUCTURE.matcher(source).find() && source.indexOf('*')<0 && source.indexOf('_')<0 && source.indexOf('`')<0 && source.indexOf('[')<0 && source.indexOf('<')<0 && source.indexOf('~')<0 && source.indexOf('$')<0) return false;
        try {
            TL_iv.RichMessage rich=new TL_iv.RichMessage();MarkdownParser.parseMessage(source,rich.blocks);
            if(rich.blocks.size()>2048 || rich.blocks.stream().noneMatch(block -> !(block instanceof TL_iv.pageBlockParagraph) || richInline(block.text))) return false;
            message.rich_message=rich;message.flags2|=8192;return true;
        } catch(RuntimeException invalid) { return false; }
    }
    private static boolean richInline(TL_iv.RichText text) {
        if(text==null || text instanceof TL_iv.textPlain || text instanceof TL_iv.textEmpty) return false;
        if(text instanceof TL_iv.textConcat) return text.texts.stream().anyMatch(MonogramArticles::richInline);
        return true;
    }
    public static void apply(TLRPC.Message message,String source) {
        if(source==null || source.length()>MAX_LENGTH) return;
        try {
            TL_iv.RichMessage rich=new TL_iv.RichMessage();MarkdownParser.parseMessage(source,rich.blocks);
            if(rich.blocks.isEmpty() || rich.blocks.size()>2048) return;
            message.rich_message=rich;message.flags2|=8192;
        } catch(RuntimeException invalid) { /* Keep the original Markdown readable if parsing fails. */ }
    }
}
