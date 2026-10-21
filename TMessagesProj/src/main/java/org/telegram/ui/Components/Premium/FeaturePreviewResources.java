// SPDX-License-Identifier: GPL-2.0-or-later
package org.telegram.ui.Components.Premium;

import static org.telegram.messenger.LocaleController.getString;
import android.text.SpannableStringBuilder;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.Theme;
import java.util.ArrayList;
import java.util.Collections;
import org.telegram.ui.FilterCreateActivity;

/** Shared metadata and badges for the remaining native feature illustrations. No storefront or purchase navigation. */
public final class FeaturePreviewResources {
    private FeaturePreviewResources() {}

    public final static int PREMIUM_FEATURE_UPLOAD_LIMIT = 1;
    public final static int PREMIUM_FEATURE_DOWNLOAD_SPEED = 2;
    public final static int PREMIUM_FEATURE_ADS = 3;
    public final static int PREMIUM_FEATURE_REACTIONS = 4;
    public final static int PREMIUM_FEATURE_STICKERS = 5;
    public final static int PREMIUM_FEATURE_PROFILE_BADGE = 6;
    public final static int PREMIUM_FEATURE_ANIMATED_AVATARS = 7;
    public final static int PREMIUM_FEATURE_VOICE_TO_TEXT = 8;
    public final static int PREMIUM_FEATURE_ADVANCED_CHAT_MANAGEMENT = 9;
    public final static int PREMIUM_FEATURE_APPLICATION_ICONS = 10;
    public final static int PREMIUM_FEATURE_ANIMATED_EMOJI = 11;
    public final static int PREMIUM_FEATURE_EMOJI_STATUS = 12;
    public final static int PREMIUM_FEATURE_TRANSLATIONS = 13;
    public final static int PREMIUM_FEATURE_STORIES = 14;
    public final static int PREMIUM_FEATURE_STORIES_STEALTH_MODE = 15;
    public final static int PREMIUM_FEATURE_STORIES_VIEWS_HISTORY = 16;
    public final static int PREMIUM_FEATURE_STORIES_EXPIRATION_DURATION = 17;
    public final static int PREMIUM_FEATURE_STORIES_SAVE_TO_GALLERY = 18;
    public final static int PREMIUM_FEATURE_STORIES_LINKS_AND_FORMATTING = 19;
    public final static int PREMIUM_FEATURE_STORIES_PRIORITY_ORDER = 20;
    public final static int PREMIUM_FEATURE_STORIES_CAPTION = 21;
    public final static int PREMIUM_FEATURE_WALLPAPER = 22;
    public final static int PREMIUM_FEATURE_NAME_COLOR = 23;
    public final static int PREMIUM_FEATURE_SAVED_TAGS = 24;
    public final static int PREMIUM_FEATURE_STORIES_QUALITY = 25;
    public final static int PREMIUM_FEATURE_LAST_SEEN = 26;
    public final static int PREMIUM_FEATURE_MESSAGE_PRIVACY = 27;
    public final static int PREMIUM_FEATURE_BUSINESS = 28;
    public final static int PREMIUM_FEATURE_BUSINESS_LOCATION = 29;
    public final static int PREMIUM_FEATURE_BUSINESS_OPENING_HOURS = 30;
    public final static int PREMIUM_FEATURE_BUSINESS_QUICK_REPLIES = 31;
    public final static int PREMIUM_FEATURE_BUSINESS_GREETING_MESSAGES = 32;
    public final static int PREMIUM_FEATURE_BUSINESS_AWAY_MESSAGES = 33;
    public final static int PREMIUM_FEATURE_BUSINESS_CHATBOTS = 34;
    public final static int PREMIUM_FEATURE_FOLDER_TAGS = 35;
    public final static int PREMIUM_FEATURE_BUSINESS_INTRO = 36;
    public final static int PREMIUM_FEATURE_BUSINESS_CHAT_LINKS = 37;
    public final static int PREMIUM_FEATURE_MESSAGE_EFFECTS = 38;
    public final static int PREMIUM_FEATURE_TODO = 39;
    public final static int FEATURE_GIFTS = 40;
    public final static int PREMIUM_FEATURE_SHARING_DISABLE = 41;
    public final static int PREMIUM_FEATURE_RICH_EDITOR = 43;

    public static int serverStringToFeatureType(String s) {
        switch (s) {
            case "more_upload":
                return PREMIUM_FEATURE_UPLOAD_LIMIT;
            case "faster_download":
                return PREMIUM_FEATURE_DOWNLOAD_SPEED;
            case "voice_to_text":
                return PREMIUM_FEATURE_VOICE_TO_TEXT;
            case "no_ads":
                return PREMIUM_FEATURE_ADS;
            case "infinite_reactions":
                return PREMIUM_FEATURE_REACTIONS;
            case "premium_stickers":
                return PREMIUM_FEATURE_STICKERS;
            case "advanced_chat_management":
                return PREMIUM_FEATURE_ADVANCED_CHAT_MANAGEMENT;
            case "profile_badge":
                return PREMIUM_FEATURE_PROFILE_BADGE;
            case "animated_userpics":
                return PREMIUM_FEATURE_ANIMATED_AVATARS;
            case "app_icons":
                return PREMIUM_FEATURE_APPLICATION_ICONS;
            case "animated_emoji":
                return PREMIUM_FEATURE_ANIMATED_EMOJI;
            case "emoji_status":
                return PREMIUM_FEATURE_EMOJI_STATUS;
            case "translations":
                return PREMIUM_FEATURE_TRANSLATIONS;
            case "effects":
                return PREMIUM_FEATURE_MESSAGE_EFFECTS;
            case "todo":
                return PREMIUM_FEATURE_TODO;
            case "gifts":
                return FEATURE_GIFTS;

            case "stories":
                return PREMIUM_FEATURE_STORIES;
            case "stories__stealth_mode":
                return PREMIUM_FEATURE_STORIES_STEALTH_MODE;
            case "stories__quality":
                return PREMIUM_FEATURE_STORIES_QUALITY;
            case "stories__permanent_views_history":
                return PREMIUM_FEATURE_STORIES_VIEWS_HISTORY;
            case "stories__expiration_durations":
                return PREMIUM_FEATURE_STORIES_EXPIRATION_DURATION;
            case "stories__save_stories_to_gallery":
                return PREMIUM_FEATURE_STORIES_SAVE_TO_GALLERY;
            case "stories__links_and_formatting":
                return PREMIUM_FEATURE_STORIES_LINKS_AND_FORMATTING;
            case "stories__priority_order":
                return PREMIUM_FEATURE_STORIES_PRIORITY_ORDER;
            case "stories__caption":
                return PREMIUM_FEATURE_STORIES_CAPTION;

            case "wallpapers":
                return PREMIUM_FEATURE_WALLPAPER;
            case "peer_colors":
                return PREMIUM_FEATURE_NAME_COLOR;
            case "saved_tags":
                return PREMIUM_FEATURE_SAVED_TAGS;
            case "last_seen":
                return PREMIUM_FEATURE_LAST_SEEN;
            case "message_privacy":
                return PREMIUM_FEATURE_MESSAGE_PRIVACY;
            case "folder_tags":
                return PREMIUM_FEATURE_FOLDER_TAGS;
            case "pm_noforwards":
                return PREMIUM_FEATURE_SHARING_DISABLE;
            case "rich_formatting":
                return PREMIUM_FEATURE_RICH_EDITOR;

            case "business":
                return PREMIUM_FEATURE_BUSINESS;
            case "greeting_message":
                return PREMIUM_FEATURE_BUSINESS_GREETING_MESSAGES;
            case "away_message":
                return PREMIUM_FEATURE_BUSINESS_AWAY_MESSAGES;
            case "quick_replies":
                return PREMIUM_FEATURE_BUSINESS_QUICK_REPLIES;
            case "business_bots":
                return PREMIUM_FEATURE_BUSINESS_CHATBOTS;
            case "business_intro":
                return PREMIUM_FEATURE_BUSINESS_INTRO;
            case "business_links":
                return PREMIUM_FEATURE_BUSINESS_CHAT_LINKS;
            case "business_hours":
                return PREMIUM_FEATURE_BUSINESS_OPENING_HOURS;
            case "business_location":
                return PREMIUM_FEATURE_BUSINESS_LOCATION;
        }
        return -1;
    }

    public static String featureTypeToServerString(int type) {
        switch (type) {
            case PREMIUM_FEATURE_UPLOAD_LIMIT:
                return "more_upload";
            case PREMIUM_FEATURE_DOWNLOAD_SPEED:
                return "faster_download";
            case PREMIUM_FEATURE_VOICE_TO_TEXT:
                return "voice_to_text";
            case PREMIUM_FEATURE_ADS:
                return "no_ads";
            case PREMIUM_FEATURE_REACTIONS:
                return "infinite_reactions";
            case PREMIUM_FEATURE_ANIMATED_EMOJI:
                return "animated_emoji";
            case PREMIUM_FEATURE_STICKERS:
                return "premium_stickers";
            case PREMIUM_FEATURE_ADVANCED_CHAT_MANAGEMENT:
                return "advanced_chat_management";
            case PREMIUM_FEATURE_PROFILE_BADGE:
                return "profile_badge";
            case PREMIUM_FEATURE_ANIMATED_AVATARS:
                return "animated_userpics";
            case PREMIUM_FEATURE_APPLICATION_ICONS:
                return "app_icons";
            case PREMIUM_FEATURE_EMOJI_STATUS:
                return "emoji_status";
            case PREMIUM_FEATURE_TRANSLATIONS:
                return "translations";
            case PREMIUM_FEATURE_MESSAGE_EFFECTS:
                return "effects";
            case PREMIUM_FEATURE_TODO:
                return "todo";
            case FEATURE_GIFTS:
                return "gifts";
            case PREMIUM_FEATURE_STORIES:
                return "stories";
            case PREMIUM_FEATURE_STORIES_STEALTH_MODE:
                return "stories__stealth_mode";
            case PREMIUM_FEATURE_STORIES_QUALITY:
                return "stories__quality";
            case PREMIUM_FEATURE_STORIES_VIEWS_HISTORY:
                return "stories__permanent_views_history";
            case PREMIUM_FEATURE_STORIES_EXPIRATION_DURATION:
                return "stories__expiration_durations";
            case PREMIUM_FEATURE_STORIES_SAVE_TO_GALLERY:
                return "stories__save_stories_to_gallery";
            case PREMIUM_FEATURE_STORIES_LINKS_AND_FORMATTING:
                return "stories__links_and_formatting";
            case PREMIUM_FEATURE_STORIES_PRIORITY_ORDER:
                return "stories__priority_order";
            case PREMIUM_FEATURE_STORIES_CAPTION:
                return "stories__caption";
            case PREMIUM_FEATURE_WALLPAPER:
                return "wallpapers";
            case PREMIUM_FEATURE_NAME_COLOR:
                return "peer_colors";
            case PREMIUM_FEATURE_SAVED_TAGS:
                return "saved_tags";
            case PREMIUM_FEATURE_LAST_SEEN:
                return "last_seen";
            case PREMIUM_FEATURE_MESSAGE_PRIVACY:
                return "message_privacy";
            case PREMIUM_FEATURE_FOLDER_TAGS:
                return "folder_tags";
            case PREMIUM_FEATURE_SHARING_DISABLE:
                return "pm_noforwards";
            case PREMIUM_FEATURE_RICH_EDITOR:
                return "rich_formatting";

            case PREMIUM_FEATURE_BUSINESS:
                return "business";
            case PREMIUM_FEATURE_BUSINESS_GREETING_MESSAGES:
                return "greeting_message";
            case PREMIUM_FEATURE_BUSINESS_AWAY_MESSAGES:
                return "away_message";
            case PREMIUM_FEATURE_BUSINESS_QUICK_REPLIES:
                return "quick_replies";
            case PREMIUM_FEATURE_BUSINESS_CHATBOTS:
                return "business_bots";
            case PREMIUM_FEATURE_BUSINESS_INTRO:
                return "business_intro";
            case PREMIUM_FEATURE_BUSINESS_CHAT_LINKS:
                return "business_links";
            case PREMIUM_FEATURE_BUSINESS_OPENING_HOURS:
                return "business_hours";
            case PREMIUM_FEATURE_BUSINESS_LOCATION:
                return "business_location";
        }
        return null;
    }

    public static void fillPremiumFeaturesList(ArrayList<PremiumFeatureData> premiumFeatures, int currentAccount, boolean all) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);

        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_STORIES, R.drawable.msg_filled_stories, getString(R.string.PremiumPreviewStories), LocaleController.formatString(R.string.PremiumPreviewStoriesDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_UPLOAD_LIMIT, R.drawable.msg_premium_uploads, getString(R.string.PremiumPreviewUploads), getString(R.string.PremiumPreviewUploadsDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_DOWNLOAD_SPEED, R.drawable.msg_premium_speed, getString(R.string.PremiumPreviewDownloadSpeed), getString(R.string.PremiumPreviewDownloadSpeedDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_VOICE_TO_TEXT, R.drawable.msg_premium_voice, getString(R.string.PremiumPreviewVoiceToText), getString(R.string.PremiumPreviewVoiceToTextDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_ADS, R.drawable.msg_premium_ads, getString(R.string.PremiumPreviewNoAds), getString(R.string.PremiumPreviewNoAdsDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_REACTIONS, R.drawable.msg_premium_reactions, getString(R.string.PremiumPreviewReactions2), getString(R.string.PremiumPreviewReactions2Description)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_STICKERS, R.drawable.msg_premium_stickers, getString(R.string.PremiumPreviewStickers), getString(R.string.PremiumPreviewStickersDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_ANIMATED_EMOJI, R.drawable.msg_premium_emoji, getString(R.string.PremiumPreviewEmoji), getString(R.string.PremiumPreviewEmojiDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_ADVANCED_CHAT_MANAGEMENT, R.drawable.menu_premium_tools, getString(R.string.PremiumPreviewAdvancedChatManagement), getString(R.string.PremiumPreviewAdvancedChatManagementDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_PROFILE_BADGE, R.drawable.msg_premium_badge, getString(R.string.PremiumPreviewProfileBadge), getString(R.string.PremiumPreviewProfileBadgeDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_MESSAGE_PRIVACY, R.drawable.filled_messages_paid, getString(R.string.PremiumPreviewPaidMessages), getString(R.string.PremiumPreviewPaidMessagesDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_ANIMATED_AVATARS, R.drawable.msg_premium_avatar, getString(R.string.PremiumPreviewAnimatedProfiles), getString(R.string.PremiumPreviewAnimatedProfilesDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_SAVED_TAGS, R.drawable.premium_tags, getString(R.string.PremiumPreviewTags2), getString(R.string.PremiumPreviewTagsDescription2)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_APPLICATION_ICONS, R.drawable.msg_premium_icons, getString(R.string.PremiumPreviewAppIcon), getString(R.string.PremiumPreviewAppIconDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_EMOJI_STATUS, R.drawable.premium_status, getString(R.string.PremiumPreviewEmojiStatus), getString(R.string.PremiumPreviewEmojiStatusDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_TRANSLATIONS, R.drawable.msg_premium_translate, getString(R.string.PremiumPreviewTranslations), getString(R.string.PremiumPreviewTranslationsDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_WALLPAPER, R.drawable.premium_wallpaper, getString(R.string.PremiumPreviewWallpaper), getString(R.string.PremiumPreviewWallpaperDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_NAME_COLOR, R.drawable.premium_colors, getString(R.string.PremiumPreviewProfileColor), getString(R.string.PremiumPreviewProfileColorDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_LAST_SEEN, R.drawable.menu_premium_seen, getString(R.string.PremiumPreviewLastSeen), getString(R.string.PremiumPreviewLastSeenDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_BUSINESS, R.drawable.filled_premium_business, getString(R.string.TelegramBusiness), getString(R.string.PremiumPreviewBusinessDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_MESSAGE_EFFECTS, R.drawable.menu_premium_effects, getString(R.string.PremiumPreviewEffects), getString(R.string.PremiumPreviewEffectsDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_TODO, R.drawable.msg_premium_icons, getString(R.string.PremiumPreviewTodo), getString(R.string.PremiumPreviewTodoDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_SHARING_DISABLE, R.drawable.filled_sharing_off2_24, getString(R.string.PremiumPreviewSharingDisable), getString(R.string.PremiumPreviewSharingDisableDescription)));
        premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_RICH_EDITOR, R.drawable.premium_rich_editor, getString(R.string.PremiumPreviewRichEditor), getString(R.string.PremiumPreviewRichEditorDescription)));

        if (messagesController.premiumFeaturesTypesToPosition.size() > 0) {
            for (int i = 0; i < premiumFeatures.size(); i++) {
                if (messagesController.premiumFeaturesTypesToPosition.get(premiumFeatures.get(i).type, -1) == -1 && !BuildVars.DEBUG_VERSION) {
                    premiumFeatures.remove(i);
                    i--;
                }
            }
        }

        Collections.sort(premiumFeatures, (o1, o2) -> {
            int type1 = messagesController.premiumFeaturesTypesToPosition.get(o1.type, Integer.MAX_VALUE);
            int type2 = messagesController.premiumFeaturesTypesToPosition.get(o2.type, Integer.MAX_VALUE);
            return type1 - type2;
        });
    }

    public static void fillBusinessFeaturesList(ArrayList<PremiumFeatureData> premiumFeatures, int currentAccount, boolean additional) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);

        if (!additional) {
            premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_BUSINESS_LOCATION, R.drawable.filled_location, getString(R.string.PremiumBusinessLocation), getString(R.string.PremiumBusinessLocationDescription)));
            premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_BUSINESS_OPENING_HOURS, R.drawable.filled_premium_hours, getString(R.string.PremiumBusinessOpeningHours), getString(R.string.PremiumBusinessOpeningHoursDescription)));
            premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_BUSINESS_QUICK_REPLIES, R.drawable.filled_open_message, getString(R.string.PremiumBusinessQuickReplies), getString(R.string.PremiumBusinessQuickRepliesDescription)));
            premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_BUSINESS_GREETING_MESSAGES, R.drawable.premium_status, getString(R.string.PremiumBusinessGreetingMessages), getString(R.string.PremiumBusinessGreetingMessagesDescription)));
            premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_BUSINESS_AWAY_MESSAGES, R.drawable.filled_premium_away, getString(R.string.PremiumBusinessAwayMessages), getString(R.string.PremiumBusinessAwayMessagesDescription)));
            premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_BUSINESS_INTRO, R.drawable.filled_premium_intro, getString(R.string.PremiumBusinessIntro), getString(R.string.PremiumBusinessIntroDescription)));
        } else {
            premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_EMOJI_STATUS, R.drawable.filled_premium_status2, getString(R.string.PremiumPreviewBusinessEmojiStatus), getString(R.string.PremiumPreviewBusinessEmojiStatusDescription)));
            premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_FOLDER_TAGS, R.drawable.premium_tags, getString(R.string.PremiumPreviewFolderTags), getString(R.string.PremiumPreviewFolderTagsDescription)));
            premiumFeatures.add(new PremiumFeatureData(PREMIUM_FEATURE_STORIES, R.drawable.filled_premium_camera, getString(R.string.PremiumPreviewBusinessStories), getString(R.string.PremiumPreviewBusinessStoriesDescription)));
        }

        if (messagesController.businessFeaturesTypesToPosition.size() > 0) {
            for (int i = 0; i < premiumFeatures.size(); i++) {
                if (messagesController.businessFeaturesTypesToPosition.get(premiumFeatures.get(i).type, -1) == -1 && !BuildVars.DEBUG_VERSION) {
                    premiumFeatures.remove(i);
                    i--;
                }
            }
        }

        Collections.sort(premiumFeatures, (o1, o2) -> {
            int type1 = messagesController.businessFeaturesTypesToPosition.get(o1.type, Integer.MAX_VALUE);
            int type2 = messagesController.businessFeaturesTypesToPosition.get(o2.type, Integer.MAX_VALUE);
            return type1 - type2;
        });
    }

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

    public static class PremiumFeatureData {
        public final int type;
        public final int icon;
        public final CharSequence title;
        public final String description;
        public int yOffset;

        public PremiumFeatureData(int type, int icon, CharSequence title, String description) {
            this.type = type;
            this.icon = icon;
            this.title = title;
            this.description = description;
        }
    }
}
