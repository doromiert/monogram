// SPDX-License-Identifier: AGPL-3.0-only
// JVM interoperability fixture; never included in the APK.
package android.util;
public final class Base64 {
 public static final int DEFAULT=0, NO_WRAP=2;
 public static byte[] decode(String value,int flags){return java.util.Base64.getDecoder().decode(value.replaceAll("[\\t\\r\\n ]",""));}
 public static String encodeToString(byte[] value,int flags){return java.util.Base64.getEncoder().encodeToString(value);}
}
