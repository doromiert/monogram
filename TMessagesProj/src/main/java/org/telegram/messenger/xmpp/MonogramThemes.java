// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import androidx.core.graphics.ColorUtils;
import java.util.ArrayList;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.EmojiThemes;

/** Stable local themes for the native pickers; no Telegram theme service is required. */
public final class MonogramThemes {
    private MonogramThemes() {}
    public static ArrayList<TLRPC.TL_theme> catalog() {
        int[] seeds={0xff3b82d6,0xff358959,0xffbf507b,0xff835cc7,0xffbb712c,0xff258c99};
        String[] emojis={"🦋","🌿","🌸","💜","🔥","❄️"};
        String[] names={"Ocean","Forest","Blossom","Violet","Amber","Glacier"};
        ArrayList<TLRPC.TL_theme> themes=new ArrayList<>();
        for(int i=0;i<seeds.length;i++) {
            TLRPC.TL_theme theme=new TLRPC.TL_theme();theme.id=-7100-i;theme.slug="monogram_"+names[i].toLowerCase(java.util.Locale.ROOT);
            theme.title=names[i];theme.emoticon=emojis[i];theme.flags=8|64;theme.for_chat=true;
            for(int mode=0;mode<4;mode++) {
                boolean dark=mode>=2;int seed=seeds[i];
                TLRPC.TL_themeSettings settings=new TLRPC.TL_themeSettings();
                settings.base_theme=mode==0 ? new TLRPC.TL_baseThemeClassic() : mode==1 ? new TLRPC.TL_baseThemeDay()
                        : mode==2 ? new TLRPC.TL_baseThemeNight() : new TLRPC.TL_baseThemeTinted();
                settings.accent_color=dark ? ColorUtils.blendARGB(seed,0xffffffff,.3f) : seed;
                settings.outbox_accent_color=settings.accent_color;settings.flags=1|2|8;
                settings.message_colors.add(ColorUtils.blendARGB(dark ? 0xff14161b : 0xffffffff,seed,dark ? .65f : .2f));
                TLRPC.TL_wallPaperNoFile wallpaper=new TLRPC.TL_wallPaperNoFile();wallpaper.flags=4;wallpaper.settings=new TLRPC.TL_wallPaperSettings();
                wallpaper.settings.flags=1;wallpaper.settings.background_color=ColorUtils.blendARGB(dark ? 0xff14161b : 0xfff3f5f7,seed,dark ? .12f : .08f);
                settings.wallpaper=wallpaper;theme.settings.add(settings);
            }
            themes.add(theme);
        }
        return themes;
    }
    public static ArrayList<EmojiThemes> chats(int account) {
        ArrayList<EmojiThemes> result=new ArrayList<>();
        for(TLRPC.TL_theme theme:catalog()) {
            theme.settings.remove(3);theme.settings.remove(1);
            result.add(new EmojiThemes(account,theme,false));
        }
        return result;
    }
}
