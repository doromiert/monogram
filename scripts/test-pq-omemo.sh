#!/usr/bin/env bash
# Disposable localhost accounts only. Never contacts a user's XMPP server/account.
set -euo pipefail
cd "$(dirname "$0")/.."
for program in prosody prosodyctl openssl keytool python3; do
    command -v "$program" >/dev/null || { echo "Missing test tool: $program" >&2; exit 1; }
done
native=${MONOGRAM_PQ_NATIVE_DIRECTORY:-"$PWD/.state/pq/backend/10861bff671740174f4e99f7e30dc492fcf6ec8b/jni/host"}
if [[ -z ${MONOGRAM_PQ_NATIVE_DIRECTORY:-} ]]; then bash scripts/build-pq-omemo.sh host; fi
mkdir -p .state/pq
state=$(mktemp -d "$PWD/.state/pq/server.XXXXXX")
server_pid=
cleanup() { if [[ -n $server_pid ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi; }
trap cleanup EXIT INT TERM
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
mkdir -p "$state/data" "$state/certs"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' \
    -keyout "$state/certs/localhost.key" -out "$state/certs/localhost.crt" > "$state/cert.log" 2>&1
keytool -importcert -noprompt -alias local-pq-test -file "$state/certs/localhost.crt" \
    -keystore "$state/trust.p12" -storepass monogram-pq-test > "$state/trust.log" 2>&1
cat > "$state/prosody.cfg.lua" <<EOF
run_as_root = false
daemonize = false
pidfile = "$state/prosody.pid"
data_path = "$state/data"
interfaces = { "127.0.0.1" }
c2s_ports = { $port }
s2s_ports = {}
http_ports = {}
https_ports = {}
ssl = { key = "$state/certs/localhost.key"; certificate = "$state/certs/localhost.crt" }
c2s_require_encryption = true
allow_registration = false
log = { { levels = { min = "warn" }; to = "console" } }
modules_enabled = { "roster"; "saslauth"; "tls"; "disco"; "carbons"; "pep"; "smacks" }
VirtualHost "localhost"
authentication = "internal_hashed"
Component "conference.localhost" "muc"
modules_enabled = { "muc_mam" }
EOF
for user in pqalice pqbob pqaclalice pqaclbob pqroomalice pqroombob pqmissingalice pqmissingbob pqtrustalice pqtrustbob pqownalice pqownbob; do prosodyctl --config "$state/prosody.cfg.lua" register "$user" localhost monogram-pq-test > "$state/register-$user.log" 2>&1; done
prosody --config "$state/prosody.cfg.lua" -F > "$state/server.log" 2>&1 &
server_pid=$!
python3 - "$port" "$server_pid" <<'PY'
import os,socket,sys,time
for attempt in range(100):
    os.kill(int(sys.argv[2]),0)
    try:
        with socket.create_connection(('127.0.0.1',int(sys.argv[1])),timeout=.2): break
    except OSError: time.sleep(.1)
else: raise SystemExit('Disposable Prosody did not start')
PY
export MONOGRAM_PQ_IT_STATE="$state"
export MONOGRAM_PQ_IT=1 MONOGRAM_PQ_IT_PORT="$port" MONOGRAM_PQ_IT_TRUST="$state/trust.p12"
args=()
if [[ -n ${MONOGRAM_PQ_TEST_INIT:-} ]]; then args+=(-I "$MONOGRAM_PQ_TEST_INIT"); fi
./gradlew --console=plain --max-workers=2 "${args[@]}" -PpqNativeDirectory="$native" :xmpp-core:pqTest :xmpp-core:pqInteropTest
printf 'Disposable PQ test logs: %s\n' "$state"
