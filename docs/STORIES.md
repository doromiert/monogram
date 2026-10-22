# Monogram stories baseline

Implemented in 0.2.142 using XEP-0501 (`urn:xmpp:pubsub-social-feed:stories:0`) and Atom enclosure media, matching the Monocles story representation inspected in its source. Native Telegram story editing and viewing surfaces are reused.

Stories last 24 hours and are shared with subscribed contacts through a presence-access PEP node. Node access is confirmed before publishing; unsupported configuration fails rather than publishing publicly. Media uses HTTPS HTTP Upload and is not OMEMO encrypted. The UI states this limitation. The server must support the required PEP and upload operations.

Local account/JID-scoped metadata supports immediate cached display, local read markers, bounded per-peer fetches and expiry filtering. Initial discovery scans up to 50 roster contacts; live PEP notifications and explicit peer loading refresh other contacts. Caption edits preserve the original expiry. Own stories can be deleted. Story replies use quoted ordinary messages and their normal encryption policy.

Public/custom audiences, viewer receipts, story reactions and media editing are not implemented. Cross-client publication was not exercised against real contacts during release validation. This is a protocol/UI baseline, not complete Telegram or Monocles story parity.

References: https://xmpp.org/extensions/xep-0501.html and https://codeberg.org/monocles/monocles_chat .
