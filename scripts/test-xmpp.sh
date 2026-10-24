#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for command in prosody prosodyctl openssl keytool mvn; do
    command -v "$command" >/dev/null || { echo "Run inside nix develop .#test" >&2; exit 1; }
done
mkdir -p .state
./scripts/build-omemo2.sh host > .state/omemo2-host-build.log 2>&1
export MONOGRAM_OMEMO2=1
state=$(mktemp -d "$PWD/.state/integration.XXXXXX")
server_pid=
cleanup() {
    if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
https_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
mkdir -p "$state/data" "$state/certs"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj '/CN=localhost' \
    -addext 'subjectAltName=DNS:localhost' -keyout "$state/certs/localhost.key" \
    -out "$state/certs/localhost.crt" >"$state/cert.log" 2>&1
keytool -importcert -noprompt -alias local-prosody -file "$state/certs/localhost.crt" \
    -keystore "$state/trust.p12" -storepass monogram-test >"$state/trust.log" 2>&1
openssl pkcs12 -export -in "$state/certs/localhost.crt" -inkey "$state/certs/localhost.key" \
    -out "$state/certs/server.p12" -passout pass:monogram-test >"$state/server-key.log" 2>&1
cat > "$state/prosody.cfg.lua" <<CONFIG
run_as_root = false
daemonize = false
pidfile = "$state/prosody.pid"
data_path = "$state/data"
plugin_paths = { "$PWD/server" }
interfaces = { "127.0.0.1" }
c2s_ports = { $port }
s2s_ports = {}
http_ports = {}
https_ports = { $https_port }
ssl = { key = "$state/certs/localhost.key"; certificate = "$state/certs/localhost.crt" }
c2s_require_encryption = true
allow_registration = false
allow_user_invites = true
registration_invite_only = true
admins = { "roomalice@localhost" }
log = { { levels = { min = "info" }; to = "console" } }
modules_enabled = { "blocklist"; "roster"; "lastactivity"; "vcard"; "saslauth"; "tls"; "disco"; "carbons"; "pep"; "private"; "smacks"; "mam"; "monogram_schedule"; "invites_adhoc"; "invites_register" }
VirtualHost "localhost"
authentication = "internal_hashed"
ssl = { key = "$state/certs/localhost.key"; certificate = "$state/certs/localhost.crt" }
archive_expires_after = "1d"
default_archive_policy = true
Component "conference.localhost" "muc"
modules_enabled = { "muc_mam" }
muc_avatar_advertise_hashes = "message"
archive_expires_after = "1d"
Component "upload.localhost" "http_file_share"
http_host = "localhost"
http_file_share_size_limit = 1024*1024
http_file_share_daily_quota = 10*1024*1024
CONFIG
for user in blindmodernalice blindmodernbob blindlegacyalice blindlegacybob partialalice partialbob pepaclalice pepaclbob blockdevicealice blockdevicebob blockingalice blockingbob callsalice callsbob legacyjournalalice legacyjournalbob receiptalice receiptbob subscribealice subscribebob bookmarkmodern bookmarklegacy roomalice roombob alice bob typingalice typingbob modernalice modernbob peeralice peerbob firstpeeralice firstpeerbob devicealice devicebob sessionalice sessionbob fallbackalice fallbackbob; do
    prosodyctl --config "$state/prosody.cfg.lua" register "$user" localhost monogram-test
 done
prosody --config "$state/prosody.cfg.lua" -F >"$state/server.log" 2>&1 &
server_pid=$!
python3 - "$port" "$server_pid" <<'PY'
import os, socket, sys, time
for _ in range(100):
    os.kill(int(sys.argv[2]), 0)
    try:
        with socket.create_connection(('127.0.0.1', int(sys.argv[1])), timeout=.2): break
    except OSError: time.sleep(.1)
else: raise SystemExit('Prosody did not start')
PY
export MONOGRAM_IT=1 MONOGRAM_IT_PORT="$port" MONOGRAM_IT_STATE="$state" MONOGRAM_IT_HTTPS_PORT="$https_port"
test_options=()
if [ -n "${MONOGRAM_TESTS:-}" ]; then test_options+=("-Dtest=$MONOGRAM_TESTS"); fi
mvn -f xmpp-core/pom.xml clean test "${test_options[@]}" -DargLine="-Xcheck:jni -Djava.library.path=$PWD/.state/omemo2-host -Djavax.net.ssl.trustStore=$state/trust.p12 -Djavax.net.ssl.trustStorePassword=monogram-test"
if [ -n "${MONOGRAM_ANDROID_DEVICE:-}" ]; then
    ./scripts/test-android-store.sh "$MONOGRAM_ANDROID_DEVICE"
fi
printf 'Integration server log: %s\n' "$state/server.log"
