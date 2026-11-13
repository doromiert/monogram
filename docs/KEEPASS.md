# KeePassDX sign-in

Enable KeePassDX as Android’s preferred Autofill service through KeePassDX’s
**Settings → Form filling**. On Monogram’s sign-in form, select the username or
password suggestion, or tap **Password manager** to request Autofill explicitly.
When no provider is enabled, that action explains setup and opens Android’s
credential-provider settings (Android 14+) or Android settings (older versions).
Android 6/7 can use KeePassDX’s Magikeyboard.

Use your full XMPP address as the entry’s username. KeePassDX can remember the
Android app association: `com.negzero.monogram` or the debug package
`com.negzero.monogram.beta`. Both username and password fields expose stable
resource IDs and native text Autofill values. Registration uses new-username and
new-password hints. Autofill does not submit the form automatically.

After successful authentication, Monogram commits the Autofill session so the
provider can offer to save/update credentials. Leaving an unsuccessful sign-in
cancels the session and clears the password. Only sign-in fields opt in; chat,
wallet and unrelated rich editors retain their Autofill exclusions. Existing
credential caching and OMEMO identities are unchanged.

Verified with Android SDK 36 / Robolectric 4.16.1: credentials accept provider
values; unrelated rich editors remain excluded. The fresh 0.2.203 arm64 APK uses
the previous signing certificate and identical native libraries. Real KeePassDX
provider prompts need acceptance on a phone configured with KeePassDX.

References: [KeePassDX Autofill](https://github.com/Kunzisoft/KeePassDX/wiki/AutoFill),
[Android Autofill guidance](https://developer.android.com/identity/autofill/autofill-optimize).
