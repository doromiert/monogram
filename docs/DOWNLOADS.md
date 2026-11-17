# Attachment downloads

Ordinary documents and archives stay manual. Tapping Download opens Android’s
Files save picker before fetching bytes. Cancelling starts no download and resets
the message control. The selected destination receives only the verified cached
attachment, copied outside the UI thread with transfer cancellation and account
checks. Cached documents also offer the picker through Save to Downloads.

Photos, videos, audio and stickers keep their network/type/size policies. Unknown
Legacy URI-only attachments can inspect response metadata to discover media;
document bodies are refused by the automatic download policy. Sticker/emoji pack
attachments retain the existing native pack preview instead of being exported.

Verification: API36 Robolectric checks cover media/document policy, save intent
filename/MIME, cancellation, exact cached-byte export and refusal to export other
private files. Physical DocumentsUI/provider acceptance is not exercised by these
tests; no physical phone is controlled during this change.
