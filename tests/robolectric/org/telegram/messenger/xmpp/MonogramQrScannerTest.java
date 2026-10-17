package org.telegram.messenger.xmpp;

import android.graphics.Bitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.QrCodeScanner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramQrScannerTest {
    private static int[] pixels(BitMatrix code, boolean inverted, boolean mirrored) {
        int w = code.getWidth(), h = code.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            pixels[y * w + x] = code.get(mirrored ? w - 1 - x : x, y) ^ inverted ? 0xff000000 : 0xffffffff;
        return pixels;
    }
    @Test public void offlineQrDecodingHandlesInversionMirroringAndOuterCorners() throws Exception {
        String text = "xmpp:friend@example.org";
        BitMatrix code = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240);
        int min = 240, max = 0;
        for (int y = 0; y < 240; y++) for (int x = 0; x < 240; x++) if (code.get(x,y)) { min = Math.min(min,x); max = Math.max(max,x); }
        for (boolean inverted : new boolean[]{false,true}) for (boolean mirrored : new boolean[]{false,true}) {
            List<QrCodeScanner.Scan> scans = QrCodeScanner.scan(new RGBLuminanceSource(240,240,pixels(code,inverted,mirrored)));
            assertEquals(1,scans.size()); assertEquals(text,scans.get(0).text);
            float[] corners = scans.get(0).corners;
            assertEquals(8,corners.length);
            float left = Float.MAX_VALUE, right = -Float.MAX_VALUE;
            for (int i = 0; i < 8; i += 2) { left = Math.min(left,corners[i]); right = Math.max(right,corners[i]); }
            assertEquals(min,left,2); assertEquals(max+1,right,2);
        }
    }
    @Test public void bitmapInputKeepsCallerPixelsAndOwnership() throws Exception {
        BitMatrix code = new QRCodeWriter().encode("https://example.org/scan",BarcodeFormat.QR_CODE,200,200);
        int[] pixels = pixels(code,true,false);
        Bitmap bitmap = Bitmap.createBitmap(pixels,200,200,Bitmap.Config.ARGB_8888);
        try {
            assertEquals("https://example.org/scan",QrCodeScanner.scan(bitmap).get(0).text);
            assertFalse(bitmap.isRecycled()); assertEquals(pixels[0],bitmap.getPixel(0,0));
        } finally { bitmap.recycle(); }
    }
    @Test public void severalCodesAreRetainedAndBlankImagesAreIgnored() throws Exception {
        BitMatrix a = new QRCodeWriter().encode("one",BarcodeFormat.QR_CODE,180,180);
        BitMatrix b = new QRCodeWriter().encode("two",BarcodeFormat.QR_CODE,180,180);
        int[] canvas = new int[400*200]; java.util.Arrays.fill(canvas,0xffffffff);
        for (int y = 0; y < 180; y++) for (int x = 0; x < 180; x++) {
            if(a.get(x,y)) canvas[(y+10)*400+x+10]=0xff000000;
            if(b.get(x,y)) canvas[(y+10)*400+x+210]=0xff000000;
        }
        java.util.Set<String> texts = new java.util.HashSet<>();
        for(QrCodeScanner.Scan scan:QrCodeScanner.scan(new RGBLuminanceSource(400,200,canvas))) texts.add(scan.text);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("one","two")),texts);
        java.util.Arrays.fill(canvas,0xffffffff);
        assertTrue(QrCodeScanner.scan(new RGBLuminanceSource(400,200,canvas)).isEmpty());
    }
}
