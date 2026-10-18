// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import com.negzero.monogram.xmpp.InlineEmoji;
import java.io.*;
import java.util.*;
import org.telegram.tgnet.TLRPC;

/** Account-database-owned inline emoji blobs and native entities; never fetches a CID from a server. */
public final class MonogramEmojis {
    private static final long PREFIX=0x4d45000000000000L, THUMB=0x4d46000000000000L, MASK=0x0000ffffffffffffL;
    private MonogramEmojis() {}
    public static boolean isLocalId(long id) { return (id & ~MASK)==PREFIX && (id & MASK)!=0; }
    public static boolean isThumb(long volume) { return (volume & ~MASK)==THUMB; }
    public static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS inline_emoji_messages(message INTEGER PRIMARY KEY,body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS inline_emoji_ranges(message INTEGER NOT NULL,position INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,cid TEXT NOT NULL,PRIMARY KEY(message,position))");
        db.execSQL("CREATE TABLE IF NOT EXISTS inline_emoji_assets(cid TEXT PRIMARY KEY,document INTEGER NOT NULL UNIQUE,mime TEXT NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,size INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS inline_emoji_state(id INTEGER PRIMARY KEY,namespace INTEGER NOT NULL)");
        db.execSQL("INSERT OR IGNORE INTO inline_emoji_state VALUES(1,?)",new Object[]{new java.security.SecureRandom().nextInt() & 0xffffffffL});
    }
    public static boolean hasEntities(Collection<TLRPC.MessageEntity> entities) {
        if(entities!=null) for(TLRPC.MessageEntity entity:entities) if(entity instanceof TLRPC.TL_messageEntityCustomEmoji) return true;
        return false;
    }
    public static InlineEmoji fromEntities(int account,String body,Collection<TLRPC.MessageEntity> entities) {
        if(body==null || entities==null) return null;
        ArrayList<InlineEmoji.Range> ranges=new ArrayList<>();Map<Long,InlineEmoji.Asset> assets=new HashMap<>();Set<Long> unavailable=new HashSet<>();int bytes=0;
        for(TLRPC.MessageEntity entity:entities) {
            if(!(entity instanceof TLRPC.TL_messageEntityCustomEmoji) || entity.offset<0 || entity.length<=0 || (long)entity.offset+entity.length>body.length()) continue;
            if(!boundary(body,entity.offset) || !boundary(body,entity.offset+entity.length)) continue;
            long id=((TLRPC.TL_messageEntityCustomEmoji)entity).document_id;
            if(unavailable.contains(id)) continue;
            InlineEmoji.Asset asset=assets.get(id);
            try {
                if(asset==null) {
                    TLRPC.Document document=MonogramPacks.document(account,id);
                    if(document==null) document=MonogramController.get(account).customEmoji(id);
                    if(document==null || document.localPath==null || !InlineEmoji.supported(document.mime_type)) { unavailable.add(id);continue; }
                    File file=new File(document.localPath);
                    if(!file.isFile() || file.length()<1 || file.length()>InlineEmoji.MAX_BYTES || bytes+file.length()>InlineEmoji.MAX_BYTES) { unavailable.add(id);continue; }
                    byte[] data=read(file,InlineEmoji.MAX_BYTES);MonogramPacks.validateMedia(data,document.mime_type);
                    asset=new InlineEmoji.Asset(document.mime_type,data);assets.put(id,asset);bytes+=data.length;
                }
                ranges.add(new InlineEmoji.Range(body.codePointCount(0,entity.offset),body.codePointCount(0,entity.offset+entity.length),asset));
                if(ranges.size()>=InlineEmoji.MAX_RANGES || assets.size()>=InlineEmoji.MAX_ASSETS) break;
            } catch(Exception invalid) { unavailable.add(id); }
        }
        try { return ranges.isEmpty() ? null : new InlineEmoji(body,ranges); }
        catch(IllegalArgumentException overlapping) { return null; }
    }
    private static boolean boundary(String body,int at) { return at==0 || at==body.length() || !Character.isHighSurrogate(body.charAt(at-1)) || !Character.isLowSurrogate(body.charAt(at)); }
    public static boolean save(SQLiteDatabase db,Context context,String database,int id,InlineEmoji model) {
        if(model==null) return false;
        try(Cursor message=db.rawQuery("SELECT body,readable,deleted FROM messages WHERE id=?",new String[]{Integer.toString(id)})) {
            if(!message.moveToFirst() || message.getInt(1)==0 || message.getInt(2)!=0 || !model.body.equals(message.getString(0))) return false;
        }
        ArrayList<InlineEmoji.Range> accepted=new ArrayList<>();Map<String,Boolean> available=new HashMap<>();boolean repaired=false;
        for(InlineEmoji.Range range:model.ranges) {
            Boolean valid=available.get(range.asset.cid);
            if(valid==null) {
                try { boolean[] changed={false}; storeAsset(db,context,database,range.asset,changed);repaired|=changed[0];valid=true; }
                catch(Exception invalid) { valid=false; }
                available.put(range.asset.cid,valid);
            }
            if(valid) accepted.add(range);
        }
        String previous=null;try(Cursor row=db.rawQuery("SELECT body FROM inline_emoji_messages WHERE message=?",new String[]{Integer.toString(id)})) { if(row.moveToFirst()) previous=row.getString(0); }
        boolean same=Objects.equals(previous,model.body);int position=0;
        try(Cursor rows=db.rawQuery("SELECT start,end,cid FROM inline_emoji_ranges WHERE message=? ORDER BY position",new String[]{Integer.toString(id)})) {
            while(rows.moveToNext()) {
                if(position>=accepted.size()) { same=false;break; }
                InlineEmoji.Range range=accepted.get(position++);if(range.start!=rows.getInt(0) || range.end!=rows.getInt(1) || !range.asset.cid.equals(rows.getString(2))) same=false;
            }
            if(position!=accepted.size()) same=false;
        }
        if(same) return repaired;
        ContentValues message=new ContentValues();message.put("message",id);message.put("body",model.body);
        db.insertWithOnConflict("inline_emoji_messages",null,message,SQLiteDatabase.CONFLICT_REPLACE);
        db.delete("inline_emoji_ranges","message=?",new String[]{Integer.toString(id)});position=0;
        for(InlineEmoji.Range range:accepted) {
            ContentValues value=new ContentValues();value.put("message",id);value.put("position",position++);value.put("start",range.start);value.put("end",range.end);value.put("cid",range.asset.cid);db.insertOrThrow("inline_emoji_ranges",null,value);
        }
        return true;
    }
    private static long namespace(SQLiteDatabase db) { try(Cursor row=db.rawQuery("SELECT namespace FROM inline_emoji_state WHERE id=1",null)) { if(!row.moveToFirst()) throw new IllegalStateException("Missing emoji cache namespace");return row.getLong(0)&0xffffffffL; } }
    private static File directory(Context context,String database) { return new File(context.getFilesDir(),"monogram-inline-emoji/"+new File(database).getName()); }
    private static String extension(String mime) { return "image/png".equals(mime)?"png":"image/webp".equals(mime)?"webp":"video/webm".equals(mime)?"webm":"tgs"; }
    private static File file(Context context,String database,String cid,String mime) {
        if(!cid.matches("sha-256\\+[0-9a-f]{64}@bob\\.xmpp\\.org") || !InlineEmoji.supported(mime)) throw new IllegalArgumentException("Invalid cached emoji");
        return new File(directory(context,database),cid.substring(8,72)+"."+extension(mime));
    }
    private static void storeAsset(SQLiteDatabase db,Context context,String database,InlineEmoji.Asset asset,boolean[] changed) throws Exception {
        byte[] data=asset.data();File target=file(context,database,asset.cid,asset.mime);
        if(data==null) {
            if(!target.isFile()) throw new IOException();data=read(target,InlineEmoji.MAX_BYTES);
            new InlineEmoji.Asset(asset.cid,asset.mime,data);
        }
        MonogramPacks.validateMedia(data,asset.mime);int width=512,height=512;
        if(asset.mime.startsWith("image/")) {
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);
            if(options.outWidth<1 || options.outHeight<1 || options.outWidth>2048 || options.outHeight>2048 || (long)options.outWidth*options.outHeight>4194304) throw new IOException();
            width=options.outWidth;height=options.outHeight;
        }
        boolean valid=false;
        if(target.isFile()) try { new InlineEmoji.Asset(asset.cid,asset.mime,read(target,InlineEmoji.MAX_BYTES));valid=true; } catch(Exception stale) { }
        if(!valid) {
            if(!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException();
            AtomicFile file=new AtomicFile(target);FileOutputStream output=null;
            try { output=file.startWrite();output.write(data);file.finishWrite(output);changed[0]=true; }
            catch(Exception failure) { if(output!=null) file.failWrite(output);throw failure; }
        }
        long document=0;
        try(Cursor row=db.rawQuery("SELECT document,mime FROM inline_emoji_assets WHERE cid=?",new String[]{asset.cid})) {
            if(row.moveToFirst()) { if(!asset.mime.equals(row.getString(1))) throw new IOException();document=row.getLong(0); }
        }
        if(document==0) {
            document=PREFIX|Long.parseLong(asset.hash().substring(0,12),16);if((document&MASK)==0) document++;
            for(;;) { try(Cursor row=db.rawQuery("SELECT cid FROM inline_emoji_assets WHERE document=?",new String[]{Long.toString(document)})) { if(!row.moveToFirst()) break; } document=PREFIX|((document+1)&MASK); }
            ContentValues value=new ContentValues();value.put("cid",asset.cid);value.put("document",document);value.put("mime",asset.mime);value.put("width",width);value.put("height",height);value.put("size",data.length);db.insertOrThrow("inline_emoji_assets",null,value);changed[0]=true;
        }
    }
    public static void decorate(SQLiteDatabase db,Context context,String database,TLRPC.Message message,int payload) {
        if(MonogramAttachments.has(message)) return; // Caption image ranges require a separate SFS fallback projection.
        String body;
        try(Cursor row=db.rawQuery("SELECT e.body FROM inline_emoji_messages e JOIN messages m ON m.id=e.message WHERE e.message=? AND e.body=m.body AND m.readable=1 AND m.deleted=0",new String[]{Integer.toString(payload)})) { if(!row.moveToFirst()) return;body=row.getString(0); }
        ArrayList<InlineEmoji.Range> ranges=new ArrayList<>();Map<String,Long> documents=new HashMap<>();
        try(Cursor rows=db.rawQuery("SELECT r.start,r.end,a.cid,a.document,a.mime FROM inline_emoji_ranges r JOIN inline_emoji_assets a ON a.cid=r.cid WHERE r.message=? ORDER BY r.position",new String[]{Integer.toString(payload)})) {
            while(rows.moveToNext()) {
                if(ranges.size()>=InlineEmoji.MAX_RANGES) return;
                String cid=rows.getString(2);ranges.add(new InlineEmoji.Range(rows.getInt(0),rows.getInt(1),new InlineEmoji.Asset(cid,rows.getString(4),null)));documents.put(cid,rows.getLong(3));
            }
        } catch(IllegalArgumentException invalid) { return; }
        InlineEmoji visible;
        try { visible=MonogramFormatting.projectInline(db,payload,new InlineEmoji(body,ranges),message.message); }
        catch(IllegalArgumentException invalid) { return; }
        if(visible==null || !Objects.equals(message.message,visible.body)) return;
        message.entities.removeIf(entity->entity instanceof TLRPC.TL_messageEntityCustomEmoji);
        for(InlineEmoji.Range range:visible.ranges) {
            TLRPC.Document document=document(db,context,database,documents.get(range.asset.cid));if(document==null) continue;
            TLRPC.TL_messageEntityCustomEmoji entity=new TLRPC.TL_messageEntityCustomEmoji();entity.offset=visible.body.offsetByCodePoints(0,range.start);entity.length=visible.body.offsetByCodePoints(0,range.end)-entity.offset;entity.document_id=document.id;message.entities.add(entity);
        }
        if(!message.entities.isEmpty()) message.flags|=TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
    }
    public static TLRPC.Document document(SQLiteDatabase db,Context context,String database,long id) {
        if(!isLocalId(id)) return null;
        try(Cursor row=db.rawQuery("SELECT cid,mime,width,height,size FROM inline_emoji_assets WHERE document=?",new String[]{Long.toString(id)})) {
            if(!row.moveToFirst()) return null;String cid=row.getString(0),mime=row.getString(1);File file=file(context,database,cid,mime);
            if(!file.isFile() || file.length()!=row.getLong(4) || file.length()<1 || file.length()>InlineEmoji.MAX_BYTES) return null;
            TLRPC.TL_document document=new TLRPC.TL_document();document.id=id;document.dc_id=Integer.MIN_VALUE;document.file_reference=new byte[0];document.mime_type=mime;document.size=file.length();document.localPath=file.getAbsolutePath();
            TLRPC.TL_documentAttributeCustomEmoji attribute=new TLRPC.TL_documentAttributeCustomEmoji();attribute.free=true;attribute.alt="🙂";attribute.stickerset=new TLRPC.TL_inputStickerSetEmpty();document.attributes.add(attribute);
            TLRPC.TL_documentAttributeImageSize dimensions=new TLRPC.TL_documentAttributeImageSize();dimensions.w=row.getInt(2);dimensions.h=row.getInt(3);document.attributes.add(dimensions);
            TLRPC.TL_photoSize thumb=new TLRPC.TL_photoSize();thumb.type="s";thumb.w=dimensions.w;thumb.h=dimensions.h;thumb.size=(int)document.size;thumb.location=new TLRPC.TL_fileLocationToBeDeprecated();
            thumb.location.volume_id=THUMB|namespace(db)<<16|(id>>>32&0xffffL);thumb.location.local_id=(int)id;thumb.location.dc_id=Integer.MIN_VALUE;document.thumbs.add(thumb);document.flags|=1;return document;
        } catch(IllegalArgumentException invalid) { return null; }
    }
    public static File thumb(int account,long volume,int local) {
        long id=PREFIX|(volume&0xffffL)<<32|(local&0xffffffffL);TLRPC.Document document=MonogramController.get(account).customEmoji(id);
        return document!=null && document.thumbs.get(0).location.volume_id==volume ? new File(document.localPath) : null;
    }
    public static void remove(SQLiteDatabase db,int id) { db.delete("inline_emoji_ranges","message=?",new String[]{Integer.toString(id)});db.delete("inline_emoji_messages","message=?",new String[]{Integer.toString(id)}); }
    public static void clear(SQLiteDatabase db,Context context,String database) {
        db.delete("inline_emoji_ranges",null,null);db.delete("inline_emoji_messages",null,null);db.delete("inline_emoji_assets",null,null);
        db.execSQL("UPDATE inline_emoji_state SET namespace=? WHERE id=1",new Object[]{new java.security.SecureRandom().nextInt()&0xffffffffL});
        File directory=directory(context,database);File[] files=directory.listFiles();if(files!=null) for(File file:files) if(file.isFile()) file.delete();directory.delete();
    }
    private static byte[] read(File file,int max) throws Exception {
        try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1) { if(output.size()+count>max) throw new IOException();output.write(buffer,0,count); }return output.toByteArray();
        }
    }
}
