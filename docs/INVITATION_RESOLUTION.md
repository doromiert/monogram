# Cross-device invitation resolution

A confirmed server room bookmark with autojoin enabled resolves the matching pending group invitation for this account. Existing XEP-0402 notifications and bookmark refresh carry this state; LAN sync is not required. Other pending invitations remain intact, and fingerprint trust is unchanged. Desktop refreshes open invitation lists and closes an obsolete Join Room dialog. Failed/cached bookmark reads never resolve invitations. Older servers without notifications reconcile on their next successful refresh/reconnect.

Verified with an Android API36 regression for selective/idempotent removal and native desktop request/dialog/account fencing. Physical phone interaction was not used.
