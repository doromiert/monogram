//! Shared validation for portable settings; never carries live OMEMO ratchets.
use crate::Result;
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use serde_json::Value;
fn string<'a>(v: &'a Value, k: &str, max: usize) -> Result<&'a str> {
    v[k].as_str()
        .filter(|s| s.len() <= max && !s.chars().any(|c| c == '\0'))
        .ok_or_else(|| "Invalid portable setting.".into())
}
pub fn validate(kind: &str, owner: &str, id: &str, v: &Value) -> Result<()> {
    if !v.is_object() {
        return Err("Invalid portable settings object.".into());
    }
    match kind {
        "folders" => {
            if id != "folders"
                || serde_json::to_vec(v).map_err(crate::failure)?.len() > 524288
                || !v["all_hidden"].is_boolean()
            {
                return Err("Invalid folder settings.".into());
            }
            let rules = v["rules"]
                .as_object()
                .filter(|r| r.len() <= 64)
                .ok_or("At most 64 folders can sync.")?;
            let order = v["order"]
                .as_array()
                .filter(|o| o.len() <= 65)
                .ok_or("Invalid folder order.")?;
            let mut names = std::collections::BTreeSet::new();
            for name in order {
                let name = name.as_str().ok_or("Invalid folder name.")?;
                if !names.insert(name) || (name != "All" && !rules.contains_key(name)) {
                    return Err("Invalid folder order.".into());
                }
            }
            if !names.contains("All") || rules.keys().any(|n| !names.contains(n.as_str())) {
                return Err("Missing folder in order.".into());
            }
            if let Some(rooms) = v.get("rooms") {
                validate_members(rooms)?;
            }
            for (name, rule) in rules {
                if let Some(pinned) = rule.get("pinned") {
                    validate_members(pinned)?;
                }
                if name.is_empty()
                    || name.len() > 320
                    || name.chars().any(char::is_control)
                {
                    return Err("Invalid folder name.".into());
                }
                let types = rule["types"].as_array().ok_or("Invalid folder types.")?;
                if types.len() > 5
                    || types.iter().any(|t| {
                        !matches!(
                            t.as_str(),
                            Some("contacts" | "non_contacts" | "groups" | "channels" | "bots")
                        )
                    })
                {
                    return Err("Invalid folder type.".into());
                }
                for flag in ["exclude_muted", "exclude_read", "exclude_archived"] {
                    if !rule[flag].is_boolean() {
                        return Err("Invalid folder rule.".into());
                    }
                }
                for field in ["include", "exclude"] {
                    let members = rule[field]
                        .as_array()
                        .filter(|m| m.len() <= 1024)
                        .ok_or("Folder has too many chats.")?;
                    for jid in members {
                        let jid = jid.as_str().ok_or("Invalid folder chat.")?;
                        if jid.len() > 1024
                            || !jid.contains('@')
                            || jid.contains('/')
                            || jid.chars().any(char::is_whitespace)
                        {
                            return Err("Invalid folder chat address.".into());
                        }
                    }
                }
                if let Some(icon) = rule.get("icon") {
                    if !icon.as_str().is_some_and(|s| {
                        s.len() <= 64 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
                    }) {
                        return Err("Invalid folder icon.".into());
                    }
                }
            }
        }
        "account" => {
            if string(v, "jid", 1024)? != owner
                || id != "login"
                || !owner.contains('@')
                || owner.contains('/')
                || owner.chars().any(char::is_whitespace)
                || !v["direct_tls"].is_boolean()
                || !v["port"].as_u64().is_some_and(|p| p > 0 && p <= 65535)
            {
                return Err("Invalid portable XMPP account.".into());
            }
            let host = string(v, "host", 1024)?;
            if host.chars().any(char::is_whitespace) || host.contains('/') {
                return Err("Invalid portable server address.".into());
            }
            string(v, "password", 4096)?;
        }
        "token" => {
            if !["giphy", "telegram-packs", "translation", "monero-daemon"].contains(&id) {
                return Err("Unsupported credential type.".into());
            }
            string(v, "value", 4096)?;
        }
        "fingerprint" => {
            let mode = string(v, "mode", 16)?;
            if !["classic", "legacy", "pq"].contains(&mode) {
                return Err("Invalid fingerprint protocol.".into());
            }
            let fp = string(v, "fingerprint", 128)?;
            if fp.len() != if mode == "pq" { 128 } else { 64 }
                || !fp.bytes().all(|b| b.is_ascii_hexdigit())
                || !v["device"]
                    .as_u64()
                    .is_some_and(|d| d > 0 && d <= i32::MAX as u64)
            {
                return Err("Invalid device fingerprint.".into());
            }
            let address = string(v, "address", 1024)?;
            if !address.contains('@')
                || address.contains('/')
                || address.chars().any(char::is_whitespace)
            {
                return Err("Invalid fingerprint account.".into());
            }
            if !["verified", "undecided", "rejected"].contains(&string(v, "trust", 16)?) {
                return Err("Invalid fingerprint decision.".into());
            }
        }
        "monero-settings" => {
            if !["MAINNET", "STAGENET", "TESTNET"].contains(&string(v, "network", 16)?)
                || !v["enabled"].is_boolean()
                || !v["require_encryption"].is_boolean()
            {
                return Err("Invalid Monero settings.".into());
            }
            let node = string(v, "daemon", 2048)?;
            if !https_node(node) {
                return Err("Use an HTTPS Monero node.".into());
            }
            string(v, "username", 256)?;
        }
        "wallet" => {
            let network = string(v, "network", 16)?;
            let address = string(v, "address", 128)?;
            if id != address || !monero_address(network, address) {
                return Err("Invalid portable wallet address.".into());
            }
            let name = string(v, "name", 320)?;
            if name.is_empty() || name.chars().any(char::is_control) {
                return Err("Invalid wallet name.".into());
            }
            let keys = B64
                .decode(string(v, "keys", 8 * 1024 * 1024)?)
                .map_err(|_| "Invalid encrypted wallet keys.")?;
            if keys.len() < 32 || keys.len() > 4 * 1024 * 1024 {
                return Err("Invalid encrypted wallet-key size.".into());
            }
            let password = string(v, "password", 1024)?;
            if password.is_empty() || !password.is_ascii() || v["height"].as_u64().is_none() {
                return Err("Invalid wallet recovery data.".into());
            }
            if v.get("max_index")
                .is_some_and(|n| !n.as_u64().is_some_and(|n| n <= 10000))
            {
                return Err("Invalid wallet address range.".into());
            }
            let node = string(v, "node", 2048)?;
            if !https_node(node) {
                return Err("Use an HTTPS Monero node.".into());
            }
        }
        _ => return Err("Unsupported portable settings type.".into()),
    }
    Ok(())
}
pub fn monero_address(network: &str, address: &str) -> bool {
    if address.len() != 95 {
        return false;
    }
    let alphabet = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    let mut decoded = Vec::with_capacity(69);
    for block in address.as_bytes().chunks(11) {
        let size = match block.len() {
            11 => 8,
            7 => 5,
            _ => return false,
        };
        let mut n = 0u64;
        for c in block {
            let Some(v) = alphabet.iter().position(|x| x == c) else {
                return false;
            };
            let Some(next) = n.checked_mul(58).and_then(|n| n.checked_add(v as u64)) else {
                return false;
            };
            n = next;
        }
        if size < 8 && n >= (1u64 << (8 * size)) {
            return false;
        }
        decoded.extend_from_slice(&n.to_be_bytes()[8 - size..]);
    }
    if decoded.len() != 69
        || decoded[0]
            != match network {
                "MAINNET" => 18,
                "STAGENET" => 24,
                "TESTNET" => 53,
                _ => return false,
            }
    {
        return false;
    }
    use tiny_keccak::Hasher;
    let mut h = tiny_keccak::Keccak::v256();
    h.update(&decoded[..65]);
    let mut digest = [0; 32];
    h.finalize(&mut digest);
    digest[..4] == decoded[65..]
}

fn validate_members(v: &Value) -> Result<()> {
    let members = v
        .as_array()
        .filter(|m| m.len() <= 1024)
        .ok_or("Invalid folder chat list.")?;
    for jid in members {
        let jid = jid.as_str().ok_or("Invalid folder chat.")?;
        if jid.len() > 1024
            || !jid.contains('@')
            || jid.contains('/')
            || jid.chars().any(char::is_whitespace)
        {
            return Err("Invalid folder chat address.".into());
        }
    }
    Ok(())
}

fn https_node(node: &str) -> bool {
    if node.is_empty() {
        return true;
    }
    url::Url::parse(node).is_ok_and(|u| {
        u.scheme() == "https"
            && u.host_str().is_some()
            && u.username().is_empty()
            && u.password().is_none()
            && u.query().is_none()
            && u.fragment().is_none()
            && matches!(u.path(), "" | "/")
            && u.port() != Some(0)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn folders_validate_order_members_and_optional_metadata() {
        let valid = json!({"rules":{"Friends":{"types":["contacts"],"include":["alice@example.invalid"],"exclude":[],"pinned":["alice@example.invalid"],"icon":"cat","exclude_muted":true,"exclude_read":false,"exclude_archived":true}},"order":["Friends","All"],"all_hidden":true,"rooms":[]});
        assert!(validate("folders", "me@example.invalid", "folders", &valid).is_ok());
        let mut desktop = valid.clone();
        desktop["rules"]["All"] = desktop["rules"]["Friends"].clone();
        desktop["rules"]["All"]["icon"] = json!("");
        assert!(validate("folders", "me@example.invalid", "folders", &desktop).is_ok());
        let mut invalid = valid.clone();
        invalid["order"] = json!(["Friends", "Friends", "All"]);
        assert!(validate("folders", "me@example.invalid", "folders", &invalid).is_err());
        let mut invalid = valid;
        invalid["rules"]["Friends"]["include"] = json!(["../bad@example.invalid"]);
        assert!(validate("folders", "me@example.invalid", "folders", &invalid).is_err());
    }
}
