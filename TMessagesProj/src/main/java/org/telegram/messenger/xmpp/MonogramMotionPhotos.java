// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import androidx.media3.extractor.jpeg.MotionPhotoDescription;
import androidx.media3.extractor.jpeg.XmpMotionPhotoDescriptionParser;
import java.io.*;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.TLRPC;

/** The uploaded/authenticated object remains the complete JPEG Motion Photo. Worker-thread I/O only. */
public final class MonogramMotionPhotos {
    public static final String SOURCE = "monogram-motion-photo";
    public static final String STILL = "monogram-motion-still";
    public static final String REBUILD = "monogram-motion-edited";
    private MonogramMotionPhotos() {}
    public static boolean isMotion(TLRPC.Message message) {
        return message != null && message.media != null && message.media.live_photo
                && (MonogramAttachments.has(message) || message.params != null && message.params.containsKey(SOURCE));
    }
    public static boolean edited(VideoEditedInfo info) {
        return info != null && (!info.mixedSoundInfos.isEmpty() || info.mediaEntities != null || info.paintPath != null
                || info.blurPath != null || info.filterState != null || info.cropState != null || info.startTime > 0
                || info.endTime > 0 && info.endTime != info.estimatedDuration || info.muted
                || info.originalWidth > 0 && info.resultWidth > 0 && info.originalWidth != info.resultWidth
                || info.originalHeight > 0 && info.resultHeight > 0 && info.originalHeight != info.resultHeight);
    }
    public static boolean isJpeg(File source) {
        try(InputStream input=new FileInputStream(source)) { return input.read()==255 && input.read()==216; }
        catch(IOException missing) { return false; }
    }
    /** Some camera pickers expose HEIC/AVIF covers. Normalize the entire still, never a video frame/thumbnail. */
    static File jpegStill(File source,File directory,com.negzero.monogram.xmpp.FileTransfer.Job job) throws IOException {
        if(isJpeg(source)) return source;
        job.check();
        if(!source.isFile() || source.length()>32L*1024*1024) throw new IOException("This motion photo still is too large to prepare. Send the original file instead.");
        android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        android.graphics.BitmapFactory.decodeFile(source.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>12000 || bounds.outHeight>12000
                || (long)bounds.outWidth*bounds.outHeight>40000000) throw new IOException("Cannot prepare this motion photo at its original resolution. Send the original file instead.");
        android.graphics.Bitmap bitmap=null;File jpeg=File.createTempFile("motion-still-", ".jpg",directory);
        try {
            bitmap=android.graphics.BitmapFactory.decodeFile(source.getAbsolutePath());
            if(bitmap==null || bitmap.getWidth()!=bounds.outWidth || bitmap.getHeight()!=bounds.outHeight)
                throw new IOException("Cannot read the full-resolution motion photo still. Select the original photo again.");
            job.check();
            try(OutputStream output=new FileOutputStream(jpeg)) {
                if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,97,output)) throw new IOException("Cannot prepare the motion photo still. Free some storage and retry.");
            }
            job.check();return jpeg;
        } catch(OutOfMemoryError unavailable) { jpeg.delete();throw new IOException("Not enough memory to prepare this motion photo at full resolution. Send the original file instead.",unavailable); }
        catch(IOException failed) { jpeg.delete();throw failed; }
        finally { if(bitmap!=null) bitmap.recycle(); }
    }
    public static final class Clip {
        public final long offset, length, timestampUs;
        Clip(long offset,long length,long timestampUs) { this.offset=offset;this.length=length;this.timestampUs=timestampUs; }
    }
    public static Clip inspect(File file) {
        if(file == null || !file.isFile() || file.length() < 32) return null;
        try (RandomAccessFile bytes = new RandomAccessFile(file,"r")) {
            if(bytes.readUnsignedShort()!=0xffd8) return null;
            // Bound metadata scanning before handing XML to the existing Media3 parser.
            String xmp=null;long scanned=2;
            for(int segments=0;segments<256 && scanned<2*1024*1024;segments++) {
                if(bytes.readUnsignedByte()!=255) return null;
                int marker;do { marker=bytes.readUnsignedByte(); } while(marker==255);
                if(marker==0xda || marker==0xd9) break;
                if(marker==0x01 || marker>=0xd0 && marker<=0xd7) continue;
                int length=bytes.readUnsignedShort();if(length<2 || bytes.getFilePointer()+length-2>file.length()) return null;
                byte[] data=new byte[length-2];bytes.readFully(data);scanned+=length+2;
                byte[] prefix="http://ns.adobe.com/xap/1.0/\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                if(marker==0xe1 && data.length>prefix.length && java.util.Arrays.equals(prefix,java.util.Arrays.copyOf(data,prefix.length))) {
                    xmp=new String(data,prefix.length,data.length-prefix.length,java.nio.charset.StandardCharsets.UTF_8);
                    if(xmp.contains("<!DOCTYPE") || xmp.contains("<!ENTITY")) return null;
                    break;
                }
            }
            if(xmp==null) return null;
            MotionPhotoDescription description=XmpMotionPhotoDescriptionParser.parse(xmp);
            if(description==null || description.items.size()<2 || description.items.size()>16) return null;
            MotionPhotoDescription.ContainerItem first=description.items.get(0),last=description.items.get(description.items.size()-1);
            if(!"image/jpeg".equals(first.mime) || !"Primary".equals(first.semantic)
                    || !"video/mp4".equals(last.mime) || !"MotionPhoto".equals(last.semantic)
                    || last.length<16 || last.length>512L*1024*1024 || last.length>file.length()-bytes.getFilePointer() || last.padding!=0) return null;
            for(int i=1;i<description.items.size()-1;i++) if("MotionPhoto".equals(description.items.get(i).semantic)) return null;
            long offset=file.length()-last.length;bytes.seek(offset);
            long box=bytes.readInt()&0xffffffffL;
            if(box<16 || box>last.length || box>1024*1024 || bytes.readInt()!=0x66747970) return null;
            return new Clip(offset,last.length,description.photoPresentationTimestampUs);
        } catch(Exception invalid) { return null; }
    }
    static File videoFile(File container) { return new File(container.getParentFile(),container.getName()+".motion.mp4"); }
    static File extract(File container,Clip clip) throws IOException {
        File video=videoFile(container);
        if(video.isFile() && video.length()==clip.length) return video;
        File temporary=File.createTempFile("motion-", ".part",container.getParentFile());
        try {
            try(RandomAccessFile input=new RandomAccessFile(container,"r");FileOutputStream output=new FileOutputStream(temporary)) {
                input.seek(clip.offset);byte[] buffer=new byte[64*1024];long remaining=clip.length;
                while(remaining>0) { int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0) throw new EOFException();output.write(buffer,0,count);remaining-=count; }
                output.getFD().sync();
            }
            if(!temporary.renameTo(video)) throw new IOException("Cannot prepare motion photo playback");
            return video;
        } finally { temporary.delete(); }
    }
    /** Rebuild only when the user actually edited a clip. Unedited files keep every original byte. */
    static File snapshot(MonogramFileRequest request,File directory) throws Exception {
        if(request.message.params==null || !"1".equals(request.message.params.get(REBUILD)))
            return com.negzero.monogram.xmpp.FileTransfer.snapshot(request.file,directory,request.job);
        String still=request.message.params.get(STILL);
        if(still==null || !new File(still).isFile()) throw new FileNotFoundException("The motion photo still is missing. Select the photo again.");
        File sourceStill=new File(still),normalized=jpegStill(sourceStill,directory,request.job);
        File target=File.createTempFile("motion-photo-", ".jpg",directory);
        try {
            try(OutputStream output=new FileOutputStream(target)) {
                OutputStream checked=new FilterOutputStream(output) {
                    @Override public void write(int value) throws IOException { request.job.check();out.write(value); }
                    @Override public void write(byte[] value,int offset,int length) throws IOException { request.job.check();out.write(value,offset,length); }
                };
                org.telegram.messenger.MediaController.writeMotionPhoto(normalized,request.file,checked,null);
            }
            request.job.check();
            if(inspect(target)==null) throw new IOException("Cannot prepare this motion photo. Select the original photo again.");
            return target;
        } catch(Exception failed) { target.delete();throw failed; }
        finally { if(!normalized.equals(sourceStill)) normalized.delete(); }
    }
}
