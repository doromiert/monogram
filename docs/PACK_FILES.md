# Opening sticker and emoji pack files

`.pack`, `.nzsp` and `.xmsp` all open the existing XMSP v1 archive format. Tap a downloaded chat attachment, or use Open with Monogram in a file manager. Monogram validates the archive on a worker and opens the native sticker/emoji pack sheet. The pack starts uninstalled: use Add Pack to install it. Existing installed packs are preserved if validation fails. Closing the loading dialog cancels the operation; account changes invalidate the result.

Android file managers may report an opaque content URI with `application/octet-stream` or `application/zip`. Monogram appears as an Open with candidate for those types and checks DISPLAY_NAME in the background; it rejects ordinary ZIP/binary files without a pack suffix. Dedicated `application/vnd.monogram.sticker-pack` and `application/x-monogram-pack` types also work. Filename-bearing file/content URIs can resolve by extension with or without MIME metadata. No remote HTTP URL is fetched by the external-open handler; content access uses the URI permission granted by the sender.

A signed-in account is required. If signed out, sign in and reopen the file. Archives never import credentials, native IDs or remote connection policy. The existing archive byte, entry, path, hash and media validation limits still apply. This adds no wire format or XMPP extension.

Desktop uses the same archive profile and centered pack dialog. Its shared-mime-info file and desktop entry register the same suffixes for Open with Monogram.

Verification for 0.2.224: 33 Android pack/archive tests pass, covering URI/MIME intent-filter matching, preview-before-install, installation and malformed input without library damage. Desktop native cold-library/preview/Add Pack/error acceptance passes, plus 287 default desktop tests. Physical Android file-manager interaction is not yet verified.
