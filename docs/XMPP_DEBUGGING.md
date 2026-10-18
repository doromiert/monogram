# XMPP message debugging

Open Settings → XMPP connection → XMPP debugging and enable **Capture message XML**.
It works immediately on an existing connection. Send or receive the affected
message, return here, and tap **Refresh**. Tap a sent/received entry to view its
wire XML in a selectable monospace viewer; **Copy** copies that entry.

The log includes message stanzas and message-wrapped history/carbon responses.
It does not capture SASL authentication, passwords, IQ requests or presence.
OMEMO wire XML remains encrypted; the viewer does not decrypt a stanza for display.

Capture is per account and stays in memory. It retains at most 100 entries and
131,072 text characters total, trimming individual entries to 32,768 characters
plus a truncation marker. **Clear captured XML**, disabling capture, logout or
process restart removes the entries. Capturing remains enabled after a restart
if selected, but its old entries are gone. Nothing is automatically uploaded.
Copied XML can contain message text, addresses and attachment keys.

While capture is enabled, long-press a chat message and choose **Message diagnostics**.
This shows the displayed media type, native sticker classification, saved stanza
identities, encryption mode, legacy sticker hint, cached file presence, stored
file-sharing XML and the projected document attributes. This inspector also works
for older saved messages whose original wire XML was not captured.

For a received sticker shown as an image, compare the sender's XML, receiver's
XML and receiver's diagnostics. A missing outer `<sticker xmlns='urn:xmpp:stickers:0'/>`
marker differs from a present marker that was lost during storage/projection.
