package org.telegram.messenger.xmpp;

/** Actual Android decoder check; host Skia in Robolectric may omit gain-map reconstruction. */
public final class MonogramHdrDeviceTest {
    public static void main(String[] args) throws Exception {
        java.io.File file=new java.io.File(args[0]);
        byte[] before=java.nio.file.Files.readAllBytes(file.toPath());
        android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
        if(!MonogramHdr.hasGainmap(bitmap))throw new AssertionError("Android did not retain the JPEG gain map");
        bitmap.recycle();
        if(!file.getAbsolutePath().equals(MonogramHdr.originalSource(file.getAbsolutePath(),null)))
            throw new AssertionError("Sampled HDR detection did not select the original");
        if(!java.util.Arrays.equals(before,java.nio.file.Files.readAllBytes(file.toPath())))
            throw new AssertionError("HDR source bytes were modified");
        if(args.length>1){
            android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;
            android.graphics.BitmapFactory.decodeFile(args[1],options);options.inJustDecodeBounds=false;
            options.inPreferredConfig=MonogramHdr.preferredConfig(options,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Bitmap hdr=android.graphics.BitmapFactory.decodeFile(args[1],options);
            if(!MonogramHdr.isHdr(hdr) || hdr.getConfig()!=android.graphics.Bitmap.Config.RGBA_F16)
                throw new AssertionError("PQ still lost its color space or precision: "+(hdr==null?"null":hdr.getColorSpace()+" "+hdr.getConfig()));
            hdr.recycle();
        }
        System.out.println("Android Ultra HDR/PQ decode, sampled detection and original-byte preservation passed");
    }
}
