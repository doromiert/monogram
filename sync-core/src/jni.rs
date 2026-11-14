use crate::{Mesh, Result, Store, failure};
use jni::{
    JNIEnv,
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jlong, jstring},
};
use std::{
    collections::HashMap,
    panic::{AssertUnwindSafe, catch_unwind},
    path::PathBuf,
    sync::{
        Arc, LazyLock, Mutex,
        atomic::{AtomicI64, Ordering},
    },
};
static HANDLES: LazyLock<Mutex<HashMap<i64, Arc<Mesh>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT: AtomicI64 = AtomicI64::new(1);
fn handle(id: jlong) -> Result<Arc<Mesh>> {
    HANDLES
        .lock()
        .map_err(failure)?
        .get(&id)
        .cloned()
        .ok_or("Device sync is stopped.".into())
}
fn string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String> {
    Ok(env.get_string(&value).map_err(failure)?.into())
}
fn text(env: &mut JNIEnv<'_>, value: String) -> Result<jstring> {
    Ok(env.new_string(value).map_err(failure)?.into_raw())
}
fn call<T: Default>(env: &mut JNIEnv<'_>, work: impl FnOnce(&mut JNIEnv<'_>) -> Result<T>) -> T {
    match catch_unwind(AssertUnwindSafe(|| work(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            T::default()
        }
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "Device sync stopped unexpectedly. Restart sync.",
            );
            T::default()
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_start(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
    key: JByteArray,
    name: JString,
    ips: JString,
) -> jlong {
    call(&mut env, |env| {
        let key = zeroize::Zeroizing::new(env.convert_byte_array(&key).map_err(failure)?);
        let key: [u8; 32] = key
            .as_slice()
            .try_into()
            .map_err(|_| "Invalid sync storage key.")?;
        let mut store = Store::open(PathBuf::from(string(env, root)?), key, string(env, name)?)?;
        let config: serde_json::Value =
            serde_json::from_str(&string(env, ips)?).map_err(failure)?;
        let addresses: Vec<std::net::IpAddr> = serde_json::from_value(if config.is_array() {
            config.clone()
        } else {
            config["ips"].clone()
        })
        .map_err(failure)?;
        if let Some(excluded) = config["excluded"].as_array() {
            if excluded.len() > 256 {
                return Err("Too many excluded accounts.".into());
            }
            for owner in excluded {
                store.exclude(owner.as_str().ok_or("Invalid removed account.")?)?;
            }
        }
        let mesh = Mesh::start(store)?;
        mesh.set_addresses(addresses);
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        HANDLES.lock().map_err(failure)?.insert(id, mesh);
        Ok(id)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_inspect(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    query: JString,
) -> jstring {
    call(&mut env, |env| {
        let mesh = handle(id)?;
        let query = string(env, query)?;
        if query == "nearby" {
            return text(env, mesh.nearby().to_string());
        }
        if query == "numeric" {
            return text(env, mesh.numeric_status()?.to_string());
        }
        let store = mesh.store.lock().map_err(failure)?;
        let value=match query.as_str(){"metas"=>serde_json::to_string(&store.metas()),"pending"=>serde_json::to_string(&store.pending()),_=>{
  let peers:Vec<_>=store.state.peers.values().map(|p|serde_json::json!({"id":p.id,"name":p.name})).collect();
  Ok(serde_json::json!({"id":store.state.id,"name":store.state.name,"status":*mesh.status.lock().map_err(failure)?,"peers":peers,"records":store.state.objects.len(),"pending":store.pending().len()}).to_string())
 }}.map_err(failure)?;
        text(env, value)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_invite(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jstring {
    call(&mut env, |env| text(env, handle(id)?.invite()?))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_join(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    code: JString,
) -> jstring {
    call(&mut env, |env| {
        let code = string(env, code)?;
        text(env, handle(id)?.join(&code)?)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_put(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    kind: JString,
    owner: JString,
    item: JString,
    path: JString,
) -> jstring {
    call(&mut env, |env| {
        let (kind, owner, item, path) = (
            string(env, kind)?,
            string(env, owner)?,
            string(env, item)?,
            string(env, path)?,
        );
        let mesh = handle(id)?;
        let meta = mesh.store.lock().map_err(failure)?.put(
            &kind,
            &owner,
            &item,
            std::path::Path::new(&path),
        )?;
        text(env, serde_json::to_string(&meta).map_err(failure)?)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_putJson(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    kind: JString,
    owner: JString,
    item: JString,
    json: JString,
) -> jstring {
    call(&mut env, |env| {
        let (kind, owner, item, value) = (
            string(env, kind)?,
            string(env, owner)?,
            string(env, item)?,
            string(env, json)?,
        );
        let mesh = handle(id)?;
        let meta = mesh
            .store
            .lock()
            .map_err(failure)?
            .put_json(&kind, &owner, &item, &value)?;
        text(env, serde_json::to_string(&meta).map_err(failure)?)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_read(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    key: JString,
) -> jstring {
    call(&mut env, |env| {
        let key = string(env, key)?;
        let mesh = handle(id)?;
        let path = mesh.store.lock().map_err(failure)?.stage_export(&key)?;
        text(
            env,
            path.to_str().ok_or("Invalid private sync path.")?.into(),
        )
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_applied(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    key: JString,
    digest: JString,
) -> jboolean {
    call(&mut env, |env| {
        let (key, digest) = (string(env, key)?, string(env, digest)?);
        handle(id)?
            .store
            .lock()
            .map_err(failure)?
            .mark_applied(&key, &digest)?;
        Ok(1)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_signature(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    key: JString,
    value: JString,
) -> jstring {
    call(&mut env, |env| {
        let (key, value) = (string(env, key)?, string(env, value)?);
        let mesh = handle(id)?;
        let mut store = mesh.store.lock().map_err(failure)?;
        if !value.is_empty() {
            store.set_signature(&key, value)?;
        };
        text(env, store.signature(&key).unwrap_or_default())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_forget(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    peer: JString,
) -> jboolean {
    call(&mut env, |env| {
        handle(id)?.forget(&string(env, peer)?)?;
        Ok(1)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_cancelInvite(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jboolean {
    call(&mut env, |_| {
        handle(id)?.cancel_invite();
        Ok(1)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_stop(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jboolean {
    call(&mut env, |_| {
        if let Some(mesh) = HANDLES.lock().map_err(failure)?.remove(&id) {
            mesh.stop();
        }
        Ok(1)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_validate(
    mut env: JNIEnv,
    _class: JClass,
    kind: JString,
    owner: JString,
    id: JString,
    json: JString,
) -> jboolean {
    call(&mut env, |env| {
        let (kind, owner, id, json) = (
            string(env, kind)?,
            string(env, owner)?,
            string(env, id)?,
            string(env, json)?,
        );
        let value = serde_json::from_str(&json).map_err(failure)?;
        crate::profile::validate(&kind, &owner, &id, &value)?;
        Ok(1)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_account(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    owner: JString,
    allow: jboolean,
) -> jboolean {
    call(&mut env, |env| {
        let owner = string(env, owner)?;
        let mesh = handle(id)?;
        if allow != 0 {
            mesh.store.lock().map_err(failure)?.allow(&owner)?;
        } else {
            mesh.exclude(&owner)?;
        }
        Ok(1)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_numeric(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jstring {
    call(&mut env, |env| {
        text(env, handle(id)?.numeric_invite()?.to_string())
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_negzero_monogram_sync_NativeMesh_joinNumeric(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    peer: JString,
    code: JString,
) -> jstring {
    call(&mut env, |env| {
        let (peer, code) = (string(env, peer)?, string(env, code)?);
        text(env, handle(id)?.join_numeric(&peer, &code)?)
    })
}
