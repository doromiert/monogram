# Monetization backend removal

## Google Pay / Wallet SDK

The Google Wallet SDK dependency
`com.google.android.gms:play-services-wallet:19.1.0` is removed from
TMessagesProj/build.gradle, along with the manifest's
`com.google.android.gms.wallet.api.enabled` opt-in metadata.

PaymentFormActivity no longer imports or calls any Google Wallet SDK API. This
pass deletes PaymentsClient creation, environment/options configuration,
readiness requests/callbacks, Google Pay button construction, provider-specific
Google Pay parameter parsing, card/network/tokenization request JSON,
loadPaymentData/AutoResolveHelper launches and activity-result token processing.
The now-unused decimal-price helper, request code and Wallet-only fields/imports
are removed as well. No replacement SDK, payment provider, compatibility shim or
hidden fallback was added.

Existing PaymentFormActivity constructors/delegates retain their generated
TL_inputPaymentCredentialsGooglePay parameter types so compiled stock source
callers remain compatible pending broader payment-class removal. The dormant
stock form still carries that generated protocol value between steps and can
assign an explicitly supplied value to a stock payment request; it cannot create
Google Wallet client requests, receive PaymentData or obtain a token through the
removed SDK path. This is a protocol/schema compatibility boundary, not a working
Google Pay integration. PaymentFormActivity.onFragmentCreate also centrally
refuses XMPP before registering observers or starting UI, covering direct internal
checkout callers. Existing XMPP purchase-route rejection remains in place;
this change does not unlock checkout or add any visible payment option.

## Stripe and native card tokenization

The `com.stripe:stripe-android:2.0.2` dependency and all direct Stripe SDK
imports/use in PaymentFormActivity are removed. The native Stripe/Card/Token
construction, validation, SDK token request and success/error processing are
removed with the provider public-key parsing.

The same old sendCardData method used Stripe's Card object to build SmartGlocal
HTTP tokenization requests. That external card-tokenization backend is removed
as part of the shared deletion, including its AsyncTask, provider URL override,
HTTP POST, response/error parsing and callback. There is no replacement card
model, SDK shim, alternate provider or automatic WebView fallback. If a dormant
native-card Send handler is reached, it resets its progress state and uses the
existing unsupported-purchases explanation. Stripe/SmartGlocal provider-name
classification remains solely to avoid reclassifying them as a WebView fallback.
Existing generated TL constructor/delegate signatures stay compatible.

No checks were run for this Stripe source checkpoint. The next parent release
gate must compile/package and inspect runtime dependencies to verify Stripe's
absence alongside the already removed Wallet dependency.

## Remaining boundaries

This is a bounded SDK deletion, not a claim that every monetary source class or
library has been removed. Google Wallet and Stripe SDK dependencies and their
native card tokenization backends have been removed. Google Play Billing 8.0.0 remains because BillingController and other stock code
still directly import billing client types, even though billing startup and
XMPP purchase paths are guarded by existing policy. Those type users must be
removed together before deleting their dependencies.

TON/Wallet and TonConnect Java/JNI classes and the bundled wallet-engine sources
remain compiled. This pass does not delete that engine, generated wallet TL
objects, session storage or payment/gift/Stars UI class families. Prior XMPP
entrypoint/link/notification guards and free native feature work are documented
in FREE_FEATURES.md; dependency absence alone does not certify all possible
stock purchase routes have been deleted. No unrelated Maps, Vision, ML Kit or
other Google SDK dependency is changed.

The Wallet removal passed parent compile/package and dependency inspection at
0.2.125, as recorded below. This latest Stripe deletion has not run tests, builds,
dependency-resolution tasks, phone operations or account/network actions.
The parent owns the next combined release gate. Constructors/delegates and native
nonpayment features must continue compiling without payment SDK shims.

## Billing client retirement boundary

The existing unavailable-build flag now prevents BillingController from even constructing a Google Play client, including calls made just to format currency. isReady stays false; product/purchase queries return explicit BILLING_UNAVAILABLE results with empty data. Result listeners are not retained, subscription management cannot launch the Play Store, late setup/purchase callbacks do not assign or consume purchases, and disconnect callbacks do not schedule reconnection. Optional completion callbacks still complete without claiming a purchase. Currency formatting helpers remain available to stock source.

This retires runtime billing in the unavailable build; it does not remove billing8's compiled API types or dependency from the numerous remaining stock payment classes. Two focused host cases pass at the 0.2.125 release gate: no client construction and explicit query/listener refusal; late purchase/setup/disconnect callbacks, completion handling and blocked subscription navigation. Nix arm64 packaging also passes, and runtime dependencyInsight reports no play-services-wallet dependency. Phone UI acceptance remains unverified.

## Billing manifest retirement

The native manifest now removes the Billing permission, both SDK ProxyBillingActivity registrations, billingclient version metadata and the two billing-service/test-service package queries during dependency merge. Its unused TON wallet query is removed too. The Billing8 artifact/API types remain temporarily for compiled stock references, but its payment components are not registered. Normal Maps package visibility remains. Release acceptance must inspect the packaged manifest, not merely these merger directives.

0.2.126 release gate passes: Nix arm64 packaging, two focused disabled-billing cases, no Stripe runtime dependency, absence of Stripe/Google Wallet class definitions in the APK, and inspection of the actual signed APK manifest confirming no Billing permission, proxy activities, version metadata or billing-service queries. Merger directives were corrected after artifact inspection caught retained queries. Billing8 API types and TON engine still compile; full removal is unfinished. No phone UI actions were performed.
