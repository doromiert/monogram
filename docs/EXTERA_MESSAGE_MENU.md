# exteraGram message-menu options

Chat Settings → Chat behavior → Message menu contains independent, account-local
Copy photo and Message details switches. Copy photo defaults on; Message details
defaults off. Existing Save/Repeat switches remain separate and default off.

## Message details

Details uses the existing native message menu's swipe-back foreground, not a
separate dialog or webpage. Its native rows have icons, two-line values, marquee
for long metadata and a bounded scroll viewport with a top scroll shadow. A back
row returns to the message menu. The fixed viewport uses pixel LayoutParams
without a second density conversion, so loading does not resize the foreground.
Metadata is requested only when Details is opened, with local media inspection
off the UI thread. There is no automatic download or attachment-URL fetch.

Available values include the local message ID; send, forward and visible-edit
dates; sender and conversation address; encryption mode; recorded XMPP IDs;
MIME type, filename, byte size, resolution, duration and bitrate; cached file
path; and software/GPS EXIF when a cached image actually contains those tags.
Missing or unsupported metadata is omitted. This is a local diagnostic view,
not a server-delivery receipt or proof of successful peer decryption. It does
not include the message body, raw XML, attachment download URL, encryption key
or OMEMO fingerprints. Raw XML remains behind the separate debugging feature.

Most rows copy their value on tap. Cached-file rows open through the existing
native file viewer; GPS rows explicitly launch a geo intent in an installed map
app. Long-press copies a row's value. These explicit actions can disclose local
metadata/decrypted media to the chosen recipient app or clipboard, including
sender addresses, paths or GPS. Merely opening Details sends nothing. Media
inspection only reads a nonempty, existing local attachment/native cache file.

Both loader and popup retain the originating account and generation. Wrong-account
MessageObjects are rejected before any metadata/path lookup; logout/relogin,
detachment or an inactive foreground prevents delayed updates/actions. Failed
metadata extraction shows an error rather than exposing debug exception text.
Opening a missing/blocked map handler is handled without crashing.

## Copy photo

This action appears only for eligible photos/image media where native
forwarding/paid-media restrictions permit it. Secret/ephemeral media, blurred
previews and unrevealed sensitive media are excluded. Stickers use their existing
export actions. Copy requires an already-present local file: it never downloads
an attachment just to populate the clipboard. Image format/dimensions and the
existing transfer-size bound are validated before copying bytes into a private
clipboard cache, with a MIME-aware FileProvider URI. This copies image bytes,
not a remote URL or encrypted sharing key. Missing files and unsupported copies
produce a visible error.

Account-generation/request checks prevent a late clipboard mutation after
logout or a newer copy request. A short bounded cache history lets apps that
already received an earlier clipboard URI read it; superseded exports are
trimmed after successful copy (eight recent files and a 24-hour retention check).
This does not promise a globally persistent favorite/image collection.

## Evidence and limits

Reference APK: supplied `exteraGram-full-universal-20261004.apk`, SHA-256
`1ed01526c0cb57bc798c29cc06ab4d01d30282ce2cc1b177f0222e906c22581f`.
Static evidence already extracted by the parent is in ignored
`.state/ExteraConfig.java`, `.state/ExteraChatActivity.java`,
`.state/ExteraMessageDetailsPopupWrapper.java` and the debug variant. Those
excerpts establish the switches and native swipe-back metadata row structure,
including local media/EXIF inspection and copy/open actions. The Monogram port
substitutes recorded XMPP identity/encryption metadata for Telegram-only values.
It does not implement exteraGram's JPEG fingerprint utility or claim that every
stock Telegram field/action has an XMPP equivalent.

These are source/static APK comparisons, not observed exteraGram runtime behavior,
REA evidence records or a pixel-exact visual-parity claim. The parent owns the
combined release checks. This review did not run builds, tests or phone actions.
Native acceptance should check both switches/account isolation; short/long
metadata and submenu back/reopen; media not downloaded; image/video metadata;
map/file opening and copy; missing-media errors; and logout while metadata or
clipboard preparation is pending.

0.2.123 release gate: Nix arm64 packaging and seven focused Android cases pass. Two native-graphics clipboard cases cover byte-preserving independent export/MIME sniffing and malformed-image rejection with no export residue; chat-menu preferences cover APK defaults and independent account choices. Details UI/media extraction and phone interaction remain unverified.
