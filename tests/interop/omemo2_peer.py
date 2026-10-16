"""Disposable loopback peer using independent Slixmpp/OMEMO/Twomemo implementations.

Slixmpp-omemo 2.2 has no SCE API. This fixture wraps/unwraps XML itself, while
the independent libraries handle PEP, bundles, signatures, ratchets and payload crypto.
Never use its fixture-only trust policy with a real account.
"""
import asyncio
import copy
import importlib.metadata
import json
import logging
import os
from pathlib import Path
import secrets
import ssl
import sys
import traceback
from xml.etree import ElementTree as ET

from omemo.storage import Just, Nothing, Storage
from slixmpp import ClientXMPP, JID
from slixmpp.plugins import register_plugin
from slixmpp.xmlstream.handler import CoroutineCallback
from slixmpp.xmlstream.matcher import MatchXPath
from slixmpp_omemo import TrustLevel, XEP_0384
import twomemo.etree

NS = "urn:xmpp:omemo:2"
SCE = "urn:xmpp:sce:1"
CLIENT = "jabber:client"
MODE = os.environ["MONOGRAM_PEER_MODE"]
assert MODE in {"native-first", "peer-first"}
PREFIX = "firstpeer" if MODE == "peer-first" else "peer"
OWN, REMOTE = f"{PREFIX}bob@localhost", f"{PREFIX}alice@localhost"
STATE = Path(os.environ["MONOGRAM_IT_STATE"]).resolve()
assert STATE.parent == Path.cwd() / ".state" and STATE.name.startswith("integration.")
MARKER = STATE / f"{MODE}.ready.json"


class Memory(Storage):
    def __init__(self):
        super().__init__()
        self.data = {}

    async def _load(self, key):
        return Just(self.data[key]) if key in self.data else Nothing()

    async def _store(self, key, value):
        self.data[key] = copy.deepcopy(value)

    async def _delete(self, key):
        self.data.pop(key, None)


class FixturePlugin(XEP_0384):
    name = "xep_0384"

    def plugin_init(self):
        self._fixture_storage = Memory()
        super().plugin_init()

    @property
    def storage(self):
        return self._fixture_storage

    @property
    def _btbv_enabled(self):
        return False

    async def _devices_blindly_trusted(self, devices, identifier):
        raise AssertionError("Blind trust is disabled")

    async def _prompt_manual_trust(self, devices, identifier):
        manager = await self.get_session_manager()
        for device in devices:
            assert device.bare_jid in {OWN, REMOTE}, "Only disposable fixture accounts may be approved"
            await manager.set_trust(device.bare_jid, device.identity_key, TrustLevel.TRUSTED.value)


register_plugin(FixturePlugin)


class Peer(ClientXMPP):
    def __init__(self):
        super().__init__(OWN + "/Independent", "monogram-test")
        self.ssl_context = ssl.create_default_context(cafile=str(STATE / "certs/localhost.crt"))
        self.enable_direct_tls = False
        self.enable_plaintext = False
        self.ready = asyncio.Event()
        self.lock = asyncio.Lock()
        self.add_event_handler("session_start", self.start)
        self.register_handler(CoroutineCallback("fixture-messages", MatchXPath(f"{{{CLIENT}}}message"), self.message))
        self.register_plugin("xep_0384", module=sys.modules[__name__])

    async def start(self, _):
        self.send_presence()
        await self.plugin["xep_0384"].get_session_manager()
        MARKER.write_text(json.dumps({"versions": {name: importlib.metadata.version(name)
                          for name in ("slixmpp-omemo", "twomemo", "omemo")}}))
        self.ready.set()

    async def send_content(self, content, to):
        envelope = ET.Element(f"{{{SCE}}}envelope")
        protected = ET.SubElement(envelope, f"{{{SCE}}}content")
        for child in content:
            protected.append(copy.deepcopy(child))
        ET.SubElement(envelope, f"{{{SCE}}}rpad").text = secrets.token_urlsafe(512)
        ET.SubElement(envelope, f"{{{SCE}}}from", {"jid": OWN})
        ET.SubElement(envelope, f"{{{SCE}}}to", {"jid": REMOTE})
        plugin = self.plugin["xep_0384"]
        await plugin.refresh_device_lists({JID(REMOTE)}, force_download=True)
        manager = await plugin.get_session_manager()
        messages, errors = await manager.encrypt(frozenset({REMOTE}), {NS: ET.tostring(envelope)},
                                                  backend_priority_order=[NS])
        assert not errors and len(messages) == 1
        stanza = self.make_message(mto=to, mtype="chat")
        stanza.append(twomemo.etree.serialize_message(next(iter(messages))))
        stanza.enable("store")
        stanza.send()

    async def message(self, stanza):
        if stanza["from"].bare != REMOTE:
            return
        async with self.lock:
            try:
                if stanza["body"] == "start-independent-peer" and MODE == "peer-first":
                    content = ET.Element(f"{{{SCE}}}content")
                    ET.SubElement(content, f"{{{CLIENT}}}body").text = "Independent peer starts 🦄"
                    await self.send_content(content, stanza["from"])
                    return
                encrypted = stanza.xml.find(f"{{{NS}}}encrypted")
                if encrypted is None:
                    return
                manager = await self.plugin["xep_0384"].get_session_manager()
                plaintext, _, _ = await manager.decrypt(twomemo.etree.parse_message(encrypted, REMOTE))
                if plaintext is None:
                    return
                envelope = ET.fromstring(plaintext)
                assert envelope.tag == f"{{{SCE}}}envelope"
                assert envelope.find(f"{{{SCE}}}from").get("jid") == REMOTE
                assert envelope.find(f"{{{SCE}}}to").get("jid") == OWN
                assert envelope.find(f"{{{SCE}}}rpad") is not None
                content = envelope.find(f"{{{SCE}}}content")
                assert content is not None and content.find(f"{{{CLIENT}}}body") is not None
                await self.send_content(content, stanza["from"])
            except Exception:
                traceback.print_exc()
                (STATE / f"{MODE}.error").write_text(traceback.format_exc())


async def main():
    peer = Peer()
    await peer.connect("127.0.0.1", int(os.environ["MONOGRAM_IT_PORT"]))
    await asyncio.wait_for(peer.ready.wait(), 30)
    await asyncio.Event().wait()


if __name__ == "__main__":
    logging.basicConfig(level=logging.WARNING)
    asyncio.run(main())
