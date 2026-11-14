# Legacy OMEMO JVM check

The Rust test exchanges an encrypted body and reply with the same upstream Java
crypto libraries used by Monogram mobile. Set `MONOGRAM_LEGACY_JAVA_CP` to the
classpath of `smack-core` and `smack-omemo` 4.4.8, `signal-protocol-java` 2.6.2,
`curve25519-java` 0.4.1, `protobuf-java` 2.5.0 and `bcprov-jdk15to18` 1.80.

```sh
nix develop --command cargo test --test transport \
  crypto::legacy::tests::smack_signal_interoperability -- --ignored --exact
```

The check uses disposable in-memory Signal state. The Rust Prosody fixture
separately exercises real device publication, trust and automatic selection.
