# Developer tools

Settings → Developer tools opens native service discovery, ad-hoc commands and
existing message XML diagnostics. The same menu is accessible under XMPP
connection, and both tools appear in settings search. XML capture remains off
unless explicitly enabled.

## Service discovery

[XEP-0030](https://xmpp.org/extensions/xep-0030.html) queries support an arbitrary
server domain, bare JID or full JID and optional node. The initial target is the
signed-in account’s server. Identities, supported features and child items are
shown; tapping an item browses its exact JID and node. Information and item
errors are independent so one unsupported query does not hide the other result.
The response XML can be viewed and copied. Browsing does not execute commands.

## Ad-hoc commands

[XEP-0050](https://xmpp.org/extensions/xep-0050.html) command discovery queries
`http://jabber.org/protocol/commands` on the selected service or account. Choosing
a command opens a review page; Run command initiates it. Commands can take effect
immediately, so browsing and execution are separate actions.

[XEP-0004](https://xmpp.org/extensions/xep-0004.html) forms support text, private
text, multiline text, single and multiple JIDs, boolean fields and single/multiple
choice lists. Hidden fields (including FORM_TYPE) are preserved, fixed fields are
read-only, required answers are checked before submission, and Smack applies
field validation. Options retain their wire values independently of labels.
Notes and result forms, including reported rows, are displayed. Private fields
are masked outside their editor. XML is shown only on explicit request and may
contain submitted/private values; it is not added to the message capture log.
Media/captcha widgets and custom non-data-form command payloads are not rendered.

Next, Back, Complete and Cancel follow the service’s offered actions. Leaving an
active command sends a best-effort cancellation; it cannot undo changes already
made by the service. Network errors never automatically retry a command action.
An interrupted response invalidates the local command session rather than
silently repeating a potentially destructive operation. Server stanza errors
remain visible. Commands use the selected account’s normal authenticated XMPP
connection and permissions; they do not gain administrator access.

Requests run on a dedicated worker so discovery cannot block outgoing messages.
Account/connection changes invalidate callbacks and command ownership. No new
account PEP nodes are configured by opening these tools.

## Phone-number UI

Monogram account/profile/privacy settings use XMPP addresses. Telephone fields
from an existing vCard remain stored and are preserved during other field edits,
but are omitted from profile displays and the native field editor. Legacy phone
privacy, contact sync and number-verification entries are absent from settings
and search. Automatic phone-number detection and clickable actions in message
text are retained.
