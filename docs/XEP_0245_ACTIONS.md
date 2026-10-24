# XEP-0245 action messages

Specification: https://xmpp.org/extensions/xep-0245.html

A body beginning with the exact, case-sensitive string `/me ` is an action. Monogram sends and stores that body unchanged using the conversation's selected encryption mode. No custom stanza extension, presence event, or server-side action is generated. Other XMPP clients can interpret the same body according to XEP-0245.

Monogram presents actions in Telegram's centered service-message cell (`TYPE_DATE`, content type 1), using a custom action label such as `* Alex waves`. The label is italic, and its leading asterisk distinguishes a user-authored action from an actual membership or presence event. Sender names come from the same user/chat lookup as other message presentation, including room occupant identities and local contact names. Updating a sender's display name updates the action label without modifying the body.

Presentation applies to both received history and optimistic outgoing messages. The original message object, body, identifiers, encryption metadata and sending state are retained; the service action and styled label are presentation data only. Transport and storage never substitute the display label for `/me`.

Captions, forwarded content, real system events, `/ME`, `/meshrugs`, and text with leading whitespace are not converted. Messages without Monogram's recorded/queued encryption metadata are not treated as XMPP action messages.

Regression tests: `MonogramActionsTest` covers exact matching, plaintext/legacy OMEMO/OMEMO 2 in both directions, service-cell type, italic styling, original-body preservation, refreshed sender names, queued send state, and excluded media/system/forwarded cases.
