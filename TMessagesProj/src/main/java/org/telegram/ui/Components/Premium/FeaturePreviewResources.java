// SPDX-License-Identifier: GPL-2.0-or-later
package org.telegram.ui.Components.Premium;

import android.text.SpannableStringBuilder;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.FilterCreateActivity;

/** Shared style IDs for native particle artwork and New badges. */
public final class FeaturePreviewResources {
    private FeaturePreviewResources() {}

    public final static int PREMIUM_FEATURE_ADS = 3;
    public final static int PREMIUM_FEATURE_REACTIONS = 4;
    public final static int PREMIUM_FEATURE_PROFILE_BADGE = 6;
    public final static int PREMIUM_FEATURE_ANIMATED_AVATARS = 7;
    public final static int PREMIUM_FEATURE_ADVANCED_CHAT_MANAGEMENT = 9;
    public final static int PREMIUM_FEATURE_ANIMATED_EMOJI = 11;
    public final static int PREMIUM_FEATURE_WALLPAPER = 22;
    public final static int PREMIUM_FEATURE_SAVED_TAGS = 24;
    public final static int PREMIUM_FEATURE_MESSAGE_PRIVACY = 27;
    public final static int PREMIUM_FEATURE_BUSINESS = 28;
    public final static int PREMIUM_FEATURE_RICH_EDITOR = 43;

    public static CharSequence applyNewSpan(String str) {
        return applyNewSpan(str, -1);
    }

    public static CharSequence applyNewSpan(String str, int fontSize) {
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder(str);
        spannableStringBuilder.append("  d");
        FilterCreateActivity.NewSpan span = new FilterCreateActivity.NewSpan(false, fontSize);
        span.setColor(Theme.getColor(Theme.key_premiumGradient1));
        spannableStringBuilder.setSpan(span, spannableStringBuilder.length() - 1, spannableStringBuilder.length(), 0);
        return spannableStringBuilder;
    }

    public static CharSequence applyNewSpanTimed(CharSequence str, int since) {
        if (ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime() - since > 4 * 7 * 24 * 60 * 60)
            return str;
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder(str);
        spannableStringBuilder.append("  d");
        FilterCreateActivity.NewSpan span = new FilterCreateActivity.NewSpan(false, -1);
        span.setColor(Theme.getColor(Theme.key_premiumGradient1));
        spannableStringBuilder.setSpan(span, spannableStringBuilder.length() - 1, spannableStringBuilder.length(), 0);
        return spannableStringBuilder;
    }

}
