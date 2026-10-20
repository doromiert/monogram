// Disposable, synthetic WebRTC peer. No browser profile, capture device or XMPP account is reused.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawn} from 'node:child_process';

const state = path.resolve(process.argv[2] || '');
const mode = process.argv[3];
if (!['native-offer', 'browser-offer'].includes(mode) || !fs.statSync(state).isDirectory()) throw Error('State directory and mode required');
const token = crypto.randomBytes(16).toString('hex');
const result = {browser: null, native: null};
const queues = {browser: [], native: []};
const waiting = new Map();
let browser, stopped = false, doneSent = false;
const save = () => fs.writeFileSync(path.join(state, 'result.json'), JSON.stringify(result), {mode: 0o600});
save();
function json(response, value, code = 200) {
  response.writeHead(code, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'});
  response.end(JSON.stringify(value));
}
function offerShape(sdp) {
  const sections = []; let section;
  for (const line of sdp.split(/\r?\n/)) {
    if (line.startsWith('m=')) {
      const fields = line.slice(2).split(' ');
      section = {audio: fields[0] === 'audio', video: fields[0] === 'video', zeroPort: fields[1] === '0', bundleOnly: false, ice: false, fingerprint: false};
      sections.push(section);
    } else if (section) {
      if (line === 'a=bundle-only') section.bundleOnly = true;
      if (line.startsWith('a=ice-ufrag:')) section.ice = true;
      if (line.startsWith('a=fingerprint:')) section.fingerprint = true;
    }
  }
  return sections;
}
function deliver(who, message) {
  if (!message || !['ready', 'offer', 'answer', 'candidate', 'error', 'done'].includes(message.type)) throw Error('Invalid message');
  if (message.type === 'offer' && typeof message.sdp === 'string') fs.writeFileSync(path.join(state, 'offer-shape.json'), JSON.stringify(offerShape(message.sdp)), {mode: 0o600});
  const pending = waiting.get(who);
  if (pending) { clearTimeout(pending.timer); waiting.delete(who); json(pending.response, message); }
  else {
    if (queues[who].length >= 128 || queues[who].reduce((sum, item) => sum + JSON.stringify(item).length, 0) + JSON.stringify(message).length > 1048576) throw Error('Relay full');
    queues[who].push(message);
  }
}

// Runs only in the disposable Firefox page; synthetic canvas and oscillator replace devices.
async function firefoxPeer(mode, base) {
  let pc, tone, draw, statsTimer, finished = false, reported = false;
  const pending = [];
  const post = async (route, message) => {
    const response = await fetch(base + route, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(message)});
    if (!response.ok) throw Error('relay');
  };
  const finish = async (pass, reason, metrics = {}) => {
    if (finished || pass && reported) return;
    if (pass) { reported = true; clearInterval(statsTimer); await post('result/browser', {pass, reason, ...metrics}); return; }
    finished = true; clearInterval(draw); clearInterval(statsTimer);
    try { await post('result/browser', {pass, reason, ...metrics}); } finally { pc?.close(); tone?.close(); }
  };
  const fail = () => finish(false, 'browser-media-failed').catch(() => {});
  window.addEventListener('unhandledrejection', fail);
  window.addEventListener('error', fail);
  try {
    pc = new RTCPeerConnection({iceServers: [], bundlePolicy: 'max-bundle'});
    pc.onicecandidate = event => {
      if (event.candidate?.candidate) post('native', {type: 'candidate', mid: event.candidate.sdpMid, index: event.candidate.sdpMLineIndex, candidate: event.candidate.candidate}).catch(fail);
    };
    pc.onconnectionstatechange = () => { if (pc.connectionState === 'failed') fail(); };
    const remote = document.createElement('video');
    remote.autoplay = true; remote.muted = true; remote.width = 160; remote.height = 120;
    document.body.append(remote);
    pc.ontrack = event => {
      if (event.track.kind === 'video') { remote.srcObject = new MediaStream([event.track]); remote.play().catch(fail); }
    };
    tone = new AudioContext();
    const oscillator = tone.createOscillator(), sink = tone.createMediaStreamDestination();
    oscillator.frequency.value = 440; oscillator.connect(sink); oscillator.start();
    await tone.resume();
    if (tone.state !== 'running') throw Error('synthetic-audio-clock');
    pc.addTrack(sink.stream.getAudioTracks()[0], sink.stream);
    const canvas = document.createElement('canvas'); canvas.width = 160; canvas.height = 120;
    document.body.append(canvas);
    const context = canvas.getContext('2d'); let frame = 0;
    draw = setInterval(() => {
      context.fillStyle = frame++ % 2 ? '#248bdd' : '#f07328'; context.fillRect(0, 0, 160, 120);
      context.fillStyle = '#ffffff'; context.fillRect(frame % 140, 20, 20, 60);
    }, 66);
    const synthetic = canvas.captureStream(15);
    pc.addTrack(synthetic.getVideoTracks()[0], synthetic);
    let setting = false;
    const description = async message => {
      if (setting || pc.remoteDescription) throw Error('description-order');
      setting = true;
      await pc.setRemoteDescription({type: message.type, sdp: message.sdp});
      while (pending.length) await pc.addIceCandidate(pending.shift());
      if (message.type === 'offer') {
        const answer = await pc.createAnswer(); await pc.setLocalDescription(answer);
        await post('native', {type: 'answer', sdp: answer.sdp});
      }
    };
    statsTimer = setInterval(async () => {
      if (finished) return;
      try {
        let frames = 0, audioPackets = 0, videoPackets = 0, dtls = false;
        for (const stat of (await pc.getStats()).values()) {
          if (stat.type === 'inbound-rtp' && (stat.kind || stat.mediaType) === 'video') frames += stat.framesDecoded || 0;
          if (stat.type === 'outbound-rtp' && (stat.kind || stat.mediaType) === 'audio') audioPackets += stat.packetsSent || 0;
          if (stat.type === 'outbound-rtp' && (stat.kind || stat.mediaType) === 'video') videoPackets += stat.packetsSent || 0;
          if (stat.type === 'transport' && stat.dtlsState === 'connected') dtls = true;
        }
        const transports = [...new Set(pc.getTransceivers().map(item => item.receiver.transport).filter(Boolean))];
        dtls ||= transports.length > 0 && transports.every(item => item.state === 'connected');
        await post('progress/browser', {frames, audioPackets, videoPackets, dtls, connection: pc.connectionState, transports: transports.length});
        if (dtls && frames >= 3 && audioPackets >= 3 && videoPackets >= 3) await finish(true, 'synthetic-media', {frames, audioPackets, videoPackets, dtls});
      } catch { fail(); }
    }, 500);
    await post('native', {type: 'ready', audio: true});
    if (mode === 'browser-offer') {
      const offer = await pc.createOffer(); await pc.setLocalDescription(offer);
      await post('native', {type: 'offer', sdp: offer.sdp});
    }
    while (!finished) {
      const response = await fetch(base + 'browser');
      if (!response.ok) throw Error('relay');
      const message = await response.json();
      if (message.type === 'candidate') {
        const candidate = {candidate: message.candidate, sdpMid: message.mid, sdpMLineIndex: message.index};
        if (pc.remoteDescription) await pc.addIceCandidate(candidate);
        else { if (pending.length >= 128) throw Error('candidate-limit'); pending.push(candidate); }
      } else if (message.type === 'offer' || message.type === 'answer') await description(message);
      else if (message.type === 'done') { finished = true; clearInterval(draw); clearInterval(statsTimer); pc.close(); tone.close(); }
      else if (message.type === 'error') throw Error('remote');
    }
  } catch { await finish(false, 'browser-media-failed').catch(() => {}); }
}

const server = http.createServer(async (request, response) => {
  const route = request.url?.split('?')[0];
  const prefix = '/' + token + '/';
  if (!route?.startsWith(prefix)) { response.writeHead(404); response.end(); return; }
  const name = route.slice(prefix.length);
  if (request.method === 'GET' && name === '') {
    response.writeHead(200, {'Content-Type': 'text/html', 'Cache-Control': 'no-store', 'Content-Security-Policy': "default-src 'self'; script-src 'unsafe-inline'; connect-src 'self'; media-src blob:; img-src 'self' data:"});
    response.end('<!doctype html><meta charset="utf-8"><title>Monogram synthetic peer</title><body><script>(' + firefoxPeer.toString() + ')(' + JSON.stringify(mode) + ',' + JSON.stringify(prefix) + ')</script>');
  } else if (request.method === 'GET' && ['native', 'browser'].includes(name)) {
    if (queues[name].length) { json(response, queues[name].shift()); return; }
    if (waiting.has(name)) { json(response, {error: 'duplicate-poll'}, 409); return; }
    const timer = setTimeout(() => { waiting.delete(name); json(response, {type: 'wait'}); }, 1000);
    waiting.set(name, {response, timer});
    response.on('close', () => { if (waiting.get(name)?.response === response) { waiting.delete(name); clearTimeout(timer); } });
  } else if (request.method === 'POST' && ['native', 'browser', 'result/native', 'result/browser', 'progress/browser'].includes(name)) {
    let body = '', size = 0;
    try {
      for await (const chunk of request) { size += chunk.length; if (size > 262144) throw Error('body-limit'); body += chunk; }
      const message = JSON.parse(body);
      if (name === 'progress/browser') {
        const progress = {frames: Number(message.frames || 0), audioPackets: Number(message.audioPackets || 0), videoPackets: Number(message.videoPackets || 0), dtls: !!message.dtls, transports: Number(message.transports || 0), connection: ['new', 'connecting', 'connected', 'disconnected', 'failed', 'closed'].includes(message.connection) ? message.connection : 'unknown'};
        fs.writeFileSync(path.join(state, 'progress.json'), JSON.stringify(progress), {mode: 0o600});
      } else if (name.startsWith('result/')) {
        const who = name.split('/')[1];
        if (typeof message.pass !== 'boolean') throw Error('result');
        // Results contain counters only; SDP, candidate addresses and credentials are never saved.
        result[who] = {pass: message.pass, frames: Number(message.frames || 0), audioPackets: Number(message.audioPackets || 0), videoPackets: Number(message.videoPackets || 0), dtls: !!message.dtls, reason: message.pass ? 'synthetic-media' : 'media-failed'};
        save();
        if (!doneSent && result.browser && result.native) { doneSent = true; deliver('browser', {type: 'done'}); deliver('native', {type: 'done'}); }
      } else deliver(name, message);
      json(response, {ok: true});
    } catch { json(response, {error: 'invalid-message'}, 400); }
  } else { response.writeHead(404); response.end(); }
});

function stop() {
  if (stopped) return; stopped = true;
  browser?.kill('SIGTERM');
  for (const pending of waiting.values()) { clearTimeout(pending.timer); pending.response.destroy(); }
  server.close(); server.closeAllConnections();
  if (browser?.pid && browser.exitCode === null && browser.signalCode === null) {
    const deadline = setTimeout(() => browser.kill('SIGKILL'), 5000);
    browser.once('exit', () => { clearTimeout(deadline); process.exit(0); });
  } else process.exit(0);
}
process.on('SIGTERM', stop); process.on('SIGINT', stop);
setTimeout(() => { if (!result.browser || !result.native) { result.browser ??= {pass: false, reason: 'timeout'}; result.native ??= {pass: false, reason: 'timeout'}; save(); } stop(); }, 120000).unref();
server.listen(0, '127.0.0.1', () => {
  const port = server.address().port;
  const profile = path.join(state, 'firefox-profile'); fs.mkdirSync(profile);
  const preferences = {
    'browser.shell.checkDefaultBrowser': false, 'browser.startup.homepage_override.mstone': 'ignore',
    'browser.newtabpage.enabled': false, 'browser.newtabpage.activity-stream.feeds.telemetry': false,
    'datareporting.policy.dataSubmissionEnabled': false, 'toolkit.telemetry.enabled': false,
    'app.update.enabled': false, 'extensions.update.enabled': false, 'app.normandy.enabled': false,
    'network.captive-portal-service.enabled': false, 'network.connectivity-service.enabled': false,
    'browser.startup.homepage': 'about:blank', 'media.autoplay.default': 0, 'media.autoplay.block-webaudio': false,
    'media.peerconnection.ice.obfuscate_host_addresses': false,
    'media.peerconnection.ice.default_address_only': false, 'media.peerconnection.ice.no_host': false,
  };
  fs.writeFileSync(path.join(profile, 'user.js'), Object.entries(preferences).map(([key, value]) => 'user_pref(' + JSON.stringify(key) + ',' + JSON.stringify(value) + ');').join('\n'));
  const log = fs.openSync(path.join(state, 'firefox.log'), 'w', 0o600);
  browser = spawn(process.env.MONOGRAM_FIREFOX || 'firefox', ['--headless', '--no-remote', '--profile', profile, 'http://127.0.0.1:' + port + '/' + token + '/'], {stdio: ['ignore', log, log], env: {...process.env, MOZ_CRASHREPORTER_DISABLE: '1', XDG_CACHE_HOME: path.join(state, 'browser-cache')}});
  fs.closeSync(log);
  browser.on('error', () => { result.browser = {pass: false, reason: 'browser-launch'}; save(); stop(); });
  fs.writeFileSync(path.join(state, 'peer.json'), JSON.stringify({port, token}), {mode: 0o600});
});
