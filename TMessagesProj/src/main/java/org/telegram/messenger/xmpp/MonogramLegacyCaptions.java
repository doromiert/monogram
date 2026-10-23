// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.ForwardedContent;
import org.telegram.tgnet.TLRPC;

/** Presentation-only links for captions sent by this device. Wire messages remain intact. */
final class MonogramLegacyCaptions {
    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_media_captions(message INTEGER PRIMARY KEY,companion INTEGER NOT NULL UNIQUE,placeholder INTEGER NOT NULL DEFAULT 0)");
    }
    static String visibleClause(String alias) {
        return " AND NOT EXISTS(SELECT 1 FROM local_media_captions lc JOIN messages lp ON lp.id=lc.message WHERE lc.companion="+alias+".id)";
    }
    static int parent(SQLiteDatabase db,int companion) {
        try(Cursor row=db.rawQuery("SELECT lc.message FROM local_media_captions lc JOIN messages m ON m.id=lc.message WHERE lc.companion=?",new String[]{Integer.toString(companion)})) {
            return row.moveToFirst() ? row.getInt(0) : 0;
        }
    }
    static boolean link(SQLiteDatabase db,int parent,int companion,boolean placeholder) {
        if(parent<=0 || companion<=0 || parent==companion) return false;
        // A local outbox link cannot fold another account/contact's message or a non-legacy send.
        try(Cursor row=db.rawQuery("SELECT p.body,c.body FROM messages p JOIN attachments a ON a.message=p.id JOIN messages c ON c.id=? AND c.peer=p.peer WHERE p.id=? AND p.outgoing=1 AND c.outgoing=1 AND p.readable=1 AND c.readable=1 AND p.deleted=0 AND c.deleted=0 AND p.encrypted=1 AND c.encrypted=1 AND p.encryption=1 AND c.encryption=1 AND NOT EXISTS(SELECT 1 FROM attachments ca WHERE ca.message=c.id)",new String[]{Integer.toString(companion),Integer.toString(parent)})) {
            if(!row.moveToFirst()) return false;
            try { if(!com.negzero.monogram.xmpp.FileLink.parse(row.getString(0)).encrypted()) return false; }
            catch(java.io.IOException invalid) { return false; }
        }
        ContentValues values=new ContentValues();values.put("message",parent);values.put("companion",companion);values.put("placeholder",placeholder ? 1 : 0);
        return db.insertWithOnConflict("local_media_captions",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
    }
    static void decorate(MonogramStore store,SQLiteDatabase db,TLRPC.Message file,long self) {
        if(!MonogramAttachments.has(file)) return;
        try(Cursor row=db.rawQuery("SELECT lc.companion,lc.placeholder FROM local_media_captions lc JOIN messages c ON c.id=lc.companion WHERE lc.message=? AND c.deleted=0 AND c.readable=1",new String[]{Integer.toString(file.id)})) {
            if(!row.moveToFirst()) return;
            TLRPC.Message caption=store.getMessage(row.getInt(0),self);
            file.message=row.getInt(1)!=0 ? "" : caption.message;
            file.entities.clear();
            if(row.getInt(1)==0) file.entities.addAll(caption.entities);
            if(file.entities.isEmpty()) file.flags&=~TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
            else file.flags|=TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
    }
    /** Recover only the old generated placeholder, with matching known local attribution.
     * Arbitrary nearby captions and incoming messages must never be guessed or hidden. */
    static void recoverPlaceholders(SQLiteDatabase db) {
        try(Cursor parents=db.rawQuery("SELECT m.id,m.peer,m.time_ms,f.sender,f.date FROM local_forwarded_messages f JOIN messages m ON m.id=f.message JOIN attachments a ON a.message=m.id WHERE m.outgoing=1 AND m.deleted=0 AND m.encryption=1 AND NOT EXISTS(SELECT 1 FROM local_media_captions lc WHERE lc.message=m.id)",null)) {
            while(parents.moveToNext()) {
                int parent=parents.getInt(0);
                try(Cursor next=db.rawQuery("SELECT id,body,outgoing,time_ms FROM messages WHERE peer=? AND deleted=0 AND (time_ms>? OR (time_ms=? AND id>?)) ORDER BY time_ms,archive_order,id LIMIT 1",new String[]{parents.getString(1),parents.getString(2),parents.getString(2),Integer.toString(parent)})) {
                    if(!next.moveToFirst() || next.getInt(2)!=1 || next.getLong(3)-parents.getLong(2)>30000) continue;
                    ForwardedContent forward=ForwardedContent.parse(next.getString(1));
                    if(forward==null || !forward.name.equals(parents.getString(3)) || forward.date!=parents.getInt(4)) continue;
                    String localized=org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MonogramForwardedAttachment);
                    if(forward.body.equals("Forwarded attachment") || forward.body.equals(localized)) link(db,parent,next.getInt(0),true);
                }
            }
        }
    }
}
