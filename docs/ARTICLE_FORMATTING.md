# Article formatting over XMPP

Monogram keeps Telegram's native article editor and renderer. Rich-message requests are not Telegram text requests: the editor stores their blocks in `rich_message` and leaves `message` empty. The XMPP bridge converts those blocks before sending, including on corrections. Sending an empty body previously caused `IllegalArgumentException` before delivery.

Ordinary formatted text continues to use [XEP-0393](https://xmpp.org/extensions/xep-0393.html) body styling and [XEP-0394](https://xmpp.org/extensions/xep-0394.html) ranges. XEP-0394 covers spans, code blocks, lists and quotes; it does not define an article/table representation. [XEP-0481](https://xmpp.org/extensions/xep-0481.html), currently Experimental, defines content-type hints, including Markdown:

```xml
<message type='chat'>
  <body>| Item | Count |
| --- | --- |
| Apples | 3 |</body>
  <content xmlns='urn:xmpp:content' type='text/markdown'/>
</message>
```

Textual articles use readable Markdown in the body and the empty XEP-0481 hint. Monogram reuses its existing Markdown parser and native article rendering, including tables. Other clients may render Markdown or show its source. The sender sees “This will look broken outside of Monogram”. This warning does not remove or disable formatting controls.

The conversion handles headings, basic inline formatting, explicit links, code, quotes, ordered/unordered lists, checkboxes, math and tables. Underline, highlight, subscript/superscript and spoilers use inline HTML extensions; their support in other Markdown clients varies. Details sections become expanded text; table merged-cell spans, alignment and decorative layout do not round-trip. Embedded media and interactive widgets are rejected with an explicit transport error rather than omitted. They require separate XMPP attachment/widget work; this is not complete Telegram article parity.

The content hint is stored in the existing formatting record, with a versioned backward-readable local codec. Reply-prefix slicing preserves the hint. OMEMO 2 includes it inside authenticated protected content. Legacy OMEMO encrypts the Markdown body and sends only the empty renderer hint publicly: receivers accept this hint only as a presentation selector for the decrypted body. They never accept alternative public content in its place. A server may remove/change that legacy hint, affecting rendering but not the decrypted text. This matches legacy OMEMO's inability to authenticate arbitrary extensions.

Incoming Markdown is limited to 32,768 UTF-16 units before parsing; outgoing articles also have a 32 KiB UTF-8 limit. Native recursive rendering is preceded by AST depth/node limits. Unparseable or oversized incoming content remains readable as the original text. No external HTML or scripts are executed by this conversion.
