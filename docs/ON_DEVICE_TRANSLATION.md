# On-device translation evaluation

Evaluated 2026-10-20. This completes the investigation item, not an offline
translation implementation or device-quality claim.

## Repository integration

Monogram currently offers LibreTranslate, Google Cloud Translation and DeepL in
`MonogramTranslationSettingsActivity`, with requests in `MonogramTranslation`.
Those providers send selected plaintext to a service. Native translation UI and
language identification already exist. `TMessagesProj/build.gradle` includes
`com.google.mlkit:language-id:17.0.6`; its translation dependency is commented out.
Both Android modules target a minimum API of 23.

## Options

ML Kit is the practical first candidate. Its translation engine runs locally,
without sending message text to a translation server. Quality depends on the
language pair; non-English pairs pass through English. This suits a candidate
for everyday chat, but quality still needs evaluation on actual conversations.
[Google's translation overview](https://developers.google.com/ml-kit/language/translation)
describes these limits. The supported list includes Polish, Romanian and English;
source and destination choices must be restricted to the SDK's supported codes.
[Official language coverage](https://developers.google.com/ml-kit/language/translation/translation-language-support).

The Android SDK supports API 23+, matching this app. Google documents translation
models at roughly 30 MB each, with download, enumeration and deletion APIs.
Models must be present before translation; Wi-Fi downloads are recommended.
Translator instances need closing when unused. These are SDK model downloads,
not Cloud Translation requests or existing Google API-key settings.
[Android integration and model management](https://developers.google.com/ml-kit/language/translation/android).

Android's system `TranslationManager` is an alternative on API 31+. Capabilities
must be queried off the UI thread, translator creation may return null, and an
OEM may provide no translation service. It cannot cover the app's API 23–30
devices. Inference: it is useful as an optional detected provider, but cannot be
the sole offline implementation for this project.
[Android API reference](https://developer.android.com/reference/android/view/translation/TranslationManager).

## Proposed native provider flow

Add an explicit **On device** choice alongside the existing providers. Reuse the
native translation controls and existing language detector. Keep provider choice
per account; expose installed language models as device-wide resources. Show
download/ready/failure state, deletion controls and storage estimates before
downloading. Default to Wi-Fi and require an explicit choice to use mobile data.
Do not download every language or start model work at app startup.

An offline-provider failure must stay local: never silently fall back to a cloud
provider with decrypted chat text. Account switches and cancelled message/chat
translation must discard stale results. Retain the existing system-language-only
automatic exclusion policy.

Before enabling the dependency, review packaging/reproducibility and Google's
required translation attribution. The SDK has specific attribution guidelines;
this evaluation does not claim completed distribution review.
[ML Kit usage guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms).

No model, dependency or device setting was changed for this evaluation. A future
implementation still needs native model-management UI and update-gate coverage
for airplane mode, unavailable/evicted models, cancellation, storage limits,
account changes and translation quality. It must also verify actual operation
on builds/devices without Google Play services instead of assuming support.
