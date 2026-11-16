#!/usr/bin/env python3
"""Synthetic loopback TLS fixture: lost PUT acknowledgement and an idle GET body.

Pass a private directory containing cert.pem and key.pem; the selected port is
written there as port. The certificate must include IP:10.0.2.2 for the emulator.
No account credentials or real files are used.
"""
import http.server,ssl,threading,time,json,sys
from pathlib import Path
root=Path(sys.argv[1]);objects={};counts={};lock=threading.Lock()
class Handler(http.server.BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def log_message(self,*args):pass
 def do_PUT(self):
  length=int(self.headers.get('Content-Length','0'))
  if not 0<length<=32*1024*1024:self.send_error(413);return
  data=self.rfile.read(length);key=self.path.replace('/put/','/object/');objects[key]=data
  with lock:counts[self.path]=counts.get(self.path,0)+1
  if self.path.endswith('/first'):time.sleep(12)
  elif self.path.endswith('/slow'):time.sleep(3.3)
  try:self.send_response(201);self.send_header('Content-Length','0');self.end_headers()
  except OSError:pass
 def do_HEAD(self):
  data=objects.get(self.path);self.send_response(200 if data is not None else 404);self.send_header('Content-Length',str(len(data) if data is not None else 0));self.end_headers()
 def do_GET(self):
  if self.path=='/stats':
   data=json.dumps(counts).encode();self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  data=objects.get(self.path)
  if data is None:self.send_error(404);return
  with lock:counts[self.path]=counts.get(self.path,0)+1;count=counts[self.path]
  self.send_response(200);self.send_header('Content-Length',str(len(data)));self.send_header('Content-Type','application/octet-stream');self.end_headers()
  try:
   if self.path.endswith('/first') and count==1:self.wfile.write(data[:1]);self.wfile.flush();time.sleep(12)
   else:self.wfile.write(data)
  except OSError:pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(root/'cert.pem',root/'key.pem');server.socket=tls.wrap_socket(server.socket,server_side=True);(root/'port').write_text(str(server.server_port));print('Fixture HTTPS port',server.server_port,flush=True);server.serve_forever()
