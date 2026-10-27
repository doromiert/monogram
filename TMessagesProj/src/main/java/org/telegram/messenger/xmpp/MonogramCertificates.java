// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.*;
import android.util.Base64;
import com.negzero.monogram.xmpp.*;
import java.io.ByteArrayInputStream;
import java.security.cert.*;
import org.telegram.messenger.ApplicationLoader;

/** Public server certificates only. Exceptions are scoped to the service domain and configured endpoint. */
public final class MonogramCertificates {
    private MonogramCertificates() {}
    private static SharedPreferences prefs(){return ApplicationLoader.applicationContext.getSharedPreferences("monogram-server-certificates",Context.MODE_PRIVATE);}
    private static String key(Account account){return account.jid.getDomain()+"\n"+(account.host==null ? "" : account.host.toLowerCase(java.util.Locale.ROOT))+"\n"+account.port;}
    private static X509Certificate read(String value)throws Exception {
        if(value==null)return null;if(value.length()>32768)throw new CertificateException("Certificate is too large");
        return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.decode(value,Base64.NO_WRAP)));
    }
    public static X509Certificate pending(Account account)throws Exception{return read(prefs().getString("pending-"+key(account),null));}
    public static X509Certificate trusted(Account account)throws Exception{return read(prefs().getString("trusted-"+key(account),null));}
    public static String endpoint(Account account){return account.jid.getDomain()+"\nHost: "+(account.host==null ? "Automatic discovery" : account.host)+" · port "+account.port;}
    public static Account configure(Account account)throws Exception {
        X509Certificate approved=trusted(account);String fingerprint=approved==null ? null : ServerCertificateTrust.fingerprint(approved);
        ServerCertificateTrust trust=new ServerCertificateTrust(fingerprint,certificate->{
            try {
                byte[] der=certificate.getEncoded();if(der.length>24576)throw new CertificateException("Certificate is too large");
                if(!prefs().edit().putString("pending-"+key(account),Base64.encodeToString(der,Base64.NO_WRAP)).commit())throw new IllegalStateException("Could not save server certificate for review");
            }catch(CertificateEncodingException failed){throw new IllegalStateException("Could not read server certificate",failed);}
            catch(CertificateException failed){throw new IllegalStateException(failed);}
        });
        return new Account(account.jid.toString(),account.host,account.port,account.resource.toString(),account.proxy,trust);
    }
    public static void approve(Account account,X509Certificate certificate)throws Exception {
        X509Certificate current=pending(account);ServerCertificateTrust.selfSigned(new X509Certificate[]{certificate});
        if(current==null || !ServerCertificateTrust.fingerprint(current).equals(ServerCertificateTrust.fingerprint(certificate)))throw new CertificateException("The presented certificate changed. Review it again.");
        if(!prefs().edit().putString("trusted-"+key(account),Base64.encodeToString(certificate.getEncoded(),Base64.NO_WRAP)).remove("pending-"+key(account)).commit())throw new IllegalStateException("Could not save certificate approval");
    }
    public static void forget(Account account) {if(!prefs().edit().remove("trusted-"+key(account)).commit())throw new IllegalStateException("Could not remove certificate approval");}
}
