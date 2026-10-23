#!/usr/bin/env python3
"""Runs the actual Java bridge against a private TLS node fixture; requires JDK and openssl."""
import http.server,ssl,tempfile,pathlib,subprocess,threading,argparse,os
p=argparse.ArgumentParser();p.add_argument('--java-home',default=os.environ.get('JAVA_HOME',''));p.add_argument('--openssl',default='openssl');args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]
java=lambda tool:str(pathlib.Path(args.java_home)/'bin'/tool) if args.java_home else tool
connections=set();requests=[]
class Server(http.server.ThreadingHTTPServer):daemon_threads=True
class Handler(http.server.BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def do_POST(self):
  assert self.headers.get('Authorization') is None,'Loopback authorization leaked upstream'
  assert self.headers.get('Proxy-Authorization') is None
  raw=self.rfile.read(int(self.headers['Content-Length']));assert raw==bytes([0,1,255,10])
  connections.add(self.connection);requests.append(self.path)
  self.send_response(200)
  if self.path=='/chunked':
   self.send_header('Transfer-Encoding','chunked');self.end_headers();self.wfile.write(b'4\r\n'+raw+b'\r\n0\r\n\r\n')
  else:
   self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
  self.wfile.flush()
 def log_message(self,*args):pass
with tempfile.TemporaryDirectory(prefix='monogram-wallet-tls-') as directory:
 d=pathlib.Path(directory);cert=d/'cert.pem';key=d/'key.pem'
 subprocess.run([args.openssl,'req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 server=Server(('127.0.0.1',0),Handler);tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(cert,key);server.socket=tls.wrap_socket(server.socket,server_side=True)
 threading.Thread(target=server.serve_forever,daemon=True).start()
 stub=d/'android/util/Base64.java';stub.parent.mkdir(parents=True)
 stub.write_text('package android.util; public class Base64 { public static final int NO_WRAP=2; public static String encodeToString(byte[] b,int flags){return java.util.Base64.getEncoder().encodeToString(b);} }')
 classes=d/'classes';classes.mkdir()
 subprocess.run([java('javac'),'--release','8','-d',str(classes),str(stub),str(root/'TMessagesProj/src/main/java/com/negzero/monogram/wallet/WalletTlsBridge.java'),str(root/'tests/native-wallet/WalletTlsBridgeTest.java')],check=True)
 try:
  subprocess.run([java('java'),'-cp',str(classes),'com.negzero.monogram.wallet.WalletTlsBridgeTest',f'https://localhost:{server.server_port}',str(cert)],check=True,timeout=30)
  assert len(requests)==4,requests
  assert len(connections)==1,f'{len(connections)} TLS connections for 4 RPCs'
  print('PASS: four RPCs used one verified TLS connection; no automatic RPC replay or local credential forwarding')
 finally:server.shutdown();server.server_close()
