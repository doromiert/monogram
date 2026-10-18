// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.MessageMarkup;
import java.util.*;
import org.telegram.tgnet.TLRPC;

/** Native entities use UTF-16; XMPP markup and fallback intervals use Unicode code points. */
public final class MonogramFormatting {
    private MonogramFormatting() {}
    public static MessageMarkup fromEntities(String body,Collection<TLRPC.MessageEntity> entities) {
        if(body==null) throw new IllegalArgumentException("Missing formatting text");
        List<MessageMarkup.Range> inline=new ArrayList<>(),blocks=new ArrayList<>();
        TreeSet<Integer> boundaries=new TreeSet<>();
        if(entities!=null) for(TLRPC.MessageEntity entity:entities) {
            if(entity==null || entity.offset<0 || entity.length<=0 || (long)entity.offset+entity.length>body.length()) continue;
            int end=entity.offset+entity.length;
            if(!boundary(body,entity.offset) || !boundary(body,end)) continue;
            MessageMarkup.Style style=style(entity);if(style==null) continue;
            int startCp=body.codePointCount(0,entity.offset),endCp=body.codePointCount(0,end);
            String language=entity instanceof TLRPC.TL_messageEntityPre ? ((TLRPC.TL_messageEntityPre)entity).language : null;
            if(language!=null && (language.length()>128 || language.codePoints().anyMatch(Character::isISOControl))) language=null;
            MessageMarkup.Range range=new MessageMarkup.Range(startCp,endCp,Collections.singleton(style),language);
            boundaries.add(startCp);boundaries.add(endCp);
            if(style==MessageMarkup.Style.PRE || style==MessageMarkup.Style.QUOTE) {
                boolean crossing=false;
                for(MessageMarkup.Range block:blocks) if(startCp<block.end && block.start<endCp
                        && (startCp<block.start && endCp<block.end || block.start<startCp && block.end<endCp)) crossing=true;
                if(!crossing) blocks.add(range);
            } else inline.add(range);
            if(inline.size()+blocks.size()>=MessageMarkup.MAX_RANGES) break;
        }
        List<MessageMarkup.Range> ranges=new ArrayList<>(blocks);Integer previous=null;
        for(int point:boundaries) {
            if(previous!=null && point>previous) {
                EnumSet<MessageMarkup.Style> active=EnumSet.noneOf(MessageMarkup.Style.class);
                for(MessageMarkup.Range range:inline) if(range.start<=previous && range.end>=point) active.addAll(range.styles);
                if(!active.isEmpty() && ranges.size()<MessageMarkup.MAX_RANGES) ranges.add(new MessageMarkup.Range(previous,point,active,null));
            }
            previous=point;
        }
        return new MessageMarkup(body,ranges);
    }
    private static boolean boundary(String text,int offset) {
        return offset==0 || offset==text.length() || !Character.isHighSurrogate(text.charAt(offset-1)) || !Character.isLowSurrogate(text.charAt(offset));
    }
    private static MessageMarkup.Style style(TLRPC.MessageEntity entity) {
        if(entity instanceof TLRPC.TL_messageEntityBold) return MessageMarkup.Style.STRONG;
        if(entity instanceof TLRPC.TL_messageEntityItalic) return MessageMarkup.Style.EMPHASIS;
        if(entity instanceof TLRPC.TL_messageEntityStrike) return MessageMarkup.Style.DELETED;
        if(entity instanceof TLRPC.TL_messageEntityCode) return MessageMarkup.Style.CODE;
        if(entity instanceof TLRPC.TL_messageEntityPre) return MessageMarkup.Style.PRE;
        if(entity instanceof TLRPC.TL_messageEntityBlockquote) return MessageMarkup.Style.QUOTE;
        return null;
    }
    public static ArrayList<TLRPC.MessageEntity> entities(MessageMarkup markup) {
        ArrayList<TLRPC.MessageEntity> entities=new ArrayList<>();
        if(markup==null) return entities;
        for(MessageMarkup.Range range:markup.ranges) for(MessageMarkup.Style style:range.styles) {
            TLRPC.MessageEntity entity;
            switch(style) {
                case STRONG:entity=new TLRPC.TL_messageEntityBold();break;
                case EMPHASIS:entity=new TLRPC.TL_messageEntityItalic();break;
                case DELETED:entity=new TLRPC.TL_messageEntityStrike();break;
                case CODE:entity=new TLRPC.TL_messageEntityCode();break;
                case PRE:entity=new TLRPC.TL_messageEntityPre();((TLRPC.TL_messageEntityPre)entity).language=range.language==null ? "" : range.language;break;
                case QUOTE:entity=new TLRPC.TL_messageEntityBlockquote();break;
                default:continue;
            }
            entity.offset=range.utf16Start(markup.body);entity.length=range.utf16End(markup.body)-entity.offset;entities.add(entity);
        }
        return entities;
    }
    public static void apply(TLRPC.Message message,MessageMarkup markup) {
        if(markup==null) return;
        MessageMarkup display=markup.display();
        if(!Objects.equals(message.message,display.body)) message.entities.clear();
        else message.entities.removeIf(entity -> style(entity)!=null);
        message.message=display.body;message.entities.addAll(entities(display));
        if(message.entities.isEmpty()) message.flags&=~TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        else message.flags|=TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
    }
    static String displayText(String body,String encoded) {
        if(body==null) return "";
        try {
            MessageMarkup markup=MessageMarkup.decode(body,encoded);
            return markup==null ? body : markup.display().body;
        } catch(IllegalArgumentException malformed) { return body; }
    }
    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS message_formatting (message INTEGER PRIMARY KEY,body TEXT NOT NULL,markup TEXT,caption_body TEXT,caption_markup TEXT)");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS clear_message_formatting AFTER UPDATE OF body,readable,deleted ON messages WHEN NEW.deleted!=0 OR NEW.readable=0 OR OLD.body!=NEW.body BEGIN DELETE FROM message_formatting WHERE message=NEW.id; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS delete_message_formatting AFTER DELETE ON messages BEGIN DELETE FROM message_formatting WHERE message=OLD.id; END");
    }
    static boolean remember(SQLiteDatabase db,int id,String body,boolean readable,MessageMarkup markup,MessageMarkup caption) {
        if(!readable || body==null || markup==null && caption==null || markup!=null && !body.equals(markup.body)) return false;
        try(Cursor current=db.rawQuery("SELECT body,readable,deleted FROM messages WHERE id=?",new String[]{Integer.toString(id)})) {
            if(!current.moveToFirst() || current.getInt(1)==0 || current.getInt(2)!=0 || !body.equals(current.getString(0))) return false;
        }
        ContentValues value=new ContentValues();value.put("message",id);value.put("body",body);
        if(markup!=null) value.put("markup",markup.encode());
        if(caption!=null) { value.put("caption_body",caption.body);value.put("caption_markup",caption.encode()); }
        return db.insertWithOnConflict("message_formatting",null,value,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
    }
    static void decorate(SQLiteDatabase db,TLRPC.Message message,int payload,String rawBody) {
        try(Cursor row=db.rawQuery("SELECT body,markup,caption_body,caption_markup FROM message_formatting WHERE message=?",new String[]{Integer.toString(payload)})) {
            if(!row.moveToFirst() || !Objects.equals(rawBody,row.getString(0))) return;
            MessageMarkup visible;
            if(MonogramAttachments.has(message)) {
                if(row.isNull(3) || !Objects.equals(message.message,row.getString(2))) return;
                visible=MessageMarkup.decode(row.getString(2),row.getString(3));
                if(visible==null) return;
            } else {
                if(row.isNull(1)) return;
                visible=MessageMarkup.decode(rawBody,row.getString(1));
                if(visible==null) return;
                if(!Objects.equals(rawBody,message.message)) {
                    if(message.reply_to==null || message.reply_to.reply_to_msg_id==0) return;
                    try(Cursor reply=db.rawQuery("SELECT fallback FROM replies WHERE message=?",new String[]{Integer.toString(payload)})) {
                        if(!reply.moveToFirst()) return;
                        visible=visible.without(fallback(reply.getString(0),rawBody.codePointCount(0,rawBody.length())));
                    }
                }
                if(!Objects.equals(visible.body,message.message)) return;
            }
            apply(message,visible);
        } catch(IllegalArgumentException malformed) { /* Corrupt local formatting must not hide readable message text. */ }
    }
    static com.negzero.monogram.xmpp.InlineEmoji projectInline(SQLiteDatabase db,int payload,com.negzero.monogram.xmpp.InlineEmoji inline,String visibleBody) {
        MessageMarkup markup=MessageMarkup.body(inline.body);
        try(Cursor row=db.rawQuery("SELECT body,markup FROM message_formatting WHERE message=?",new String[]{Integer.toString(payload)})) {
            if(row.moveToFirst() && inline.body.equals(row.getString(0)) && !row.isNull(1)) {
                MessageMarkup decoded=MessageMarkup.decode(inline.body,row.getString(1));if(decoded!=null) markup=decoded;
            }
        }
        com.negzero.monogram.xmpp.InlineEmoji complete=markup.displayInline(inline);
        if(complete!=null && Objects.equals(complete.body,visibleBody)) return complete;
        try(Cursor reply=db.rawQuery("SELECT fallback FROM replies WHERE message=?",new String[]{Integer.toString(payload)})) {
            if(reply.moveToFirst()) {
                List<int[]> spans=fallback(reply.getString(0),inline.body.codePointCount(0,inline.body.length()));
                inline=inline.without(spans);markup=markup.without(spans);
            }
        }
        return markup.displayInline(inline);
    }
    private static List<int[]> fallback(String encoded,int length) {
        List<int[]> ranges=new ArrayList<>();
        if(encoded==null || encoded.isEmpty()) return ranges;
        for(String part:encoded.split(",")) {
            String[] ends=part.split(":");if(ends.length!=2) throw new IllegalArgumentException("Invalid reply fallback");
            int start=Integer.parseInt(ends[0]),end="*".equals(ends[1]) ? length : Integer.parseInt(ends[1]);
            if(start<0 || end<start || end>length) throw new IllegalArgumentException("Invalid reply fallback");ranges.add(new int[]{start,end});
        }
        return ranges;
    }
}
