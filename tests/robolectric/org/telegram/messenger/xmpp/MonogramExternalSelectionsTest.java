package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramMediaDraftsTest.Account.class,MonogramMediaDraftsTest.App.class,MonogramMediaDraftsTest.Ui.class,MonogramMediaDraftsTest.Caption.class,MonogramMediaDraftsTest.Storage.class,MonogramMediaDraftsTest.NativeMessage.class})
public class MonogramExternalSelectionsTest {
    private final MonogramMediaDraftsTest fixture=new MonogramMediaDraftsTest();
    private final ArrayList<String> errors=new ArrayList<>();
    private Context context;
    public static class Documents extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "application/pdf"; }
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) { MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});cursor.addRow(new Object[]{uri.getLastPathSegment()+".pdf"});return cursor; }
        @Override public Uri insert(Uri uri,ContentValues values) { return null; }
        @Override public int delete(Uri uri,String where,String[] args) { return 0; }
        @Override public int update(Uri uri,ContentValues values,String where,String[] args) { return 0; }
    }
    @Before public void setup() { fixture.setup();context=RuntimeEnvironment.getApplication();ShadowContentResolver.registerProviderInternal("draft-fixture",new Documents()); }
    @After public void close() throws Exception { fixture.close(); }
    private Uri document(String name,boolean readable) {
        Uri uri=Uri.parse("content://draft-fixture/"+name);
        if(readable) Shadows.shadowOf(context.getContentResolver()).registerInputStream(uri,new ByteArrayInputStream(new byte[]{1,2,3,4}));return uri;
    }
    private void stage(String token,int request,Intent result) throws Exception {
        CountDownLatch ready=new CountDownLatch(1);assertTrue(MonogramExternalSelections.stage(token,request,result,errors::add,ready::countDown));assertTrue(ready.await(10,TimeUnit.SECONDS));
    }
    private MonogramMediaDrafts.Restored restore(int account,long dialog) throws Exception {
        MonogramMediaDrafts.Session session=MonogramMediaDrafts.bind(account,dialog,errors::add);CountDownLatch ready=new CountDownLatch(1);AtomicReference<MonogramMediaDrafts.Restored> restored=new AtomicReference<>();
        session.restore(value->{restored.set(value);ready.countDown();});assertTrue(ready.await(10,TimeUnit.SECONDS));return restored.get();
    }
    @Test public void launchOwnerRequestAndLogoutGenerationMustMatch() {
        String token=MonogramExternalSelections.launch(0,42,21,null);assertNotNull(token);assertTrue(MonogramExternalSelections.matches(token,21,0,42));assertFalse(MonogramExternalSelections.matches(token,1,0,42));assertFalse(MonogramExternalSelections.matches(token,21,1,42));assertFalse(MonogramExternalSelections.matches(token,21,0,43));
        MonogramMediaDrafts.Owner owner=MonogramExternalSelections.owner(token,21);MonogramMediaDrafts.logout(0,owner.jid);assertNull(MonogramExternalSelections.owner(token,21));
    }
    @Test public void safMultipleDocumentsKeepProviderFilenamesAndBecomeNativeSelections() throws Exception {
        Uri a=document("first",true),b=document("second",true);Intent result=new Intent().setData(a);ClipData clip=ClipData.newRawUri("files",a);clip.addItem(new ClipData.Item(b));result.setClipData(clip);
        String token=MonogramExternalSelections.launch(0,42,21,null);stage(token,21,result);MonogramMediaDrafts.Restored restored=restore(0,42);
        assertEquals("document",restored.kind);assertEquals(2,restored.files.size());assertEquals("first.pdf",new File(restored.files.get(0)).getName());assertEquals("second.pdf",new File(restored.files.get(1)).getName());assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(new File(restored.files.get(0)).toPath()));assertNull(MonogramExternalSelections.owner(token,21));
    }
    @Test public void inaccessibleUriRetainsRecoverableMetadataAndReportsFailure() throws Exception {
        Uri missing=document("missing",false);String token=MonogramExternalSelections.launch(0,42,21,null);stage(token,21,new Intent().setData(missing));
        MonogramMediaDrafts.Restored restored=restore(0,42);assertEquals(0,restored.files.size());assertEquals(1,restored.missing);assertFalse(errors.isEmpty());
    }
    @Test public void cameraOutputPathSurvivesLaunchPersistenceAndHasPrivateRecoveryCopy() throws Exception {
        File output=File.createTempFile("camera-",".jpg",context.getCacheDir());Files.write(output.toPath(),new byte[]{5,6,7});String token=MonogramExternalSelections.launch(0,42,0,output.getAbsolutePath());assertEquals(output.getAbsolutePath(),MonogramExternalSelections.cameraPath(token,0));
        stage(token,0,null);assertTrue(output.delete());MonogramMediaDrafts.Restored restored=restore(0,42);assertEquals("photo",restored.kind);assertEquals(1,restored.photos.size());assertTrue(new File(restored.photos.get(0).path).isFile());assertFalse(output.getAbsolutePath().equals(restored.photos.get(0).path));
    }
    @Test public void returnedSelectionForAnotherConversationIsNeverImportedIntoCurrentOwner() throws Exception {
        Uri uri=document("old-conversation",true);String token=MonogramExternalSelections.launch(0,42,21,null);assertFalse(MonogramExternalSelections.matches(token,21,0,43));stage(token,21,new Intent().setData(uri));
        assertTrue(restore(0,43).files.isEmpty());assertTrue(restore(1,42).files.isEmpty());assertEquals(1,restore(0,42).files.size());
    }
    @Test public void unsupportedSchemesAndOwnPrivateProviderAreRejectedBeforeSnapshot() {
        String token=MonogramExternalSelections.launch(0,42,21,null);assertFalse(MonogramExternalSelections.stage(token,21,new Intent().setData(Uri.parse("https://example.org/file")),errors::add,()->fail("No ready callback")));
        Uri internal=Uri.parse("content://"+org.telegram.messenger.ApplicationLoader.getApplicationId()+".provider/private-file");assertFalse(MonogramExternalSelections.stage(token,21,new Intent().setData(internal),errors::add,()->fail("No ready callback")));Uri privateFile=Uri.fromFile(new File(context.getFilesDir(),"private-credentials"));assertFalse(MonogramExternalSelections.stage(token,21,new Intent().setData(privateFile),errors::add,()->fail("No ready callback")));assertFalse(errors.isEmpty());
    }
    private void awaitWrites() throws Exception { ((ExecutorService)org.robolectric.util.ReflectionHelpers.getStaticField(MonogramMediaDrafts.class,"WRITER")).submit(()->{}).get(10,TimeUnit.SECONDS); }
    @Test public void incompatibleResultKeepsExistingKindCaptionAndRecoversAfterExplicitDiscard() throws Exception {
        File original=File.createTempFile("selected-",".jpg",context.getCacheDir());Files.write(original.toPath(),new byte[]{1,2,3});
        org.telegram.messenger.MediaController.PhotoEntry photo=new org.telegram.messenger.MediaController.PhotoEntry(0,1,0,original.getAbsolutePath(),0,0,false,10,10,3);
        MonogramMediaDrafts.Session session=MonogramMediaDrafts.bind(0,42,errors::add);Map<Object,Object> selected=new HashMap<>();selected.put(1,photo);session.save(selected,Collections.singletonList((Object)1),"Keep this caption",false,false);awaitWrites();byte[] before=Files.readAllBytes(session.owner.manifest().getBaseFile().toPath());
        String token=MonogramExternalSelections.launch(0,42,21,null);Intent returned=new Intent().setData(document("deferred",true));assertFalse(MonogramExternalSelections.stage(token,21,returned,errors::add,()->fail("Conflicting result cannot open")));
        assertTrue(session.active());assertArrayEquals(before,Files.readAllBytes(session.owner.manifest().getBaseFile().toPath()));MonogramMediaDrafts.Restored old=restore(0,42);assertEquals(1,old.photos.size());assertEquals("Keep this caption",old.caption);assertTrue(old.files.isEmpty());
        MonogramMediaDrafts.bind(0,42,errors::add).discard();CountDownLatch ready=new CountDownLatch(1);assertTrue(MonogramExternalSelections.recoverWhenEmpty(0,42,errors::add,ready::countDown));assertTrue(ready.await(10,TimeUnit.SECONDS));MonogramMediaDrafts.Restored recovered=restore(0,42);assertEquals("document",recovered.kind);assertEquals(1,recovered.files.size());assertEquals("",recovered.caption);
    }
    @Test public void sameKindResultCannotReplaceBatchAwaitingNativeHandoff() throws Exception {
        File original=File.createTempFile("pending-",".txt",context.getCacheDir());Files.write(original.toPath(),new byte[]{7,8,9});MonogramMediaDrafts.Session session=MonogramMediaDrafts.bind(0,42,errors::add);
        session.saveFiles("document",Collections.singletonList(original.getAbsolutePath()),null,"Queued caption",false,true);awaitWrites();byte[] before=Files.readAllBytes(session.owner.manifest().getBaseFile().toPath());
        String token=MonogramExternalSelections.launch(0,42,21,null);assertFalse(MonogramExternalSelections.stage(token,21,new Intent().setData(document("later",true)),errors::add,()->fail("Pending batch must remain intact")));
        assertTrue(session.active());assertArrayEquals(before,Files.readAllBytes(session.owner.manifest().getBaseFile().toPath()));assertNotNull(MonogramExternalSelections.owner(token,21));assertFalse(MonogramMediaDrafts.captureFileHandoff(0,42,Collections.singletonList(original.getAbsolutePath())).isEmpty());
        assertFalse(MonogramExternalSelections.recoverWhenEmpty(0,42,errors::add,()->fail("Must finish/discard first")));assertEquals("Queued caption",restore(0,42).caption);
    }
}
