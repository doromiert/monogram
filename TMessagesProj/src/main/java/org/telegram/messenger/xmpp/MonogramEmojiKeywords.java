// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.res.AssetManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.telegram.tgnet.TLRPC;

/** Supplies the existing Telegram keyword cache without a Telegram account or network request. */
public final class MonogramEmojiKeywords {
    private MonogramEmojiKeywords() {}
    public static TLRPC.TL_emojiKeywordsDifference load(AssetManager assets,String language,int fromVersion) throws IOException {
        String normalized=language==null ? "en" : language.toLowerCase(Locale.ROOT).split("[-_]",2)[0];
        String selected=normalized.equals("pl") || normalized.equals("ro") ? normalized : "en";
        TLRPC.TL_emojiKeywordsDifference result=new TLRPC.TL_emojiKeywordsDifference();
        result.lang_code=selected; result.from_version=fromVersion; result.version=48;
        if(fromVersion==result.version) return result;
        try(BufferedReader input=new BufferedReader(new InputStreamReader(assets.open("emoji-keywords-"+selected+".tsv"),StandardCharsets.UTF_8))) {
            String line;
            while((line=input.readLine())!=null) {
                String[] columns=line.split("\t");
                if(columns.length<2) throw new IOException("Invalid emoji keyword asset");
                TLRPC.TL_emojiKeyword keyword=new TLRPC.TL_emojiKeyword(); keyword.keyword=columns[0];
                keyword.emoticons.addAll(Arrays.asList(columns).subList(1,columns.length)); result.keywords.add(keyword);
            }
        }
        return result;
    }
}
