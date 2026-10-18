package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramPackArchiveTest {
    private static final MonogramPackArchive.Check CHECK = () -> {};
    private static final MonogramPackArchive.Progress PROGRESS = (name,done,total) -> {};
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
    @Test public void namedSnapshotRoundTripsWithoutNativeIdsCredentialsOrConnectionPolicy() throws Exception {
        File directory=Files.createTempDirectory("xmsp-roundtrip").toFile();
        MonogramPacks.Pack original=fixture(directory); original.privatePack=false; original.connected=true;
        File archive=new File(directory,"pack.xmsp"); MonogramPackArchive.write(original,directory,archive,CHECK);
        File stage=new File(directory,"stage"); assertTrue(stage.mkdir());
        MonogramPacks.Pack restored=MonogramPackArchive.read(archive,stage,CHECK,PROGRESS);
        assertEquals("Friends",restored.name);assertEquals("Alice laughing",restored.items.get(0).name);assertEquals("😂",restored.items.get(0).emoji);
        assertEquals(0,restored.id);assertEquals(0,restored.items.get(0).id);assertNull(restored.source);
        assertTrue(restored.privatePack);assertFalse(restored.connected);
        assertArrayEquals(PNG,Files.readAllBytes(new File(stage,restored.items.get(0).file).toPath()));
        try(ZipFile zip=new ZipFile(archive)) {
            String manifest=new String(MonogramPackArchive.read(zip.getInputStream(zip.getEntry("manifest.json")),1024*1024,CHECK),"UTF-8");
            assertFalse(manifest.contains("token"));assertFalse(manifest.contains("source"));assertFalse(manifest.contains("privatePack"));
            assertFalse(manifest.contains(Long.toString(original.id)));assertEquals(2,zip.size());
        }
    }
    @Test public void unsafeSnapshotsAreRejectedBeforeAnyArtworkIsInstalled() throws Exception {
        File directory=Files.createTempDirectory("xmsp-invalid").toFile();File valid=new File(directory,"valid.xmsp");
        MonogramPackArchive.write(fixture(directory),directory,valid,CHECK);
        LinkedHashMap<String,byte[]> entries=new LinkedHashMap<>();
        try(ZipFile zip=new ZipFile(valid)) { Enumeration<? extends ZipEntry> all=zip.entries();while(all.hasMoreElements()){ZipEntry entry=all.nextElement();entries.put(entry.getName(),MonogramPackArchive.read(zip.getInputStream(entry),1024*1024,CHECK));} }
        for(int mode=0;mode<5;mode++) {
            LinkedHashMap<String,byte[]> modified=new LinkedHashMap<>(entries);
            JSONObject manifest=new JSONObject(new String(modified.get("manifest.json"),"UTF-8"));
            if(mode==0) modified.put("../escape.png",PNG);
            if(mode==1) manifest.getJSONArray("items").getJSONObject(0).put("sha256",String.join("",Collections.nCopies(64,"0")));
            if(mode==2) manifest.getJSONArray("items").put(manifest.getJSONArray("items").getJSONObject(0));
            if(mode==3) modified.remove(new ArrayList<>(entries.keySet()).get(1));
            modified.put("manifest.json",manifest.toString().getBytes("UTF-8"));
            File bad=new File(directory,"bad"+mode+".xmsp");writeZip(bad,modified,mode==4);
            File stage=new File(directory,"stage"+mode);assertTrue(stage.mkdir());
            try { MonogramPackArchive.read(bad,stage,CHECK,PROGRESS);fail("Accepted unsafe mode "+mode); } catch(Exception expected) { }
            assertEquals(0,Objects.requireNonNull(stage.list()).length);
        }
    }
    @Test public void telegramFailuresExposeOnlyRedactedCategoriesAndTheExactReportedLinkParses() throws Exception {
        assertEquals("NeuAnaxKulupeNaklejei",MonogramPacks.parseLink("https://t.me/addstickers/NeuAnaxKulupeNaklejei").name);
        for(int code:new int[]{400,401,403,404,413,429,503}) {
            JSONObject error=new JSONObject().put("error_code",code).put("description","secret_bot_token https://private.example/artwork");
            String diagnostic=MonogramPacks.telegramError(code,error).getMessage();
            assertTrue(diagnostic.contains("HTTP "+code));assertFalse(diagnostic.contains("secret_bot_token"));assertFalse(diagnostic.contains("https://"));
        }
        assertTrue(MonogramPacks.telegramError(400,new JSONObject().put("error_code",400).put("description","Bad Request: STICKERSET_INVALID")).getMessage().contains("could not find"));
        assertTrue(MonogramPacks.telegramError(400,new JSONObject().put("error_code",400).put("description","Bad Request: file is too big")).getMessage().contains("size limit"));
    }
    @Test public void portablePacksAllowMoreThanTwoHundredNamedStickers() throws Exception {
        File directory=Files.createTempDirectory("many-stickers").toFile();
        MonogramPacks.Pack pack=fixture(directory);
        for(int i=1;i<260;i++) {
            MonogramPacks.Item item=new MonogramPacks.Item();item.id=0x4d50000200000000L+i;
            item.name="Sticker "+i;item.emoji="🙂";item.mime="image/png";item.file="opaque.png";item.width=item.height=1;
            pack.items.add(item);
        }
        File archive=new File(directory,"many.xmsp");MonogramPackArchive.write(pack,directory,archive,CHECK);
        File staging=new File(directory,"restored");assertTrue(staging.mkdir());
        assertEquals(260,MonogramPackArchive.read(archive,staging,CHECK,PROGRESS).items.size());
    }
    private static MonogramPacks.Pack fixture(File directory) throws Exception {
        Files.write(new File(directory,"opaque.png").toPath(),PNG);
        MonogramPacks.Pack pack=new MonogramPacks.Pack();pack.id=0x4d50000100000001L;pack.name="Friends";pack.source="https://t.me/addstickers/NotExported";
        MonogramPacks.Item item=new MonogramPacks.Item();item.id=0x4d50000100000002L;item.name="Alice laughing";item.emoji="😂";item.mime="image/png";item.file="opaque.png";item.width=item.height=1;pack.items.add(item);return pack;
    }
    private static void writeZip(File target,LinkedHashMap<String,byte[]> entries,boolean deflated) throws Exception {
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(target))) {
            for(Map.Entry<String,byte[]> value:entries.entrySet()) {
                ZipEntry entry=new ZipEntry(value.getKey());byte[] data=value.getValue();
                if(!deflated){CRC32 crc=new CRC32();crc.update(data);entry.setMethod(ZipEntry.STORED);entry.setSize(data.length);entry.setCompressedSize(data.length);entry.setCrc(crc.getValue());}
                zip.putNextEntry(entry);zip.write(data);zip.closeEntry();
            }
        }
    }
}
