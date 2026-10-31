// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.json.*;

/** Local resource-name icon packs. Compatible with exteraGram's metadata.json schema. */
public final class MonogramIconPacks {
    private MonogramIconPacks() {}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static volatile Map<Integer,File> icons=Collections.emptyMap();
    private static volatile Map<String,File> customIcons=Collections.emptyMap();
    private static final android.util.LruCache<String,Bitmap> bitmaps=new android.util.LruCache<String,Bitmap>(24*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };
    private static final class Style {Integer color;int alpha=255;ColorFilter filter;}
    private static final Map<Drawable,Style> styles=Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Paint> drawingPaint=new ThreadLocal<Paint>() { @Override protected Paint initialValue(){return new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);} };
    private static volatile List<Pack> packs=Collections.emptyList();
    private static volatile boolean initialized;
    private static String publishedFingerprint="";
    public static final class Pack {
        public final String id,name;public final JSONObject metadata;public final File directory;
        Pack(File dir,JSONObject data) throws Exception {
            id=data.getString("packId");name=data.getString("packName");
            if(!id.matches("[A-Za-z0-9_.-]{1,100}")||id.startsWith("."))throw new IOException("Invalid pack ID");
            metadata=data;directory=dir;
            if(data.optJSONObject("icons")==null)data.put("icons",new JSONObject());
        }
        public JSONObject mappings() {return metadata.optJSONObject("icons");}
    }
    private static File root() {return new File(ApplicationLoader.applicationContext.getFilesDir(),"appearance-icon-packs");}
    private static android.content.SharedPreferences prefs() {return ApplicationLoader.applicationContext.getSharedPreferences("appearance-icon-packs",0);}
    public static List<Pack> list() {return packs;}
    public static boolean enabled(Pack p) {return prefs().getBoolean("enabled/"+p.id,true);}
    public static Drawable drawable(Resources res,int id,Drawable original) {
        Bitmap bitmap=bitmap(icons.get(id));
        if(bitmap==null)return original;
        final int width=original.getIntrinsicWidth(),height=original.getIntrinsicHeight();
        return new BitmapDrawable(res,bitmap) {
            @Override public int getIntrinsicWidth(){return width>0?width:super.getIntrinsicWidth();}
            @Override public int getIntrinsicHeight(){return height>0?height:super.getIntrinsicHeight();}
        };
    }
    public static Bitmap decodeResource(Resources res,int id){return decodeResource(res,id,null);}
    public static Bitmap decodeResource(Resources res,int id,BitmapFactory.Options options){
        Bitmap bitmap=bitmap(icons.get(id));if(bitmap==null)return BitmapFactory.decodeResource(res,id,options);
        if(options!=null && options.inJustDecodeBounds){options.outWidth=bitmap.getWidth();options.outHeight=bitmap.getHeight();return null;}
        return bitmap.copy(Bitmap.Config.ARGB_8888,false);
    }
    private static Bitmap bitmap(File file) {
        if(file==null)return null;
        String key=file.getPath();Bitmap result=bitmaps.get(key);
        if(result==null)try{result=decode(file,Math.min(512,AndroidUtilities.dp(96)));if(result!=null)bitmaps.put(key,result);}
        catch(Exception invalid){FileLog.e("Could not decode local icon");}
        return result;
    }
    public static int resourceId(Resources res,String key) {
        String type="drawable",name=key;int dot=key.indexOf('.');
        if(dot>=0){type=key.substring(0,dot);name=key.substring(dot+1);}
        if(!type.equals("drawable")&&!type.equals("raw")&&!type.equals("mipmap"))return 0;
        return res.getIdentifier(name,type,ApplicationLoader.applicationContext.getPackageName());
    }
    public static List<String> resourceNames() {
        TreeSet<String> names=new TreeSet<>();
        for(String type:new String[]{"drawable","mipmap","raw"})try {
            Class<?> resource=Class.forName(R.class.getName()+"$"+type);
            for(java.lang.reflect.Field field:resource.getFields())names.add(type.equals("drawable")?field.getName():type+"."+field.getName());
        }catch(ClassNotFoundException absent){}
        names.addAll(MonogramUiIcons.names());return new ArrayList<>(names);
    }
    public static void color(Drawable owner,int color){if(customIcons.isEmpty())return;synchronized(styles){styles.computeIfAbsent(owner,k->new Style()).color=color;}}
    public static void alpha(Drawable owner,int alpha){if(customIcons.isEmpty())return;synchronized(styles){styles.computeIfAbsent(owner,k->new Style()).alpha=alpha;}}
    public static void filter(Drawable owner,ColorFilter filter){if(customIcons.isEmpty())return;synchronized(styles){styles.computeIfAbsent(owner,k->new Style()).filter=filter;}}
    private static File custom(String key){File file=customIcons.get(key);return file==null && key.lastIndexOf('.')>3?customIcons.get(key.substring(0,key.lastIndexOf('.'))):file;}
    public static int beginCustom(Drawable owner,Canvas canvas,String key){
        if(bitmap(custom(key))==null)return -1;
        RectF bounds=customBounds(owner,key);
        if(key.startsWith("ui.MediaActionDrawable.")){int save=canvas.save();canvas.clipRect(bounds,android.graphics.Region.Op.DIFFERENCE);return save;}
        return canvas.saveLayerAlpha(bounds,0);
    }
    public static void finishCustom(Drawable owner,Canvas canvas,String key,int layer){
        if(layer<0)return;canvas.restoreToCount(layer);drawCustom(owner,canvas,key);
    }
    public static boolean drawCustom(Drawable owner,Canvas canvas,String key) {
        File file=custom(key);
        Bitmap bitmap=bitmap(file);if(bitmap==null)return false;
        Paint paint=drawingPaint.get();paint.reset();paint.setFilterBitmap(true);paint.setAntiAlias(true);
        synchronized(styles){Style style=styles.get(owner);if(style!=null){paint.setAlpha(style.alpha);paint.setColorFilter(style.filter!=null?style.filter:style.color==null?null:new PorterDuffColorFilter(style.color,PorterDuff.Mode.SRC_IN));}}
        drawBitmap(canvas,bitmap,customBounds(owner,key),paint);return true;
    }
    private static RectF customBounds(Drawable owner,String key){
        Rect bounds=owner.getBounds();
        float width=bounds.isEmpty()?(owner.getIntrinsicWidth()>0?owner.getIntrinsicWidth():AndroidUtilities.dp(24)):bounds.width();
        float height=bounds.isEmpty()?(owner.getIntrinsicHeight()>0?owner.getIntrinsicHeight():AndroidUtilities.dp(24)):bounds.height();
        RectF result=new RectF(bounds.left,bounds.top,bounds.left+width,bounds.top+height);
        if(key.startsWith("ui.MediaActionDrawable."))result.inset(width*.25f,height*.25f);
        return result;
    }
    public static int beginCustomBounds(Canvas canvas,String key,RectF bounds){return bitmap(custom(key))==null?-1:canvas.saveLayerAlpha(bounds,0);}
    public static void finishCustomBounds(Canvas canvas,String key,RectF bounds,int layer){
        if(layer<0)return;canvas.restoreToCount(layer);Bitmap bitmap=bitmap(custom(key));if(bitmap!=null)drawBitmap(canvas,bitmap,bounds,null);
    }
    public static boolean drawResource(Canvas canvas,int id,RectF bounds,Paint paint){Bitmap bitmap=bitmap(icons.get(id));if(bitmap==null)return false;drawBitmap(canvas,bitmap,bounds,paint);return true;}
    private static void drawBitmap(Canvas canvas,Bitmap bitmap,RectF bounds,Paint paint){
        float scale=Math.min(bounds.width()/bitmap.getWidth(),bounds.height()/bitmap.getHeight());
        float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
        canvas.drawBitmap(bitmap,null,new RectF(bounds.centerX()-w/2,bounds.centerY()-h/2,bounds.centerX()+w/2,bounds.centerY()+h/2),paint);
    }
    public static void initialize() {if(!initialized){initialized=true;reload(null);}}
    private static byte[] read(InputStream input,int limit) throws IOException {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[16384];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>limit)throw new IOException("File is too large");out.write(buf,0,n);}return out.toByteArray();
        }
    }
    private static File inside(File dir,String name) throws IOException {
        File file=new File(dir,name).getCanonicalFile();if(!file.toPath().startsWith(dir.getCanonicalFile().toPath())||file.equals(dir.getCanonicalFile()))throw new IOException("Invalid icon path");return file;
    }
    public static void reload(Runnable done) {
        IO.execute(()->{load();if(done!=null)AndroidUtilities.runOnUIThread(done);});
    }
    private static void load() {
        ArrayList<Pack> found=new ArrayList<>();Map<Integer,File> next=new HashMap<>();Map<String,File> custom=new HashMap<>();
        root().mkdirs();File[] dirs=root().listFiles();
        if(dirs!=null)for(File dir:dirs)try {
            if(!dir.isDirectory()||dir.getName().startsWith("."))continue;
            Pack p=new Pack(dir,new JSONObject(new String(read(new FileInputStream(new File(dir,"metadata.json")),1024*1024),StandardCharsets.UTF_8)));found.add(p);
        }catch(Exception invalid){FileLog.e("Invalid local icon pack");}
        String order=prefs().getString("order","");List<String> ids=Arrays.asList(order.split(","));
        found.sort((a,b)->Integer.compare(ids.contains(a.id)?ids.indexOf(a.id):Integer.MAX_VALUE,ids.contains(b.id)?ids.indexOf(b.id):Integer.MAX_VALUE));
        StringBuilder fingerprint=new StringBuilder();
        for(Pack pack:found)fingerprint.append(pack.id).append(':').append(enabled(pack)).append(':').append(pack.metadata.toString()).append(';');
        packs=Collections.unmodifiableList(found);
        if(publishedFingerprint.equals(fingerprint.toString()))return;
        boolean notify=!icons.isEmpty() || !found.isEmpty();
        Resources res=ApplicationLoader.applicationContext.getResources();
        for(Pack p:found) if(enabled(p))for(Iterator<String> it=p.mappings().keys();it.hasNext();)try {
            String key=it.next();File file=inside(p.directory,p.mappings().getString(key));
            if(key.startsWith("ui.")){if(!custom.containsKey(key))custom.put(key,file);}
            else {int id=resourceId(res,key);if(id!=0&&!next.containsKey(id))next.put(id,file);}
        }catch(Exception invalid){FileLog.e("Could not load local icon");}
        bitmaps.evictAll();icons=Collections.unmodifiableMap(next);customIcons=Collections.unmodifiableMap(custom);publishedFingerprint=fingerprint.toString();
        if(notify)AndroidUtilities.runOnUIThread(()->{
            org.telegram.ui.ActionBar.Theme.reloadAllResources(ApplicationLoader.applicationContext);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        });
    }
    private static Bitmap decode(File file,int size) throws Exception {
        if(!file.isFile()||file.length()>4*1024*1024)return null;
        Bitmap result;
        if(file.getName().toLowerCase(Locale.ROOT).endsWith(".svg")) {
            String xml=new String(read(new FileInputStream(file),1024*1024),StandardCharsets.UTF_8);
            if(xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new IOException("External SVG entities are unsupported");
            result=SvgHelper.getBitmap(xml,size,size,true);
        } else {
            byte[] header=new byte[12];int length;try(InputStream input=new FileInputStream(file)){length=input.read(header);}
            boolean png=length>=8 && header[0]==(byte)137 && header[1]==80 && header[2]==78 && header[3]==71 && header[4]==13 && header[5]==10 && header[6]==26 && header[7]==10;
            boolean jpeg=length>=3 && header[0]==(byte)255 && header[1]==(byte)216 && header[2]==(byte)255;
            boolean webp=length>=12 && new String(header,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(header,8,4,StandardCharsets.US_ASCII).equals("WEBP");
            if(!png && !jpeg && !webp)throw new IOException("Choose a PNG, WebP, JPEG or SVG icon");
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>16384||bounds.outHeight>16384)throw new IOException("Invalid image");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/size);
            Bitmap source=BitmapFactory.decodeFile(file.getPath(),options);if(source==null)return null;
            float ratio=Math.min(1f,size/(float)Math.max(source.getWidth(),source.getHeight()));
            result=Bitmap.createScaledBitmap(source,Math.max(1,Math.round(source.getWidth()*ratio)),Math.max(1,Math.round(source.getHeight()*ratio)),true);if(result!=source)source.recycle();
        }
        if(result!=null)result.setDensity(ApplicationLoader.applicationContext.getResources().getDisplayMetrics().densityDpi);
        return result;
    }
    private static void write(Pack pack) throws Exception {
        byte[] bytes=pack.metadata.toString(2).getBytes(StandardCharsets.UTF_8);
        android.util.AtomicFile file=new android.util.AtomicFile(new File(pack.directory,"metadata.json"));
        FileOutputStream stream=null;try{stream=file.startWrite();stream.write(bytes);file.finishWrite(stream);}catch(Exception failure){file.failWrite(stream);throw failure;}
    }
    private static void task(Throwing action,java.util.function.Consumer<String> result) {
        IO.execute(()->{String error=null;try{action.run();load();}catch(Exception failure){error=failure.getMessage();if(error==null)error="Icon pack operation failed";}String message=error;AndroidUtilities.runOnUIThread(()->result.accept(message));});
    }
    private interface Throwing {void run() throws Exception;}
    public static void create(String name,java.util.function.Consumer<String> result) {
        task(()->{if(name.trim().isEmpty())throw new IOException("Enter a pack name");String id="monogram."+UUID.randomUUID();File dir=new File(root(),id);if(!dir.mkdirs())throw new IOException("Could not create pack");write(new Pack(dir,new JSONObject().put("packId",id).put("packName",name.trim()).put("author","Monogram").put("version","1.0").put("icons",new JSONObject())));},result);
    }
    public static void rename(Pack pack,String name,java.util.function.Consumer<String> result) {task(()->{if(name.trim().isEmpty())throw new IOException("Enter a name");Pack changed=new Pack(pack.directory,new JSONObject(pack.metadata.toString()));changed.metadata.put("packName",name.trim());write(changed);},result);}
    public static void enable(Pack pack,boolean enabled,java.util.function.Consumer<String> result) {task(()->prefs().edit().putBoolean("enabled/"+pack.id,enabled).commit(),result);}
    public static void order(List<String> ids) {prefs().edit().putString("order",android.text.TextUtils.join(",",ids)).apply();reload(null);}
    public static void remove(Pack pack,java.util.function.Consumer<String> result) {task(()->delete(pack.directory),result);}
    private static void delete(File f) {if(f.isDirectory()){File[] files=f.listFiles();if(files!=null)for(File child:files)delete(child);}f.delete();}
    public static void removeIcon(Pack pack,String resource,java.util.function.Consumer<String> result) {task(()->{Pack changed=new Pack(pack.directory,new JSONObject(pack.metadata.toString()));String path=changed.mappings().optString(resource);changed.mappings().remove(resource);write(changed);if(!changed.mappings().toString().contains(path)&&!path.isEmpty())inside(pack.directory,path).delete();},result);}
    public static void addIcon(Pack pack,String resource,Uri uri,java.util.function.Consumer<String> result) {
        task(()->{
            if(!resourceNames().contains(resource))throw new IOException("Unknown icon resource name");
            byte[] data=read(ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri),4*1024*1024);
            String head=new String(data,0,Math.min(256,data.length),StandardCharsets.UTF_8);boolean svg=head.contains("<svg")||head.contains("<?xml");
            File icon=new File(pack.directory,UUID.randomUUID()+(svg?".svg":".png"));try(FileOutputStream out=new FileOutputStream(icon)){out.write(data);}
            Bitmap decoded=decode(icon,AndroidUtilities.dp(24));if(decoded==null){icon.delete();throw new IOException("Choose a PNG, WebP, JPEG or SVG icon");}decoded.recycle();
            Pack changed=new Pack(pack.directory,new JSONObject(pack.metadata.toString()));changed.mappings().put(resource,icon.getName());write(changed);
        },result);
    }
    public static void importPack(Uri uri,java.util.function.Consumer<String> result) {
        task(()->{
            root().mkdirs();File staging=new File(root(),".import-"+UUID.randomUUID());staging.mkdirs();boolean installed=false;
            try {
                long total=0;int files=0;Set<String> entries=new HashSet<>();
                try(ZipInputStream zip=new ZipInputStream(ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri))) {
                    ZipEntry entry;while((entry=zip.getNextEntry())!=null) {
                        if(++files>8192)throw new IOException("Too many icon files");
                        if(!entries.add(entry.getName()))throw new IOException("Duplicate icon archive entry");
                        File dest=inside(staging,entry.getName());if(entry.isDirectory()){dest.mkdirs();continue;}
                        dest.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(dest)) {
                            byte[] buffer=new byte[16384];int n;long size=0;while((n=zip.read(buffer))!=-1){total+=n;size+=n;if(total>64L*1024*1024||size>4*1024*1024)throw new IOException("Icon archive is too large");out.write(buffer,0,n);}
                        }
                    }
                }
                Pack pack=new Pack(staging,new JSONObject(new String(read(new FileInputStream(new File(staging,"metadata.json")),1024*1024),StandardCharsets.UTF_8)));
                Set<File> validated=new HashSet<>();
                for(Iterator<String> it=pack.mappings().keys();it.hasNext();) {
                    String key=it.next();if(!key.matches("(?:[a-zA-Z][a-zA-Z0-9_]*\\.)*[a-zA-Z][a-zA-Z0-9_]*"))throw new IOException("Invalid icon name");
                    File file=inside(staging,pack.mappings().getString(key));if(!file.isFile())throw new IOException("Pack references a missing icon");
                    if(validated.add(file)){Bitmap valid=decode(file,32);if(valid==null)throw new IOException("Invalid icon image: "+key);valid.recycle();}
                }
                File target=new File(root(),pack.id);if(target.exists())throw new IOException("This icon pack is already installed");
                if(!staging.renameTo(target))throw new IOException("Could not install icon pack");installed=true;
            }finally{if(!installed)delete(staging);}
        },result);
    }
    public static void export(Pack pack,java.util.function.Consumer<File> ready,java.util.function.Consumer<String> failed) {
        IO.execute(()->{try {
            File target=new File(ApplicationLoader.applicationContext.getCacheDir(),pack.id+".zip");
            try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(target))) {
                Set<String> files=new LinkedHashSet<>();files.add("metadata.json");for(Iterator<String> it=pack.mappings().keys();it.hasNext();)files.add(pack.mappings().getString(it.next()));
                for(String name:files){zip.putNextEntry(new ZipEntry(name));zip.write(read(new FileInputStream(inside(pack.directory,name)),4*1024*1024));zip.closeEntry();}
            }
            AndroidUtilities.runOnUIThread(()->ready.accept(target));
        }catch(Exception error){AndroidUtilities.runOnUIThread(()->failed.accept("Could not export icon pack"));}});
    }
}
