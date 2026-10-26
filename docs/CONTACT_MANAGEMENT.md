# Roster contacts and local contact details

Adding an existing conversation's user through the contact editor now creates a
standard server roster item. It saves the selected name and private note locally;
adding a contact does not automatically request or grant presence access. Use
Contact request and Control access for those independent subscription directions.
Renaming an existing contact remains a local display override.

Native Delete contact removes the server roster entry and its subscriptions. It
also removes this device's explicit future-request approval policy and pending
request notice. It preserves messages, cached media, local aliases/notes, OMEMO
identity keys and fingerprint trust. Blocking and deleting a conversation remain
separate actions. Deleting a contact is not cryptographic device revocation.

The UI commits normal deletion after server acknowledgment. Undoable list removal
is restored on failure and the server roster is reloaded, including when a
multi-contact operation partly succeeded. Requests are account-fenced.

Schema 34 records actual roster membership independently of known message authors.
The previous projection incorrectly marked every known non-self user as a contact,
which could resurrect a deleted contact flag after history/profile loading. Fresh
connections replace the membership snapshot from the authenticated server roster;
pushes and successful native adds/deletes update it. Migrating creates the empty
cache without touching history or keys; membership is populated on connection.

Verification: disposable Prosody tests exercise add/remove, independent presence
subscriptions, roster group preservation, explicit-policy removal and retained
OMEMO device identity. Android regressions cover membership persistence, schema
33→34 migration, preserved message/name data and native deletion failure recovery.
These tests do not claim visual phone acceptance or cover a third-party server's
noncompliant roster implementation.
