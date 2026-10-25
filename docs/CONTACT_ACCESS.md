# Contact access

Contact profiles expose **Contact request** and **Control access** directly.
The same control screen remains reachable from Conversation security and the
Requests and invitations inbox. Anonymous room occupants, rooms and the user's
own account do not get direct-contact controls.

## Two independent directions

A Contact request sends the standard XMPP presence subscription request. The
profile shows Pending while it is outstanding and Access granted after the
contact accepts. Sending a request does not grant that contact reciprocal access.
Control access can cancel an outstanding request.

Allow access records an explicit permission for this one contact under the
signed-in account on this device. Monogram approves that contact's standard
subscription request while signed in; an already pending request is approved
immediately. Until a request arrives, the UI says Waiting for request. Once the
server confirms a subscription from the contact, it says Allowed. No unrelated
incoming request is automatically accepted. Account changes isolate the saved
policy, and logout clears it with the account's other local access state.

After approval, the XMPP server holds the actual subscription and enforces access
to presence and data published with a presence/subscribed-contact access model.
Public vCard/profile data and public OMEMO 2 key nodes keep their existing sharing
rules. This feature never changes PEP node access models or OMEMO fingerprint
trust. Accept and request back remains available for a reciprocal relationship.

Revoke access clears the saved auto-approval permission before sending the
standard revocation. It stops the contact's subscription to the user's presence;
the independent subscription to that contact is retained. Existing aliases and
roster groups are preserved. Failed network operations remain visible as errors;
the app does not claim that a server grant/revocation has completed without the
corresponding roster state.

## Compatibility with server pre-approval bugs

[RFC 6121](https://xmpp.org/rfcs/rfc6121.html#subscriptions-preapproval) defines
server pre-approval. The tested Prosody 13.0.6 implementation accepts and stores
it but omits approved= from roster responses; its unsubscribed handler also
retains the hidden pre-approval. The live regression test reproduced automatic
re-approval after a supposedly successful revocation.

Monogram therefore uses explicit per-contact approval policy rather than creating
new unsolicited server pre-approvals. Previously existing server pre-approvals
are shown when the server reports them. When revoking, Monogram also reloads and
reasserts the contact's current name/groups using an ordinary roster set. In the
tested server this clears the hidden pre-approval while preserving subscriptions,
labels and groups; compliant servers have already cleared pre-approval through
the preceding unsubscribed stanza. The contact is never removed/re-added.

This policy is local to Monogram on the current device. After the first real
approval, active access is server-side and visible to other XMPP clients. Future
auto-approval of a fresh request requires Monogram to be signed in. No new custom
wire extension or global server ACL configuration is introduced.

## Validation

The local-server integration tests cover a request awaiting explicit approval,
one-way manual grants and contact-only PEP reads, reciprocal acceptance,
revocation, clearing hidden server pre-approvals, preserving roster metadata,
restoring the explicit policy into another client session, and rejecting self or
full-resource targets. Tests use disposable accounts, not the user's contacts.
