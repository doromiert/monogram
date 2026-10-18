# Settings UI migration

The reference is the Chat Settings screen (`ThemeActivity`, basic mode) in the pinned official Telegram Android base. Custom XMPP pages use its native `RecyclerListView.setSections()` renderer (12 dp horizontal inset, 16 dp corner radius), `ActionBar.setAdaptiveBackground()` and native themed cells. Do not add a separate imitation of the glass surface.

## Surface inventory

| Entry point | Implementation | Current presentation |
| --- | --- | --- |
| Settings home | `SettingsActivity` | Existing native section renderer and navigation |
| My profile / settings name search | `UserInfoActivity` | Native profile editor; name search no longer opens the older standalone name form |
| Chat Settings | `ThemeActivity` | Native reference |
| Privacy and Security | `PrivacySettingsActivity` | Existing native sections and adaptive bar |
| Notifications | `NotificationsSettingsActivity` | Existing native sections and adaptive bar |
| Data and Storage | `DataSettingsActivity` | Existing native sections and adaptive bar |
| Chat folders | `FiltersSetupActivity` | Existing native sections and adaptive bar |
| Devices | `SessionsActivity` | Existing native sections and adaptive bar |
| Power Saving | `LiteModeSettingsActivity` | Existing native sections and adaptive bar |
| Language | `LanguageSelectActivity` | Existing native sections and adaptive bar |
| Round-video settings | `RoundVideoSettingsActivity` | Existing native sections and adaptive bar |
| XMPP connection | `MonogramConnectionActivity` | Shared native settings surface; connection, background operation and invitations cards |
| Conversation security | `MonogramSecurityActivity` | Shared native settings surface; encryption, reactions, verification, recipient policy and device cards |
| GIF search | `MonogramGifSettingsActivity` | Shared native settings surface |
| Translation | `MonogramTranslationSettingsActivity` | Shared native settings surface; provider/server/key value cells |
| Packs / individual pack | `MonogramPacksActivity` | Shared native settings surface; nonmodal account-owned import progress |
| Server invitations | `MonogramAccountInviteActivity` | Shared native settings surface |
| Contact authorization / requests | `MonogramContactActivity` | Shared native settings surface; presence values, request actions and explanatory footers |
| Room configuration / join / create | `MonogramRoomActivity` | Shared native settings surface and existing form cells |
| Room members | `MonogramRoomMembersActivity` | Shared native settings surface |
| New chat | `MonogramNewChatActivity` | Shared native settings surface |

The stock sticker, quick-reaction, proxy, storage, passcode and two-step-verification list screens also already use native sections. Forms and dialogs, settings search results, and nested navigation remain part of the overall review scope; a native-section call alone does not prove every state matches the reference.

## Invariants

Keep account selection, setting IDs, saved choices, API-key secrecy, foreground-connection behavior, fingerprint trust confirmations and per-recipient policy unchanged. Use existing native switches, values, destructive-row tags, text fields and dialogs. Theme updates must preserve accent/destructive distinctions.

Import progress persists when its screen closes, displays item and current-file byte progress when known, and allows cancellation. It does not resume across process death. Existing imported files must not need reimporting.

## Verification status

Production Java/APK compilation and six focused pack checks pass. Source review confirms the native section renderer and adaptive bar are shared by all ten custom forms. The authorization/security card pass moves explanations outside cards rather than leaving isolated headers. This is implementation and source evidence, not rendered acceptance.

Still required before the full migration is complete:

- Render all reachable settings screens, their nested forms and dialogs, and compare against Chat Settings.
- Check light and dark themes, theme changes while a screen is open, scroll transitions, RTL and large text.
- Check native selectors, switches, text fields and destructive/action rows for legibility and clipping.
- Confirm reconnect, background settings, fingerprint review, profile editing and provider settings remain usable.
- Verify the import progress, installed-pack artwork and sticker long-press on the device. Never modify the user's real packs or trust decisions just to exercise a check.

No phone navigation or setting changes were used as verification for this batch.
