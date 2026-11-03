// SPDX-License-Identifier: AGPL-3.0-only
// JVM interoperability fixture; never included in the APK.
package eu.siacs.conversations.utils; public final class CryptoHelper {
 public static String bytesToHex(byte[] value){StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
