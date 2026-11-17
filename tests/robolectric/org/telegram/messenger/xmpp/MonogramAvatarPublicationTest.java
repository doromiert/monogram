package org.telegram.messenger.xmpp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.negzero.monogram.xmpp.XmppProfile;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramAvatarPublicationTest {
    @Test public void noisyPhotosFitTheWireBudgetAndRemainValidPng() throws Exception {
        Bitmap image=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
        int[] pixels=new int[512*512];Random random=new Random(42);
        for(int i=0;i<pixels.length;i++) pixels[i]=0xff000000 | random.nextInt(1<<24);
        image.setPixels(pixels,0,512,0,0,512,512);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));image.recycle();
        byte[] original=output.toByteArray();assertTrue(original.length>256*1024);
        byte[] png=MonogramProfiles.publicAvatar(original);
        assertTrue(png.length<=XmppProfile.MAX_PUBLIC_AVATAR_BYTES);
        Bitmap decoded=BitmapFactory.decodeByteArray(png,0,png.length);
        assertNotNull(decoded);assertTrue(decoded.getWidth()>1);assertTrue(decoded.getWidth()<512);
        assertEquals(decoded.getWidth(),decoded.getHeight());decoded.recycle();
        // Local contact overrides retain their independent, higher-resolution allowance.
        byte[] local=MonogramProfiles.png(original);assertTrue(local.length>png.length);
    }
    @Test public void smallTransparentIconsAndRemovalRemainSupported() throws Exception {
        Bitmap image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);image.setPixel(1,1,0xffff0000);
        ByteArrayOutputStream output=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,output);image.recycle();
        byte[] png=MonogramProfiles.publicAvatar(output.toByteArray());
        Bitmap decoded=BitmapFactory.decodeByteArray(png,0,png.length);
        assertNotNull(decoded);assertEquals(32,decoded.getWidth());assertEquals(0,decoded.getPixel(0,0));
        assertEquals(0xffff0000,decoded.getPixel(1,1));decoded.recycle();
        assertEquals(0,MonogramProfiles.publicAvatar(new byte[0]).length);
        assertThrows(IllegalArgumentException.class,()->MonogramProfiles.publicAvatar(new byte[]{1,2,3}));
    }
}
