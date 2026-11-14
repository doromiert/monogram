# Desktop/mobile interoperability reference

These Java/JNI peer and vector-generator sources moved from the desktop repository
to keep desktop builds, developer shells and runtime Java-free. Existing source
licenses and fixture provenance are unchanged. Generated vectors remain in
`desktop/tests/mobile-reference` for the native Rust test suite.

Build the optional live comparison from the mobile JDK environment:

```sh
JAVA_HOME=/path/to/jdk bash tests/desktop-reference/build.sh /tmp/monogram-peer /path/to/desktop
```

Then set `MONOGRAM_MOBILE_CRYPTO_FIXTURE=/tmp/monogram-peer` for desktop's ignored
`crypto::tests::native_mobile_interoperability` test. The fixture uses disposable
in-memory keys and never opens real accounts.
