# APK delivery

Read `../AGENTS.md` before working here. Mobile and desktop must reach **100%
feature parity**, including protocol, security and persistence behavior.
The desktop counterpart is `../desktop/`; keep the relevant feature documents
and its parity ledger current. A fixture is not an implemented feature.

After completing a feature or fix, always build a fresh APK from the final source
and provide its path or download link. Compilation and tests alone do not finish
the delivery. Follow the user's current branch and release-publishing instructions.

The published Android identity is `com.negzero.monogram.beta`, including optimized
release builds. Changing build type must not change this ID or the established
signing certificate. Before publishing, run `scripts/check-apk-update.py` against
the previous published APK using SDK build-tools; it checks both APK identities,
verified signers and increasing versionCode. Do not infer compatibility from a
matching certificate alone. Never uninstall the user's app to work around this.

Before publishing or pushing out any Android update, install the exact final APK
in an Android emulator, launch it, and keep it running for at least 60 seconds.
Monitor its process and crash logs for the entire interval. Any crash blocks
publication: fix the crash, rebuild, and restart the full 60-second check. Unit
tests, compilation and a physical-phone check do not replace this requirement.
