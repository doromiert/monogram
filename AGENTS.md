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
