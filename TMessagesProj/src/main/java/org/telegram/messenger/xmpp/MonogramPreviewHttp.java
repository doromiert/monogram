// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;

/** Small, bounded HTTPS reader. DNS is checked once and the resulting address is pinned to the socket. */
final class MonogramPreviewHttp {
    static final int TIMEOUT = 5000;
    static final class Response {
        final URI url;
        final String type;
        final byte[] bytes;
        Response(URI url, String type, byte[] bytes) { this.url=url; this.type=type; this.bytes=bytes; }
    }
    static URI safeUrl(String address) throws IOException {
        try {
            URI url=new URI(address);
            String host=url.getHost();
            if(!"https".equalsIgnoreCase(url.getScheme()) || host==null || host.length()>253 || url.getRawUserInfo()!=null
                    || url.getPort()!=-1 && url.getPort()!=443 || address.length()>4096 || address.indexOf('\\')>=0)
                throw new IOException("Preview requires a public HTTPS address");
            if(host.endsWith(".") || !host.contains(".") && !host.contains(":")) throw new IOException("Local host");
            // Fragments can contain private keys and never belong in a preview request.
            int fragment=address.indexOf('#');
            return new URI(fragment<0 ? address : address.substring(0,fragment));
        } catch(URISyntaxException invalid) { throw new IOException("Invalid preview URL",invalid); }
    }
    static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b=address.getAddress(); int first=b[0]&255, second=b[1]&255;
        if(b.length==4) return first!=0 && first!=10 && first!=127 && first<224
                && !(first==100 && second>=64 && second<=127) && !(first==169 && second==254)
                && !(first==172 && second>=16 && second<=31) && !(first==192 && (second==0 || second==168 || second==88 && (b[2]&255)==99))
                && !(first==198 && (second==18 || second==19 || second==51 && (b[2]&255)==100))
                && !(first==203 && second==0 && (b[2]&255)==113);
        // Accept only IPv6 global unicast; reject translation/tunnel/documentation ranges too.
        return b.length==16 && (first&0xe0)==0x20 && !(first==0x20 && second==0x01
                && ((b[2]&255)==0 || (b[2]&255)==0x0d && (b[3]&255)==0xb8)) && !(first==0x20 && second==0x02) && !(first==0x3f && second==0xff);
    }
    static Response fetch(String address,int limit,java.util.function.BooleanSupplier permitted) throws IOException {
        return fetch(address,limit,permitted,false);
    }
    static Response fetchHtml(String address,int limit,java.util.function.BooleanSupplier permitted) throws IOException {
        return fetch(address,limit,permitted,true);
    }
    private static Response fetch(String address,int limit,java.util.function.BooleanSupplier permitted,boolean htmlPrefix) throws IOException {
        final long started=System.nanoTime();
        final java.util.function.BooleanSupplier permission=permitted;
        permitted=() -> permission.getAsBoolean() && System.nanoTime()-started<TimeUnit.SECONDS.toNanos(15);
        URI url=safeUrl(address);
        for(int redirects=0;redirects<=4;redirects++) {
            if(!permitted.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Preview cancelled");
            String host=url.getHost(); if(host.startsWith("[")) host=host.substring(1,host.length()-1);
            InetAddress[] addresses=InetAddress.getAllByName(host);
            if(addresses.length==0) throw new IOException("No preview address");
            for(InetAddress ip:addresses) if(!publicAddress(ip)) throw new IOException("Unsafe preview destination");
            try(Socket raw=new Socket()) {
                raw.connect(new InetSocketAddress(addresses[0],443),TIMEOUT); raw.setSoTimeout(TIMEOUT);
                try(SSLSocket socket=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,host,443,true)) {
                    socket.setSoTimeout(TIMEOUT);
                    if(android.os.Build.VERSION.SDK_INT>=24) { SSLParameters tls=socket.getSSLParameters();tls.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(tls); }
                    socket.startHandshake();
                    if(android.os.Build.VERSION.SDK_INT<24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host,socket.getSession())) throw new SSLPeerUnverifiedException("Invalid preview certificate hostname");
                    if(!permitted.getAsBoolean()) throw new IOException("Preview cancelled");
                    String path=url.getRawPath();if(path==null || path.isEmpty()) path="/";
                    if(url.getRawQuery()!=null) path+="?"+url.getRawQuery();
                    String request="GET "+path+" HTTP/1.1\r\nHost: "+url.getRawAuthority()+"\r\nUser-Agent: Monogram-LinkPreview/1\r\nAccept: text/html, application/json, image/jpeg, image/png, image/webp\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
                    InputStream input=new BufferedInputStream(socket.getInputStream());
                    String[] status=line(input,permitted).split(" ",3);
                    if(status.length<2 || !status[0].startsWith("HTTP/1.")) throw new IOException("Invalid HTTP response");
                    int code;try { code=Integer.parseInt(status[1]); } catch(NumberFormatException invalid) { throw new IOException("Invalid status",invalid); }
                    Map<String,String> headers=new HashMap<>();int headerBytes=0;
                    while(true) {
                        String line=line(input,permitted);headerBytes+=line.length()+2;
                        if(headerBytes>32768) throw new IOException("Preview headers too large");
                        if(line.isEmpty()) break;
                        int colon=line.indexOf(':');if(colon<=0) throw new IOException("Invalid preview header");
                        String name=line.substring(0,colon).toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();
                        if(headers.put(name,value)!=null && (name.equals("content-length") || name.equals("transfer-encoding"))) throw new IOException("Ambiguous preview framing");
                    }
                    if(Arrays.asList(301,302,303,307,308).contains(code)) {
                        String next=headers.get("location");if(next==null || redirects==4) throw new IOException("Preview redirect limit");
                        try { url=safeUrl(url.resolve(next).toString()); } catch(IllegalArgumentException invalid) { throw new IOException("Invalid redirect",invalid); }
                        continue;
                    }
                    if(code!=200) throw new IOException("Preview unavailable");
                    String encoding=headers.get("content-encoding");if(encoding!=null && !encoding.equalsIgnoreCase("identity")) throw new IOException("Unsupported preview encoding");
                    String type=headers.getOrDefault("content-type","");
                    boolean html=type.toLowerCase(Locale.ROOT).startsWith("text/html") || type.toLowerCase(Locale.ROOT).startsWith("application/xhtml+xml");
                    return new Response(url,type,body(input,headers,limit,permitted,htmlPrefix && html));
                }
            }
        }
        throw new IOException("Preview redirect limit");
    }
    private static String line(InputStream input,java.util.function.BooleanSupplier permitted) throws IOException {
        ByteArrayOutputStream result=new ByteArrayOutputStream();
        for(int i=0;i<8192;i++) { if(!permitted.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Preview cancelled");int next=input.read();if(next<0) throw new EOFException();if(next=='\n') return result.toString("ISO-8859-1").replaceFirst("\\r$","");result.write(next); }
        throw new IOException("Preview header too long");
    }
    static byte[] body(InputStream input,Map<String,String> headers,int limit,java.util.function.BooleanSupplier permitted) throws IOException {
        return body(input,headers,limit,permitted,false);
    }
    static byte[] body(InputStream input,Map<String,String> headers,int limit,java.util.function.BooleanSupplier permitted,boolean prefix) throws IOException {
        String transfer=headers.get("transfer-encoding"),length=headers.get("content-length");
        if(transfer!=null && (!"chunked".equalsIgnoreCase(transfer) || length!=null)) throw new IOException("Unsupported preview framing");
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        if(transfer!=null) {
            while(true) {
                String number=line(input,permitted).split(";",2)[0].trim();long count;
                try { count=Long.parseLong(number,16); } catch(NumberFormatException invalid) { throw new IOException("Invalid chunk",invalid); }
                if(count==0) return output.toByteArray();
                if(count<0) throw new IOException("Invalid chunk size");
                if(count>limit-output.size()) {
                    if(!prefix) throw new IOException("Preview too large");
                    copy(input,output,limit-output.size(),limit,permitted);return output.toByteArray();
                }
                copy(input,output,(int)count,limit,permitted);if(!line(input,permitted).isEmpty()) throw new IOException("Invalid chunk ending");
            }
        }
        long count=-1;
        if(length!=null) try { count=Long.parseLong(length); } catch(NumberFormatException invalid) { throw new IOException("Invalid length",invalid); }
        if(count<0 && length!=null || count>limit && !prefix) throw new IOException("Preview too large");
        if(prefix && (count<0 || count>limit)) {
            byte[] buffer=new byte[8192];
            while(output.size()<limit) {
                if(!permitted.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Preview cancelled");
                int read=input.read(buffer,0,Math.min(buffer.length,limit-output.size()));if(read<0) break;output.write(buffer,0,read);
            }
        } else copy(input,output,(int)count,limit,permitted);
        return output.toByteArray();
    }
    private static void copy(InputStream input,ByteArrayOutputStream output,int count,int limit,java.util.function.BooleanSupplier permitted) throws IOException {
        byte[] buffer=new byte[8192];int remaining=count;
        while(remaining!=0) {
            if(!permitted.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Preview cancelled");
            int read=input.read(buffer,0,remaining<0 ? buffer.length : Math.min(buffer.length,remaining));
            if(read<0) { if(remaining>0) throw new EOFException();return; }
            if(output.size()+read>limit) throw new IOException("Preview too large");output.write(buffer,0,read);if(remaining>0) remaining-=read;
        }
    }
}
