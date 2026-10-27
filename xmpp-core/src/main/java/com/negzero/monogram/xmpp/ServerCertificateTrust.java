// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.security.*;
import java.security.cert.*;
import java.util.function.Consumer;
import javax.net.ssl.*;

/** An explicit leaf exception for a valid self-signed certificate; never disables endpoint verification. */
public final class ServerCertificateTrust implements X509TrustManager {
    private final X509TrustManager system;
    private final String pinned;
    private final Consumer<X509Certificate> rejected;
    public ServerCertificateTrust(String pinned,Consumer<X509Certificate> rejected) throws Exception {
        this.system=manager(null);this.pinned=pinned;this.rejected=rejected;
    }
    private static X509TrustManager manager(KeyStore store) throws Exception {
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);
        for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager)return (X509TrustManager)manager;
        throw new KeyStoreException("No certificate trust manager available");
    }
    public static String fingerprint(X509Certificate certificate) throws CertificateEncodingException,NoSuchAlgorithmException {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());StringBuilder text=new StringBuilder();
        for(byte part:hash)text.append(String.format(java.util.Locale.ROOT,"%02x",part&255));return text.toString();
    }
    public static void selfSigned(X509Certificate[] chain) throws CertificateException {
        if(chain==null || chain.length!=1 || chain[0]==null)throw new CertificateException("Only an individual self-signed certificate can be approved");
        X509Certificate certificate=chain[0];certificate.checkValidity();
        if(!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal()))throw new CertificateException("Certificate is not self-signed");
        try{certificate.verify(certificate.getPublicKey());}catch(GeneralSecurityException invalid){throw new CertificateException("Invalid certificate signature",invalid);}
    }
    @Override public void checkServerTrusted(X509Certificate[] chain,String authType) throws CertificateException {
        if(chain==null || chain.length==0 || chain[0]==null)throw new CertificateException("Missing server certificate");
        chain[0].checkValidity();
        try {system.checkServerTrusted(chain,authType);return;}catch(CertificateException untrusted) {
            selfSigned(chain);
            try {
                X509Certificate leaf=chain[0];
                if(pinned==null || !MessageDigest.isEqual(pinned.getBytes(java.nio.charset.StandardCharsets.US_ASCII),fingerprint(leaf).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    rejected.accept(leaf);throw new CertificateException("Self-signed server certificate needs approval; review its fingerprint in Server certificate",untrusted);
                }
                KeyStore anchor=KeyStore.getInstance(KeyStore.getDefaultType());anchor.load(null,null);anchor.setCertificateEntry("approved-server",leaf);
                manager(anchor).checkServerTrusted(chain,authType);
            }catch(CertificateException invalid){throw invalid;}
            catch(Exception invalid){throw new CertificateException("Cannot validate approved certificate",invalid);}
        }
    }
    @Override public void checkClientTrusted(X509Certificate[] chain,String authType)throws CertificateException {system.checkClientTrusted(chain,authType);}
    @Override public X509Certificate[] getAcceptedIssuers(){return system.getAcceptedIssuers();}
}
