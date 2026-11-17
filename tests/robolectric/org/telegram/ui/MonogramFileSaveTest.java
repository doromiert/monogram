package org.telegram.ui;

import static org.junit.Assert.*;
import android.content.Intent;
import android.net.Uri;
import com.negzero.monogram.xmpp.FileTransfer;
import java.io.*;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.telegram.messenger.xmpp.MonogramAttachments;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramFileSaveTest {
    @Test public void documentsStayManualWhileMediaKeepsItsPolicy() {
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = new TLRPC.TL_document();
        for (String mime : new String[]{"application/zip", "text/plain", "application/pdf", "application/octet-stream"}) {
            media.document.mime_type=mime; assertFalse(MonogramAttachments.isMedia(media));
        }
        for (String mime : new String[]{"image/png", "video/mp4", "audio/ogg", "application/x-tgsticker"}) {
            media.document.mime_type=mime; assertTrue(MonogramAttachments.isMedia(media));
        }
        assertFalse(MonogramAttachments.isMedia(null));
        assertTrue(MonogramAttachments.isMedia(new TLRPC.TL_messageMediaPhoto() {{photo=new TLRPC.TL_photo();}}));
    }
    @Test public void pickerKeepsTheOriginalExtensionAndCancellingDoesNotStartATransfer() {
        TLRPC.TL_document document=new TLRPC.TL_document();document.mime_type="application/zip";
        TLRPC.TL_documentAttributeFilename name=new TLRPC.TL_documentAttributeFilename();name.file_name="notes.zip";document.attributes.add(name);
        Intent intent=MonogramFileSave.intent(document);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.getAction());
        assertEquals("application/zip",intent.getType());assertEquals("notes.zip",intent.getStringExtra(Intent.EXTRA_TITLE));
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertTrue(MonogramFileSave.onResult(9471,android.app.Activity.RESULT_CANCELED,null));
        assertFalse(MonogramFileSave.onResult(9472,android.app.Activity.RESULT_CANCELED,null));
    }
    @Test public void explicitSaveCopiesOnlyCachedBytesAndHonorsCancellation() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();
        File directory=new File(context.getFilesDir(),"monogram-attachments/fixture");assertTrue(directory.isDirectory() || directory.mkdirs());
        File file=new File(directory,String.join("",Collections.nCopies(64,"a")));
        byte[] bytes=new byte[140000];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        Files.write(file.toPath(),bytes);
        Uri uri=Uri.parse("content://fixture/document/output");ByteArrayOutputStream output=new ByteArrayOutputStream();
        org.robolectric.Shadows.shadowOf(context.getContentResolver()).registerOutputStream(uri,output);
        MonogramAttachments.copyTo(context,file,uri,new FileTransfer.Job());assertArrayEquals(bytes,output.toByteArray());
        FileTransfer.Job stopped=new FileTransfer.Job();stopped.cancel();
        ByteArrayOutputStream cancelled=new ByteArrayOutputStream();org.robolectric.Shadows.shadowOf(context.getContentResolver()).registerOutputStream(uri,cancelled);
        try {MonogramAttachments.copyTo(context,file,uri,stopped);fail("Cancelled save accepted");}catch(IOException expected){}
        assertEquals(0,cancelled.size());
        File privateFile=new File(context.getFilesDir(),"private-data");Files.write(privateFile.toPath(),bytes);
        try {MonogramAttachments.copyTo(context,privateFile,uri,new FileTransfer.Job());fail("Private data exported");}catch(IOException expected){}
    }
}
