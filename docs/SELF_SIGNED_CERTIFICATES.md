# Self-signed XMPP server certificates

Sign-in and XMPP connection settings expose Server certificate. A failed TLS
attempt can record a valid self-signed leaf for explicit review, including its
subject, issuer, validity and SHA-256 fingerprint. Trust requires confirmation;
there is no accept-all option or automatic first-use approval. A changed pending
certificate invalidates an older confirmation. Public certificate data, not
passwords or private keys, is stored in the certificate preferences.

Approval is an exception for the exact leaf fingerprint, scoped to the XMPP
service domain plus configured host and port. Accounts using the same service
and endpoint share that exception. A different domain, host, port or self-signed
certificate requires separate approval. Normal platform CA trust remains the
first path. Expired certificates, invalid self-signatures, missing certificates
and arbitrary untrusted chains are rejected. A valid private-CA chain is not
misclassified as a self-signed leaf exception.

The custom trust manager is applied to XMPP sign-in/reconnect and account
registration. STARTTLS remains required; Smack's separate service-domain hostname
verification is unchanged. Approval does not affect OMEMO identity trust, file
upload HTTPS, previews or wallet node TLS. Forgetting approval reconnects a
configured account; the sign-in screen asks the user to retry explicitly.

Tests use disposable localhost keys in xmpp-core/src/test/resources, never shipped
in the APK. Real local TLS handshakes prove rejection before approval, acceptance
after matching approval, and continued wrong-hostname rejection. Further tests
cover replacement/expiry rejection, endpoint scoping, revocation and a changed
certificate while confirmation was open. No production server exception was added.
