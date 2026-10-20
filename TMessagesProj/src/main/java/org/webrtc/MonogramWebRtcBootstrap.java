// SPDX-License-Identifier: GPL-3.0-or-later
package org.webrtc;

import android.content.Context;
import org.telegram.messenger.NativeLoader;

/** Share Telegram's engine without resetting field trials or loading a second WebRTC library. */
public final class MonogramWebRtcBootstrap {
    private static boolean initialized;

    private MonogramWebRtcBootstrap() {}

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        Context application = context.getApplicationContext();
        NativeLoader.initNativeLibs(application);
        if (!NativeLoader.loaded()) throw new IllegalStateException("Native media library unavailable");
        nativeInitialize();
        // nativeInitialize shares InitAndroid/JVM/SSL with NativeInstance. The bundled factory's
        // nativeInitializeAndroidGlobals only initializes that same JVM; initialize() would also
        // reset process-wide field trials, including those owned by Telegram calls.
        ContextUtils.initialize(application);
        NativeLibrary.initialize(name -> NativeLoader.loaded(), "tmessages.49");
        initialized = true;
    }

    private static native void nativeInitialize();
}
