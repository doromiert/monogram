use crate::{Result, failure, hash, now, random};
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use ring::{aead, digest};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;
const CHUNK: usize = 64 * 1024;
pub const MAX_RECORD: u64 = 128 * 1024 * 1024;
pub const MAX_RECORDS: usize = 4096;
#[derive(Clone, Serialize, Deserialize, Debug, PartialEq, Eq)]
pub struct Meta {
    pub kind: String,
    pub owner: String,
    pub id: String,
    pub stamp: u64,
    pub origin: String,
    pub digest: String,
    pub size: u64,
}
impl Meta {
    pub fn key(&self) -> String {
        serde_json::to_string(&(&self.kind, &self.owner, &self.id)).unwrap()
    }
    pub fn valid(&self) -> bool {
        matches!(
            self.kind.as_str(),
            "account" | "pack" | "wallet" | "fingerprint" | "token" | "monero-settings" | "folders"
        ) && !self.owner.is_empty()
            && self.owner.len() <= 1024
            && self.owner.contains('@')
            && !self
                .owner
                .chars()
                .any(|c| c.is_whitespace() || c.is_control() || c == '/')
            && self.id.len() <= 256
            && !self.id.is_empty()
            && !self.id.chars().any(char::is_control)
            && uuid::Uuid::parse_str(&self.origin).is_ok()
            && self.stamp > 0
            && self.stamp <= now().saturating_add(86_400_000)
            && self.digest.len() == 64
            && self.digest.bytes().all(|b| b.is_ascii_hexdigit())
            && self.size <= MAX_RECORD
    }
    pub fn newer(&self, other: &Self) -> bool {
        (&self.stamp, &self.origin) > (&other.stamp, &other.origin)
    }
    fn aad(&self) -> Vec<u8> {
        serde_json::to_vec(&(1, self.key(), &self.digest, self.size)).unwrap()
    }
}
#[derive(Clone, Serialize, Deserialize)]
pub struct Peer {
    pub id: String,
    pub name: String,
    pub pin: String,
    pub token: String,
    pub endpoint: String,
}
#[derive(Serialize, Deserialize)]
pub struct State {
    pub id: String,
    pub name: String,
    pub cert: String,
    pub private_key: String,
    pub peers: BTreeMap<String, Peer>,
    pub objects: BTreeMap<String, Meta>,
    pub applied: BTreeMap<String, String>,
    #[serde(default)]
    pub native: BTreeMap<String, String>,
    #[serde(default)]
    pub excluded: BTreeSet<String>,
    pub counter: u64,
}
pub struct Transfer {
    root: PathBuf,
    meta: Meta,
    key: Zeroizing<[u8; 32]>,
}
pub struct Store {
    pub root: PathBuf,
    pub state: State,
    key: Zeroizing<[u8; 32]>,
    _lock: File,
}
fn open_new(path: &Path) -> Result<File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path).map_err(failure)
}
fn aead_key(key: &[u8; 32]) -> aead::LessSafeKey {
    aead::LessSafeKey::new(aead::UnboundKey::new(&aead::AES_256_GCM, key).unwrap())
}
fn seal(key: &[u8; 32], aad: &[u8], data: &[u8]) -> Result<Vec<u8>> {
    let nonce = random::<12>();
    let mut bytes = data.to_vec();
    aead_key(key)
        .seal_in_place_append_tag(
            aead::Nonce::assume_unique_for_key(nonce),
            aead::Aad::from(aad),
            &mut bytes,
        )
        .map_err(failure)?;
    let mut out = nonce.to_vec();
    out.extend(bytes);
    Ok(out)
}
fn unseal(key: &[u8; 32], aad: &[u8], bytes: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
    if bytes.len() < 28 {
        return Err("Invalid encrypted sync data.".into());
    }
    let nonce = bytes[..12].try_into().unwrap();
    let mut out = Zeroizing::new(bytes[12..].to_vec());
    let len = aead_key(key)
        .open_in_place(
            aead::Nonce::assume_unique_for_key(nonce),
            aead::Aad::from(aad),
            &mut out,
        )
        .map_err(|_| "Sync data authentication failed.".to_string())?
        .len();
    out.truncate(len);
    Ok(out)
}
impl Store {
    pub fn open(root: PathBuf, key: [u8; 32], name: String) -> Result<Self> {
        fs::create_dir_all(root.join("objects")).map_err(failure)?;
        let lock = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(root.join("lock"))
            .map_err(failure)?;
        lock.try_lock()
            .map_err(|_| "Device sync is already open in another process.".to_string())?;
        for entry in fs::read_dir(&root).map_err(failure)?.flatten() {
            if entry.file_name().to_string_lossy().starts_with(".export-")
                || entry.file_name().to_string_lossy().starts_with(".json-")
            {
                let _ = fs::remove_file(entry.path());
            }
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&root, fs::Permissions::from_mode(0o700)).map_err(failure)?;
        }
        let state = if root.join("state").exists() {
            let bytes = fs::read(root.join("state")).map_err(failure)?;
            serde_json::from_slice(&unseal(&key, b"monogram-sync-state-v1", &bytes)?)
                .map_err(failure)?
        } else {
            let signing = rcgen::KeyPair::generate().map_err(failure)?;
            let cert = rcgen::CertificateParams::new(vec!["monogram.local".into()])
                .map_err(failure)?
                .self_signed(&signing)
                .map_err(failure)?;
            State {
                id: uuid::Uuid::new_v4().to_string(),
                name: name.chars().filter(|c| !c.is_control()).take(80).collect(),
                cert: B64.encode(cert.der()),
                private_key: B64.encode(signing.serialize_der()),
                peers: BTreeMap::new(),
                objects: BTreeMap::new(),
                applied: BTreeMap::new(),
                native: BTreeMap::new(),
                excluded: BTreeSet::new(),
                counter: now(),
            }
        };
        let this = Self {
            root,
            state,
            key: Zeroizing::new(key),
            _lock: lock,
        };
        this.save()?;
        Ok(this)
    }
    pub fn save(&self) -> Result<()> {
        let bytes = Zeroizing::new(serde_json::to_vec(&self.state).map_err(failure)?);
        let sealed = seal(&self.key, b"monogram-sync-state-v1", &bytes)?;
        let path = self.root.join(format!(".state-{}", uuid::Uuid::new_v4()));
        let mut file = open_new(&path)?;
        file.write_all(&sealed).map_err(failure)?;
        file.sync_all().map_err(failure)?;
        fs::rename(&path, self.root.join("state")).map_err(failure)?;
        File::open(&self.root)
            .and_then(|f| f.sync_all())
            .map_err(failure)
    }
    pub fn pin(&self) -> Result<String> {
        Ok(hash(&B64.decode(&self.state.cert).map_err(failure)?))
    }
    pub fn metas(&self) -> Vec<Meta> {
        self.state.objects.values().cloned().collect()
    }
    pub fn path(&self, meta: &Meta) -> PathBuf {
        self.root
            .join("objects")
            .join(hash(format!("{}{}", meta.key(), meta.digest).as_bytes()))
    }
    pub fn put(&mut self, kind: &str, owner: &str, id: &str, path: &Path) -> Result<Meta> {
        let size = fs::metadata(path).map_err(failure)?.len();
        if size > MAX_RECORD {
            return Err("Sync item exceeds 128 MiB.".into());
        }
        let mut file = File::open(path).map_err(failure)?;
        let mut h = digest::Context::new(&digest::SHA256);
        let mut buffer = [0; CHUNK];
        loop {
            let n = file.read(&mut buffer).map_err(failure)?;
            if n == 0 {
                break;
            }
            h.update(&buffer[..n]);
        }
        let digest: String = h
            .finish()
            .as_ref()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        let mut meta = Meta {
            kind: kind.into(),
            owner: owner.into(),
            id: id.into(),
            stamp: now().max(self.state.counter + 1),
            origin: self.state.id.clone(),
            digest,
            size,
        };
        if !meta.valid() || self.state.excluded.contains(&meta.owner) {
            return Err("Invalid or locally removed sync account.".into());
        }
        if let Some(previous) = self.state.objects.get(&meta.key()) {
            if previous.digest == meta.digest {
                return Ok(previous.clone());
            }
        }
        self.state.counter = meta.stamp;
        let mut file = File::open(path).map_err(failure)?;
        let transfer = self.transfer(&meta);
        let staged = transfer.receive(&mut file)?;
        self.commit(&meta, &staged)?;
        let _ = fs::remove_file(staged);
        self.state.applied.insert(meta.key(), meta.digest.clone());
        self.save()?;
        meta.origin = self.state.id.clone();
        Ok(meta)
    }
    pub fn put_json(&mut self, kind: &str, owner: &str, id: &str, value: &str) -> Result<Meta> {
        let value: serde_json::Value = serde_json::from_str(value).map_err(failure)?;
        crate::profile::validate(kind, owner, id, &value)?;
        let data = Zeroizing::new(serde_json::to_vec(&value).map_err(failure)?);
        let path = self.root.join(format!(".json-{}", uuid::Uuid::new_v4()));
        let mut out = open_new(&path)?;
        out.write_all(&data).map_err(failure)?;
        drop(out);
        let result = self.put(kind, owner, id, &path);
        let _ = fs::remove_file(path);
        result
    }
    pub fn stage_export(&self, key: &str) -> Result<PathBuf> {
        let path = self.root.join(format!(".export-{}", uuid::Uuid::new_v4()));
        self.export(key, &path)?;
        Ok(path)
    }
    pub fn signature(&self, key: &str) -> Option<String> {
        self.state.native.get(key).cloned()
    }
    pub fn set_signature(&mut self, key: &str, value: String) -> Result<()> {
        self.state.native.insert(key.into(), value);
        self.save()
    }
    pub fn transfer(&self, meta: &Meta) -> Transfer {
        Transfer {
            root: self.root.clone(),
            meta: meta.clone(),
            key: Zeroizing::new(*self.key),
        }
    }
    pub fn commit(&mut self, meta: &Meta, path: &Path) -> Result<bool> {
        if !meta.valid() || self.state.excluded.contains(&meta.owner) {
            return Err("Invalid or locally removed sync account.".into());
        }
        if self.state.objects.len() >= MAX_RECORDS && !self.state.objects.contains_key(&meta.key())
        {
            return Err("Sync library reached its safe item limit.".into());
        }
        if self
            .state
            .objects
            .values()
            .map(|m| m.size)
            .sum::<u64>()
            .saturating_add(meta.size)
            > 8 * 1024 * 1024 * 1024
        {
            return Err("Sync library exceeds 8 GiB.".into());
        }
        if self
            .state
            .objects
            .get(&meta.key())
            .is_some_and(|old| !meta.newer(old))
        {
            return Ok(false);
        }
        fs::rename(path, self.path(meta)).map_err(failure)?;
        File::open(self.root.join("objects"))
            .and_then(|f| f.sync_all())
            .map_err(failure)?;
        let previous = self.state.objects.insert(meta.key(), meta.clone());
        self.state.counter = self.state.counter.max(meta.stamp);
        self.save()?;
        if let Some(previous) = previous {
            let _ = fs::remove_file(self.path(&previous));
        }
        Ok(true)
    }
    pub fn export(&self, key: &str, path: &Path) -> Result<()> {
        let meta = self
            .state
            .objects
            .get(key)
            .ok_or("Sync item unavailable.")?;
        let mut out = open_new(path)?;
        let result = self.transfer(meta).send(&mut out);
        if result.is_err() {
            let _ = fs::remove_file(path);
        }
        result
    }
    pub fn mark_applied(&mut self, key: &str, digest: &str) -> Result<()> {
        if self
            .state
            .objects
            .get(key)
            .is_none_or(|m| m.digest != digest)
        {
            return Err("Sync item changed.".into());
        }
        self.state.applied.insert(key.into(), digest.into());
        self.save()
    }
    pub fn exclude(&mut self, owner: &str) -> Result<()> {
        let metas: Vec<_> = self
            .state
            .objects
            .values()
            .filter(|m| m.owner == owner)
            .cloned()
            .collect();
        self.state.excluded.insert(owner.into());
        for meta in metas {
            let key = meta.key();
            self.state.objects.remove(&key);
            self.state.applied.remove(&key);
            self.state.native.remove(&key);
            let _ = fs::remove_file(self.path(&meta));
        }
        self.save()
    }
    pub fn allow(&mut self, owner: &str) -> Result<()> {
        if self.state.excluded.remove(owner) {
            self.save()?;
        }
        Ok(())
    }
    pub fn pending(&self) -> Vec<Meta> {
        self.state
            .objects
            .values()
            .filter(|m| self.state.applied.get(&m.key()) != Some(&m.digest))
            .cloned()
            .collect()
    }
}

impl Transfer {
    fn path(&self) -> PathBuf {
        self.root.join("objects").join(hash(
            format!("{}{}", self.meta.key(), self.meta.digest).as_bytes(),
        ))
    }
    pub fn receive(&self, reader: &mut impl Read) -> Result<PathBuf> {
        let meta = &self.meta;
        if !meta.valid() {
            return Err("Invalid peer sync item.".into());
        }
        let temp = self
            .root
            .join("objects")
            .join(format!(".incoming-{}", uuid::Uuid::new_v4()));
        let result = (|| {
            let mut output = open_new(&temp)?;
            let mut remaining = meta.size;
            let mut h = digest::Context::new(&digest::SHA256);
            let mut index = 0u64;
            loop {
                let len = remaining.min(CHUNK as u64) as usize;
                let mut bytes = Zeroizing::new(vec![0; len]);
                reader.read_exact(&mut bytes).map_err(failure)?;
                h.update(&bytes);
                let mut aad = meta.aad();
                aad.extend(index.to_be_bytes());
                let encrypted = seal(&self.key, &aad, &bytes)?;
                output
                    .write_all(&(encrypted.len() as u32).to_be_bytes())
                    .map_err(failure)?;
                output.write_all(&encrypted).map_err(failure)?;
                remaining -= len as u64;
                index += 1;
                if remaining == 0 {
                    break;
                }
            }
            let digest: String = h
                .finish()
                .as_ref()
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect();
            if digest != meta.digest {
                return Err("Peer sync item checksum failed.".into());
            }
            output.sync_all().map_err(failure)?;
            Ok(temp.clone())
        })();
        if result.is_err() {
            let _ = fs::remove_file(temp);
        }
        result
    }
    pub fn send(&self, writer: &mut impl Write) -> Result<()> {
        let meta = &self.meta;
        let mut input = File::open(self.path()).map_err(failure)?;
        let mut remaining = meta.size;
        let mut index = 0u64;
        let mut h = digest::Context::new(&digest::SHA256);
        loop {
            let mut len = [0; 4];
            input.read_exact(&mut len).map_err(failure)?;
            let len = u32::from_be_bytes(len) as usize;
            if !(28..=CHUNK + 28).contains(&len) {
                return Err("Invalid encrypted sync chunk.".into());
            }
            let mut encrypted = vec![0; len];
            input.read_exact(&mut encrypted).map_err(failure)?;
            let mut aad = meta.aad();
            aad.extend(index.to_be_bytes());
            let bytes = unseal(&self.key, &aad, &encrypted)?;
            let expected = remaining.min(CHUNK as u64) as usize;
            if bytes.len() != expected {
                return Err("Truncated sync item.".into());
            }
            h.update(&bytes);
            writer.write_all(&bytes).map_err(failure)?;
            remaining -= bytes.len() as u64;
            index += 1;
            if remaining == 0 {
                break;
            }
        }
        let digest: String = h
            .finish()
            .as_ref()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        if digest != meta.digest || input.read(&mut [0]).map_err(failure)? != 0 {
            return Err("Sync item verification failed.".into());
        }
        Ok(())
    }
}

impl Drop for State {
    fn drop(&mut self) {
        use zeroize::Zeroize;
        self.private_key.zeroize();
        for peer in self.peers.values_mut() {
            peer.token.zeroize();
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn private_records_authenticate_content_and_master_key() {
        let root =
            std::env::temp_dir().join(format!("monogram-sync-store-{}", uuid::Uuid::new_v4()));
        let key = random();
        let mut store = Store::open(root.clone(), key, "Fixture".into()).unwrap();
        let input = root.join("input");
        fs::write(&input, b"fictional private credential").unwrap();
        let meta = store
            .put("token", "alice@example.invalid", "giphy", &input)
            .unwrap();
        let encrypted = fs::read(store.path(&meta)).unwrap();
        assert!(!encrypted.windows(10).any(|b| b == b"credential"));
        let mut recovered = Vec::new();
        store.transfer(&meta).send(&mut recovered).unwrap();
        assert_eq!(recovered, b"fictional private credential");
        drop(store);
        assert!(Store::open(root.clone(), random(), "Fixture".into()).is_err());
        let store = Store::open(root.clone(), key, "Fixture".into()).unwrap();
        assert_eq!(store.state.objects[&meta.key()], meta);
        let mut corrupted = encrypted;
        corrupted[18] ^= 1;
        fs::write(store.path(&meta), corrupted).unwrap();
        assert!(store.transfer(&meta).send(&mut Vec::new()).is_err());
        let mut bad = meta.clone();
        bad.owner = "../alice@example.invalid".into();
        assert!(!bad.valid());
        bad = meta.clone();
        bad.stamp = u64::MAX;
        assert!(!bad.valid());
        let _ = fs::remove_dir_all(root);
    }
}
