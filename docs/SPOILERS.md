# Spoiler messages

Monogram uses its existing text-formatting menu's **Spoiler** action and the
media picker's **Hide with spoiler** action. Tap the hidden text or media to
reveal it using the native message renderer.

## Whole messages and media: XEP-0382

Feature: `urn:xmpp:spoiler:0`. Monogram advertises this in service discovery.
[ XEP-0382 ](https://xmpp.org/extensions/xep-0382.html) is Deferred; it describes
whole-message hiding, including inline images. Monogram sends an empty hint:

```xml
<message xmlns='jabber:client' type='chat'>
  <body>https://files.example.org/movie.mp4</body>
  <spoiler xmlns='urn:xmpp:spoiler:0'/>
</message>
```

With file sharing (XEP-0447), the same marker hides the attachment and caption.
A fully spoilered text body also gets this marker for other supporting clients.
Incoming optional hint strings never replace the message's authenticated body.

## Partial text: Monogram extension to XEP-0394

Neither XEP-0382 nor [XEP-0394](https://xmpp.org/extensions/xep-0394.html) defines
spoiler ranges. Monogram adds a namespaced child to XEP-0394's extensible `span`:

```xml
<message xmlns='jabber:client' type='chat'>
  <body>The answer is 42</body>
  <markup xmlns='urn:xmpp:markup:0'>
    <span start='14' end='16'>
      <spoiler xmlns='urn:monogram:spoiler:0'/>
    </span>
  </markup>
  <unstyled xmlns='urn:xmpp:styling:0'/>
</message>
```

`start` is inclusive, `end` exclusive, both measured in Unicode code points,
not UTF-16 code units. The only content is the original body. A spoiler may
coexist with standard inline styles within one span. The custom namespace is
also advertised in service discovery; it is **not** an XSF-assigned XEP.
Clients without this extension may display partial spoilers as ordinary text.
Clients without XEP-0382 may display whole spoilers normally too.

## Encryption, persistence and previews

OMEMO 2 carries both kinds of metadata inside its authenticated encrypted
content. Legacy Conversations OMEMO encrypts only the body; spoiler markers and
range coordinates travel as public, unauthenticated presentation hints. They
can only hide parts of the decrypted text: they cannot supply alternate text,
links, file metadata or other styles. Consequently legacy spoiler hints reveal
the presence/positions of spoilers and do not provide tamper-proof hiding.
Spoilers are a display preference, not encryption or access control.

The same parsing is used for DMs, rooms, carbons and archive messages. Local
formatting records retain spoiler state across restarts, replies and captions;
the persistence codec reads previous versions as well. Native spoiler entities
provide notification redaction. Link previews are neither fetched nor displayed
when their URL intersects a text spoiler, preventing a preview from exposing it.
