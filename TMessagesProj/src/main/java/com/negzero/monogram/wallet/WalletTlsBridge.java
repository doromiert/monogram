// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Authenticated loopback HTTP -> verified HTTPS transport for the upstream wallet API.
 * Its automatic SSL mode accepts invalid certificates, so it must never reach a remote node directly.
 * Every upstream socket verifies both the Android trust chain and the HTTPS hostname.
 * No RPC body is interpreted, persisted or logged here; binary wallet RPC remains native.
 */
public final class WalletTlsBridge implements AutoCloseable {
    private final ServerSocket server;
    private final URI target;
    private final SSLSocketFactory tls;
    private final Set<Socket> sockets=Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService workers=new ThreadPoolExecutor(0,4,15,TimeUnit.SECONDS,new SynchronousQueue<>());
    private final Thread listener;
    private final ArrayDeque<Upstream> idle=new ArrayDeque<>();
    private static final class Upstream {
        final SSLSocket socket;
        final BufferedInputStream input;
        final BufferedOutputStream output;
        long returned;
        Upstream(SSLSocket socket) throws IOException {
            this.socket=socket;input=new BufferedInputStream(socket.getInputStream(),32768);
            output=new BufferedOutputStream(socket.getOutputStream(),32768);
        }
    }
    private void discard(Upstream remote) {
        sockets.remove(remote.socket);try{remote.socket.close();}catch(IOException ignored){}
    }
    private Upstream connect() throws Exception {
        synchronized(idle){while(!idle.isEmpty()){
            Upstream remote=idle.removeFirst();
            if(!remote.socket.isClosed() && System.nanoTime()-remote.returned<TimeUnit.SECONDS.toNanos(15))return remote;
            discard(remote);
        }}
        phase="Connecting HTTPS";Socket tcp=new Socket();sockets.add(tcp);
        try {
            tcp.connect(new InetSocketAddress(target.getHost(),target.getPort()<0 ? 443 : target.getPort()),15000);
            tcp.setSoTimeout(30000);
            SSLSocket secure=(SSLSocket)tls.createSocket(tcp,target.getHost(),target.getPort()<0 ? 443 : target.getPort(),true);
            sockets.remove(tcp);sockets.add(secure);
            try {
                SSLParameters parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");
                secure.setSSLParameters(parameters);secure.setSoTimeout(30000);phase="TLS handshake";secure.startHandshake();
                if(closed)throw new IOException("Wallet connection closed.");
                return new Upstream(secure);
            }catch(Exception error){sockets.remove(secure);secure.close();throw error;}
        }catch(Exception error){sockets.remove(tcp);tcp.close();throw error;}
    }
    private static void copy(InputStream input,OutputStream output,long length,byte[] buffer) throws IOException {
        while(length>0){int count=input.read(buffer,0,(int)Math.min(buffer.length,length));
            if(count<0)throw new EOFException();output.write(buffer,0,count);length-=count;}
    }
    /** Delimit every response before pooling its TLS socket; the local RPC stays close-delimited. */
    private boolean response(Upstream remote,OutputStream reply) throws IOException {
        String status=line(remote.input);
        if(!status.matches("HTTP/1\\.[01] [2-5][0-9]{2}.*"))throw new IOException("Invalid node response.");
        List<String> headers=new ArrayList<>();long length=-1;boolean chunked=false,close=status.startsWith("HTTP/1.0");int total=0;
        for(int count=0;count<100;count++){
            String header=line(remote.input);total+=header.length();if(total>65536)throw new IOException("Node headers too long.");
            if(header.isEmpty())break;int colon=header.indexOf(':');if(colon<=0)throw new IOException("Invalid node header.");
            String name=header.substring(0,colon).toLowerCase(Locale.ROOT),value=header.substring(colon+1).trim();
            if(name.equals("content-length")){
                if(length>=0 || !value.matches("[0-9]{1,10}"))throw new IOException("Invalid node content length.");length=Long.parseLong(value);
            }else if(name.equals("transfer-encoding")){
                if(chunked || !value.equalsIgnoreCase("chunked"))throw new IOException("Invalid node transfer encoding.");chunked=true;
            }else if(name.equals("connection")){close|=value.toLowerCase(Locale.ROOT).contains("close");continue;}
            headers.add(header);if(count==99)throw new IOException("Too many node headers.");
        }
        if(chunked && length>=0)throw new IOException("Ambiguous node response framing.");
        write(reply,status+"\r\n");for(String header:headers)write(reply,header+"\r\n");write(reply,"Connection: close\r\n\r\n");
        byte[] buffer=new byte[32768];
        if(status.startsWith("HTTP/1.1 204") || status.startsWith("HTTP/1.1 304"))return !close;
        if(chunked){
            while(true){
                String size=line(remote.input);String value=size.split(";",2)[0];
                if(!value.matches("[0-9a-fA-F]{1,8}"))throw new IOException("Invalid node chunk.");
                long count=Long.parseLong(value,16);write(reply,size+"\r\n");
                if(count==0){
                    for(int i=0;i<100;i++){String trailer=line(remote.input);write(reply,trailer+"\r\n");if(trailer.isEmpty())return !close;}
                    throw new IOException("Too many node trailers.");
                }
                copy(remote.input,reply,count,buffer);
                if(!line(remote.input).isEmpty())throw new IOException("Invalid node chunk ending.");write(reply,"\r\n");
            }
        }
        if(length>=0){copy(remote.input,reply,length,buffer);return !close;}
        int count;while((count=remote.input.read(buffer))!=-1)reply.write(buffer,0,count);return false;
    }
    private volatile boolean closed;
    volatile Exception lastFailure;
    volatile String phase="Waiting for native RPC";
    public final String user="monogram",password;
    private final String authorization,nonce;
    private final LinkedHashMap<String,Long> nonceCounts=new LinkedHashMap<>();
    public WalletTlsBridge(String endpoint) throws Exception {this(endpoint,(SSLSocketFactory)SSLSocketFactory.getDefault());}
    // Package-private injection is exclusively for delivery tests with a private localhost test CA.
    WalletTlsBridge(String endpoint,SSLSocketFactory factory) throws Exception {
        target=new URI(endpoint);tls=factory;
        if(!"https".equalsIgnoreCase(target.getScheme()) || target.getHost()==null || target.getRawUserInfo()!=null || target.getRawQuery()!=null || target.getRawFragment()!=null || (!target.getPath().isEmpty() && !target.getPath().equals("/")) || target.getPort()==0 || target.getPort()>65535)
            throw new IllegalArgumentException("Use an HTTPS node URL without credentials, path or query.");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);StringBuilder token=new StringBuilder();for(byte b:random)token.append(String.format(Locale.ROOT,"%02x",b&255));Arrays.fill(random,(byte)0);password=token.toString();nonce=UUID.randomUUID().toString();
        authorization="Basic "+android.util.Base64.encodeToString((user+":"+password).getBytes(StandardCharsets.US_ASCII),android.util.Base64.NO_WRAP);
        server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),4);
        listener=new Thread(this::listen,"Monogram wallet TLS");listener.setDaemon(true);listener.start();
    }
    public String endpoint(){return "http://127.0.0.1:"+server.getLocalPort();}
    private void listen(){while(!closed){Socket client=null;try{
        client=server.accept();client.setSoTimeout(5000);sockets.add(client);final Socket accepted=client;workers.execute(()->proxy(accepted));
    }catch(Exception rejected){if(client!=null){sockets.remove(client);try{client.close();}catch(IOException ignored){}}}}}
    private static String md5(String value) throws Exception {
        byte[] hash=java.security.MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.ISO_8859_1));StringBuilder text=new StringBuilder();for(byte b:hash)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();
    }
    private boolean authorized(String header,String method,String uri) throws Exception {
        if(header.startsWith("Basic "))return java.security.MessageDigest.isEqual(header.getBytes(StandardCharsets.US_ASCII),authorization.getBytes(StandardCharsets.US_ASCII));
        if(!header.startsWith("Digest "))return false;
        Map<String,String> fields=new HashMap<>();String value=header.substring(7);int offset=0;
        java.util.regex.Pattern field=java.util.regex.Pattern.compile("\\s*([a-zA-Z]+)=([\"]([^\"]*)[\"]|([^, ]+))\\s*(,|$)");
        while(offset<value.length()){
            java.util.regex.Matcher match=field.matcher(value);match.region(offset,value.length());if(!match.lookingAt())return false;
            String key=match.group(1).toLowerCase(Locale.ROOT);if(fields.put(key,match.group(3)==null ? match.group(4) : match.group(3))!=null)return false;offset=match.end();
        }
        if(!user.equals(fields.get("username")) || !"Monogram wallet".equals(fields.get("realm")) || !nonce.equals(fields.get("nonce")) || !uri.equals(fields.get("uri")) || !"auth".equals(fields.get("qop")) || !"MD5".equalsIgnoreCase(fields.getOrDefault("algorithm","MD5")))return false;
        String nc=fields.get("nc"),cnonce=fields.get("cnonce"),response=fields.get("response");
        if(nc==null || !nc.matches("[0-9a-fA-F]{8}") || cnonce==null || cnonce.length()>256 || response==null)return false;
        String expected=md5(md5(user+":Monogram wallet:"+password)+":"+nonce+":"+nc+":"+cnonce+":auth:"+md5(method+":"+uri));
        if(!java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),response.getBytes(StandardCharsets.US_ASCII)))return false;
        long number=Long.parseLong(nc,16);synchronized(nonceCounts){if(number<=nonceCounts.getOrDefault(cnonce,0L))return false;nonceCounts.put(cnonce,number);if(nonceCounts.size()>1024)nonceCounts.remove(nonceCounts.keySet().iterator().next());}return true;
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream value=new ByteArrayOutputStream();int previous=-1;
        for(int size=0;size<8192;size++){int next=input.read();if(next<0)throw new EOFException();if(previous=='\r' && next=='\n'){byte[] bytes=value.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.ISO_8859_1);}value.write(next);previous=next;}
        throw new IOException("HTTP header too long.");
    }
    private static void write(OutputStream output,String value)throws IOException{output.write(value.getBytes(StandardCharsets.ISO_8859_1));}
    private void proxy(Socket client){proxy(client,0);}
    private void proxy(Socket client,int attempt){Upstream remote=null;boolean reusable=false,responseStarted=false;try{
        BufferedInputStream input=new BufferedInputStream(client.getInputStream());OutputStream reply=client.getOutputStream();
        // The upstream automatic mode probes TLS even for loopback HTTP. Reject that
        // probe immediately so it reconnects using authenticated local HTTP.
        input.mark(1);int first=input.read();input.reset();
        if(first==22){write(reply,"HTTP/1.0 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");reply.flush();return;}
        String request=line(input);String[] parts=request.split(" ",-1);
        if(parts.length!=3 || (!parts[0].equals("POST") && !parts[0].equals("GET")) || !parts[1].startsWith("/") || parts[1].startsWith("//") || (!parts[2].equals("HTTP/1.1") && !parts[2].equals("HTTP/1.0")))throw new IOException("Invalid wallet request.");
        List<String> headers=new ArrayList<>();long length=0;boolean lengthSeen=false,authorized=false;int total=request.length();
        for(int count=0;count<100;count++){
            String header=line(input);total+=header.length();if(total>65536)throw new IOException("HTTP headers too long.");if(header.isEmpty())break;
            int colon=header.indexOf(':');if(colon<=0 || header.charAt(0)==' ' || header.charAt(0)=='\t')throw new IOException("Invalid header.");
            String name=header.substring(0,colon).toLowerCase(Locale.ROOT),value=header.substring(colon+1).trim();
            if(name.equals("authorization")){if(authorized || !authorized(value,parts[0],parts[1]))throw new IOException("Invalid local authorization.");authorized=true;}
            else if(name.equals("content-length")){if(lengthSeen || !value.matches("[0-9]{1,9}"))throw new IOException("Invalid content length.");lengthSeen=true;length=Long.parseLong(value);if(length>32*1024*1024)throw new IOException("Wallet request too large.");}
            else if(name.equals("transfer-encoding") || name.equals("expect") || name.equals("upgrade"))throw new IOException("Unsupported transfer mode.");
            else if(!name.equals("host") && !name.equals("connection") && !name.equals("proxy-authorization"))headers.add(header);
            if(count==99)throw new IOException("Too many headers.");
        }
        if(!authorized){
            // The native Digest client retries on the same connection. Drain the first
            // body and keep that socket alive for exactly one authenticated retry.
            byte[] discard=new byte[4096];while(length>0){int read=input.read(discard,0,(int)Math.min(discard.length,length));if(read<0)throw new EOFException();length-=read;}
            write(reply,"HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"Monogram wallet\", nonce=\""+nonce+"\", algorithm=MD5, qop=\"auth\"\r\nContent-Length: 0\r\nConnection: "+(attempt==0 ? "keep-alive" : "close")+"\r\n\r\n");reply.flush();
            if(attempt==0)proxy(client,1);return;
        }
        if(closed)return;
        phase="Authenticated native RPC";remote=connect();
        OutputStream output=remote.output;write(output,parts[0]+" "+parts[1]+" HTTP/1.1\r\n");
        String host=target.getHost();if(host.contains(":"))host="["+host+"]";if(target.getPort()>0)host+=":"+target.getPort();
        write(output,"Host: "+host+"\r\nConnection: keep-alive\r\nContent-Length: "+length+"\r\n");
        for(String header:headers)write(output,header+"\r\n");write(output,"\r\n");
        copy(input,output,length,new byte[32768]);output.flush();client.setSoTimeout(30000);
        // Never automatically replay an RPC: it may be a transaction broadcast.
        phase="Reading node reply";responseStarted=true;reusable=response(remote,reply);reply.flush();phase="RPC complete";
    }catch(Exception failure){lastFailure=failure;phase="Connection failed: "+failure.getClass().getSimpleName();try{if(!responseStarted)write(client.getOutputStream(),"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");}catch(IOException ignored){}}
    finally{sockets.remove(client);try{client.close();}catch(IOException ignored){}
        if(remote!=null){synchronized(idle){
            if(reusable && !closed && idle.size()<4){remote.returned=System.nanoTime();idle.addLast(remote);}else discard(remote);
        }}
    }}
    @Override public void close(){closed=true;try{server.close();}catch(IOException ignored){}synchronized(sockets){for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}sockets.clear();}synchronized(idle){idle.clear();}workers.shutdownNow();}
}
