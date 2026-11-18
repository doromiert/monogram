-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

-keep class org.webrtc.* { *; }
-keep class org.webrtc.audio.* { *; }
-keep class org.webrtc.voiceengine.* { *; }
-keep class org.telegram.messenger.* { *; }
-keep class org.telegram.messenger.camera.* { *; }
-keep class org.telegram.messenger.secretmedia.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.time.* { *; }
-keep class org.telegram.messenger.video.* { *; }
-keep class org.telegram.messenger.voip.* { *; }
-keep class org.telegram.SQLite.** { *; }
-keep class org.telegram.tgnet.ConnectionsManager { *; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.tgnet.RequestDelegate { *; }
-keep class com.negzero.monogram.xmpp.Omemo2Native** { *; }
-keepclassmembers class * implements com.negzero.monogram.xmpp.Omemo2Records {
    public byte[] load(int, java.lang.String, int);
    public void save(int, java.lang.String, int, byte[]);
}
-keep class org.telegram.ui.Stories.recorder.FfmpegAudioWaveformLoader { *; }
-keep class androidx.mediarouter.app.MediaRouteButton { *; }
-keepclassmembers class ** {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class org.telegram.utils.code.highlight.PrismaHighlighter {
    private static native <methods>;
}

# https://developers.google.com/ml-kit/known-issues#android_issues
-keep class com.google.mlkit.nl.languageid.internal.LanguageIdentificationJni { *; }

# Huawei Services
-keep class com.huawei.hianalytics.**{ *; }
-keep class com.huawei.updatesdk.**{ *; }
-keep class com.huawei.hms.**{ *; }

# Don't warn about checkerframework and Kotlin annotations
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

-keep class io.nano.tex.** {*;}

-keep class org.telegram.tgnet.** { *; }

# JLatexMath: macro/atom classes are loaded reflectively by Class.forName
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Use -keep to explicitly keep any other classes shrinking would remove
#-dontoptimize
#-dontobfuscate

# JNI bridge/callback names are part of the pinned native wallet ABI.
-keep class com.negzero.monogram.wallet.WalletNative { *; }
-keepclassmembers class * implements com.negzero.monogram.wallet.WalletNative$Progress { public void block(long, boolean); }

# Smack startup/provider XML uses original class names and public constructors.
-keep class org.jivesoftware.smack.** { *; }
-keepnames class org.jivesoftware.smackx.**
# Extension filters discover these public static fields through Class.getField().
-keepclassmembers class org.jivesoftware.smackx.** { public static <fields>; }
-keep,allowoptimization class * extends org.jivesoftware.smack.provider.Provider { public <init>(); }
-keep class org.jivesoftware.smackx.omemo.OmemoInitializer { *; }
# Managers listed by the pinned4.4.8 extensions/experimental/omemo startup XML.
-keep class org.jivesoftware.smackx.bytestreams.ibb.InBandBytestreamManager { *; }
-keep class org.jivesoftware.smackx.bytestreams.socks5.Socks5BytestreamManager { *; }
-keep class org.jivesoftware.smackx.caps.EntityCapsManager { *; }
-keep class org.jivesoftware.smackx.commands.AdHocCommandManager { *; }
-keep class org.jivesoftware.smackx.disco.ServiceDiscoveryManager { *; }
-keep class org.jivesoftware.smackx.eme.ExplicitMessageEncryptionManager { *; }
-keep class org.jivesoftware.smackx.filetransfer.FileTransferManager { *; }
-keep class org.jivesoftware.smackx.hoxt.HOXTManager { *; }
-keep class org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager { *; }
-keep class org.jivesoftware.smackx.iot.data.IoTDataManager { *; }
-keep class org.jivesoftware.smackx.iot.discovery.IoTDiscoveryManager { *; }
-keep class org.jivesoftware.smackx.iot.provisioning.IoTProvisioningManager { *; }
-keep class org.jivesoftware.smackx.iqlast.LastActivityManager { *; }
-keep class org.jivesoftware.smackx.iqversion.VersionManager { *; }
-keep class org.jivesoftware.smackx.mediaelement.MediaElementManager { *; }
-keep class org.jivesoftware.smackx.message_fastening.MessageFasteningManager { *; }
-keep class org.jivesoftware.smackx.muc.MultiUserChatManager { *; }
-keep class org.jivesoftware.smackx.muc.bookmarkautojoin.MucBookmarkAutojoinManager { *; }
-keep class org.jivesoftware.smackx.omemo.OmemoManager { *; }
-keep class org.jivesoftware.smackx.ping.PingManager { *; }
-keep class org.jivesoftware.smackx.privacy.PrivacyListManager { *; }
-keep class org.jivesoftware.smackx.receipts.DeliveryReceiptManager { *; }
-keep class org.jivesoftware.smackx.sid.StableUniqueStanzaIdManager { *; }
-keep class org.jivesoftware.smackx.softwareinfo.form.SoftwareInfoForm { *; }
-keep class org.jivesoftware.smackx.time.EntityTimeManager { *; }
-keep class org.jivesoftware.smackx.vcardtemp.VCardManager { *; }
-keep class org.jivesoftware.smackx.xdata.XDataManager { *; }
-keep class org.jivesoftware.smackx.xdatalayout.XDataLayoutManager { *; }
-keep class org.jivesoftware.smackx.xdatavalidation.XDataValidationManager { *; }
-keep class org.jivesoftware.smackx.xhtmlim.XHTMLManager { *; }
-keep class org.jivesoftware.smackx.xmlelement.DataFormsXmlElementManager { *; }
