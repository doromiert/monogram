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
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramIconPacksTest.App.class,MonogramIconPacksTest.Device.class,MonogramIconPacksTest.Log.class,MonogramIconPacksTest.Notifications.class,MonogramIconPacksTest.Themes.class,MonogramIconPacksTest.ResourceNames.class,MonogramIconPacksTest.Lite.class})
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
    @Implements(value=org.telegram.ui.ActionBar.Theme.class,isInAndroidSdk=false)
    public static class Themes {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static void reloadAllResources(android.content.Context context){}
    }
    @Implements(android.content.res.Resources.class)
    public static class ResourceNames extends org.robolectric.shadows.ShadowResources {
        @Implementation protected int getIdentifier(String name,String type,String pkg){
            try{return Class.forName(R.class.getName()+"$"+type).getField(name).getInt(null);}catch(Exception missing){return 0;}
        }
    }
    @Implements(value=LiteMode.class,isInAndroidSdk=false)
    public static class Lite {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static boolean isEnabled(int flag){return false;}
    }
    @Before public void setup(){ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();}
    private Uri archive(Map<String,String> entries) throws Exception {
        Map<String,byte[]> bytes=new LinkedHashMap<>();for(Map.Entry<String,String> e:entries.entrySet())bytes.put(e.getKey(),e.getValue().getBytes(StandardCharsets.UTF_8));return archiveBytes(bytes);
    }
    private Uri archiveBytes(Map<String,byte[]> entries) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
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
    private Uri icons(String id,org.json.JSONObject mappings)throws Exception {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDPsAAAAASUVORK5CYII=");
        Map<String,byte[]> files=new LinkedHashMap<>();files.put("metadata.json",new org.json.JSONObject().put("packId",id).put("packName",id).put("icons",mappings).toString().getBytes(StandardCharsets.UTF_8));files.put("icon.png",png);return archiveBytes(files);
    }
    @Test public void catalogIncludesUnfilteredResourcesAnimationsAndPaintedControlStates(){
        List<String> names=MonogramIconPacks.resourceNames();assertTrue(names.contains("device_desktop_other"));assertTrue(names.contains("raw.camera_flip"));assertTrue(names.contains("ui.BackDrawable.back"));assertTrue(names.contains("ui.MediaActionDrawable.download"));assertTrue(names.contains("ui.Switch.checked"));
    }
    @Test public void validRasterOverridesReachResourcesLottieAndCustomDrawablesWithoutChangingIntrinsicSize()throws Exception {
        String id="fixture.complete."+UUID.randomUUID();org.json.JSONObject mappings=new org.json.JSONObject().put("msg_edit","icon.png").put("raw.camera_flip","icon.png").put("ui.BackDrawable.back","icon.png");
        assertNull(install(icons(id,mappings)));
        android.graphics.drawable.ColorDrawable original=new android.graphics.drawable.ColorDrawable() {public int getIntrinsicWidth(){return 37;}public int getIntrinsicHeight(){return 19;}};
        android.graphics.drawable.Drawable replacement=MonogramIconPacks.drawable(ApplicationLoader.applicationContext.getResources(),R.drawable.msg_edit,original);
        assertNotSame(original,replacement);assertEquals(37,replacement.getIntrinsicWidth());assertEquals(19,replacement.getIntrinsicHeight());
        android.graphics.Canvas canvas=new android.graphics.Canvas();android.graphics.RectF bounds=new android.graphics.RectF(0,0,24,24);
        assertTrue(MonogramIconPacks.drawResource(canvas,R.raw.camera_flip,bounds,null));
        original.setBounds(0,0,24,24);int layer=MonogramIconPacks.beginCustom(original,canvas,"ui.BackDrawable.back");assertTrue(layer>=0);MonogramIconPacks.finishCustom(original,canvas,"ui.BackDrawable.back",layer);
        assertFalse(MonogramIconPacks.drawCustom(original,canvas,"ui.missing"));
    }
    @Test public void rawSvgReplacementRetainsItsTintAndFadeAlpha()throws Exception {
        assertNull(install(icons("fixture.svg."+UUID.randomUUID(),new org.json.JSONObject().put("raw.camera_flip","icon.png"))));
        SvgHelper.SvgDrawable svg=new SvgHelper.SvgDrawable();org.robolectric.util.ReflectionHelpers.setField(svg,"packResourceId",R.raw.camera_flip);svg.setBounds(0,0,24,24);
        android.graphics.ColorFilter tint=new android.graphics.PorterDuffColorFilter(android.graphics.Color.RED,android.graphics.PorterDuff.Mode.SRC_IN);svg.setColorFilter(tint);svg.setAlpha(64);svg.draw(new android.graphics.Canvas());
        android.graphics.Paint paint=org.robolectric.util.ReflectionHelpers.getField(svg,"packPaint");assertEquals(64,paint.getAlpha());assertSame(tint,paint.getColorFilter());
    }
    @Test public void nativeArrowAnimationStillAdvancesWhenItsArtworkIsReplaced()throws Exception {
        assertNull(install(icons("fixture.animation."+UUID.randomUUID(),new org.json.JSONObject().put("ui.BackDrawable.close","icon.png"))));
        org.telegram.ui.ActionBar.BackDrawable arrow=new org.telegram.ui.ActionBar.BackDrawable(false);arrow.setColor(android.graphics.Color.RED);arrow.setRotation(1,true);
        org.robolectric.util.ReflectionHelpers.setField(arrow,"lastFrameTime",System.currentTimeMillis()-30L);
        android.graphics.Canvas canvas=new android.graphics.Canvas();int before=canvas.getSaveCount();arrow.draw(canvas);
        float rotation=org.robolectric.util.ReflectionHelpers.getField(arrow,"currentRotation");assertTrue(rotation>0 && rotation<1);assertEquals(before,canvas.getSaveCount());
    }
    @Test public void disablingTheWinningPackFallsBackToTheNextPackAndThenToStock()throws Exception {
        String first="fixture.first."+UUID.randomUUID(),second="fixture.second."+UUID.randomUUID();org.json.JSONObject mappings=new org.json.JSONObject().put("ui.BackDrawable","icon.png");
        assertNull(install(icons(first,mappings)));assertNull(install(icons(second,mappings)));MonogramIconPacks.order(Arrays.asList(first,second));
        CountDownLatch loaded=new CountDownLatch(1);MonogramIconPacks.reload(loaded::countDown);assertTrue(loaded.await(10,TimeUnit.SECONDS));
        Map<?,?> custom=org.robolectric.util.ReflectionHelpers.getStaticField(MonogramIconPacks.class,"customIcons");assertTrue(((File)custom.get("ui.BackDrawable")).getPath().contains(first));
        for(String id:new String[]{first,second}){
            MonogramIconPacks.Pack pack=MonogramIconPacks.list().stream().filter(p->p.id.equals(id)).findFirst().get();CountDownLatch done=new CountDownLatch(1);MonogramIconPacks.enable(pack,false,error->{assertNull(error);done.countDown();});assertTrue(done.await(10,TimeUnit.SECONDS));
            custom=org.robolectric.util.ReflectionHelpers.getStaticField(MonogramIconPacks.class,"customIcons");if(id.equals(first))assertTrue(((File)custom.get("ui.BackDrawable")).getPath().contains(second));else assertFalse(custom.containsKey("ui.BackDrawable"));
        }
    }
    @Test public void largePacksKeepEveryMappingWhileRasterMemoryIsBounded()throws Exception {
        org.json.JSONObject mappings=new org.json.JSONObject();for(int i=0;i<6000;i++)mappings.put("ui.fixture"+i,"icon.png");
        assertNull(install(icons("fixture.large."+UUID.randomUUID(),mappings)));
        Map<?,?> custom=org.robolectric.util.ReflectionHelpers.getStaticField(MonogramIconPacks.class,"customIcons");assertTrue(custom.containsKey("ui.fixture5999"));
        android.util.LruCache<?,?> cache=org.robolectric.util.ReflectionHelpers.getStaticField(MonogramIconPacks.class,"bitmaps");assertTrue(cache.size()<=24*1024*1024);
    }
    @Test public void malformedRasterAndExternalSvgEntitiesAreRejectedBeforeInstallation()throws Exception {
        for(String payload:new String[]{"not an image","<!DOCTYPE svg [<!ENTITY test SYSTEM 'file:///private'>]><svg xmlns='http://www.w3.org/2000/svg'>&test;</svg>"}){
            String id="fixture.invalid."+UUID.randomUUID();String file=payload.startsWith("<!")?"icon.svg":"icon.png";
            Map<String,String> entries=new LinkedHashMap<>();entries.put("metadata.json",new org.json.JSONObject().put("packId",id).put("packName",id).put("icons",new org.json.JSONObject().put("msg_edit",file)).toString());entries.put(file,payload);
            assertNotNull(install(archive(entries)));assertFalse(MonogramIconPacks.list().stream().anyMatch(p->p.id.equals(id)));
        }
    }
}
