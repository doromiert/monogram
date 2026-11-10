// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared mobile/desktop reaction vocabulary. See resources/.../reaction-emoji.NOTICE. */
public final class ReactionEmoji {
    private static final Map<String,String> VALUES=load();
    private ReactionEmoji() {}
    private static Map<String,String> load() {
        Map<String,String> result=new HashMap<>();
        try(InputStream stream=ReactionEmoji.class.getResourceAsStream("reaction-emoji.tsv")) {
            if(stream==null) throw new IOException("Missing emoji vocabulary");
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
                for(String line;(line=reader.readLine())!=null;) {int tab=line.indexOf('\t');if(tab>0)result.put(line.substring(0,tab),line.substring(tab+1));}
            }
        } catch(IOException error) {throw new ExceptionInInitializerError(error);}
        return Collections.unmodifiableMap(result);
    }
    public static String key(String emoji) {return emoji==null?"":emoji.replace("\ufe0f","");}
    public static String canonical(String emoji) {return VALUES.get(key(emoji));}
}
