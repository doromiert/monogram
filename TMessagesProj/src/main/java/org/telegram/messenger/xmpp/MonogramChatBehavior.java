// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import org.telegram.messenger.MessagesController;

/** exteraGram's native chat behavior, stored with this account's other chat settings. */
public final class MonogramChatBehavior {
    private MonogramChatBehavior() {}
    public static boolean hideKeyboardOnScroll(int account) { return get(account,"hide-keyboard-on-scroll"); }
    public static boolean commaAfterMention(int account) { return get(account,"comma-after-mention"); }
    public static boolean showSaveButton(int account) { return MessagesController.getMainSettings(account).getBoolean("monogram-extera-show-save",false); }
    public static boolean showRepeatButton(int account) { return MessagesController.getMainSettings(account).getBoolean("monogram-extera-show-repeat",false); }
    public static boolean swipeLoop(int account) { return MessagesController.getMainSettings(account).getBoolean("monogram-extera-swipe-loop",false); }
    public static boolean swipeReversed(int account) { return MessagesController.getMainSettings(account).getBoolean("monogram-extera-swipe-reversed",false); }
    public static void swipeLoop(int account,boolean enabled) { save(account,"swipe-loop",enabled); }
    public static void swipeReversed(int account,boolean enabled) { save(account,"swipe-reversed",enabled); }
    private static boolean get(int account,String key) { return MessagesController.getMainSettings(account).getBoolean("monogram-extera-"+key,true); }
    public static void hideKeyboardOnScroll(int account,boolean enabled) { save(account,"hide-keyboard-on-scroll",enabled); }
    public static void commaAfterMention(int account,boolean enabled) { save(account,"comma-after-mention",enabled); }
    public static void showSaveButton(int account,boolean enabled) { save(account,"show-save",enabled); }
    public static void showRepeatButton(int account,boolean enabled) { save(account,"show-repeat",enabled); }
    private static void save(int account,String key,boolean enabled) { MessagesController.getMainSettings(account).edit().putBoolean("monogram-extera-"+key,enabled).apply(); }
    public static String mentionSuffix(int account,boolean bot) { return !bot && commaAfterMention(account) ? ", " : " "; }
}
