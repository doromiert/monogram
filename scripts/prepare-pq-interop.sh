#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
revision=49c0e78045532a78033ffd448ca4316859267bf2
reference="$root/.state/monocles-pq-reference"
if [[ ! -d "$reference/.git" ]]; then
    git clone --no-checkout https://codeberg.org/monocles/monocles_chat.git "$reference"
    git -C "$reference" checkout --detach "$revision"
fi
[[ $(git -C "$reference" rev-parse HEAD) == "$revision" ]]
[[ -z $(git -C "$reference" status --porcelain --untracked-files=no) ]]
out="$root/.state/pq/reference-java/eu/siacs/conversations/crypto/axolotl"
mkdir -p "$out"
for file in XmppOmemo2Message CryptoFailedException NotEncryptedForThisDeviceException; do
    cp "$reference/src/main/java/eu/siacs/conversations/crypto/axolotl/$file.java" "$out/$file.java"
done
