# XMPP location sharing

Static points and venues selected through Monogram's existing native Telegram
location picker now use the ordinary durable message/outbox path. Their fallback
body is an RFC 5870 `geo:latitude,longitude;u=uncertainty` URI, optionally followed
by a venue title/address on separate lines. This is ordinary authenticated content:
legacy OMEMO encrypts the body, modern OMEMO protects it in SCE, and room sending
uses the existing room-recipient encryption/trust policy. No public geolocation
extension is added to an encrypted stanza. Plaintext chat mode remains plaintext.

Exact geo bodies received live or through history project back into native
location/venue media, retaining the original text for copying/reply excerpts.
Forwarding creates a new native location send, using the destination chat's own
encryption mode. Replies, temporary local IDs, retry/failed queue persistence,
account ownership and session fences reuse the existing message path. This is
static sharing only: live tracking/start/update/stop protocols are not implemented,
and the native live-share action is disabled for XMPP. Telegram's venue-search bot
is unavailable and is not queried; selecting a point/current permitted position
still works. Received venue labels are preserved.

The native full-screen map/picker uses an `IMapsProvider` implementation backed by
OpenStreetMap raster tiles, without a Google API key or Play Services location.
Only visible tiles in an explicitly opened map are requested, with two download
workers, at most 48 pending requests per map, stale queued viewport suppression,
a bounded memory/disk cache, a seven-day default freshness period and retry
cooldown. Attribution links to OpenStreetMap's copyright page. Map panning, pinch
zoom, markers, radius circles and camera movements use the existing native UI.
Satellite/hybrid and Google-specific night vector styling are not supported; the
unsupported map-style selector is hidden. Native location permission remains
required to display the phone's position; lookup/update subscriptions exist only
while that map is resumed and location display is enabled, and stop on pause or
destroy. Manually viewing/choosing a point needs no device-position permission.

Ordinary chat bubbles use a local map placeholder. Receiving/opening a chat does
not send its coordinates to a map provider. Explicitly opening the map requests
its visible area from tile.openstreetmap.org, revealing the map area and IP address
to that provider. External directions/open-map actions offer a generic `geo:` app
intent, avoiding a mandatory Google Maps installation or automatic submission of
the phone's current position to a Google directions URL. Missing external apps
leave the built-in map available.

Published in 0.2.120 after two core geo parsing and two native transport/projection/forwarding checks pass, with successful Nix arm64 packaging. No real location was captured, shared or viewed. Native gesture/layout behavior and independent-client exchange remain unverified. Capture/restore backup work remains separate.

References: [RFC 5870](https://www.rfc-editor.org/rfc/rfc5870),
[OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/),
[OpenStreetMap attribution](https://www.openstreetmap.org/copyright).
