mod numeric;
use crate::{Meta, Peer, Result, Store, constant, failure, hash, now, random, wire};
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use rustls::{
    ClientConfig, ClientConnection, DigitallySignedStruct, ServerConfig, ServerConnection,
    SignatureScheme, StreamOwned,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime},
};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    thread,
    time::Duration,
};
#[derive(Clone, Serialize, Deserialize)]
pub struct Invite {
    pub format: String,
    pub version: u32,
    pub id: String,
    pub name: String,
    pub endpoints: Vec<SocketAddr>,
    pub pin: String,
    pub code: String,
    pub expires: u64,
}
#[derive(Serialize, Deserialize)]
struct Hello {
    version: u32,
    action: String,
    id: String,
    name: String,
    pin: String,
    endpoint: u16,
    #[serde(default)]
    excluded: Vec<String>,
    token: String,
    manifest: Vec<Meta>,
}
#[derive(Serialize, Deserialize)]
struct Exchange {
    manifest: Vec<Meta>,
    want: Vec<String>,
}
#[derive(Debug)]
struct Pinned(String);
impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        cert: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> std::result::Result<ServerCertVerified, rustls::Error> {
        if constant(&hash(cert.as_ref()), &self.0) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "Device certificate changed. Pair again.".into(),
            ))
        }
    }
    fn verify_tls12_signature(
        &self,
        msg: &[u8],
        cert: &CertificateDer<'_>,
        sig: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            msg,
            cert,
            sig,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }
    fn verify_tls13_signature(
        &self,
        msg: &[u8],
        cert: &CertificateDer<'_>,
        sig: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            msg,
            cert,
            sig,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}
struct SocketGuard<'a> {
    mesh: &'a Mesh,
    id: u64,
}
impl Drop for SocketGuard<'_> {
    fn drop(&mut self) {
        self.mesh
            .sockets
            .lock()
            .unwrap()
            .retain(|(id, _, _)| *id != self.id);
    }
}
pub struct Mesh {
    pub store: Mutex<Store>,
    pub status: Mutex<String>,
    stopped: AtomicBool,
    invite: Mutex<Option<Invite>>,
    port: u16,
    server: Arc<ServerConfig>,
    addresses: Mutex<Vec<SocketAddr>>,
    sockets: Mutex<Vec<(u64, String, TcpStream)>>,
    serial: std::sync::atomic::AtomicU64,
    active: AtomicUsize,
    code_attempts: AtomicUsize,
    numeric: AtomicBool,
    candidates: Mutex<BTreeMap<String, numeric::Candidate>>,
}
fn local(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => ip.is_private() || ip.is_link_local() || ip.is_loopback(),
        IpAddr::V6(ip) => ip.is_loopback() || ip.is_unique_local() || ip.is_unicast_link_local(),
    }
}
#[cfg(not(target_os = "android"))]
fn endpoints(port: u16) -> Vec<SocketAddr> {
    get_if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|i| match i.ip() {
            IpAddr::V4(ip) if !ip.is_loopback() && local(ip.into()) => {
                Some(SocketAddr::new(ip.into(), port))
            }
            _ => None,
        })
        .take(8)
        .collect()
}
#[cfg(target_os = "android")]
fn endpoints(_port: u16) -> Vec<SocketAddr> {
    vec![]
}
fn socket(address: SocketAddr) -> Result<TcpStream> {
    if !local(address.ip()) {
        return Err("Device sync only connects to local-network addresses.".into());
    }
    let socket = TcpStream::connect_timeout(&address, Duration::from_secs(3)).map_err(failure)?;
    socket
        .set_read_timeout(Some(Duration::from_secs(15)))
        .map_err(failure)?;
    socket
        .set_write_timeout(Some(Duration::from_secs(15)))
        .map_err(failure)?;
    Ok(socket)
}
fn client(address: SocketAddr, pin: &str) -> Result<StreamOwned<ClientConnection, TcpStream>> {
    let config =
        ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(failure)?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(Pinned(pin.into())))
            .with_no_client_auth();
    let connection = ClientConnection::new(
        Arc::new(config),
        ServerName::try_from("monogram.local").unwrap(),
    )
    .map_err(failure)?;
    Ok(StreamOwned::new(connection, socket(address)?))
}
impl Mesh {
    pub fn start(store: Store) -> Result<Arc<Self>> {
        let cert = CertificateDer::from(B64.decode(&store.state.cert).map_err(failure)?);
        let key = PrivateKeyDer::try_from(B64.decode(&store.state.private_key).map_err(failure)?)
            .map_err(failure)?;
        let server =
            ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_protocol_versions(&[&rustls::version::TLS13])
                .map_err(failure)?
                .with_no_client_auth()
                .with_single_cert(vec![cert], key)
                .map_err(failure)?;
        let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)).map_err(failure)?;
        listener.set_nonblocking(true).map_err(failure)?;
        let port = listener.local_addr().map_err(failure)?.port();
        let mesh = Arc::new(Self {
            store: Mutex::new(store),
            status: Mutex::new("Ready to pair".into()),
            stopped: AtomicBool::new(false),
            invite: Mutex::new(None),
            port,
            server: Arc::new(server),
            addresses: Mutex::new(endpoints(port)),
            sockets: Mutex::new(vec![]),
            serial: std::sync::atomic::AtomicU64::new(1),
            active: AtomicUsize::new(0),
            code_attempts: AtomicUsize::new(0),
            numeric: AtomicBool::new(false),
            candidates: Mutex::new(BTreeMap::new()),
        });
        let weak = Arc::downgrade(&mesh);
        thread::spawn(move || {
            loop {
                let Some(mesh) = weak
                    .upgrade()
                    .filter(|m| !m.stopped.load(Ordering::Relaxed))
                else {
                    break;
                };
                match listener.accept() {
                    Ok((stream, address)) if local(address.ip()) => {
                        if mesh.active.fetch_add(1, Ordering::AcqRel) >= 4 {
                            mesh.active.fetch_sub(1, Ordering::AcqRel);
                            continue;
                        }
                        thread::spawn(move || {
                            let _ = stream.set_read_timeout(Some(Duration::from_secs(15)));
                            let _ = stream.set_write_timeout(Some(Duration::from_secs(15)));
                            let _ = mesh.accept(stream, address);
                            mesh.active.fetch_sub(1, Ordering::AcqRel);
                        });
                    }
                    _ => {
                        drop(mesh);
                        thread::sleep(Duration::from_millis(100));
                    }
                }
            }
        });
        let weak = Arc::downgrade(&mesh);
        thread::spawn(move || {
            loop {
                let Some(mesh) = weak
                    .upgrade()
                    .filter(|m| !m.stopped.load(Ordering::Relaxed))
                else {
                    break;
                };
                let peers: Vec<_> = mesh
                    .store
                    .lock()
                    .unwrap()
                    .state
                    .peers
                    .values()
                    .cloned()
                    .collect();
                for peer in peers {
                    if mesh.stopped.load(Ordering::Relaxed) {
                        break;
                    }
                    if let Err(error) = mesh.exchange(&peer) {
                        *mesh.status.lock().unwrap() = error;
                    }
                }
                drop(mesh);
                thread::sleep(Duration::from_secs(5));
            }
        });
        Self::discover(&mesh);
        Ok(mesh)
    }
    pub fn set_addresses(&self, ips: Vec<IpAddr>) {
        *self.addresses.lock().unwrap() = ips
            .into_iter()
            .filter(|ip| local(*ip) && !ip.is_loopback())
            .take(8)
            .map(|ip| SocketAddr::new(ip, self.port))
            .collect();
    }
    pub fn port(&self) -> u16 {
        self.port
    }
    pub fn cancel_invite(&self) {
        self.invite.lock().unwrap().take();
        self.numeric.store(false, Ordering::Release);
    }
    pub fn is_stopped(&self) -> bool {
        self.stopped.load(Ordering::Acquire)
    }
    pub fn stop(&self) {
        self.stopped.store(true, Ordering::Release);
        self.cancel_invite();
        for (_, _, socket) in self.sockets.lock().unwrap().iter() {
            let _ = socket.shutdown(std::net::Shutdown::Both);
        }
    }
    fn watch(&self, stream: &TcpStream, peer: &str) -> Result<SocketGuard<'_>> {
        if self.is_stopped() {
            return Err("Device sync stopped.".into());
        }
        let id = self.serial.fetch_add(1, Ordering::Relaxed);
        self.sockets
            .lock()
            .unwrap()
            .push((id, peer.into(), stream.try_clone().map_err(failure)?));
        Ok(SocketGuard { mesh: self, id })
    }
    pub fn invite(&self) -> Result<String> {
        if self.is_stopped() {
            return Err("Device sync stopped.".into());
        }
        let store = self.store.lock().unwrap();
        let invitation = Invite {
            format: "monogram-lan-sync".into(),
            version: 1,
            id: store.state.id.clone(),
            name: store.state.name.clone(),
            endpoints: self.addresses.lock().unwrap().clone(),
            pin: store.pin()?,
            code: B64.encode(random::<32>()),
            expires: now() + 120_000,
        };
        if invitation.endpoints.is_empty() {
            return Err("Connect both devices to the same local network.".into());
        }
        let value = serde_json::to_vec(&invitation).map_err(failure)?;
        *self.invite.lock().unwrap() = Some(invitation);
        Ok(format!(
            "monogram-sync:{}",
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(value)
        ))
    }
    pub fn join(&self, code: &str) -> Result<String> {
        if code.len() > 8192 {
            return Err("Invalid pairing code.".into());
        }
        let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(
                code.strip_prefix("monogram-sync:")
                    .ok_or("Scan a Monogram device-pairing QR code.")?,
            )
            .map_err(failure)?;
        let invite: Invite = serde_json::from_slice(&bytes).map_err(failure)?;
        if uuid::Uuid::parse_str(&invite.id).is_err()
            || invite.name.len() > 320
            || invite.name.chars().any(char::is_control)
            || invite.format != "monogram-lan-sync"
            || invite.version != 1
            || invite.expires < now()
            || invite.expires > now() + 180_000
            || invite.endpoints.len() > 8
            || invite.pin.len() != 64
            || B64.decode(&invite.code).map_err(failure)?.len() != 32
        {
            return Err("This pairing code is invalid or expired.".into());
        }
        let store = self.store.lock().unwrap();
        if store.state.id == invite.id {
            return Err("Choose another device.".into());
        }
        let hello = Hello {
            version: 1,
            action: "pair".into(),
            id: store.state.id.clone(),
            name: store.state.name.clone(),
            pin: store.pin()?,
            endpoint: self.port,
            excluded: vec![],
            token: invite.code.clone(),
            manifest: vec![],
        };
        drop(store);
        for address in &invite.endpoints {
            let Ok(mut stream) = client(*address, &invite.pin) else {
                continue;
            };
            let _guard = self.watch(&stream.sock, &invite.id)?;
            if wire::send(&mut stream, &hello).is_err() {
                continue;
            }
            let reply: Peer = wire::read(&mut stream)?;
            if reply.id != invite.id
                || reply.pin != invite.pin
                || B64.decode(&reply.token).map_err(failure)?.len() != 32
            {
                return Err("Pairing identity verification failed.".into());
            }
            if self.is_stopped() {
                return Err("Device sync stopped.".into());
            }
            let peer = Peer {
                endpoint: address.to_string(),
                ..reply
            };
            let name = peer.name.clone();
            let mut store = self.store.lock().unwrap();
            if store.state.peers.len() >= 32 {
                return Err("At most 32 paired devices are supported.".into());
            }
            store.state.peers.insert(peer.id.clone(), peer);
            store.save()?;
            *self.status.lock().unwrap() = "Paired · syncing".into();
            return Ok(name);
        }
        Err("Could not reach the device. Check Wi-Fi and its pairing screen.".into())
    }
    fn accept(&self, stream: TcpStream, address: SocketAddr) -> Result<()> {
        let socket_guard = self.watch(&stream, "")?;
        let connection = ServerConnection::new(self.server.clone()).map_err(failure)?;
        let mut stream = StreamOwned::new(connection, stream);
        let hello: Hello = wire::read(&mut stream)?;
        for (id, peer, _) in self.sockets.lock().unwrap().iter_mut() {
            if *id == socket_guard.id {
                *peer = hello.id.clone();
            }
        }
        if self.is_stopped()
            || hello.excluded.len() > 256
            || hello.excluded.iter().any(|s| s.len() > 1024)
            || hello.version != 1
            || hello.name.len() > 320
            || hello.name.chars().any(char::is_control)
            || uuid::Uuid::parse_str(&hello.id).is_err()
            || hello.pin.len() != 64
            || hello.endpoint == 0
            || hello.manifest.len() > crate::storage::MAX_RECORDS
        {
            return Err("Invalid device handshake.".into());
        }
        if hello.action == "totp" {
            return self.accept_numeric(hello, address, &mut stream);
        }
        if hello.action == "pair" {
            let mut invitation = self.invite.lock().unwrap();
            let invite = invitation
                .as_ref()
                .filter(|i| i.expires >= now() && constant(&i.code, &hello.token))
                .ok_or("Pairing request is not authorized.")?;
            let _ = invite;
            let mut store = self.store.lock().unwrap();
            if store.state.peers.len() >= 32 {
                return Err("At most 32 paired devices are supported.".into());
            }
            if store
                .state
                .peers
                .get(&hello.id)
                .is_some_and(|p| p.pin != hello.pin)
            {
                return Err("Device identity changed; forget it before pairing again.".into());
            }
            let token = B64.encode(random::<32>());
            let reply = Peer {
                id: store.state.id.clone(),
                name: store.state.name.clone(),
                pin: store.pin()?,
                token: token.clone(),
                endpoint: String::new(),
            };
            store.state.peers.insert(
                hello.id.clone(),
                Peer {
                    id: hello.id,
                    name: hello.name,
                    pin: hello.pin,
                    token,
                    endpoint: SocketAddr::new(address.ip(), hello.endpoint).to_string(),
                },
            );
            store.save()?;
            *invitation = None;
            drop(invitation);
            self.numeric.store(false, Ordering::Release);
            drop(store);
            wire::send(&mut stream, &reply)?;
            *self.status.lock().unwrap() = "Paired · syncing".into();
            return Ok(());
        }
        if hello.action != "exchange" {
            return Err("Invalid sync request.".into());
        }
        let store = self.store.lock().unwrap();
        let peer = store
            .state
            .peers
            .get(&hello.id)
            .filter(|p| constant(&p.token, &hello.token) && p.pin == hello.pin)
            .ok_or("Device is no longer paired.")?;
        let _ = peer;
        let local: Vec<_> = store
            .metas()
            .into_iter()
            .filter(|m| !hello.excluded.contains(&m.owner))
            .collect();
        let incoming: BTreeMap<_, _> = hello.manifest.into_iter().map(|m| (m.key(), m)).collect();
        if incoming.values().any(|m| !m.valid()) {
            return Err("Invalid sync manifest.".into());
        }
        let want: Vec<_> = incoming
            .values()
            .filter(|m| !store.state.excluded.contains(&m.owner))
            .filter(|m| {
                store
                    .state
                    .objects
                    .get(&m.key())
                    .is_none_or(|old| m.newer(old))
            })
            .map(Meta::key)
            .collect();
        drop(store);
        wire::send(
            &mut stream,
            &Exchange {
                manifest: local.clone(),
                want: want.clone(),
            },
        )?;
        let count: usize = wire::read(&mut stream)?;
        if count > want.len() {
            return Err("Unexpected sync item count.".into());
        }
        for _ in 0..count {
            let meta: Meta = wire::read(&mut stream)?;
            if !want.contains(&meta.key()) || incoming.get(&meta.key()) != Some(&meta) {
                return Err("Unexpected sync item.".into());
            }
            self.receive(&meta, &mut stream)?;
        }
        let outgoing: Vec<_> = local
            .into_iter()
            .filter(|m| incoming.get(&m.key()).is_none_or(|old| m.newer(old)))
            .collect();
        wire::send(&mut stream, &outgoing.len())?;
        for meta in outgoing {
            wire::send(&mut stream, &meta)?;
            self.send(&meta, &mut stream)?;
        }
        *self.status.lock().unwrap() = "Up to date".into();
        Ok(())
    }
    fn exchange(&self, peer: &Peer) -> Result<()> {
        let address: SocketAddr = peer.endpoint.parse().map_err(failure)?;
        let mut stream = client(address, &peer.pin)?;
        let _socket_guard = self.watch(&stream.sock, &peer.id)?;
        let store = self.store.lock().unwrap();
        if store
            .state
            .peers
            .get(&peer.id)
            .is_none_or(|p| p.pin != peer.pin || !constant(&p.token, &peer.token))
        {
            return Err("Device is no longer paired.".into());
        }
        let hello = Hello {
            version: 1,
            action: "exchange".into(),
            id: store.state.id.clone(),
            name: store.state.name.clone(),
            pin: store.pin()?,
            endpoint: self.port,
            excluded: store.state.excluded.iter().cloned().collect(),
            token: peer.token.clone(),
            manifest: store.metas(),
        };
        drop(store);
        wire::send(&mut stream, &hello)?;
        let exchange: Exchange = wire::read(&mut stream)?;
        if exchange.manifest.len() > crate::storage::MAX_RECORDS
            || exchange.manifest.iter().any(|m| !m.valid())
            || exchange.want.len() > crate::storage::MAX_RECORDS
        {
            return Err("Invalid sync manifest.".into());
        }
        let outgoing: Vec<_> = hello
            .manifest
            .into_iter()
            .filter(|m| exchange.want.contains(&m.key()))
            .collect();
        wire::send(&mut stream, &outgoing.len())?;
        for meta in outgoing {
            wire::send(&mut stream, &meta)?;
            self.send(&meta, &mut stream)?;
        }
        let count: usize = wire::read(&mut stream)?;
        if count > exchange.manifest.len() {
            return Err("Unexpected sync item count.".into());
        }
        for _ in 0..count {
            let meta: Meta = wire::read(&mut stream)?;
            if !exchange.manifest.contains(&meta) {
                return Err("Unexpected sync item.".into());
            }
            self.receive(&meta, &mut stream)?;
        }
        *self.status.lock().unwrap() = "Up to date".into();
        Ok(())
    }
    fn send(&self, meta: &Meta, writer: &mut impl std::io::Write) -> Result<()> {
        let transfer = self.store.lock().unwrap().transfer(meta);
        transfer.send(writer)
    }
    fn receive(&self, meta: &Meta, reader: &mut impl std::io::Read) -> Result<()> {
        if self.is_stopped() {
            return Err("Device sync stopped.".into());
        }
        let transfer = self.store.lock().unwrap().transfer(meta);
        let path = transfer.receive(reader)?;
        let result = if self.is_stopped() {
            Err("Device sync stopped.".into())
        } else {
            self.store.lock().unwrap().commit(meta, &path)
        };
        let _ = std::fs::remove_file(path);
        result.map(|_| ())
    }
    pub fn exclude(&self, owner: &str) -> Result<()> {
        self.store.lock().unwrap().exclude(owner)?;
        for (_, _, socket) in self.sockets.lock().unwrap().iter() {
            let _ = socket.shutdown(std::net::Shutdown::Both);
        }
        Ok(())
    }
    pub fn forget(&self, id: &str) -> Result<()> {
        {
            let mut store = self.store.lock().unwrap();
            store.state.peers.remove(id);
            store.save()?;
        }
        for (_, peer, socket) in self.sockets.lock().unwrap().iter() {
            if peer == id {
                let _ = socket.shutdown(std::net::Shutdown::Both);
            }
        }
        Ok(())
    }
    fn discover(this: &Arc<Self>) {
        numeric::discover(this);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    pub(super) fn node(name: &str) -> Arc<Mesh> {
        let root =
            std::env::temp_dir().join(format!("monogram-sync-test-{}", uuid::Uuid::new_v4()));
        Mesh::start(Store::open(root, random(), name.into()).unwrap()).unwrap()
    }
    fn pair(from: &Arc<Mesh>, to: &Arc<Mesh>) {
        *from.addresses.lock().unwrap() = vec![SocketAddr::from(([127, 0, 0, 1], from.port))];
        let code = from.invite().unwrap();
        to.join(&code).unwrap();
    }
    fn sync(from: &Arc<Mesh>, to: &Arc<Mesh>) {
        let id = to.store.lock().unwrap().state.id.clone();
        let peer = from.store.lock().unwrap().state.peers[&id].clone();
        from.exchange(&peer).unwrap();
    }
    #[test]
    fn tls_mesh_forwards_records_and_preserves_newer_versions() {
        let a = node("desktop");
        let b = node("phone");
        let c = node("second phone");
        pair(&a, &b);
        pair(&b, &c);
        let source = a.store.lock().unwrap().root.join("fixture");
        fs::write(&source, b"fictional secret").unwrap();
        let original = a
            .store
            .lock()
            .unwrap()
            .put("account", "alice@example.invalid", "login", &source)
            .unwrap();
        sync(&b, &a);
        sync(&c, &b);
        assert_eq!(
            c.store.lock().unwrap().state.objects[&original.key()].digest,
            original.digest
        );
        fs::write(&source, b"updated fictional secret").unwrap();
        let newest = c
            .store
            .lock()
            .unwrap()
            .put("account", "alice@example.invalid", "login", &source)
            .unwrap();
        sync(&b, &c);
        sync(&a, &b);
        assert_eq!(
            a.store.lock().unwrap().state.objects[&original.key()],
            newest
        );
        let wrong = Peer {
            pin: "00".repeat(32),
            ..b.store.lock().unwrap().state.peers[&a.store.lock().unwrap().state.id].clone()
        };
        assert!(b.exchange(&wrong).is_err());
        let id = b.store.lock().unwrap().state.id.clone();
        a.forget(&id).unwrap();
        let forgotten =
            b.store.lock().unwrap().state.peers[&a.store.lock().unwrap().state.id].clone();
        assert!(b.exchange(&forgotten).is_err());
        for mesh in [&a, &b, &c] {
            mesh.stop();
            let _ = fs::remove_dir_all(&mesh.store.lock().unwrap().root);
        }
    }
}
