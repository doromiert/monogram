package org.telegram.messenger.xmpp;
import com.negzero.monogram.xmpp.FileLink;
import com.negzero.monogram.xmpp.FileTransfer;
import org.jivesoftware.smackx.httpfileupload.element.Slot;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;
/** Real Android HTTPS fixture; synthetic bytes/local server only, no application or account. */
public final class TransferResponseDeviceTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static FileLink upload(String base,String name,File source)throws Exception {
        Method method=FileTransfer.class.getDeclaredMethod("uploadToSlot",Slot.class,File.class,String.class,boolean.class,FileTransfer.Job.class,FileTransfer.Progress.class);method.setAccessible(true);
        try{return (FileLink)method.invoke(null,new Slot(new URL(base+"/put/"+name),new URL(base+"/object/"+name)),source,"application/octet-stream",true,new FileTransfer.Job(),null);}
        catch(InvocationTargetException error){throw (Exception)error.getCause();}
    }
    public static void main(String[]args)throws Exception {
        File root=new File(args[0]);root.mkdirs();KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);
        try(InputStream in=new FileInputStream(args[1])){store.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(in));}
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,trust.getTrustManagers(),null);HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        byte[] expected=new byte[65537];new Random(19).nextBytes(expected);File source=new File(root,"synthetic-sticker.bin"),target=new File(root,"downloaded");try(OutputStream out=new FileOutputStream(source)){out.write(expected);}
        long start=System.nanoTime();FileLink first=upload(args[2],"first",source);long uploadMs=(System.nanoTime()-start)/1000000;
        check(uploadMs>=4500&&uploadMs<8500,"lost PUT response must be confirmed within its bounded deadline");
        start=System.nanoTime();FileTransfer.download(first,target,new FileTransfer.Job(),null);long downloadMs=(System.nanoTime()-start)/1000000;
        byte[] actual=new byte[expected.length];try(DataInputStream in=new DataInputStream(new FileInputStream(target))){in.readFully(actual);check(in.read()==-1,"download length");}check(Arrays.equals(expected,actual),"retry must restart GCM and preserve plaintext");
        check(downloadMs>=4500&&downloadMs<8500,"inactive GET body must reconnect without user action");
        start=System.nanoTime();upload(args[2],"slow",source);long slowMs=(System.nanoTime()-start)/1000000;check(slowMs>=3000&&slowMs<5000,"healthy slow accepted response stays intact");
        HttpURLConnection counts=(HttpURLConnection)new URL(args[2]+"/stats").openConnection();String stats;try(InputStream in=counts.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);stats=out.toString("UTF-8");}finally{counts.disconnect();}
        check(stats.contains("\"/put/first\": 1"),"accepted object must not be uploaded a second time");check(stats.contains("\"/object/first\": 2"),"GET retry stays bounded");
        check(Arrays.stream(root.listFiles()).noneMatch(f->f.getName().endsWith(".part")),"no partial files remain");source.delete();target.delete();
        System.out.println("PASS Android HTTPS: accepted PUT confirmation="+uploadMs+"ms; stalled encrypted GET recovery="+downloadMs+"ms; healthy slow PUT="+slowMs+"ms; exact plaintext/no duplicate PUT/no partial files");
    }
}
