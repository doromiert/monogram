// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.utils.settings;

/** Cached device appearance options. These never change XMPP account or message state. */
public final class MonogramAppearance {
    private MonogramAppearance() {}
    public static final BooleanSetting singleCornerRadius = BooleanSetting.of("appearance_single_radius", false);
    public static final BooleanSetting snow = BooleanSetting.of("appearance_snow", false);
    public static final BooleanSetting hideStories = BooleanSetting.of("appearance_hide_stories", false);
    public static final BooleanSetting senderAvatars = BooleanSetting.of("appearance_sender_avatars", true);
    public static final IntSetting title = IntSetting.of("appearance_title", 0);
    public static final IntSetting folderCounters = IntSetting.of("appearance_folder_counters", 0);
    // Preserve Monogram's existing default, but allow the user to reveal All Chats.
    public static final BooleanSetting hideAllChats = BooleanSetting.of("appearance_hide_all_chats", true);
    public static final BooleanSetting squareFab = BooleanSetting.of("appearance_square_fab", false);
    public static final BooleanSetting systemFonts = BooleanSetting.of("appearance_system_fonts", false);
    public static final BooleanSetting loading = BooleanSetting.of("appearance_material_loading", false);
    public static final BooleanSetting slider = BooleanSetting.of("appearance_material_slider", false);
    public static final BooleanSetting switches = BooleanSetting.of("appearance_material_switches", false);
    public static final BooleanSetting chatHeaderBackground = BooleanSetting.of("appearance_chat_header_background", true);
    public static final BooleanSetting chatHeader = BooleanSetting.of("appearance_material_chat_header", false);
    public static final BooleanSetting navigationStyle = BooleanSetting.of("appearance_material_navigation", false);
    public static final BooleanSetting fabStyle = BooleanSetting.of("appearance_material_fab", false);
    public static final BooleanSetting gooeyAvatar = BooleanSetting.of("appearance_gooey_avatar", true);
    public static final BooleanSetting customThemes = BooleanSetting.of("appearance_chat_themes", true);
    public static final IntSetting sectionRadius = IntSetting.of("appearance_section_radius", 20);
    public static final BooleanSetting separateHeaders = BooleanSetting.of("appearance_separate_headers", true);
    public static final IntSetting dividers = IntSetting.of("appearance_dividers", 1);
    public static final IntSetting glassOutline = IntSetting.of("appearance_glass_outline", 0);
    public static final BooleanSetting glassMenu = BooleanSetting.of("appearance_glass_menu", true);
    public static final BooleanSetting forceBlur = BooleanSetting.of("appearance_force_blur", false);
    public static final IntSetting tablet = IntSetting.of("appearance_tablet", 0);
    public static final IntSetting bottomNavigation = IntSetting.of("appearance_bottom_navigation", 2);
    public static final IntSetting transitions = IntSetting.of("appearance_transitions", 0);
    public static final FloatSetting predictiveBack = FloatSetting.of("appearance_predictive_back", 1f);
    public static final BooleanSetting drawer = BooleanSetting.of("appearance_drawer", false);
    public static final BooleanSetting immersiveDrawer = BooleanSetting.of("appearance_immersive_drawer", true);
    public static final StringSetting menuOrder = StringSetting.of("appearance_menu_order", "profile,archive,group,contacts,calls,saved,settings");
    public static final StringSetting menuHidden = StringSetting.of("appearance_menu_hidden", "");
    /** Segmented rows always put their section titles outside the row surfaces. */
    public static boolean separatedHeaders() { return dividers.get()==2 || separateHeaders.get(); }
    public static int bounded(IntSetting value, int max) { return Math.max(0, Math.min(max, value.get())); }
}
