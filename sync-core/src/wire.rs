use crate::{Result, failure};
use serde::{Serialize, de::DeserializeOwned};
use std::io::{Read, Write};
const LIMIT: usize = 8 * 1024 * 1024;
pub fn send<T: Serialize>(writer: &mut impl Write, value: &T) -> Result<()> {
    let bytes = serde_json::to_vec(value).map_err(failure)?;
    if bytes.len() > LIMIT {
        return Err("Sync message too large.".into());
    }
    writer
        .write_all(&(bytes.len() as u32).to_be_bytes())
        .map_err(failure)?;
    writer.write_all(&bytes).map_err(failure)?;
    writer.flush().map_err(failure)
}
pub fn read<T: DeserializeOwned>(reader: &mut impl Read) -> Result<T> {
    let mut len = [0; 4];
    reader.read_exact(&mut len).map_err(failure)?;
    let len = u32::from_be_bytes(len) as usize;
    if len == 0 || len > LIMIT {
        return Err("Invalid sync frame size.".into());
    }
    let mut bytes = zeroize::Zeroizing::new(vec![0; len]);
    reader.read_exact(&mut bytes).map_err(failure)?;
    serde_json::from_slice(&bytes).map_err(failure)
}
