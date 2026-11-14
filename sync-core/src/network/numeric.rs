//! QR-free pairing: RFC6238 SHA256/8-digit TOTP as a SPAKE2 password.
//! Advertised certificates become trusted only after transcript-bound mutual key confirmation.
use super::*;
use ring::hmac;
use spake2::{Ed25519Group, Identity, Password, Spake2};
use zeroize::Zeroizing;
#[derive(Clone, Serialize, Deserialize)]
pub(super) struct Candidate {
    pub id: String,
    pub name: String,
    pub pin: String,
    pub endpoint: SocketAddr,
    pub expires: u64,
    seen: u64,
}
#[derive(Serialize, Deserialize)]
struct Beacon {
    format: String,
    id: String,
    port: u16,
    pair: Option<PublicInvite>,
}
#[derive(Serialize, Deserialize)]
struct PublicInvite {
    name: String,
    pin: String,
    expires: u64,
}
#[derive(Serialize, Deserialize)]
struct Start {
    step: u64,
    message: String,
}
#[derive(Serialize, Deserialize)]
struct Challenge {
    message: String,
    proof: String,
}
fn totp(secret: &[u8], step: u64) -> String {
    let h = hmac::sign(
        &hmac::Key::new(hmac::HMAC_SHA256, secret),
        &step.to_be_bytes(),
    );
    let bytes = h.as_ref();
    let i = (bytes[bytes.len() - 1] & 15) as usize;
    let n = u32::from_be_bytes(bytes[i..i + 4].try_into().unwrap()) & 0x7fffffff;
    format!("{:08}", n % 100_000_000)
}
fn identity(id: &str, pin: &str) -> Identity {
    Identity::new(format!("monogram-sync:1:{id}:{pin}").as_bytes())
}
fn proof(key: &[u8], role: &str, binding: &[u8]) -> String {
    let mut value = role.as_bytes().to_vec();
    value.extend(binding);
    B64.encode(hmac::sign(&hmac::Key::new(hmac::HMAC_SHA256, key), &value))
}
fn binding(hello: &Hello, id: &str, pin: &str, reply: &str) -> Vec<u8> {
    serde_json::to_vec(&(
        1,
        &hello.id,
        &hello.name,
        &hello.pin,
        hello.endpoint,
        id,
        pin,
        &hello.token,
        reply,
    ))
    .unwrap()
}
impl Mesh {
    pub fn numeric_invite(&self) -> Result<serde_json::Value> {
        if !self.numeric.load(Ordering::Acquire)
            || self
                .invite
                .lock()
                .unwrap()
                .as_ref()
                .is_none_or(|i| i.expires < now())
        {
            self.invite()?;
            self.code_attempts.store(0, Ordering::Release);
            self.numeric.store(true, Ordering::Release);
        }
        self.numeric_status()
    }
    pub fn numeric_status(&self) -> Result<serde_json::Value> {
        let invite = self.invite.lock().unwrap();
        let invite = invite
            .as_ref()
            .filter(|i| i.expires >= now() && self.numeric.load(Ordering::Acquire))
            .ok_or("Pairing ended. Open a new pairing code to try again.")?;
        let step = now() / 30_000;
        let secret = Zeroizing::new(B64.decode(&invite.code).map_err(failure)?);
        Ok(
            serde_json::json!({"code":totp(&secret,step),"remaining":30-(now()/1000)%30,"expires":invite.expires,"name":invite.name}),
        )
    }
    pub fn nearby(&self) -> serde_json::Value {
        let own = self.store.lock().unwrap().state.id.clone();
        serde_json::to_value(
            self.candidates
                .lock()
                .unwrap()
                .values()
                .filter(|p| p.id != own && p.expires >= now() && p.seen + 15_000 >= now())
                .cloned()
                .collect::<Vec<_>>(),
        )
        .unwrap()
    }
    pub fn join_numeric(&self, id: &str, code: &str) -> Result<String> {
        if code.len() != 8 || !code.bytes().all(|b| b.is_ascii_digit()) {
            return Err("Enter the eight-digit code from the other device.".into());
        }
        let peer = self
            .candidates
            .lock()
            .unwrap()
            .get(id)
            .filter(|p| p.expires >= now() && p.seen + 15_000 >= now())
            .cloned()
            .ok_or("Device disappeared. Keep its code screen open and retry.")?;
        let store = self.store.lock().unwrap();
        let own = store.state.id.clone();
        let pin = store.pin()?;
        let (pake, message) = Spake2::<Ed25519Group>::start_a(
            &Password::new(code.as_bytes()),
            &identity(&own, &pin),
            &identity(&peer.id, &peer.pin),
        );
        let hello = Hello {
            version: 1,
            action: "totp".into(),
            id: own,
            name: store.state.name.clone(),
            pin,
            endpoint: self.port,
            excluded: vec![],
            token: serde_json::to_string(&Start {
                step: now() / 30_000,
                message: B64.encode(message),
            })
            .unwrap(),
            manifest: vec![],
        };
        drop(store);
        let mut stream = client(peer.endpoint, &peer.pin)?;
        let _guard = self.watch(&stream.sock, &peer.id)?;
        wire::send(&mut stream, &hello)?;
        let challenge: Challenge = wire::read(&mut stream)?;
        let message = B64.decode(&challenge.message).map_err(failure)?;
        let key = Zeroizing::new(pake.finish(&message).map_err(failure)?);
        let transcript = binding(&hello, &peer.id, &peer.pin, &challenge.message);
        if !constant(&challenge.proof, &proof(&key, "server", &transcript)) {
            return Err("Pairing code did not match. Use the current code and try again.".into());
        }
        wire::send(&mut stream, &proof(&key, "client", &transcript))?;
        let reply: Peer = wire::read(&mut stream)?;
        if self.is_stopped()
            || reply.id != peer.id
            || reply.pin != peer.pin
            || B64.decode(&reply.token).map_err(failure)?.len() != 32
        {
            return Err("Pairing identity verification failed.".into());
        }
        let mut store = self.store.lock().unwrap();
        if store.state.peers.len() >= 32 {
            return Err("At most 32 paired devices are supported.".into());
        }
        store.state.peers.insert(
            reply.id.clone(),
            Peer {
                endpoint: peer.endpoint.to_string(),
                ..reply
            },
        );
        store.save()?;
        *self.status.lock().unwrap() = "Paired · syncing".into();
        Ok(peer.name)
    }
    pub(super) fn accept_numeric(
        &self,
        hello: Hello,
        address: SocketAddr,
        stream: &mut StreamOwned<ServerConnection, TcpStream>,
    ) -> Result<()> {
        let invitation = self
            .invite
            .lock()
            .unwrap()
            .clone()
            .filter(|i| i.expires >= now())
            .ok_or("Open the pairing code screen first.")?;
        if !self.numeric.load(Ordering::Acquire)
            || self.code_attempts.fetch_add(1, Ordering::AcqRel) >= 5
        {
            return Err("Too many attempts. Reopen the pairing code screen.".into());
        }
        let request: Start = serde_json::from_str(&hello.token).map_err(failure)?;
        if request.step.abs_diff(now() / 30_000) > 1 {
            return Err("Pairing time does not match. Check both devices’ clocks.".into());
        }
        let secret = Zeroizing::new(B64.decode(&invitation.code).map_err(failure)?);
        let code = Zeroizing::new(totp(&secret, request.step));
        let (pake, message) = Spake2::<Ed25519Group>::start_b(
            &Password::new(code.as_bytes()),
            &identity(&hello.id, &hello.pin),
            &identity(&invitation.id, &invitation.pin),
        );
        let key = Zeroizing::new(
            pake.finish(&B64.decode(&request.message).map_err(failure)?)
                .map_err(failure)?,
        );
        let message = B64.encode(message);
        let transcript = binding(&hello, &invitation.id, &invitation.pin, &message);
        wire::send(
            stream,
            &Challenge {
                message,
                proof: proof(&key, "server", &transcript),
            },
        )?;
        let confirmation: String = wire::read(stream)?;
        if !constant(&confirmation, &proof(&key, "client", &transcript)) {
            return Err("Pairing code did not match.".into());
        }
        let mut active = self.invite.lock().unwrap();
        if self.is_stopped()
            || active
                .as_ref()
                .is_none_or(|i| i.expires < now() || !constant(&i.code, &invitation.code))
        {
            return Err("Pairing code expired or was already used.".into());
        }
        let mut store = self.store.lock().unwrap();
        if store.state.peers.len() >= 32
            || store
                .state
                .peers
                .get(&hello.id)
                .is_some_and(|p| p.pin != hello.pin)
        {
            return Err("Forget the old device identity before pairing again.".into());
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
        active.take();
        self.numeric.store(false, Ordering::Release);
        drop(store);
        drop(active);
        wire::send(stream, &reply)?;
        *self.status.lock().unwrap() = "Paired · syncing".into();
        Ok(())
    }
}
pub(super) fn discover(this: &Arc<Mesh>) {
    let socket = (|| -> std::io::Result<UdpSocket> {
        let s = socket2::Socket::new(
            socket2::Domain::IPV4,
            socket2::Type::DGRAM,
            Some(socket2::Protocol::UDP),
        )?;
        s.set_reuse_address(true)?;
        s.bind(&SocketAddr::from(([0, 0, 0, 0], 45950)).into())?;
        let s: UdpSocket = s.into();
        s.join_multicast_v4(&Ipv4Addr::new(239, 255, 85, 77), &Ipv4Addr::UNSPECIFIED)?;
        s.set_read_timeout(Some(Duration::from_millis(200)))?;
        s.set_multicast_ttl_v4(1)?;
        Ok(s)
    })();
    let Ok(socket) = socket else {
        return;
    };
    let weak = Arc::downgrade(this);
    thread::spawn(move || {
        let mut previous = 0;
        let mut bytes = [0; 2048];
        loop {
            let Some(mesh) = weak.upgrade().filter(|m| !m.is_stopped()) else {
                break;
            };
            if now().saturating_sub(previous) > 1000 {
                let id = mesh.store.lock().unwrap().state.id.clone();
                let pair = if mesh.numeric.load(Ordering::Acquire) {
                    mesh.invite
                        .lock()
                        .unwrap()
                        .as_ref()
                        .filter(|i| i.expires >= now())
                        .map(|i| PublicInvite {
                            name: i.name.clone(),
                            pin: i.pin.clone(),
                            expires: i.expires,
                        })
                } else {
                    None
                };
                let payload = serde_json::to_vec(&Beacon {
                    format: "monogram-sync-v1".into(),
                    id,
                    port: mesh.port,
                    pair,
                })
                .unwrap();
                let _ = socket.send_to(&payload, "239.255.85.77:45950");
                previous = now();
            }
            if let Ok((n, address)) = socket.recv_from(&mut bytes) {
                if !local(address.ip()) {
                    continue;
                }
                let Ok(beacon) = serde_json::from_slice::<Beacon>(&bytes[..n]) else {
                    continue;
                };
                if beacon.format != "monogram-sync-v1"
                    || beacon.port == 0
                    || uuid::Uuid::parse_str(&beacon.id).is_err()
                {
                    continue;
                }
                let endpoint = SocketAddr::new(address.ip(), beacon.port);
                if let Some(pair) = beacon.pair.filter(|p| {
                    p.expires >= now()
                        && p.expires <= now() + 180_000
                        && p.pin.len() == 64
                        && p.pin.bytes().all(|b| b.is_ascii_hexdigit())
                        && p.name.len() <= 320
                        && !p.name.chars().any(char::is_control)
                }) {
                    let mut candidates = mesh.candidates.lock().unwrap();
                    candidates.retain(|_, c| c.seen + 15_000 >= now());
                    if candidates.len() < 32 || candidates.contains_key(&beacon.id) {
                        candidates.insert(
                            beacon.id.clone(),
                            Candidate {
                                id: beacon.id.clone(),
                                name: pair.name,
                                pin: pair.pin,
                                endpoint,
                                expires: pair.expires,
                                seen: now(),
                            },
                        );
                    }
                }
                let peer = mesh
                    .store
                    .lock()
                    .unwrap()
                    .state
                    .peers
                    .get(&beacon.id)
                    .cloned();
                if let Some(mut peer) = peer.filter(|p| p.endpoint != endpoint.to_string()) {
                    peer.endpoint = endpoint.to_string();
                    if mesh.exchange(&peer).is_ok() {
                        let mut store = mesh.store.lock().unwrap();
                        if store.state.peers.contains_key(&peer.id) {
                            store.state.peers.insert(peer.id.clone(), peer);
                            let _ = store.save();
                        }
                    }
                }
            }
        }
    });
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rfc6238_sha256_vector() {
        assert_eq!(
            totp(b"12345678901234567890123456789012", 59 / 30),
            "46119246"
        );
    }
    #[test]
    fn typed_code_requires_pake_proof_and_is_single_use() {
        let a = super::super::tests::node("Laptop");
        let b = super::super::tests::node("Phone");
        *a.addresses.lock().unwrap() = vec![SocketAddr::from(([127, 0, 0, 1], a.port))];
        let code = a.numeric_invite().unwrap();
        let invite = a.invite.lock().unwrap().clone().unwrap();
        let candidate = Candidate {
            id: invite.id.clone(),
            name: invite.name,
            pin: invite.pin,
            endpoint: SocketAddr::from(([127, 0, 0, 1], a.port)),
            expires: invite.expires,
            seen: now(),
        };
        b.candidates
            .lock()
            .unwrap()
            .insert(invite.id.clone(), candidate);
        assert!(b.join_numeric(&invite.id, "xxxxxxxx").is_err());
        let correct = code["code"].as_str().unwrap();
        let wrong = if correct == "00000000" {
            "11111111"
        } else {
            "00000000"
        };
        assert!(b.join_numeric(&invite.id, wrong).is_err());
        assert!(a.store.lock().unwrap().state.peers.is_empty());
        assert!(b.join_numeric(&invite.id, correct).is_ok());
        assert!(b.join_numeric(&invite.id, correct).is_err());
        a.stop();
        b.stop();
        for m in [a, b] {
            let _ = std::fs::remove_dir_all(&m.store.lock().unwrap().root);
        }
    }
}
