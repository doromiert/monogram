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
    private static volatile Map<Integer,Bitmap> icons=Collections.emptyMap();
    private static volatile List<Pack> packs=Collections.emptyList();
    private static volatile boolean initialized;
    private static String publishedFingerprint="";
    public static final class Pack {
        public final String id,name;public final JSONObject metadata;public final File directory;
        Pack(File dir,JSONObject data) throws Exception {
            id=data.getString("packId");name=data.getString("packName");
            if(!id.matches("[A-Za-z0-9_.-]{1,100}")||id.equals(".")||id.equals(".."))throw new IOException("Invalid pack ID");
            metadata=data;directory=dir;
            if(data.optJSONObject("icons")==null)data.put("icons",new JSONObject());
        }
        public JSONObject mappings() {return metadata.optJSONObject("icons");}
    }
    private static File root() {return new File(ApplicationLoader.applicationContext.getFilesDir(),"appearance-icon-packs");}
    private static android.content.SharedPreferences prefs() {return ApplicationLoader.applicationContext.getSharedPreferences("appearance-icon-packs",0);}
    public static List<Pack> list() {return packs;}
    public static boolean enabled(Pack p) {return prefs().getBoolean("enabled/"+p.id,true);}
    public static Drawable drawable(Resources res,int id) {
        Bitmap bitmap=icons.get(id);return bitmap==null?null:new BitmapDrawable(res,bitmap);
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
        ArrayList<Pack> found=new ArrayList<>();Map<Integer,Bitmap> next=new HashMap<>();
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
        Resources res=ApplicationLoader.applicationContext.getResources();int size=AndroidUtilities.dp(24);
        long bytes=0;
        for(Pack p:found) if(enabled(p))for(Iterator<String> it=p.mappings().keys();it.hasNext();)try {
            String key=it.next();int id=res.getIdentifier(key,"drawable",ApplicationLoader.applicationContext.getPackageName());
            if(id==0||next.containsKey(id))continue;
            File file=inside(p.directory,p.mappings().getString(key));
            Bitmap bitmap=decode(file,size);
            if(bitmap!=null){if(bytes+bitmap.getAllocationByteCount()>24L*1024*1024){bitmap.recycle();break;}bytes+=bitmap.getAllocationByteCount();next.put(id,bitmap);}
        }catch(Exception invalid){FileLog.e("Could not load local icon");}
        icons=Collections.unmodifiableMap(next);publishedFingerprint=fingerprint.toString();
        if(notify)AndroidUtilities.runOnUIThread(()->NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface));
    }
    private static Bitmap decode(File file,int size) throws Exception {
        if(!file.isFile()||file.length()>4*1024*1024)return null;
        Bitmap result;
        if(file.getName().toLowerCase(Locale.ROOT).endsWith(".svg")) {
            String xml=new String(read(new FileInputStream(file),1024*1024),StandardCharsets.UTF_8);
            if(xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new IOException("External SVG entities are unsupported");
            result=SvgHelper.getBitmap(xml,size,size,true);
        } else {
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>16384||bounds.outHeight>16384)throw new IOException("Invalid image");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/size);
            Bitmap source=BitmapFactory.decodeFile(file.getPath(),options);if(source==null)return null;
            result=Bitmap.createScaledBitmap(source,size,size,true);if(result!=source)source.recycle();
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
            if(!resource.matches("[a-z][a-z0-9_]*")||ApplicationLoader.applicationContext.getResources().getIdentifier(resource,"drawable",ApplicationLoader.applicationContext.getPackageName())==0)throw new IOException("Unknown icon resource name");
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
                long total=0;int files=0;
                try(ZipInputStream zip=new ZipInputStream(ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri))) {
                    ZipEntry entry;while((entry=zip.getNextEntry())!=null) {
                        if(++files>8192)throw new IOException("Too many icon files");
                        File dest=inside(staging,entry.getName());if(entry.isDirectory()){dest.mkdirs();continue;}
                        dest.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(dest)) {
                            byte[] buffer=new byte[16384];int n;long size=0;while((n=zip.read(buffer))!=-1){total+=n;size+=n;if(total>64L*1024*1024||size>4*1024*1024)throw new IOException("Icon archive is too large");out.write(buffer,0,n);}
                        }
                    }
                }
                Pack pack=new Pack(staging,new JSONObject(new String(read(new FileInputStream(new File(staging,"metadata.json")),1024*1024),StandardCharsets.UTF_8)));
                for(Iterator<String> it=pack.mappings().keys();it.hasNext();) {File file=inside(staging,pack.mappings().getString(it.next()));if(!file.isFile())throw new IOException("Pack references a missing icon");}
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
