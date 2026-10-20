package org.telegram.messenger.xmpp;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import androidx.collection.LongSparseArray;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramAudioSourcesTest.Base.class,MonogramAudioSourcesTest.User.class,MonogramAudioSourcesTest.Message.class})
public class MonogramAudioSourcesTest {
    @Implements(value=BaseController.class,isInAndroidSdk=false)
    public static class Base { @Implementation public void __constructor__(int account) {} }
    @Implements(value=UserConfig.class,isInAndroidSdk=false)
    public static class User extends Base {
        @Implementation public void __constructor__(int account) {}
        @Implementation public static UserConfig getInstance(int account) {
            return ReflectionHelpers.callConstructor(UserConfig.class,ReflectionHelpers.ClassParameter.from(int.class,account));
        }
        @Implementation public long getClientUserId() { return 7; }
    }
    @Implements(value=MessageObject.class,isInAndroidSdk=false)
    public static class Message {
        @RealObject MessageObject real;
        @Implementation public void __constructor__(int account,TLRPC.Message message,MessageObject reply,AbstractMap<Long,TLRPC.User> users,
                AbstractMap<Long,TLRPC.Chat> chats,LongSparseArray<TLRPC.User> sparseUsers,LongSparseArray<TLRPC.Chat> sparseChats,
                boolean layout,boolean media,long event,boolean repost,boolean repostVideo,boolean saved,int search) {
            real.currentAccount=account;real.messageOwner=message;
        }
    }
    public static class AudioProvider extends ContentProvider {
        File source;int opens;boolean deny;String[] projection;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri,String[] columns,String selection,String[] args,String order) {
            projection=columns;
            assertFalse(Arrays.asList(columns).contains(MediaStore.Audio.Media.DATA));
            MatrixCursor cursor=new MatrixCursor(columns);
            cursor.addRow(new Object[]{42L,"Artist","Song",125000L,"Album","song.mp3","audio/mpeg",5L});
            cursor.addRow(new Object[]{43L,"Artist","Unknown size",1000L,"Album","other.flac","application/octet-stream",-1L});
            cursor.addRow(new Object[]{44L,"Artist","Too large",1000L,"Album","huge.mp3","audio/mpeg",com.negzero.monogram.xmpp.FileTransfer.MAX_BYTES+1});
            return cursor;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
            opens++;if(deny) throw new SecurityException("Permission revoked");
            return ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return "audio/mpeg"; }
        @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
    }
    AudioProvider provider;Uri uri=Uri.parse("content://media/external/audio/media/42");
    @Before public void setup() throws Exception {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        provider=new AudioProvider();android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="media";
        info.applicationInfo=RuntimeEnvironment.getApplication().getApplicationInfo();provider.attachInfo(RuntimeEnvironment.getApplication(),info);
        provider.source=new File(RuntimeEnvironment.getApplication().getCacheDir(),"audio-source.mp3");Files.write(provider.source.toPath(),new byte[]{1,2,3,4,5});
        ShadowContentResolver.registerProviderInternal("media",provider);
    }
    @After public void cleanup() {
        for(int account=0;account<2;account++) {
            File directory=directory(account);File[] files=directory.listFiles();if(files!=null) for(File file:files) file.delete();directory.delete();
        }
        provider.source.delete();
    }
    File directory(int account) { return new File(RuntimeEnvironment.getApplication().getCacheDir(),"monogram-audio-"+account); }
    @Test public void indexedAudioWithoutDataStaysAsUriAndDoesNotOpenOrCopyTheLibrary() {
        ArrayList<MediaController.AudioEntry> entries=MonogramAudioSources.query(1);
        assertEquals(2,entries.size());assertEquals(0,provider.opens);assertFalse(directory(1).exists());
        MediaController.AudioEntry entry=entries.get(0);assertEquals(uri,entry.uri);assertEquals(uri.toString(),entry.path);
        assertEquals(1,entry.messageObject.currentAccount);assertTrue(entry.messageObject.attachPathExists);assertEquals(5,entry.size);
        assertNotEquals(entry.messageObject.getId(),entries.get(1).messageObject.getId());assertTrue(entry.messageObject.getId()<0);
        TLRPC.Message message=entry.messageObject.messageOwner;
        assertEquals(uri.toString(),message.attachPath);assertEquals("audio/mpeg",message.media.document.mime_type);
        TLRPC.TL_documentAttributeAudio audio=(TLRPC.TL_documentAttributeAudio)message.media.document.attributes.get(0);
        assertEquals("Song",audio.title);assertEquals("Artist",audio.performer);assertEquals(125,(int)audio.duration);
        assertEquals("song.mp3",((TLRPC.TL_documentAttributeFilename)message.media.document.attributes.get(1)).file_name);
        assertEquals(0,entries.get(1).size);assertEquals("audio/flac",entries.get(1).mimeType);
    }
    @Test public void selectedCopyIsPrivatePerAccountFreshBoundedAndCleansFailedOrRevokedReads() throws Exception {
        File first=MonogramAudioSources.materialize(0,uri,"song.mp3",5,() -> false);
        File second=MonogramAudioSources.materialize(1,uri,"song.mp3",5,() -> false);
        assertEquals(directory(0).getCanonicalFile(),first.getParentFile().getCanonicalFile());
        assertEquals(directory(1).getCanonicalFile(),second.getParentFile().getCanonicalFile());assertNotEquals(first,second);
        assertArrayEquals(new byte[]{1,2,3,4,5},Files.readAllBytes(first.toPath()));
        assertThrows(IOException.class,() -> MonogramAudioSources.materialize(0,uri,"song.mp3",4,() -> false));
        assertEquals(1,directory(0).list().length);
        provider.deny=true;
        assertThrows(SecurityException.class,() -> MonogramAudioSources.materialize(0,uri,"song.mp3",5,() -> false));
        assertEquals(1,directory(0).list().length);
    }
    @Test public void cancellationStopsBeforeSourceOpenAndDuringCopyWithoutPublishingBytes() throws Exception {
        assertThrows(InterruptedIOException.class,() -> MonogramAudioSources.materialize(0,uri,"song.mp3",5,() -> true));assertEquals(0,provider.opens);
        AtomicBoolean cancelled=new AtomicBoolean();ByteArrayOutputStream output=new ByteArrayOutputStream();
        InputStream input=new ByteArrayInputStream(new byte[]{1,2,3}) {
            @Override public synchronized int read(byte[] bytes,int offset,int length) { int count=super.read(bytes,offset,length);cancelled.set(true);return count; }
        };
        assertThrows(InterruptedIOException.class,() -> MonogramAudioSources.copy(input,output,3,cancelled::get));assertEquals(0,output.size());
        assertThrows(IOException.class,() -> MonogramAudioSources.copy(new ByteArrayInputStream(new byte[0]),output,3,() -> false));
    }
    @Test public void sourceValidationAndFilenameLimitsKeepProviderUrisAndExtensionsIntact() {
        assertTrue(MonogramAudioSources.isAudioSource(uri.toString()));
        for(String path:new String[]{null,"/sdcard/music.mp3","content://other/external/audio/media/42","content://media/external/video/media/42","content://media/external/audio/media/42?token=x","content://media/external/audio/media/-1"})
            assertFalse(MonogramAudioSources.isAudioSource(path));
        String name=MonogramAudioSources.filename(String.join("",Collections.nCopies(240,"a"))+".flac");
        assertEquals(200,name.length());assertTrue(name.endsWith(".flac"));assertEquals(".._unsafe_name.mp3",MonogramAudioSources.filename("../unsafe\\name.mp3"));
        assertNull(MonogramAudioSources.cover("https://example.org/cover",128));
    }
}
