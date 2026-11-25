package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Native gain-map rendering; window HDR exists only while an HDR receiver is visible. */
public final class MonogramHdr {
    public static final String SOURCE="monogram-hdr-source";
    public static final String MIME="monogram-hdr-mime";
    public static final class Source {
        public final String path,mime;
        Source(String path,String mime){this.path=path;this.mime=mime;}
    }
    private MonogramHdr() {}
    private static final class WindowState {
        final int original;
        final Set<Object> owners=Collections.newSetFromMap(new WeakHashMap<>());
        WindowState(int color){original=color;}
    }
    private static final WeakHashMap<View,WindowState> windows=new WeakHashMap<>();
    private static final WeakHashMap<Object,WeakReference<View>> roots=new WeakHashMap<>();

    public static boolean hasGainmap(Bitmap bitmap) {
        return Build.VERSION.SDK_INT>=34 && bitmap!=null && !bitmap.isRecycled() && bitmap.hasGainmap();
    }
    private static boolean hdrColorSpace(android.graphics.ColorSpace color) {
        if(Build.VERSION.SDK_INT<26 || color==null)return false;
        if(Build.VERSION.SDK_INT>=33 && (color.equals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.BT2020_PQ))
                || color.equals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.BT2020_HLG))))return true;
        return color.equals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.EXTENDED_SRGB))
                || color.equals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB));
    }
    public static boolean isHdr(Bitmap bitmap) {
        return bitmap!=null && !bitmap.isRecycled() && (hasGainmap(bitmap)
                || Build.VERSION.SDK_INT>=26 && hdrColorSpace(bitmap.getColorSpace()));
    }
    public static Bitmap.Config preferredConfig(BitmapFactory.Options options,Bitmap.Config fallback) {
        if(Build.VERSION.SDK_INT>=26 && hdrColorSpace(options.outColorSpace))return Bitmap.Config.RGBA_F16;
        return Build.VERSION.SDK_INT>=34?Bitmap.Config.ARGB_8888:fallback;
    }
    public static void update(Object owner,View parent,boolean hdr) {
        if(Build.VERSION.SDK_INT<26 || Looper.myLooper()!=Looper.getMainLooper()) return;
        View root=parent==null?null:parent.getRootView();
        WeakReference<View> previous=roots.get(owner);
        if(hdr && previous!=null && previous.get()==root && root!=null && root.isAttachedToWindow())return;
        if(previous!=null && (!hdr || previous.get()!=root)) release(owner);
        if(!hdr || root==null || !root.isAttachedToWindow() || root.getDisplay()==null
                || root.getDisplay().getHdrCapabilities().getSupportedHdrTypes().length==0
                || !(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams params=(WindowManager.LayoutParams)root.getLayoutParams();
        WindowState state=windows.get(root);
        if(state==null){state=new WindowState(params.getColorMode());windows.put(root,state);}
        state.owners.add(owner);roots.put(owner,new WeakReference<>(root));
        color(root,params,ActivityInfo.COLOR_MODE_HDR);
    }
    public static void release(Object owner) {
        if(Build.VERSION.SDK_INT<26 || Looper.myLooper()!=Looper.getMainLooper()) return;
        WeakReference<View> reference=roots.remove(owner);View root=reference==null?null:reference.get();
        WindowState state=root==null?null:windows.get(root);
        if(state==null)return;
        state.owners.remove(owner);
        if(state.owners.isEmpty()){
            windows.remove(root);
            if(root.getLayoutParams() instanceof WindowManager.LayoutParams)
                color(root,(WindowManager.LayoutParams)root.getLayoutParams(),state.original);
        }
    }
    private static void color(View root,WindowManager.LayoutParams params,int mode) {
        if(params.getColorMode()==mode)return;
        params.setColorMode(mode);
        if(root.isAttachedToWindow()){
            try{((WindowManager)root.getContext().getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(root,params);}
            catch(IllegalArgumentException ignored){} // A window can detach during a media transition.
        }
    }
    /** Native player metadata is already available; this must not probe files on the UI thread. */
    public static boolean keepOriginalVideo(org.telegram.messenger.VideoEditedInfo video) {
        return video!=null && video.hdrInfo!=null
            && (video.hdrInfo.colorTransfer==android.media.MediaFormat.COLOR_TRANSFER_ST2084
                || video.hdrInfo.colorTransfer==android.media.MediaFormat.COLOR_TRANSFER_HLG)
            && !video.roundVideo && !video.isPhoto && !video.isStory && !video.isSticker
            && video.startTime<=0 && (video.endTime<0 || video.endTime>=video.originalDuration)
            && !video.muted && video.volume==1f && video.mixedSoundInfos.isEmpty()
            && video.filterState==null && video.paintPath==null && video.blurPath==null
            && video.messagePath==null && video.messageVideoMaskPath==null && video.backgroundPath==null
            && video.cropState==null && (video.mediaEntities==null || video.mediaEntities.isEmpty())
            && video.resultWidth==video.originalWidth && video.resultHeight==video.originalHeight;
    }
    /** Worker-only, small sampled decode. Keep actual edited pixels when the picker made a new image. */
    public static Source captureOriginal(String path,android.net.Uri uri) {
        if(Build.VERSION.SDK_INT<26)return null;
        Bitmap sample=null;
        try{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=128;
            options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            if(path!=null){
                File file=new File(path);if(!file.isFile() || file.length()>128L*1024*1024)return null;
                sample=BitmapFactory.decodeFile(path,options);
            }else if(uri!=null){
                try(java.io.InputStream input=org.telegram.messenger.ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)){
                    sample=BitmapFactory.decodeStream(input,null,options);
                }
            }
            if(!isHdr(sample))return null;
            String mime=options.outMimeType;
            if(!"image/jpeg".equals(mime) && !"image/heic".equals(mime) && !"image/heif".equals(mime) && !"image/avif".equals(mime) && !"image/png".equals(mime))return null;
            String extension="image/jpeg".equals(mime)?"jpg":"image/png".equals(mime)?"png":"image/avif".equals(mime)?"avif":"heic";
            String selected=path!=null?path:org.telegram.messenger.MediaController.copyFileToCache(uri,extension);
            return selected==null?null:new Source(selected,mime);
        }catch(Exception ignored){return null;}
        finally{if(sample!=null)sample.recycle();}
    }
    public static String originalSource(String path,android.net.Uri uri) {
        Source source=captureOriginal(path,uri);return source==null?null:source.path;
    }

}
