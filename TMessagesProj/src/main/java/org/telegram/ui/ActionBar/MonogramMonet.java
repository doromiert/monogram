// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.ActionBar;

import android.os.Build;
import android.graphics.Color;
import androidx.core.graphics.ColorUtils;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.telegram.messenger.ApplicationLoader;

/** Resolves the palette tokens in Exteragram's bundled Monet templates against Android 12+ colors. */
public final class MonogramMonet {
    private static final Pattern TOKEN=Pattern.compile("([an])([123])_(10|50|[1-9]00)");
    private static final Pattern MODIFIER=Pattern.compile("([al])=(\\d{1,3})");
    private static long palette;
    private MonogramMonet() {}
    public static Integer color(String value) {
        if(Build.VERSION.SDK_INT<31) return null;
        return resolve(value,name -> {
            android.content.res.Resources resources=ApplicationLoader.applicationContext.getResources();
            int id=resources.getIdentifier(name,"color","android");
            if(id==0) throw new IllegalArgumentException("Missing system palette color");
            return resources.getColor(id,null);
        });
    }
    static Integer resolve(String value,ToIntFunction<String> colors) {
        String token=value.trim(),modifier=null;
        int opening=token.indexOf('(');
        if(opening>=0) {
            if(!token.endsWith(")")) return null;
            modifier=token.substring(opening+1,token.length()-1);token=token.substring(0,opening).trim();
        }
        int color;
        switch(token) {
            case "mBlack":color=Color.BLACK;break;
            case "mWhite":color=Color.WHITE;break;
            case "mRed200":color=0xffef9a9a;break;
            case "mRed500":color=0xfff44336;break;
            case "mRed800":color=0xffc62828;break;
            case "mGreen200":color=0xffa5d6a7;break;
            case "mGreen500":color=0xff4caf50;break;
            case "mGreen800":color=0xff2e7d32;break;
            default:
                Matcher match=TOKEN.matcher(token);
                if(!match.matches() || match.group(1).equals("n") && match.group(2).equals("3")) return null;
                color=colors.applyAsInt("system_"+(match.group(1).equals("a") ? "accent" : "neutral")+match.group(2)+"_"+match.group(3));
        }
        if(modifier!=null) {
            Matcher match=MODIFIER.matcher(modifier);
            if(!match.matches()) return null;
            int amount=Integer.parseInt(match.group(2));if(amount>100) return null;
            if(match.group(1).equals("a")) color=ColorUtils.setAlphaComponent(color,Math.round(amount*255f/100));
            else color=ColorUtils.blendARGB(Color.BLACK,color,amount/100f);
        }
        return color;
    }
    public static void refreshIfNeeded() {
        if(Build.VERSION.SDK_INT<31) return;
        long next=31L*color("a1_500")+color("n1_900");
        boolean changed=palette!=0 && palette!=next;palette=next;
        Theme.ThemeInfo current=Theme.getActiveTheme();
        if(changed && current!=null && current.assetName!=null && current.assetName.startsWith("monet_")) {
            Theme.refreshMonetTheme();
        }
    }
}
