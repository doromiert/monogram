# Inline custom emoji profile 1

Status: implemented; profile over XHTML-IM images and XEP-0231 Bits of Binary,
not a Monogram XML namespace. Source of truth: `InlineEmoji.java`; native asset
storage/projection: `MonogramEmojis.java`.

The ordinary message body contains the complete readable fallback text. A single
`html` element in `http://jabber.org/protocol/xhtml-im` contains a `body` in
`http://www.w3.org/1999/xhtml`. Its ordered `p`/`span` text and each image's `alt`
MUST reconstruct that body exactly; alternate remote text is not accepted.

```xml
<body>Hello :wave:</body>
<html xmlns='http://jabber.org/protocol/xhtml-im'>
  <body xmlns='http://www.w3.org/1999/xhtml'>
    <p><span><span>Hello </span><img src='cid:sha-256+HASH@bob.xmpp.org' alt=':wave:' width='32' height='32'/></span></p>
  </body>
</html>
<data xmlns='urn:xmpp:bob' cid='sha-256+HASH@bob.xmpp.org' type='image/webp' max-age='31536000'>BASE64</data>
```

`HASH` is the lowercase 64-digit SHA-256 of the decoded bytes; it is a placeholder
in this example. Data uses standard base64, not base64url. Every referenced asset
must be supplied in the same message and hash-verified. No CID/network lookup,
external HTTP image URL, script, arbitrary CSS or arbitrary HTML is executed.
Supported asset types are `image/gif`, `image/png`, `image/webp`,
`application/x-tgsticker` and `video/webm`. Width/height are presentation hints;
media still needs native bounded decoding.

Receiver bounds are 64 non-overlapping replacement ranges, 16 unique assets and
128 KiB total decoded asset bytes. A range covers 1–64 Unicode codepoints and its
fallback has no ISO control characters. The plain body is at most 131072 UTF-16
units / 65536 codepoints. The XML reader limits depth to 8 and traversed content
nodes to 256, rejects unknown element/attribute shapes, duplicate assets,
conflicting MIME types, hash mismatches and fallback-text differences.

Ranges in the protocol model count Unicode codepoints; native Android entities
count UTF-16 units and must be converted at the boundary. Reply/forward prefixes
shift the wire ranges rather than replacing body text. Per-account native document
IDs are local projections of content-addressed assets and must not go on the wire.

This metadata may be read from a cleartext message or authenticated OMEMO2/SCE
content. A public XHTML/BoB element attached to legacy OMEMO ciphertext is not
private/authenticated content and must be ignored. Legacy encrypted messages keep
the textual fallback instead. Malformed/unsupported metadata leaves the readable
body available and does not trigger a network fetch.
