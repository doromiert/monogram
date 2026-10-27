package org.telegram.messenger.xmpp;

import android.content.*;
import com.negzero.monogram.xmpp.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramCertificatesTest {
    X509Certificate leaf;Path root;
    @Before public void setup()throws Exception {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;context.getSharedPreferences("monogram-server-certificates",0).edit().clear().commit();
        root=Paths.get(System.getProperty("user.dir")).toAbsolutePath();while(!Files.exists(root.resolve("xmpp-core")))root=root.getParent();
        KeyStore store=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(root.resolve("xmpp-core/src/test/resources/server-certificates/localhost.p12"))){store.load(input,"test-only".toCharArray());}
        leaf=(X509Certificate)store.getCertificate(store.aliases().nextElement());
    }
    Account account(String jid,String host,int port)throws Exception{return new Account(jid,host,port,"Test");}
    void reject(Account account)throws Exception {try{MonogramCertificates.configure(account).certificateTrust.checkServerTrusted(new X509Certificate[]{leaf},"RSA");fail();}catch(CertificateException expected){}}
    @Test public void changedPresentedCertificateCannotBeApprovedUsingAnOldConfirmation()throws Exception {
        Account server=account("alice@example.org","node.example.org",5222);reject(server);
        KeyStore store=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(root.resolve("xmpp-core/src/test/resources/server-certificates/replacement.p12"))){store.load(input,"test-only".toCharArray());}
        X509Certificate replacement=(X509Certificate)store.getCertificate(store.aliases().nextElement());
        try{MonogramCertificates.configure(server).certificateTrust.checkServerTrusted(new X509Certificate[]{replacement},"RSA");fail();}catch(CertificateException expected){}
        try{MonogramCertificates.approve(server,leaf);fail();}catch(CertificateException expected){}
        assertNull(MonogramCertificates.trusted(server));MonogramCertificates.approve(server,replacement);
        MonogramCertificates.configure(server).certificateTrust.checkServerTrusted(new X509Certificate[]{replacement},"RSA");
    }
    @Test public void explicitApprovalIsEndpointScopedPersistentAndRevocable()throws Exception {
        Account server=account("alice@example.org","node.example.org",5222);reject(server);assertNotNull(MonogramCertificates.pending(server));assertNull(MonogramCertificates.trusted(server));
        MonogramCertificates.approve(server,leaf);assertNull(MonogramCertificates.pending(server));
        MonogramCertificates.configure(server).certificateTrust.checkServerTrusted(new X509Certificate[]{leaf},"RSA");
        MonogramCertificates.configure(account("bob@example.org","node.example.org",5222)).certificateTrust.checkServerTrusted(new X509Certificate[]{leaf},"RSA");
        reject(account("alice@other.example.org","node.example.org",5222));reject(account("alice@example.org","other.example.org",5222));reject(account("alice@example.org","node.example.org",5223));
        MonogramCertificates.forget(server);reject(server);
    }
}
