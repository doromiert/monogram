package com.negzero.monogram.xmpp;

import java.io.File;
import java.nio.file.Files;
import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The actual Android writer's full-resolution JPEG + MP4 is one authenticated transfer object. */
class MotionPhotoTransferTest extends FileTransferRecoveryTest {
    @Test void completeMotionContainerSurvivesEncryptedSingleUseRetryAndAuthenticatedDownload() throws Exception {
        byte[] complete=Files.readAllBytes(new File("../tests/fixtures/motion-photo/fullMP.jpg").toPath());
        File source=new File(directory,"originalMP.jpg");Files.write(source.toPath(),complete);
        Http occupied=new Http();occupied.status=409;Http accepted=new Http();accepted.status=201;
        Connection connection=new Connection(new Sequence(occupied,accepted));
        FileLink link=FileTransfer.upload(connection,source,"originalMP.jpg","image/jpeg",true,new FileTransfer.Job(),null);
        assertEquals(2,connection.slotRequests);
        assertArrayEquals(occupied.uploaded.toByteArray(),accepted.uploaded.toByteArray());
        assertArrayEquals(complete,crypt(accepted.uploaded.toByteArray(),link,Cipher.DECRYPT_MODE));
        Http get=new Http();get.response=accepted.uploaded.toByteArray();get.length=Integer.toString(get.response.length);
        File destination=new File(directory,"verified-motion");
        assertTrue(FileTransfer.download(transport(link,get),destination,new FileTransfer.Job(),null,null));
        assertArrayEquals(complete,Files.readAllBytes(destination.toPath()));
        get.response[get.response.length-1]^=1;
        assertEquals("AUTHENTICATION",assertThrows(FileTransfer.Failure.class,()->FileTransfer.download(
                transport(link,get),destination,new FileTransfer.Job(),null,null)).reason);
        assertArrayEquals(complete,Files.readAllBytes(destination.toPath()));
    }
}
