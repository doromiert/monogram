# Local crash reports

Monogram installs an uncaught-Java-exception handler in `ApplicationLoader`
before native-library initialization, while preserving the previous handler
(including Telegram's existing file-log and Android termination behavior).
The handler does not pretend the failed application can safely keep running.

It writes one bounded report to `no_backup/monogram-last-crash.txt` using
`AtomicFile`, then attempts to copy it to the system clipboard. Android can deny
clipboard access when an app is in the background; a saved pending marker causes
the next launch to offer **Copy crash report**. Developer tools also provides
**Last crash report**, with the existing selectable-text/copy viewer.

Reports contain app version, Android API, ABI, exception classes, bounded stack
frames/causes and up to 32 recent connection-state/error-class events. Raw
exception messages, thread names, logcat dumps, XMPP XML, addresses, passwords,
attachment URLs/keys, claim links, wallet mnemonics and decrypted message text are
not copied. Connection stream-error conditions are enum values, not server text.
The report is capped at 64 KiB UTF-8, cause chains/frames are bounded, cycles are
detected, and clipboard data is marked sensitive on supported Android versions.

The report stays local: no automatic issue creation, upload or contact message.
The latest report replaces the previous one. A report-copy request shares its
content with the Android clipboard, not with a Monogram server. The exception
handler always delegates to the original fatal handler even if reporting fails.

This catches uncaught Java exceptions, including JNI errors delivered as Java
throwables after installation. It does not catch SIGSEGV/native process aborts,
OS low-memory kills, forced stops or ANRs that never raise an uncaught Java
exception. Those still require platform crash/exit diagnostics. Existing
Telegram file logging is not itself evidence of clipboard crash reporting.

Synthetic tests invoke the handler with a harmless test delegate: persistence,
clipboard output, secret-message omission, stack bounds, cause cycles and
delegation are checked without killing a real app process. No phone crash was
induced for verification.
