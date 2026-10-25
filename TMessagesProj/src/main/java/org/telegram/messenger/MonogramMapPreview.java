// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.io.*;
import java.net.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Small OSM raster preview sharing the full map's HTTP tile cache. No Telegram map proxy or API key. */
public final class MonogramMapPreview extends Drawable {
    private static final int ZOOM=14;
    private static final android.util.LruCache<String,Bitmap> memory=new android.util.LruCache<String,Bitmap>(8*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap value) { return value.getAllocationByteCount(); }
    };
    private static final ExecutorService io=Executors.newFixedThreadPool(2);
    private static final Map<String,ArrayList<WeakReference<MonogramMapPreview>>> pending=new HashMap<>();
    private static final Map<String,Long> failed=new ConcurrentHashMap<>();
    private static final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();
    private final WeakReference<View> parent;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final LinkedHashMap<String,RectF> tiles=new LinkedHashMap<>();
    private final int width,height;
    private int alpha=255;
    private final float density;
    public MonogramMapPreview(View view,double latitude,double longitude,int requestedWidth,int requestedHeight) {
        parent=new WeakReference<>(view);
        float scale=Math.min(1f,Math.min(640f/Math.max(1,requestedWidth),400f/Math.max(1,requestedHeight)));
        width=Math.max(1,Math.round(requestedWidth*scale));height=Math.max(1,Math.round(requestedHeight*scale));
        density=view.getResources().getDisplayMetrics().density*scale;
        tiles.putAll(tileRects(latitude,longitude,width,height));
        for(String key:tiles.keySet()) request(key,this);
    }
    static LinkedHashMap<String,RectF> tileRects(double latitude,double longitude,int width,int height) {
        LinkedHashMap<String,RectF> tiles=new LinkedHashMap<>();
        int count=1<<ZOOM;
        double centerX=OpenStreetMapProvider.x(OpenStreetMapProvider.wrap(longitude))*count*256;
        double centerY=OpenStreetMapProvider.y(latitude)*count*256;
        double left=centerX-width/2d,top=centerY-height/2d;
        for(int y=(int)Math.floor(top/256);y<=Math.floor((top+height-0.001)/256);y++) for(int x=(int)Math.floor(left/256);x<=Math.floor((left+width-0.001)/256);x++) {
            if(y<0 || y>=count) continue;
            String key=ZOOM+"/"+Math.floorMod(x,count)+"/"+y;
            tiles.put(key,new RectF((float)(x*256-left),(float)(y*256-top),(float)((x+1)*256-left),(float)((y+1)*256-top)));
        }
        return tiles;
    }
    private static void request(String key,MonogramMapPreview drawable) {
        synchronized(pending) {
            if(memory.get(key)!=null || failed.getOrDefault(key,0L)>System.currentTimeMillis()) return;
            ArrayList<WeakReference<MonogramMapPreview>> listeners=pending.get(key);
            if(listeners!=null) { listeners.add(new WeakReference<>(drawable));return; }
            listeners=new ArrayList<>();listeners.add(new WeakReference<>(drawable));pending.put(key,listeners);
        }
        io.execute(() -> {
            Bitmap bitmap=null;
            try {
                File directory=new File(ApplicationLoader.applicationContext.getCacheDir(),"monogram-map-tiles");
                File file=new File(directory,key.replace('/','_')+".png");
                if(file.isFile() && file.lastModified()>System.currentTimeMillis()) bitmap=BitmapFactory.decodeFile(file.getPath());
                if(bitmap==null) {
                    HttpURLConnection connection=(HttpURLConnection)new URL("https://tile.openstreetmap.org/"+key+".png").openConnection();
                    connections.add(connection);
                    try {
                        if(!MonogramMaps.previewsEnabled(ApplicationLoader.applicationContext)) throw new InterruptedIOException("Previews disabled");
                        connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);
                        connection.setRequestProperty("User-Agent","Monogram Android / github.com/doromiert/monogram");
                        if(connection.getResponseCode()!=200) throw new IOException("Tile unavailable");
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
                        try(InputStream input=connection.getInputStream()) { while((read=input.read(buffer))!=-1) { if(bytes.size()+read>1024*1024) throw new IOException("Tile too large");bytes.write(buffer,0,read); } }
                        byte[] data=bytes.toByteArray();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);
                        if(bounds.outWidth!=256 || bounds.outHeight!=256) throw new IOException("Invalid tile");
                        bitmap=BitmapFactory.decodeByteArray(data,0,data.length);
                        if(directory.isDirectory() || directory.mkdir()) {
                            File temp=File.createTempFile("tile-",".tmp",directory);
                            try { try(FileOutputStream output=new FileOutputStream(temp)) { output.write(data); }if(temp.renameTo(file)) file.setLastModified(Math.max(connection.getExpiration(),System.currentTimeMillis()+7L*86400000)); }
                            finally { temp.delete(); }
                            trim(directory);
                        }
                    } finally { connections.remove(connection);connection.disconnect(); }
                }
            } catch(Exception unavailable) { failed.put(key,System.currentTimeMillis()+60000); }
            if(bitmap!=null) memory.put(key,bitmap);
            ArrayList<WeakReference<MonogramMapPreview>> listeners;
            synchronized(pending) { listeners=pending.remove(key); }
            if(listeners!=null) AndroidUtilities.runOnUIThread(() -> {
                for(WeakReference<MonogramMapPreview> ref:listeners) { MonogramMapPreview preview=ref.get();if(preview!=null) {View view=preview.parent.get();if(view!=null) view.invalidate();} }
            });
        });
    }
    public static void cancelRequests() { for(HttpURLConnection connection:connections) connection.disconnect(); }
    private static synchronized void trim(File directory) {
        File[] files=directory.listFiles((dir,name) -> name.endsWith(".png"));if(files==null) return;
        long total=0;for(File file:files) total+=file.length();
        if(total<=32L*1024*1024) return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        for(File file:files) {if(total<=24L*1024*1024) break;long size=file.length();if(file.delete()) total-=size;}
    }
    @Override public void draw(Canvas canvas) {
        Rect bounds=getBounds();if(bounds.isEmpty()) return;
        int save=alpha<255 ? canvas.saveLayerAlpha(bounds.left,bounds.top,bounds.right,bounds.bottom,alpha) : canvas.save();canvas.clipRect(bounds);canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/(float)width,bounds.height()/(float)height);
        canvas.drawColor(0xffe5e8e1);
        for(Map.Entry<String,RectF> tile:tiles.entrySet()) {Bitmap bitmap=memory.get(tile.getKey());if(bitmap!=null) canvas.drawBitmap(bitmap,null,tile.getValue(),paint);}
        paint.setColor(Color.WHITE);canvas.drawCircle(width/2f,height/2f,10*density,paint);paint.setColor(0xffe74c3c);canvas.drawCircle(width/2f,height/2f,7*density,paint);
        paint.setTextSize(11*density);paint.setColor(0xe6ffffff);String label="© OpenStreetMap contributors";float text=paint.measureText(label);
        canvas.drawRect(width-text-8*density,height-18*density,width,height,paint);paint.setColor(Color.DKGRAY);canvas.drawText(label,width-text-4*density,height-4*density,paint);
        canvas.restoreToCount(save);
    }
    @Override public int getIntrinsicWidth() { return width; }
    @Override public int getIntrinsicHeight() { return height; }
    @Override public void setAlpha(int alpha) { this.alpha=alpha; }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
