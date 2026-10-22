package org.telegram.messenger;
import static org.junit.Assert.*;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramIconPacksTest.App.class,MonogramIconPacksTest.Device.class,MonogramIconPacksTest.Log.class,MonogramIconPacksTest.Notifications.class})
public class MonogramIconPacksTest {
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App {@Implementation public static void __staticInitializer__(){}}
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Device {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static void runOnUIThread(Runnable action){action.run();}
        @Implementation public static int dp(float value){return Math.round(value);}
    }
    @Implements(value=FileLog.class,isInAndroidSdk=false)
    public static class Log {@Implementation public static void __staticInitializer__(){} @Implementation public static void e(String text){}}
    @Implements(value=NotificationCenter.class,isInAndroidSdk=false)
    public static class Notifications {
        private static final NotificationCenter global=org.robolectric.shadow.api.Shadow.newInstanceOf(NotificationCenter.class);
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static NotificationCenter getGlobalInstance(){return global;}
        @Implementation public void postNotificationName(int id,Object... args){}
    }
    @Before public void setup(){ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();}
    private Uri archive(Map<String,String> entries) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,String> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        Uri uri=Uri.parse("content://fixture/"+UUID.randomUUID());ShadowContentResolver resolver=Shadows.shadowOf(ApplicationLoader.applicationContext.getContentResolver());resolver.registerInputStream(uri,new ByteArrayInputStream(out.toByteArray()));return uri;
    }
    private String install(Uri uri) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();MonogramIconPacks.importPack(uri,value->{error.set(value);done.countDown();});assertTrue("Import callback did not complete",done.await(10,TimeUnit.SECONDS));return error.get();
    }
    @Test public void archiveCannotWriteOutsideItsStagingDirectory() throws Exception {
        String name="escape-"+UUID.randomUUID()+".txt";Map<String,String> files=new LinkedHashMap<>();files.put("../"+name,"outside");
        assertNotNull(install(archive(files)));assertFalse(new File(ApplicationLoader.applicationContext.getFilesDir(),"appearance-icon-packs/"+name).exists());
    }
    @Test public void importedMetadataPersistsAndDuplicateImportPreservesThePack() throws Exception {
        String id="fixture."+UUID.randomUUID();String data="{\"packId\":\""+id+"\",\"packName\":\"Local pack\",\"icons\":{}}";
        assertNull(install(archive(Collections.singletonMap("metadata.json",data))));
        assertTrue(MonogramIconPacks.list().stream().anyMatch(p->p.id.equals(id)&&p.name.equals("Local pack")));
        assertNotNull(install(archive(Collections.singletonMap("metadata.json",data.replace("Local pack","Changed")))));
        assertTrue(MonogramIconPacks.list().stream().anyMatch(p->p.id.equals(id)&&p.name.equals("Local pack")));
    }
    @Test public void metadataCannotReferenceAnIconOutsideThePack() throws Exception {
        String data="{\"packId\":\"fixture.bad\",\"packName\":\"Bad pack\",\"icons\":{\"msg_edit\":\"../secret.png\"}}";
        assertNotNull(install(archive(Collections.singletonMap("metadata.json",data))));
        assertFalse(MonogramIconPacks.list().stream().anyMatch(p->p.id.equals("fixture.bad")));
    }
}
