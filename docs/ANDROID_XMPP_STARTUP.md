# Android XMPP parser startup

Smack4.4.8 discovers XML parsers through a ServiceLoader captured from the first
thread's context class loader. Android account restore workers can have a loader
that does not expose application SPI resources, even while the packaged provider
class and META-INF/services entries are present. Discovery then throws during
SmackInitialization and poisons ConnectionConfiguration initialization.

`MonogramSmack.initialize(context)` explicitly registers the pinned Xpp3 factory
before Android Smack setup or configuration use. Both normal account connection
and registration enter this synchronized helper; its monitor also establishes
visibility between concurrent account workers. It does not change identities,
credentials or account storage. The small bootstrap is kept across shrinking. Android dependency graphs exclude
`xpp3:xpp3` because Android supplies `org.xmlpull.v1`; bundling those framework
interfaces also prevents R8 from building a release APK. The Smack Xpp3 adapter
itself remains explicitly pinned and uses the platform parser.

The Robolectric regression reproduces raw discovery failure with a sterile
context loader and exercises real XML/configuration startup concurrently. The
[packaged device fixture](../tests/android/smack-smoke/README.md) repeats that
check from the installed APK without opening Monogram UI or accessing accounts.
It reproduced the failure on212 and passed the fixed bootstrap on213 on API37
arm64. Release shrinking is checked separately because debug deliveries do not
run R8.

Upstream references: [SmackXmlParser4.4.8](https://github.com/igniterealtime/Smack/blob/4.4.8/smack-xmlparser/src/main/java/org/jivesoftware/smack/xml/SmackXmlParser.java),
[AndroidSmackInitializer4.4.8](https://github.com/igniterealtime/Smack/blob/4.4.8/smack-android/src/main/java/org/jivesoftware/smack/android/AndroidSmackInitializer.java).
