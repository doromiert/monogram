// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.sync;
/** TLS/at-rest encryption and framing are identical on Android and desktop. All calls run off the UI thread. */
public final class NativeMesh {
 static {System.loadLibrary("monogram_sync");}
 private NativeMesh(){}
 public static native long start(String root,byte[] key,String name,String ips);
 public static native String inspect(long handle,String query);
 public static native String numeric(long handle);
 public static native String joinNumeric(long handle,String peer,String code);
 public static native String invite(long handle);
 public static native String join(long handle,String code);
 public static native String put(long handle,String kind,String owner,String id,String path);
 public static native String putJson(long handle,String kind,String owner,String id,String json);
 public static native String read(long handle,String key);
 public static native boolean applied(long handle,String key,String digest);
 public static native String signature(long handle,String key,String value);
 public static native boolean forget(long handle,String peer);
 public static native boolean cancelInvite(long handle);
 public static native boolean stop(long handle);
 public static native boolean account(long handle,String owner,boolean allow);
 public static native boolean validate(String kind,String owner,String id,String json);
}
