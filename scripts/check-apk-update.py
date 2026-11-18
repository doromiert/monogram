#!/usr/bin/env python3
"""Reject incompatible release artifacts before upload; compare against a published APK."""
import argparse
import re
import subprocess
from pathlib import Path


def identity(apk, tools):
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package:
        raise ValueError("APK package metadata is missing")
    verified = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
    signers = set(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})", verified))
    if not signers:
        raise ValueError("APK has no verified signer")
    return package[1], int(package[2]), package[3], signers


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("previous", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    old = identity(args.previous, args.build_tools)
    new = identity(args.candidate, args.build_tools)
    if old[0] != new[0]:
        parser.error(f"App ID changed: {old[0]} -> {new[0]}")
    if old[3] != new[3]:
        parser.error("Signing certificate changed")
    if new[1] <= old[1]:
        parser.error("versionCode must increase")
    print(f"Compatible update: {new[0]} {old[2]} -> {new[2]}; signer unchanged")


if __name__ == "__main__":
    main()
