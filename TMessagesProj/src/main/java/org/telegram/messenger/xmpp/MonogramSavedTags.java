// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.telegram.tgnet.TLRPC;

/** Device-local bookmark labels; never published as XMPP reactions. Scoped to the account store. */
final class MonogramSavedTags {
    private final MonogramStore store;
    MonogramSavedTags(MonogramStore store) { this.store=store; }
    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE saved_message_tags(message INTEGER NOT NULL,tag TEXT NOT NULL,PRIMARY KEY(message,tag))");
        db.execSQL("CREATE INDEX saved_message_tags_tag ON saved_message_tags(tag,message)");
        db.execSQL("CREATE TABLE saved_tag_names(tag TEXT PRIMARY KEY,title TEXT NOT NULL,color INTEGER NOT NULL DEFAULT 0)");
    }
    static String key(TLRPC.Reaction reaction) {
        if(reaction instanceof TLRPC.TL_reactionEmoji) {
            String emoji=((TLRPC.TL_reactionEmoji)reaction).emoticon;
            if(MonogramReactions.isEmoji(emoji)) return "e:"+emoji;
        } else if(reaction instanceof TLRPC.TL_reactionCustomEmoji && ((TLRPC.TL_reactionCustomEmoji)reaction).document_id>0) {
            return "d:"+((TLRPC.TL_reactionCustomEmoji)reaction).document_id;
        }
        throw new IllegalArgumentException("Choose an emoji tag");
    }
    private static TLRPC.Reaction reaction(String key) {
        if(key.startsWith("e:")) {
            TLRPC.TL_reactionEmoji value=new TLRPC.TL_reactionEmoji();value.emoticon=key.substring(2);return value;
        }
        TLRPC.TL_reactionCustomEmoji value=new TLRPC.TL_reactionCustomEmoji();value.document_id=Long.parseLong(key.substring(2));return value;
    }
    void set(int message,long self,List<TLRPC.Reaction> tags) {
        if(store.hiddenMessage(message) || store.getMessage(message,self).dialog_id!=self) throw new IllegalArgumentException("Bookmark required");
        LinkedHashSet<String> values=new LinkedHashSet<>();
        for(TLRPC.Reaction tag:tags) values.add(key(tag));
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            db.delete("saved_message_tags","message=?",new String[]{Integer.toString(message)});
            for(String value:values) {
                ContentValues row=new ContentValues();row.put("message",message);row.put("tag",value);
                db.insertOrThrow("saved_message_tags",null,row);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void rename(TLRPC.Reaction reaction,String title) {
        String tag=key(reaction);
        if(title!=null && title.length()>128) throw new IllegalArgumentException("Tag name is too long");
        if(title==null || title.trim().isEmpty()) {
            ContentValues empty=new ContentValues();empty.put("title","");
            store.getWritableDatabase().update("saved_tag_names",empty,"tag=?",new String[]{tag});return;
        }
        ContentValues row=new ContentValues();row.put("tag",tag);row.put("title",title.trim());
        if(store.getWritableDatabase().update("saved_tag_names",row,"tag=?",new String[]{tag})==0)
            store.getWritableDatabase().insertOrThrow("saved_tag_names",null,row);
    }
    java.util.Map<String,Integer> colors() {
        java.util.Map<String,Integer> result=new java.util.HashMap<>();
        try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT tag,color FROM saved_tag_names",null)) {
            while(rows.moveToNext()) result.put(rows.getString(0),rows.getInt(1));
        }
        return result;
    }
    ArrayList<Integer> edit(long self,TLRPC.Reaction original,TLRPC.Reaction replacement,String title,int color) {
        String before=key(original),after=key(replacement);
        String name=title==null ? "" : title.trim();
        if(name.length()>128) throw new IllegalArgumentException("Tag name is too long");
        if(color!=0 && android.graphics.Color.alpha(color)!=255) throw new IllegalArgumentException("Choose an opaque text color");
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            if(!before.equals(after)) try(Cursor clash=db.rawQuery("SELECT 1 FROM saved_message_tags WHERE tag=? UNION SELECT 1 FROM saved_tag_names WHERE tag=? LIMIT 1",new String[]{after,after})) {
                if(clash.moveToFirst()) throw new IllegalArgumentException("That emoji already has a tag. Choose another emoji.");
            }
            ArrayList<Integer> changed=new ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT t.message FROM saved_message_tags t JOIN messages m ON m.id=t.message WHERE t.tag=? AND m.peer=? AND m.deleted=0",new String[]{before,Long.toString(self)})) {
                while(rows.moveToNext()) changed.add(rows.getInt(0));
            }
            if(!before.equals(after)) {
                ContentValues tag=new ContentValues();tag.put("tag",after);
                db.update("saved_message_tags",tag,"tag=?",new String[]{before});
                db.delete("saved_tag_names","tag=?",new String[]{before});
            }
            ContentValues definition=new ContentValues();definition.put("tag",after);definition.put("title",name);definition.put("color",color);
            db.insertWithOnConflict("saved_tag_names",null,definition,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();return changed;
        } finally { db.endTransaction(); }
    }
    TLRPC.TL_messageReactions snapshot(int message) {
        TLRPC.TL_messageReactions result=new TLRPC.TL_messageReactions();result.reactions_as_tags=true;
        try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT tag FROM saved_message_tags WHERE message=? ORDER BY tag",new String[]{Integer.toString(message)})) {
            while(rows.moveToNext()) {
                TLRPC.TL_reactionCount count=new TLRPC.TL_reactionCount();count.reaction=reaction(rows.getString(0));
                count.count=1;count.chosen=true;count.flags=1;count.chosen_order=result.results.size();result.results.add(count);
            }
        }
        return result;
    }
    TLRPC.TL_messages_savedReactionsTags list(long self) {
        TLRPC.TL_messages_savedReactionsTags result=new TLRPC.TL_messages_savedReactionsTags();
        try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT t.tag,COUNT(*),n.title FROM saved_message_tags t JOIN messages m ON m.id=t.message LEFT JOIN saved_tag_names n ON n.tag=t.tag WHERE m.peer=? AND m.deleted=0 GROUP BY t.tag ORDER BY COUNT(*) DESC,t.tag",new String[]{Long.toString(self)})) {
            while(rows.moveToNext()) {
                TLRPC.TL_savedReactionTag tag=new TLRPC.TL_savedReactionTag();tag.reaction=reaction(rows.getString(0));tag.count=rows.getInt(1);
                if(!rows.isNull(2)) { tag.title=rows.getString(2);tag.flags=1; }
                result.tags.add(tag);
            }
        }
        return result;
    }
    TLRPC.TL_messages_reactions defaults(long self) {
        TLRPC.TL_messages_reactions result=new TLRPC.TL_messages_reactions();
        LinkedHashSet<String> seen=new LinkedHashSet<>();
        for(TLRPC.TL_savedReactionTag tag:list(self).tags) if(seen.add(key(tag.reaction))) result.reactions.add(tag.reaction);
        ArrayList<String> emojis=new ArrayList<>(org.telegram.messenger.Emoji.getFavoriteEmoji());
        java.util.Collections.addAll(emojis,"⭐","❤️","✅","📌","💡","📚","🎵","🔥");
        for(String emoji:emojis) if(MonogramReactions.isEmoji(emoji) && seen.add("e:"+emoji)) result.reactions.add(reaction("e:"+emoji));
        return result;
    }
    static String searchClause(List<TLRPC.Reaction> tags,ArrayList<String> args) {
        StringBuilder clause=new StringBuilder();
        for(TLRPC.Reaction tag:tags) {
            clause.append(" AND EXISTS(SELECT 1 FROM saved_message_tags st WHERE st.message=m.id AND st.tag=?)");args.add(key(tag));
        }
        return clause.toString();
    }
}
