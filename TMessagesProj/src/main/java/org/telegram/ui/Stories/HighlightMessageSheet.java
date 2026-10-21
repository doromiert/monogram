package org.telegram.ui.Stories;



import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.Arrays;
import java.util.stream.Collectors;

public class HighlightMessageSheet {

    public static int TIER_PERIOD = 0;
    public static int TIER_LENGTH = 1;
    public static int TIER_EMOJIS = 2;
    public static int TIER_COLOR1 = 3;
    public static int TIER_COLOR2 = 4;
    public static int TIER_COLOR_BACKGROUND = 5;

    public static int[] getDefaultTiers() {
        return new int[] {
            /* stars */ /* period */ /* length */ /* emojis */ /* colors */            /* background color */
            10_000,     3600,        400,         20,          0xFF5B6676, 0xFF7B899D, 0xFF252C36,
            2_000,      1800,        280,         10,          0xFFE14741, 0xFFE96139, 0xFF8B0503,
            500,        900,         200,         7,           0xFFED771E, 0xFFED771E, 0xFF9B3100,
            250,        600,         150,         4,           0xFFE29A09, 0xFFE29A09, 0xFF9A3E00,
            100,        300,         110,         3,           0xFF40A920, 0xFF40A920, 0xFF176200,
            50,         120,         80,          2,           0xFF46A3EB, 0xFF46A3EB, 0xFF00508E,
            10,         60,          60,          1,           0xFF955CDB, 0xFF955CDB, 0xFF49079B,
            0,          30,          30,          0,           0xFF955CDB, 0xFF955CDB, 0xFF49079B
        };
    }

    public static int[] parseTiers(TLRPC.TL_jsonArray arr) {
        final int[] tiers = new int[arr.value.size() * 7];
        for (int i = 0; i < arr.value.size(); ++i) {
            final TLRPC.JSONValue value = arr.value.get(i);
            if (!(value instanceof TLRPC.TL_jsonObject)) continue;
            final TLRPC.TL_jsonObject obj = (TLRPC.TL_jsonObject) value;
            for (TLRPC.TL_jsonObjectValue kv : obj.value) {
                if (kv.value instanceof TLRPC.TL_jsonNumber) {
                    final int num = (int) ((TLRPC.TL_jsonNumber) kv.value).value;
                    int option = -1;
                    switch (kv.key) {
                        case "stars":           option = 0; break;
                        case "pin_period":      option = 1; break;
                        case "text_length_max": option = 2; break;
                        case "emoji_max":       option = 3; break;
                    }
                    if (option >= 0) {
                        tiers[i * 7 + option] = num;
                    }
                } else if (kv.value instanceof TLRPC.TL_jsonString) {
                    final String str = ((TLRPC.TL_jsonString) kv.value).value;
                    int option = -1;
                    switch (kv.key) {
                        case "color1":   option = 4; break;
                        case "color2":   option = 5; break;
                        case "color_bg": option = 6; break;
                    }
                    if (option >= 0) {
                        try {
                            int color = (int) Long.parseLong("FF" + str, 16);
                            tiers[i * 7 + option] = color;
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                }
            }
        }
        return tiers;
    }

    public static boolean tiersEqual(int[] a, int[] b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; ++i) {
            if (a[i] != b[i])
                return false;
        }
        return true;
    }

    public static int[] parseTiersString(String str) {
        if (str == null || str.length() == 0)
            return getDefaultTiers();
        try {
            return Arrays.stream(str.split(","))
                .mapToInt(Integer::parseInt)
                .toArray();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return getDefaultTiers();
    }

    public static String tiersToString(int[] tiers) {
        return Arrays.stream(tiers)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
    }

    public static int getTierOption(int currentAccount, int stars, int option) {
        final int[] tiers = MessagesController.getInstance(currentAccount).starsGroupcallMessageLimits;
        for (int i = 0; i < tiers.length / 7; ++i) {
            final int tierStars = tiers[i * 7];
            if (stars >= tierStars)
                return tiers[i * 7 + 1 + option];
        }
        return 0;
    }

    public static int getMaxLength(int currentAccount) {
        final int[] tiers = MessagesController.getInstance(currentAccount).starsGroupcallMessageLimits;
        if (tiers == null || tiers.length <= 1 + TIER_LENGTH) {
            return 400;
        }
        return tiers[1 + TIER_LENGTH];
    }

}
