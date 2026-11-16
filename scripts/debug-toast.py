#!/usr/bin/env python3
"""Send one visible testing prompt to a connected debug Monogram build."""
import argparse
import shlex
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial", help="ADB device serial or wireless address")
parser.add_argument("message", help="Prompt text (at most 512 characters)")
args = parser.parse_args()
if not args.message.strip() or len(args.message) > 512:
    parser.error("message must contain 1–512 characters")
command = ["am", "broadcast", "-n", "com.negzero.monogram.beta/org.telegram.messenger.MonogramDebugToastReceiver",
           "--es", "message", args.message]
subprocess.run(["adb", "-s", args.serial, "shell", shlex.join(command)], check=True, timeout=15)
