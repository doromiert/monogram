package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramStickerHintTest.MessageShell.class,MonogramStickerHintTest.ConfigShell.class})
public class MonogramStickerHintTest {
    @org.robolectric.annotation.Implements(value=MessageObject.class,isInAndroidSdk=false)
    public static class MessageShell {
        @org.robolectric.annotation.RealObject MessageObject real;
        @org.robolectric.annotation.Implementation public void __constructor__(int account,TLRPC.Message message,MessageObject reply,
                java.util.AbstractMap<Long,TLRPC.User> users,java.util.AbstractMap<Long,TLRPC.Chat> chats,
                androidx.collection.LongSparseArray<TLRPC.User> sparseUsers,androidx.collection.LongSparseArray<TLRPC.Chat> sparseChats,
                boolean layout,boolean media,long event,boolean repost,boolean repostVideo,boolean saved,int searchType) {
            real.messageOwner=message;real.type=1000;
        }
    }
    @org.robolectric.annotation.Implements(value=org.telegram.messenger.SharedConfig.class,isInAndroidSdk=false)
    public static class ConfigShell {
        @org.robolectric.annotation.Implementation public static void __staticInitializer__() {}
    }
    private static final String DATABASE="sticker-hint-fixture.db",OWN="alice@example.org",PEER="bob@example.org";
    private Context context;private MonogramStore store;private long self;
    @Before public void setup() { context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;context.deleteDatabase(DATABASE);store=new MonogramStore(context,DATABASE);self=store.peer(OWN); }
    @After public void cleanup() { store.close();context.deleteDatabase(DATABASE); }
    private FileLink link(String name) throws Exception { return FileLink.parse("aesgcm://example.org/"+name+"#"+new String(new char[88]).replace('\0','a')); }
    private XmppMessage message(String id,FileLink link,boolean hint) throws Exception { return XmppMessage.sentFile(JidCreate.entityFullFrom(OWN+"/phone"),PEER,id,link,true,null).withDisplayStickerHint(hint); }
    private File artwork(FileLink link) throws Exception {
        File file=store.attachmentFile(link.wireUrl);file.getParentFile().mkdirs();
        Files.write(file.toPath(),Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="));return file;
    }
    @Test public void receivedStickerSurvivesAndroidParsingDownloadAndReopen() throws Exception {
        org.jivesoftware.smack.android.AndroidSmackInitializer.initialize(context);
        FileShare.initialize();
        FileLink link=link("received.png");
        org.jivesoftware.smack.packet.Message wire=org.jivesoftware.smack.util.PacketParserUtils.parseStanza(
                "<message xmlns='jabber:client' from='"+PEER+"/phone' to='"+OWN+"/phone' type='chat' id='received-sticker'><sticker xmlns='urn:xmpp:stickers:0'/></message>");
        java.lang.reflect.Method parse=XmppMessage.class.getDeclaredMethod("from",org.jivesoftware.smack.packet.Message.class,
                org.jxmpp.jid.EntityBareJid.class,String.class,boolean.class,boolean.class,String.class,java.util.Date.class);
        parse.setAccessible(true);
        XmppMessage received=(XmppMessage)parse.invoke(null,wire,JidCreate.entityBareFrom(OWN),link.wireUrl,true,true,null,null);
        assertFalse(received.outgoing);assertTrue(received.displayStickerHint);
        int id=Math.abs(store.save(received));File file=artwork(link);
        assertTrue(store.attachDownloaded(id,link.wireUrl,null,file,MonogramAttachments.media(id,1,file,"received.png")));
        assertTrue(MessageObject.isStickerDocument(store.getMessage(id,self).media.document));
        store.close();store=new MonogramStore(context,DATABASE);
        TLRPC.Message restored=store.getMessage(id,self);
        MessageObject object=new MessageObject(0,restored,false,false);object.setType();
        assertEquals(MessageObject.TYPE_STICKER,object.type);
        assertTrue(object.isSticker());
        String diagnostics=store.debugMessage(id,self);
        assertTrue(diagnostics.contains("Legacy sticker hint: true"));
        assertTrue(diagnostics.contains("TL_documentAttributeSticker"));
        assertTrue(diagnostics.contains("Cached file present: true"));
    }
    @Test public void decryptedDisplayHintProjectsOnlyTheCachedMediaAndSurvivesReopen() throws Exception {
        FileLink link=link("opaque.png");store.attachmentFile(link.wireUrl).delete();XmppMessage model=message("hinted",link,true);int id=Math.abs(store.save(model));
        assertNull(store.sharing(id));assertFalse(MessageObject.isStickerDocument(store.getMessage(id,self).media.document));
        File file=artwork(link);TLRPC.MessageMedia decoded=MonogramAttachments.media(id,1,file,"opaque.png");
        // Cached photo geometry comes from metadata probing; preserve its largest non-square size.
        TLRPC.TL_photoSize large=new TLRPC.TL_photoSize();large.type="x";large.w=320;large.h=160;decoded.photo.sizes.add(large);
        store.attach(id,link.wireUrl,file,decoded);
        assertTrue(MessageObject.isStickerDocument(store.getMessage(id,self).media.document));assertNull(store.sharing(id));
        store.close();store=new MonogramStore(context,DATABASE);
        TLRPC.Message restored=store.getMessage(id,self);assertTrue(MessageObject.isStickerDocument(restored.media.document));
        TLRPC.DocumentAttribute sticker=restored.media.document.attributes.stream().filter(a->a instanceof TLRPC.TL_documentAttributeSticker).findFirst().get();
        assertTrue(sticker.stickerset instanceof TLRPC.TL_inputStickerSetEmpty);
        TLRPC.DocumentAttribute dimensions=restored.media.document.attributes.stream().filter(a->a instanceof TLRPC.TL_documentAttributeImageSize).findFirst().get();
        assertEquals(320,dimensions.w);assertEquals(160,dimensions.h);
        store.save(message("hinted",link,false));assertTrue(MessageObject.isStickerDocument(store.getMessage(id,self).media.document));
        int plain=Math.abs(store.save(message("ordinary",link,false)));store.attach(plain,link.wireUrl,file,MonogramAttachments.media(plain,1,file,"opaque.png"));
        assertFalse(MessageObject.isStickerDocument(store.getMessage(plain,self).media.document));
        FileLink document=link("document.bin");int other=Math.abs(store.save(message("not-a-sticker-image",document,true)));
        File unsupported=store.attachmentFile(document.wireUrl);unsupported.getParentFile().mkdirs();Files.write(unsupported.toPath(),"ordinary document".getBytes("UTF-8"));
        store.attach(other,document.wireUrl,unsupported,MonogramAttachments.media(other,1,unsupported,"document.bin"));
        assertFalse(MessageObject.isStickerDocument(store.getMessage(other,self).media.document));
    }
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test public void opaqueNamedTgsIsValidatedOffThreadBeforePersistedStickerProjection() throws Exception {
        FileLink link=link("opaque.bin");int id=Math.abs(store.save(message("animated",link,true)));File file=store.attachmentFile(link.wireUrl);file.getParentFile().mkdirs();
        try(java.util.zip.GZIPOutputStream output=new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(file))) {
            output.write("{\"v\":\"5.5.2\",\"w\":512,\"h\":512,\"fr\":30,\"ip\":0,\"op\":60,\"layers\":[]}".getBytes("UTF-8"));
        }
        TLRPC.MessageMedia media=MonogramAttachments.media(id,1,file,"opaque.bin");assertNotEquals("application/x-tgsticker",media.document.mime_type);
        // Ordinary GZIP or a non-sticker message must not be reinterpreted as an animation.
        MonogramStickers.inspectDisplayHint(media,file,false);assertNotEquals("application/x-tgsticker",media.document.mime_type);
        MonogramStickers.inspectDisplayHint(media,file,true);assertEquals("application/x-tgsticker",media.document.mime_type);
        store.attach(id,link.wireUrl,file,media);assertTrue(MessageObject.isAnimatedStickerDocument(store.getMessage(id,self).media.document,true));
        store.close();store=new MonogramStore(context,DATABASE);assertTrue(MessageObject.isAnimatedStickerDocument(store.getMessage(id,self).media.document,true));
        try(java.util.zip.GZIPOutputStream output=new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(file))) { output.write("ordinary document".getBytes("UTF-8")); }
        TLRPC.MessageMedia invalid=MonogramAttachments.media(id,1,file,"opaque.bin");invalid.document.mime_type="application/x-tgsticker";MonogramStickers.inspectDisplayHint(invalid,file,true);assertNotEquals("application/x-tgsticker",invalid.document.mime_type);
    }
    @Test public void schemaTwentySixMigrationPreservesAliasesHistoryAndAttachmentsWithoutInventingHints() throws Exception {
        long peer=store.peer(PEER);store.localName(peer,"My friend");FileLink link=link("old.png");int id=Math.abs(store.save(message("old",link,false)));
        File file=artwork(link);store.attach(id,link.wireUrl,file,MonogramAttachments.media(id,1,file,"old.png"));
        // Reconstruct the actual v26 attachment columns, retaining all rows and other tables.
        store.getWritableDatabase().execSQL("ALTER TABLE attachments RENAME TO before_hint");
        store.getWritableDatabase().execSQL("CREATE TABLE attachments(message INTEGER PRIMARY KEY,url TEXT NOT NULL,path TEXT,media BLOB,caption TEXT,sharing TEXT)");
        store.getWritableDatabase().execSQL("INSERT INTO attachments SELECT message,url,path,media,caption,sharing FROM before_hint");
        store.getWritableDatabase().execSQL("DROP TABLE before_hint");store.getWritableDatabase().setVersion(26);store.close();store=new MonogramStore(context,DATABASE);
        assertEquals(27,store.getReadableDatabase().getVersion());assertEquals("My friend",store.localName(peer));assertNotNull(store.getMessage(id,self).media.photo);
        assertFalse(MessageObject.isStickerDocument(store.getMessage(id,self).media.document));assertNull(store.sharing(id));assertTrue(file.isFile());
        store.save(message("old",link,true));assertTrue(MessageObject.isStickerDocument(store.getMessage(id,self).media.document));
    }
}
