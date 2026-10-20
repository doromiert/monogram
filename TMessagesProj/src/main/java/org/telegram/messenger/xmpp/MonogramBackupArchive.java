// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jxmpp.jid.impl.JidCreate;

/** An authenticated account snapshot container; restoring live stores is deliberately a separate operation. */
final class MonogramBackupArchive {
    private static final int MAX_MANIFEST = 8 * 1024 * 1024;
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList("data/history.db", "data/outbox.db", "data/omemo2.db",
            "data/legacy.db", "trust/legacy.properties", "preferences/xmpp.json", "preferences/native.json",
            "preferences/packs.json", "preferences/giphy.json", "paths.json"));
    private MonogramBackupArchive() { }
    static final class Staged implements AutoCloseable {
        final File directory;
        final String owner;
        final long created;
        private Staged(File directory, String owner, long created) { this.directory=directory;this.owner=owner;this.created=created; }
        @Override public void close() { erase(directory); }
    }

    /** Input must be a QUIESCENT, owner-filtered portable snapshot, not live files or Keystore ciphertext. */
    static void write(File portableSnapshot, File privateWork, String owner, OutputStream encrypted, char[] password) throws Exception {
        requireOwner(owner); File zip=File.createTempFile("backup-plain-", ".tmp", privateWork);
        try {
            SortedMap<String,File> files=new TreeMap<>(); collect(portableSnapshot, portableSnapshot, files);
            if (!files.keySet().containsAll(REQUIRED)) throw new IOException("Incomplete account snapshot.");
            JSONArray entries=new JSONArray(); long total=0;
            try (ZipOutputStream output=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
                for (Map.Entry<String,File> entry:files.entrySet()) {
                    output.putNextEntry(new ZipEntry(entry.getKey())); MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    long size;
                    try(InputStream input=new BufferedInputStream(new FileInputStream(entry.getValue()))) {
                        size=copy(input,output,digest,MonogramBackupEnvelope.MAX_PLAINTEXT-total);
                    }
                    total+=size; output.closeEntry();
                    entries.put(new JSONObject().put("path",entry.getKey()).put("size",size).put("sha256",hex(digest.digest())));
                }
                byte[] manifest=new JSONObject().put("format","monogram-account-backup").put("version",1).put("owner",owner)
                        .put("created",System.currentTimeMillis()).put("files",entries).toString().getBytes(StandardCharsets.UTF_8);
                if(manifest.length>MAX_MANIFEST) throw new IOException("Backup manifest is too large.");
                output.putNextEntry(new ZipEntry("manifest.json"));output.write(manifest);output.closeEntry();
            }
            try(InputStream input=new BufferedInputStream(new FileInputStream(zip))) { MonogramBackupEnvelope.encrypt(input,encrypted,password); }
        } finally { zip.delete(); }
    }

    /** Authenticates the ENTIRE envelope before opening ZIP data. Caller must validate databases before applying. */
    static Staged stage(InputStream encrypted, File privateWork, String expectedOwner, char[] password) throws Exception {
        requireOwner(expectedOwner);
        File zip=File.createTempFile("backup-auth-", ".tmp",privateWork);
        File directory=File.createTempFile("backup-restore-", ".tmp",privateWork);
        if(!directory.delete() || !directory.mkdir()) { zip.delete();throw new IOException("Could not prepare private restore staging."); }
        boolean success=false;
        try {
            try(OutputStream output=new BufferedOutputStream(new FileOutputStream(zip))) { MonogramBackupEnvelope.decrypt(encrypted,output,password); }
            Map<String,String> digests=new HashMap<>();Map<String,Long> sizes=new HashMap<>();long total=0;
            JSONObject manifest=null;
            try(ZipInputStream input=new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                ZipEntry entry;
                while((entry=input.getNextEntry())!=null) {
                    String path=entry.getName();
                    if(entry.isDirectory() || !validPath(path) || sizes.containsKey(path)) throw new IOException("Invalid or duplicate backup path.");
                    if(path.equals("manifest.json")) {
                        if(manifest!=null) throw new IOException("Duplicate backup manifest.");
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(input,bytes,null,MAX_MANIFEST);
                        manifest=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
                        continue;
                    }
                    File target=new File(directory,path);File parent=target.getParentFile();
                    if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create private staging directory.");
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");long size;
                    try(OutputStream output=new BufferedOutputStream(new FileOutputStream(target))) {
                        size=copy(input,output,digest,MonogramBackupEnvelope.MAX_PLAINTEXT-total);
                    }
                    total+=size;digests.put(path,hex(digest.digest()));sizes.put(path,size);
                }
            }
            if(manifest==null || !manifest.getString("format").equals("monogram-account-backup") || manifest.getInt("version")!=1)
                throw new IOException("Unsupported backup manifest.");
            if(!expectedOwner.equals(manifest.getString("owner"))) throw new IOException("This backup belongs to a different Jabber account.");
            if(!sizes.keySet().containsAll(REQUIRED)) throw new IOException("Incomplete account backup.");
            JSONArray entries=manifest.getJSONArray("files");Set<String> declared=new HashSet<>();
            for(int i=0;i<entries.length();i++) {
                JSONObject entry=entries.getJSONObject(i);String path=entry.getString("path");
                if(!declared.add(path) || !sizes.containsKey(path) || sizes.get(path)!=entry.getLong("size")
                        || !digests.get(path).equals(entry.getString("sha256"))) throw new IOException("Backup contents do not match its manifest.");
            }
            if(!declared.equals(sizes.keySet())) throw new IOException("Backup contains undeclared files.");
            long created=manifest.getLong("created");if(created<=0) throw new IOException("Invalid backup creation time.");
            success=true;return new Staged(directory,expectedOwner,created);
        } finally { zip.delete();if(!success) erase(directory); }
    }
    private static void collect(File root,File directory,Map<String,File> files) throws IOException {
        File[] children=directory.listFiles();if(children==null) throw new IOException("Could not read backup snapshot.");
        String prefix=root.getCanonicalPath()+File.separator;
        for(File file:children) {
            if(!file.getCanonicalPath().equals(file.getAbsolutePath()) || !file.getCanonicalPath().startsWith(prefix))
                throw new IOException("Backup snapshot contains a symbolic link or escaped path.");
            if(file.isDirectory()) collect(root,file,files);
            else if(file.isFile()) {
                String path=file.getCanonicalPath().substring(prefix.length()).replace(File.separatorChar,'/');
                if(!validPath(path) || path.equals("manifest.json")) throw new IOException("Invalid portable snapshot path.");
                files.put(path,file);
            } else throw new IOException("Unsupported backup file type.");
        }
    }
    private static boolean validPath(String path) {
        if(path.length()>1024 || path.startsWith("/") || path.indexOf('\\')>=0) return false;
        for(String part:path.split("/",-1)) if(part.isEmpty() || part.equals(".") || part.equals("..") || !part.matches("[A-Za-z0-9_.-]+")) return false;
        return true;
    }
    private static void requireOwner(String owner) throws Exception {
        if(owner==null || owner.indexOf('/')>=0 || !JidCreate.entityBareFrom(owner).toString().equals(owner))
            throw new IllegalArgumentException("Use the canonical bare Jabber account for this backup.");
    }
    private static long copy(InputStream input,OutputStream output,MessageDigest digest,long limit) throws IOException {
        byte[] buffer=new byte[16384];long total=0;int count;
        while((count=input.read(buffer))!=-1) {
            if(count==0) continue;
            if((total+=count)>limit) throw new IOException("Backup exceeds its safety limit.");
            if(digest!=null) digest.update(buffer,0,count);output.write(buffer,0,count);
        }
        return total;
    }
    private static String hex(byte[] bytes) {
        StringBuilder text=new StringBuilder();for(byte value:bytes) text.append(String.format(Locale.ROOT,"%02x",value&255));return text.toString();
    }
    private static void erase(File file) {
        if(file.isDirectory()) { File[] children=file.listFiles();if(children!=null) for(File child:children) erase(child); }
        file.delete();
    }
}
