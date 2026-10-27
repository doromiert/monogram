package com.negzero.monogram.xmpp;

import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerCertificateTrustTest {
    KeyStore store(String name)throws Exception {
        KeyStore store=KeyStore.getInstance("PKCS12");
        try(InputStream input=getClass().getResourceAsStream("/server-certificates/"+name+".p12")){store.load(input,"test-only".toCharArray());}return store;
    }
    X509Certificate certificate(String name)throws Exception {KeyStore store=store(name);return (X509Certificate)store.getCertificate(store.aliases().nextElement());}
    @Test void approvalRequiresExactLeafAndValidSelfSignatureAndExpiry()throws Exception {
        X509Certificate leaf=certificate("localhost"),changed=certificate("replacement");AtomicReference<X509Certificate> seen=new AtomicReference<>();
        assertThrows(CertificateException.class,()->new ServerCertificateTrust(null,seen::set).checkServerTrusted(new X509Certificate[]{leaf},"RSA"));assertEquals(leaf,seen.get());
        ServerCertificateTrust approved=new ServerCertificateTrust(ServerCertificateTrust.fingerprint(leaf),seen::set);
        approved.checkServerTrusted(new X509Certificate[]{leaf},"RSA");
        assertThrows(CertificateException.class,()->approved.checkServerTrusted(new X509Certificate[]{changed},"RSA"));assertEquals(changed,seen.get());
        assertThrows(CertificateException.class,()->approved.checkServerTrusted(new X509Certificate[]{leaf,changed},"RSA"));
        X509Certificate expired;
        try(InputStream input=getClass().getResourceAsStream("/server-certificates/expired.pem")){expired=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);}
        assertThrows(CertificateExpiredException.class,()->new ServerCertificateTrust(ServerCertificateTrust.fingerprint(expired),ignored->{}).checkServerTrusted(new X509Certificate[]{expired},"RSA"));
        assertTrue(approved.getAcceptedIssuers().length>0);
    }
    void handshake(String host,String pin,boolean success)throws Exception {
        KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store("localhost"),"test-only".toCharArray());
        SSLContext serverContext=SSLContext.getInstance("TLS");serverContext.init(keys.getKeyManagers(),null,null);
        try(SSLServerSocket server=(SSLServerSocket)serverContext.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<?> remote=worker.submit(()->{try(SSLSocket socket=(SSLSocket)server.accept()){socket.setSoTimeout(5000);socket.startHandshake();}catch(IOException expected){}});
            try {
                SSLContext client=SSLContext.getInstance("TLS");client.init(null,new TrustManager[]{new ServerCertificateTrust(pin,ignored->{})},null);
                try(java.net.Socket raw=new java.net.Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());SSLSocket socket=(SSLSocket)client.getSocketFactory().createSocket(raw,host,server.getLocalPort(),true)) {
                    socket.setSoTimeout(5000);SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);
                    if(success)socket.startHandshake();else assertThrows(SSLException.class,socket::startHandshake);
                }
                remote.get(6,TimeUnit.SECONDS);
            }finally{worker.shutdownNow();}
        }
    }
    @Test void realHandshakeStillRequiresApprovalAndMatchingHostname()throws Exception {
        String pin=ServerCertificateTrust.fingerprint(certificate("localhost"));
        handshake("localhost",null,false);handshake("localhost",pin,true);handshake("wrong.example.org",pin,false);
    }
}
