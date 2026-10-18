package org.telegram.messenger.xmpp;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.*;
import java.nio.file.Files;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramStickerExportTest {
    static final byte[] PNG=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
    public static class Gallery extends ContentProvider {
        ContentValues inserted,updated;Uri collection;File output;boolean deleted,fail;
        public boolean onCreate(){return true;}
        public String getType(Uri uri){return "image/png";}
        public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
        public Uri insert(Uri uri,ContentValues values){collection=uri;inserted=new ContentValues(values);return Uri.withAppendedPath(uri,"7");}
        public int update(Uri uri,ContentValues values,String selection,String[] args){updated=new ContentValues(values);return 1;}
        public int delete(Uri uri,String selection,String[] args){deleted=true;return 1;}
        public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
            if(fail) throw new FileNotFoundException("fixture failure");
            return ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE|ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
    }
    Gallery gallery;Context context;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();gallery=new Gallery();ProviderInfo info=new ProviderInfo();info.authority="media";
        gallery.attachInfo(context,info);gallery.output=File.createTempFile("gallery-test-",".bin",context.getCacheDir());
        ShadowContentResolver.registerProviderInternal("media",gallery);
    }
    @Test public void staticStickerKeepsItsBytesAndPublishesOnlyAfterTheCopy() throws Exception {
        File source=File.createTempFile("source-",".bin",context.getCacheDir());Files.write(source.toPath(),PNG);
        File prepared=MonogramStickerExport.prepare(source,"image/png",context.getCacheDir());
        try {
            assertTrue(prepared.getName().endsWith(".png"));assertArrayEquals(PNG,Files.readAllBytes(prepared.toPath()));
            assertNotNull(MonogramStickerExport.publish(context,prepared));assertArrayEquals(PNG,Files.readAllBytes(gallery.output.toPath()));
            assertEquals("image/png",gallery.inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE));
            assertEquals("Pictures/Monogram",gallery.inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            assertEquals(Integer.valueOf(1),gallery.inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertEquals(Integer.valueOf(0),gallery.updated.getAsInteger(MediaStore.MediaColumns.IS_PENDING));assertFalse(gallery.deleted);
        } finally {prepared.delete();}
    }
    @Test public void videoGoesToVideosAndFailedWritesRemoveThePendingEntry() throws Exception {
        File source=File.createTempFile("video-",".webm",context.getCacheDir());Files.write(source.toPath(),new byte[]{26,69,(byte)223,(byte)163,0,0,0,0});
        gallery.fail=true;
        try {MonogramStickerExport.publish(context,source);fail();} catch(IOException expected){}
        assertTrue(gallery.deleted);assertNull(gallery.updated);
        assertEquals("video/webm",gallery.inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals("Movies/Monogram",gallery.inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
    }
}
