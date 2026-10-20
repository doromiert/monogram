// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import org.telegram.messenger.MessagesController;

/** Account-local controls, with the defaults from the supplied exteraGram APK. */
public final class MonogramMediaPlayback {
    private MonogramMediaPlayback() {}
    public static boolean pauseVideoOnMinimize(int account) { return get(account,"pause-video",true); }
    public static boolean pauseVoiceOnMinimize(int account) { return get(account,"pause-voice",false); }
    public static boolean pauseRoundOnMinimize(int account) { return get(account,"pause-round",false); }
    public static boolean unmuteWithVolumeButtons(int account) { return get(account,"volume-unmute",false); }
    public static void pauseVideoOnMinimize(int account,boolean enabled) { save(account,"pause-video",enabled); }
    public static void pauseVoiceOnMinimize(int account,boolean enabled) { save(account,"pause-voice",enabled); }
    public static void pauseRoundOnMinimize(int account,boolean enabled) { save(account,"pause-round",enabled); }
    public static void unmuteWithVolumeButtons(int account,boolean enabled) { save(account,"volume-unmute",enabled); }
    /** APK indices: 0=5s, 1=10s, 2=15s, 3=30s. Invalid persisted values recover to the 10s default. */
    public static int doubleTapSeekDuration(int account) {
        int value=MessagesController.getMainSettings(account).getInt("monogram-extera-media-seek-duration",1);
        return value>=0 && value<=3 ? value : 1;
    }
    public static int doubleTapSeekDurationMillis(int account) { int value=doubleTapSeekDuration(account);return value<3 ? (value+1)*5000 : 30000; }
    public static void doubleTapSeekDuration(int account,int value) {
        if(value<0 || value>3) throw new IllegalArgumentException("Unsupported seek duration");
        MessagesController.getMainSettings(account).edit().putInt("monogram-extera-media-seek-duration",value).apply();
    }
    public static boolean swipeToPip(int account) { return get(account,"swipe-to-pip",false); }
    public static void swipeToPip(int account,boolean enabled) { save(account,"swipe-to-pip",enabled); }
    private static boolean get(int account,String key,boolean fallback) { return MessagesController.getMainSettings(account).getBoolean("monogram-extera-media-"+key,fallback); }
    private static void save(int account,String key,boolean enabled) { MessagesController.getMainSettings(account).edit().putBoolean("monogram-extera-media-"+key,enabled).apply(); }
}
