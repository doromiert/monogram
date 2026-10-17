package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import com.negzero.monogram.xmpp.FileShare;
import com.negzero.monogram.xmpp.MessageCorrection;
import com.negzero.monogram.xmpp.MessageFixture;
import com.negzero.monogram.xmpp.XmppMessage;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import org.jxmpp.jid.impl.JidCreate;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramCaptionEditingTest {
    private static final String OWN="alice@example.org", PEER="bob@example.org";

    private FileShare sharing(String url,String name,byte marker) throws Exception {
        byte[] hash=new byte[32]; hash[0]=marker;
        return new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>"+name+"</name><media-type>application/octet-stream</media-type><size>3</size><hash xmlns='urn:xmpp:hashes:2' algo='sha-256'>"+Base64.getEncoder().encodeToString(hash)+"</hash></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='"+url+"'/></sources></file-sharing>");
    }
    private XmppMessage file(String id,FileShare sharing,String caption,MessageCorrection correction) throws Exception {
        return XmppMessage.sentFile(JidCreate.entityFullFrom(OWN+"/phone"),PEER,id,sharing,caption,null,correction);
    }
    private void assertCaptionRoundTrip(Context context,String database,FileShare sharing) throws Exception {
        context.deleteDatabase(database); File cached=null; long self; int original;
        try {
            try(MonogramStore store=new MonogramStore(context,database)) {
                self=store.peer(OWN); long peer=store.peer(PEER);
                original=store.save(file("original-"+database,sharing,"Before",null));
                assertEquals(sharing.xml(),store.editTarget(original,peer,self).sharing.xml());
                cached=store.attachmentFile(sharing); cached.getParentFile().mkdirs(); Files.write(cached.toPath(),new byte[]{1,2,3});
                TLRPC.MessageMedia nativeMedia=MonogramAttachments.remoteMedia(original,sharing.name,sharing.mime,sharing.size);
                store.attach(original,sharing.link.wireUrl,cached,nativeMedia);
                int correction=store.save(file("caption-"+database,sharing,"After",new MessageCorrection("original-"+database)));
                assertTrue(store.reconcileCorrections(peer).hidden.contains(correction));
                TLRPC.Message rendered=store.getMessage(original,self);
                assertEquals(original,rendered.id); assertEquals("After",rendered.message); assertEquals(cached.getAbsolutePath(),rendered.attachPath);
                assertNotNull(rendered.media.document); assertEquals(original,rendered.media.document.id);
                try(Cursor c=store.getReadableDatabase().rawQuery("SELECT path,media FROM attachments WHERE message=?",new String[]{Integer.toString(correction)})) {
                    assertTrue(c.moveToFirst()); assertEquals(cached.getAbsolutePath(),c.getString(0)); assertNotNull(c.getBlob(1));
                }
            }
            try(MonogramStore reopened=new MonogramStore(context,database)) {
                TLRPC.Message rendered=reopened.getMessage(original,self);
                assertEquals("After",rendered.message); assertEquals(cached.getAbsolutePath(),rendered.attachPath);
                assertEquals(original,rendered.media.document.id);
            }
        } finally { if(cached!=null) cached.delete(); context.deleteDatabase(database); }
    }

    @Test public void plainAndModernCaptionCorrectionsKeepNativeAttachmentIdentityAcrossReopen() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        assertCaptionRoundTrip(context,"caption-plain.db",sharing("https://files.example.org/plain.bin","plain.bin",(byte)1));
        assertCaptionRoundTrip(context,"caption-modern.db",sharing("aesgcm://files.example.org/private.bin#"+new String(new char[88]).replace('\0','a'),"private.bin",(byte)2));
    }

    @Test public void rejectsReplacementMissingLegacyDowngradeAndForeignCaptionCorrections() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String database="caption-rejections.db"; context.deleteDatabase(database);
        try(MonogramStore store=new MonogramStore(context,database)) {
            long self=store.peer(OWN),peer=store.peer(PEER); String url="https://files.example.org/original.bin";
            FileShare originalSharing=sharing(url,"original.bin",(byte)1);
            int original=store.save(file("original",originalSharing,"Before",null));
            FileShare changedUrl=sharing("https://files.example.org/replacement.bin","original.bin",(byte)1);
            FileShare changedHash=sharing(url,"original.bin",(byte)2);
            FileShare changedXml=sharing(url,"renamed.bin",(byte)1);
            int replacement=store.save(file("replacement",changedUrl,"After",new MessageCorrection("original")));
            int hash=store.save(file("hash",changedHash,"After",new MessageCorrection("original")));
            int xml=store.save(file("xml",changedXml,"After",new MessageCorrection("original")));
            int missing=store.save(XmppMessage.sent(JidCreate.entityFullFrom(OWN+"/phone"),PEER,"missing","After",XmppMessage.Encryption.PLAIN,null,new MessageCorrection("original")));
            store.reconcileCorrections(peer);
            for(int rejected:new int[]{replacement,hash,xml,missing}) assertFalse(store.hiddenCorrection(rejected));
            assertEquals("Before",store.getMessage(original,self).message);

            FileShare modern=sharing("aesgcm://files.example.org/private.bin#"+new String(new char[88]).replace('\0','a'),"private.bin",(byte)3);
            int protectedOriginal=store.save(file("modern-original",modern,"Private",null));
            int downgrade=store.save(XmppMessage.sent(JidCreate.entityFullFrom(OWN+"/phone"),PEER,"downgrade","Downgrade",XmppMessage.Encryption.PLAIN,null,new MessageCorrection("modern-original")));
            int foreign=store.save(MessageFixture.modern("foreign",null,modern.body("Foreign"),false,300000,null,"modern-original",modern));
            store.reconcileCorrections(peer);
            assertFalse(store.hiddenCorrection(downgrade)); assertFalse(store.hiddenCorrection(foreign));
            assertEquals("Private",store.getMessage(protectedOriginal,self).message);

            String legacyUrl="aesgcm://files.example.org/legacy.bin#"+new String(new char[88]).replace('\0','a');
            int legacy=store.save(XmppMessage.sent(JidCreate.entityFullFrom(OWN+"/phone"),PEER,"legacy",legacyUrl,XmppMessage.Encryption.LEGACY_OMEMO,null,null));
            int legacyCorrection=store.save(XmppMessage.sent(JidCreate.entityFullFrom(OWN+"/phone"),PEER,"legacy-edit",legacyUrl,XmppMessage.Encryption.LEGACY_OMEMO,null,new MessageCorrection("legacy")));
            store.reconcileCorrections(peer); assertFalse(store.hiddenCorrection(legacyCorrection));
            try { store.editTarget(legacy,peer,self); fail("Legacy attachment edit accepted"); } catch(IllegalArgumentException expected) { }
        } finally { context.deleteDatabase(database); }
    }

    @Test public void lateOriginalDownloadKeepsCorrectedCaptionAndTranslationAcrossReopen() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); String database="caption-late-download.db"; context.deleteDatabase(database);
        File cached=null; long self; int original;
        try {
            try(MonogramStore store=new MonogramStore(context,database)) {
                self=store.peer(OWN); long peer=store.peer(PEER);
                FileShare sharing=sharing("https://files.example.org/late.bin","late.bin",(byte)4);
                original=store.save(file("late-original",sharing,"Before",null));
                int correction=store.save(file("late-caption",sharing,"After",new MessageCorrection("late-original")));
                assertTrue(store.reconcileCorrections(peer).hidden.contains(correction));
                cached=store.attachmentFile(sharing); cached.getParentFile().mkdirs(); Files.write(cached.toPath(),new byte[]{1,2,3});
                TLRPC.MessageMedia downloaded=MonogramAttachments.remoteMedia(original,sharing.name,sharing.mime,sharing.size);
                assertTrue(store.attachDownloaded(original,sharing.link.wireUrl,sharing,cached,downloaded));
                TLRPC.Message rendered=store.getMessage(original,self);
                assertEquals("After",rendered.message); assertEquals(cached.getAbsolutePath(),rendered.attachPath);
                assertEquals(original,rendered.media.document.id); assertEquals("After",store.translationText(original,peer,self).text);
                try { store.translationText(original,store.peer("charlie@example.org"),self); fail("Foreign conversation translated"); }
                catch(IllegalArgumentException expected) { }
                int unreadable=store.save(MessageFixture.modern("unreadable",null,null,true,300000,null,null,null));
                try { store.translationText(unreadable,peer,self); fail("Unreadable message translated"); }
                catch(IllegalArgumentException expected) { }
            }
            try(MonogramStore reopened=new MonogramStore(context,database)) {
                TLRPC.Message rendered=reopened.getMessage(original,self);
                assertEquals("After",rendered.message); assertEquals(cached.getAbsolutePath(),rendered.attachPath);
                assertEquals(original,rendered.media.document.id); assertEquals("After",reopened.translationText(original,reopened.peer(PEER),self).text);
            }
        } finally { if(cached!=null) cached.delete(); context.deleteDatabase(database); }
    }
}
