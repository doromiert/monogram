// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;
import java.io.*;import java.net.*;import javax.net.SocketFactory;import javax.net.ssl.*;
/** TLS completes and verifies the XMPP service domain before Smack can write a single byte. */
final class DirectTlsSocketFactory extends SocketFactory {
 private final Account account;private final SSLSocketFactory tls;private volatile SecureSocket current;
 DirectTlsSocketFactory(Account account)throws Exception {this.account=account;SSLContext context=SSLContext.getInstance("TLS");context.init(null,account.certificateTrust==null?null:new TrustManager[]{account.certificateTrust},null);tls=context.getSocketFactory();}
 boolean secure(){SecureSocket socket=current;return socket!=null&&socket.verified&&!socket.isClosed();}
 @Override public Socket createSocket(){SecureSocket socket=new SecureSocket();current=socket;return socket;}
 @Override public Socket createSocket(String host,int port)throws IOException{Socket s=createSocket();s.connect(new InetSocketAddress(host,port));return s;}
 @Override public Socket createSocket(InetAddress host,int port)throws IOException{return createSocket(host.getHostAddress(),port);}
 @Override public Socket createSocket(String h,int p,InetAddress local,int lp)throws IOException{Socket s=createSocket();s.bind(new InetSocketAddress(local,lp));s.connect(new InetSocketAddress(h,p));return s;}
 @Override public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return createSocket(h.getHostAddress(),p,l,lp);}
 private final class SecureSocket extends Socket {
  private Socket raw=new Socket();private volatile SSLSocket encrypted;private volatile boolean verified;
  @Override public void connect(SocketAddress address,int timeout)throws IOException {
   try{
    InetSocketAddress endpoint=(InetSocketAddress)address;
    if(account.proxy==null)raw.connect(address,timeout);else account.proxy.getProxySocketConnection().connect(raw,endpoint.getHostString(),endpoint.getPort(),timeout);
    raw.setSoTimeout(timeout>0?timeout:10000);
    SSLSocket socket=(SSLSocket)tls.createSocket(raw,account.jid.getDomain().toString(),endpoint.getPort(),true);encrypted=socket;
    SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);
    java.util.ArrayList<String> protocols=new java.util.ArrayList<>();for(String protocol:socket.getSupportedProtocols())if(protocol.equals("TLSv1.3")||protocol.equals("TLSv1.2"))protocols.add(protocol);socket.setEnabledProtocols(protocols.toArray(new String[0]));socket.startHandshake();verified=true;
   }catch(IOException|RuntimeException error){close();throw error;}
  }
  @Override public void connect(SocketAddress a)throws IOException{connect(a,10000);}
  private Socket active(){return encrypted==null?raw:encrypted;}
  @Override public void bind(SocketAddress a)throws IOException{raw.bind(a);}
  @Override public InputStream getInputStream()throws IOException{if(!verified)throw new SSLException("Direct TLS is not verified.");return encrypted.getInputStream();}
  @Override public OutputStream getOutputStream()throws IOException{if(!verified)throw new SSLException("Direct TLS is not verified.");return encrypted.getOutputStream();}
  @Override public synchronized void close()throws IOException{verified=false;active().close();}
  @Override public boolean isClosed(){return active().isClosed();}
  @Override public boolean isConnected(){return active().isConnected();}
  @Override public boolean isInputShutdown(){return active().isInputShutdown();}
  @Override public boolean isOutputShutdown(){return active().isOutputShutdown();}
  @Override public void shutdownInput()throws IOException{active().shutdownInput();}
  @Override public void shutdownOutput()throws IOException{active().shutdownOutput();}
  @Override public void setSoTimeout(int timeout)throws SocketException{active().setSoTimeout(timeout);}
  @Override public int getSoTimeout()throws SocketException{return active().getSoTimeout();}
  @Override public void setTcpNoDelay(boolean on)throws SocketException{active().setTcpNoDelay(on);}
  @Override public boolean getTcpNoDelay()throws SocketException{return active().getTcpNoDelay();}
  @Override public void setKeepAlive(boolean on)throws SocketException{active().setKeepAlive(on);}
  @Override public InetAddress getInetAddress(){return active().getInetAddress();}
  @Override public InetAddress getLocalAddress(){return active().getLocalAddress();}
  @Override public int getPort(){return active().getPort();}
  @Override public int getLocalPort(){return active().getLocalPort();}
  @Override public SocketAddress getRemoteSocketAddress(){return active().getRemoteSocketAddress();}
 }
}
