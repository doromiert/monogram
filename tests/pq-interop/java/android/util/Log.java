// SPDX-License-Identifier: AGPL-3.0-only
// JVM interoperability fixture; never included in the APK.
package android.util;
public final class Log { public static int w(String tag,String message){return 0;} public static int e(String tag,String message,Throwable error){return 0;} }
