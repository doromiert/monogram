// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.negzero.monogram.xmpp.MoneroPayment;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.telegram.messenger.ApplicationLoader;

/** Opt-in payment links, request records and explicit contact bindings. Secrets live in the separate wallet engine. */
public final class MonogramXmr implements AutoCloseable {
    public static final String REQUEST_PARAM="monogram-xmr-request",PRIVATE_PARAM="monogram-xmr-require-encryption";
    private final int account;
    private final Context appContext;
    private final java.util.function.Supplier<String> ownerLookup;
    private final String owner;
    private final SharedPreferences preferences;
    private Storage storage;
    private static final java.util.concurrent.ConcurrentHashMap<String,SharedPreferences> preferenceCache=new java.util.concurrent.ConcurrentHashMap<>();
    private static Context context() { return ApplicationLoader.applicationContext; }
    private static String owner(int account) { return MonogramController.get(account).accountJid(); }
    private static SharedPreferences preferences(int account,String owner) {
        return preferenceCache.computeIfAbsent(account+"\n"+owner,key->preferences(context(),account,owner));
    }
    private static SharedPreferences preferences(Context context,int account,String owner) {
        return context.getSharedPreferences("monogram-xmr-"+account+"-"+scope(owner),Context.MODE_PRIVATE);
    }
    private static String scope(String owner) {
        try {return org.telegram.messenger.Utilities.bytesToHex(java.security.MessageDigest.getInstance("SHA-256").digest(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
    public static boolean enabled(int account) {
        String owner=owner(account);return !owner.isEmpty() && preferences(account,owner).getBoolean("enabled",false);
    }
    public MonogramXmr(int account) {
        this(account,context(),()->owner(account));
    }
    MonogramXmr(int account,Context context,java.util.function.Supplier<String> ownerLookup) {
        this.account=account;this.appContext=context.getApplicationContext();this.ownerLookup=ownerLookup;owner=ownerLookup.get();
        if(owner.isEmpty()) throw new IllegalStateException("Sign in to configure Monero.");
        preferences=preferences(appContext,account,owner);
    }
    private void requireOwner() {
        if(!owner.equals(ownerLookup.get())) throw new IllegalStateException("The active XMPP account changed. Reopen Monero settings.");
    }
    public boolean enabled() { requireOwner();return preferences.getBoolean("enabled",false); }
    public void enabled(boolean enabled) { requireOwner();preferences.edit().putBoolean("enabled",enabled).commit();if(!enabled) {com.negzero.monogram.wallet.MonogramWallet.lockAccount(account);close();} }
    public MoneroPayment.Network network() {
        requireOwner();
        try { return MoneroPayment.Network.valueOf(preferences.getString("network","MAINNET")); }
        catch(IllegalArgumentException invalid) { return MoneroPayment.Network.MAINNET; }
    }
    public void network(MoneroPayment.Network network) { requireOwner();preferences.edit().putString("network",network.name()).commit(); }
    public void requireEnabled() { if(!enabled()) throw new IllegalStateException("Enable Monero in Settings first."); }
    private SQLiteDatabase db() {
        requireEnabled();
        if(storage==null) storage=new Storage(appContext,new java.io.File(appContext.getNoBackupFilesDir(),"monogram-xmr-"+account+"-"+scope(owner)+".db").getAbsolutePath());
        return storage.getWritableDatabase();
    }
    public String nextSubaddress() {
        try(Cursor rows=db().rawQuery("SELECT address FROM receiving_addresses WHERE network=? AND request IS NULL ORDER BY added,rowid LIMIT 1",new String[]{network().name()})) {
            return rows.moveToFirst() ? rows.getString(0) : null;
        }
    }
    public int unusedCount() {
        try(Cursor rows=db().rawQuery("SELECT count(*) FROM receiving_addresses WHERE network=? AND request IS NULL",new String[]{network().name()})) {rows.moveToFirst();return rows.getInt(0);}
    }
    public int addSubaddresses(String input) {
        requireEnabled();MoneroPayment.Network network=network();
        LinkedHashSet<String> addresses=new LinkedHashSet<>();
        for(String line:input.split("\\r?\\n")) {
            String value=line.trim();if(value.isEmpty()) continue;
            MoneroPayment payment=value.regionMatches(true,0,"monero:",0,7) ? MoneroPayment.parse(value,network) : MoneroPayment.address(value,network);
            if(payment.type!=MoneroPayment.AddressType.SUBADDRESS) throw new IllegalArgumentException("Import receiving subaddresses from your wallet. Mainnet subaddresses start with 8.");
            if(payment.atomicAmount!=null || !payment.description.isEmpty() || !payment.recipientName.isEmpty()) throw new IllegalArgumentException("Import plain receiving addresses, without payment amounts or labels.");
            addresses.add(payment.address);
        }
        if(addresses.isEmpty()) throw new IllegalArgumentException("Paste at least one receiving subaddress, one per line.");
        SQLiteDatabase db=db();int imported=0;db.beginTransaction();
        try {
            for(String address:addresses) {
                ContentValues value=new ContentValues();value.put("address",address);value.put("network",network.name());value.put("added",System.currentTimeMillis());
                if(db.insertWithOnConflict("receiving_addresses",null,value,SQLiteDatabase.CONFLICT_IGNORE)!=-1) imported++;
            }
            db.setTransactionSuccessful();return imported;
        } finally {db.endTransaction();}
    }
    public void createRequest(String id,MoneroPayment payment,String peer) {
        requireEnabled();MoneroPayment validated=MoneroPayment.parse(payment.uri(),network());
        if(validated.atomicAmount==null || validated.type!=MoneroPayment.AddressType.SUBADDRESS) throw new IllegalArgumentException("A request needs an amount and a receiving subaddress.");
        peer=contact(peer);SQLiteDatabase db=db();db.beginTransaction();
        try {
            try(Cursor old=db.rawQuery("SELECT uri,peer,kind,network FROM activity WHERE id=?",new String[]{id})) {
                if(old.moveToFirst()) {
                    if(!old.getString(0).equals(validated.uri()) || !old.getString(1).equals(peer) || !old.getString(2).equals("request") || !old.getString(3).equals(validated.network.name()))
                        throw new IllegalStateException("Create a new request to change its amount, note or recipient.");
                    db.setTransactionSuccessful();return;
                }
            }
            ContentValues claim=new ContentValues();claim.put("request",id);
            if(db.update("receiving_addresses",claim,"address=? AND network=? AND request IS NULL",new String[]{payment.address,payment.network.name()})!=1)
                throw new IllegalStateException("This subaddress was already used. Start a new request or import fresh subaddresses.");
            saveActivity(db,id,"request",validated,peer,"Created");
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    public void mark(String id,String state) {
        if(!state.equals("Copied") && !state.equals("Share opened") && !state.equals("QR shown") && !state.equals("Queued in chat")) throw new IllegalArgumentException("Invalid request state.");
        ContentValues value=new ContentValues();value.put("state",state);db().update("activity",value,"id=? AND kind='request'",new String[]{id});
    }
    private static void saveActivity(SQLiteDatabase db,String id,String kind,MoneroPayment payment,String peer,String state) {
        ContentValues values=new ContentValues();values.put("id",id);values.put("kind",kind);values.put("uri",payment.uri());values.put("network",payment.network.name());
        values.put("peer",peer);values.put("state",state);values.put("created",System.currentTimeMillis());db.insertOrThrow("activity",null,values);
    }
    public String contactAddress(String peer) {
        if(peer==null || peer.isEmpty()) return "";
        try(Cursor row=db().rawQuery("SELECT address FROM contact_addresses WHERE peer=? AND network=?",new String[]{contact(peer),network().name()})) {return row.moveToFirst() ? row.getString(0) : "";}
    }
    public void contactAddress(String peer,String address) {
        MoneroPayment payment=MoneroPayment.address(address,network());String contact=contact(peer);
        if(contact.isEmpty()) throw new IllegalArgumentException("Choose an XMPP contact first.");
        ContentValues value=new ContentValues();value.put("peer",contact);value.put("network",payment.network.name());value.put("address",payment.address);
        db().insertWithOnConflict("contact_addresses",null,value,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static String contact(String address) {
        if(address==null || address.isEmpty()) return "";
        try {
            org.jxmpp.jid.Jid jid=org.jxmpp.jid.impl.JidCreate.from(address);
            if(jid.hasResource() || jid.asEntityBareJidIfPossible()==null) throw new IllegalArgumentException();
            return jid.asEntityBareJidIfPossible().toString();
        } catch(Exception invalid) {throw new IllegalArgumentException("Use a complete bare XMPP address for the contact.");}
    }
    public static final class Entry {
        public final String id,kind,uri,peer,state;public final long created;
        Entry(Cursor row) {id=row.getString(0);kind=row.getString(1);uri=row.getString(2);peer=row.getString(3);state=row.getString(4);created=row.getLong(5);}
    }
    public List<Entry> recent() {
        ArrayList<Entry> entries=new ArrayList<>();
        try(Cursor rows=db().rawQuery("SELECT id,kind,uri,peer,state,created FROM activity WHERE network=? ORDER BY created DESC,rowid DESC LIMIT 30",new String[]{network().name()})) {while(rows.moveToNext()) entries.add(new Entry(rows));}
        return entries;
    }
    @Override public void close() {if(storage!=null) {storage.close();storage=null;}}
    private static final class Storage extends SQLiteOpenHelper {
        Storage(Context context,String file) {super(context,file,null,1);}
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE receiving_addresses(address TEXT PRIMARY KEY,network TEXT NOT NULL,added INTEGER NOT NULL,request TEXT UNIQUE)");
            db.execSQL("CREATE TABLE activity(id TEXT PRIMARY KEY,kind TEXT NOT NULL,uri TEXT NOT NULL,network TEXT NOT NULL,peer TEXT NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE contact_addresses(peer TEXT NOT NULL,network TEXT NOT NULL,address TEXT NOT NULL,PRIMARY KEY(peer,network))");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {throw new IllegalStateException("Unsupported Monero data version");}
    }
}
