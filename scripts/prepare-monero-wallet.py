#!/usr/bin/env python3
"""Reproducible ARM64 SDK fetch, pinned signature verification and narrow JNI build.
Requires gpg, CMake 3.22 and Android NDK 27.2; never opens a wallet or contacts a node.
"""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, urllib.request, zipfile
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--sdk',default=os.environ.get('ANDROID_SDK_ROOT',str(root/'.state/release-sdk/share/android-sdk')));parser.add_argument('--gpg',default='gpg');args=parser.parse_args()
lock=json.loads((root/'third_party/monero-wallet-sdk/lock.json').read_text())
state=root/'.state/xmr-engine';state.mkdir(parents=True,exist_ok=True)
archive=state/'monfluo-0.9.7-arm64.apk'
def sha(path):return hashlib.file_digest(open(path,'rb'),'sha256').hexdigest()
if not archive.exists():
    temporary=archive.with_suffix('.download')
    with urllib.request.urlopen(lock['apk_url'],timeout=90) as source,open(temporary,'wb') as output:shutil.copyfileobj(source,output)
    temporary.rename(archive)
if sha(archive)!=lock['apk_sha256']:raise SystemExit('Pinned SDK APK checksum mismatch')
keyring=state/'verify-keyring';keyring.mkdir(mode=0o700,exist_ok=True);keyring.chmod(0o700)
subprocess.run([args.gpg,'--homedir',str(keyring),'--batch','--import',str(root/'third_party/monero-wallet-sdk/acx.asc')],check=True,stdout=subprocess.DEVNULL)
verified=subprocess.run([args.gpg,'--homedir',str(keyring),'--batch','--status-fd','1','--verify',str(root/'third_party/monero-wallet-sdk/SHA256SUMS.asc')],check=True,capture_output=True,text=True)
if '[GNUPG:] VALIDSIG '+lock['signer_fingerprint']+' ' not in verified.stdout:raise SystemExit('Unexpected SDK signer')
# Both the signed release manifest and the pinned lock must identify the exact APK.
manifest=subprocess.run([args.gpg,'--homedir',str(keyring),'--batch','--decrypt',str(root/'third_party/monero-wallet-sdk/SHA256SUMS.asc')],check=True,capture_output=True,text=True).stdout
if not any(line.split() and line.split()[0]==lock['apk_sha256'] and line.split()[-1].endswith('monfluo-0x9x7-arm64-v8a-release.apk') for line in manifest.splitlines()):raise SystemExit('SDK absent from signed manifest')
libs=state/'jni/arm64-v8a';libs.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(archive) as package:
    for name,details in lock['libraries'].items():
        data=package.read(name)
        if len(data)!=details['size'] or hashlib.sha256(data).hexdigest()!=details['sha256']:raise SystemExit('Native SDK checksum mismatch')
        target=libs/pathlib.Path(name).name
        if not target.exists() or sha(target)!=details['sha256']:target.write_bytes(data)
sdk=pathlib.Path(args.sdk).resolve();ndk=sdk/'ndk/27.2.12479018';cmake=sdk/'cmake/3.22.1/bin/cmake';build=state/'adapter-build'
subprocess.run([str(cmake),'-S',str(root/'TMessagesProj/jni/monogram-wallet'),'-B',str(build),'-G','Ninja','-DCMAKE_MAKE_PROGRAM='+str(sdk/'cmake/3.22.1/bin/ninja'),'-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-23','-DANDROID_STL=c++_shared','-DCMAKE_BUILD_TYPE=Release','-DMONERO_SDK_DIR='+str(libs)],check=True)
subprocess.run([str(cmake),'--build',str(build),'--parallel','2'],check=True)
shutil.copyfile(build/'libmonogram_wallet.so',libs/'libmonogram_wallet.so')
print('Verified and prepared private ARM64 wallet SDK + adapter:',libs)
