# Feature audit — 26 October 2026

This source audit distinguishes current code gaps from historical TODO entries.
It is not a claim of overall feature completeness.

## Confirmed remaining gaps

- Interactive checklists: codecs/store exist but native send/receive/mutations are
  not wired. Concurrent same-revision updates can diverge; define a convergent
  operation scheme before exposing collaboration. See protocols/CHECKLISTS.md.
- Privacy settings still contain Telegram-only password/2FA/passkey, account TTL,
  general profile privacy and bot/web authorization actions. Capability-adapt the
  screen and settings search together; preserve supported call/local policies.
- Settings Devices still points at Telegram SessionsActivity. Replace with the
  account OMEMO screen and remove unsupported session-management search routes.
- Calendar imports now publish repairs, but native ChatActivity replaces a changed
  timestamp in place without rebuilding day groups/chronology. Rebuild safely
  while preserving scroll anchors, selected messages and grouped media.
- Scheduled media/rich articles/send-when-online lack the local text journal's
  complete native flow. Existing text scheduling/repeat support is real.
- Unknown, unmarked MUC private messages need discovery plus a separate occupant
  session model; restored known rooms are filtered from direct-message recovery.
- vCard4 support is absent; current interoperable profile transport is vcard-temp
  plus PEP nickname/avatar. Add bounded merge/fallback without overwriting richer
  fields or fetching arbitrary external avatar pointers.

## Fixed in this audit batch

Server roster add/delete from existing contact flows, durable roster membership,
failed-deletion recovery, calendar repair publication, repeating retry cadence,
late cancelled-download progress cleanup, known-room journal routing, and draft
checklist safety checks. Their dedicated docs state exact verification limits.
