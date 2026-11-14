// SPDX-License-Identifier: GPL-3.0-or-later
// Run on the target device to verify OS locking and encrypted-store restart.
use monogram_sync::Store;
use std::{fs, path::PathBuf, time::Duration};
fn main() {
    let root=PathBuf::from(std::env::args().nth(1).expect("Pass an empty fixture directory"));
    fs::create_dir_all(&root).unwrap();
    let native=fs::File::create(root.join("std-lock-probe")).unwrap();
    println!("Standard library lock: {:?}",native.try_lock());drop(native);
    let path=root.join("store");let first=Store::open(path.clone(),[7;32],"Lock fixture".into()).unwrap();
    let identity=first.state.id.clone();
    let worker=std::thread::spawn(move||{std::thread::sleep(Duration::from_millis(150));drop(first);});
    let next=Store::open(path,[7;32],"Lock fixture".into()).unwrap();worker.join().unwrap();
    assert_eq!(next.state.id,identity);drop(next);fs::remove_dir_all(root).unwrap();
    println!("PASS target native lock and encrypted-store restart preserve identity");
}
