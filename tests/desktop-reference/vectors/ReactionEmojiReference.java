// SPDX-License-Identifier: GPL-3.0-or-later
// Generated table input: EmojiData.java, GPL-2.0-or-later; see assets/NOTICE.md.
import java.util.*;
import org.telegram.messenger.EmojiData;
public class ReactionEmojiReference {
 static String canonical(String emoji){StringBuilder out=new StringBuilder();emoji.replace("\ufe0f","").codePoints().forEach(c->{out.appendCodePoint(c);if(c<=Character.MAX_VALUE&&EmojiData.emojiToFE0FMap.containsKey((char)c)||c==0x1f22f||c==0x1f004||c==0x1f21a||c==0x1f17f)out.append('\ufe0f');});return out.toString();}
 public static void main(String[] args){TreeMap<String,String> values=new TreeMap<>();for(String[][] source:new String[][][]{EmojiData.data,EmojiData.dataColored})for(String[] section:source)for(String emoji:section)values.put(emoji.replace("\ufe0f",""),canonical(emoji));for(CharSequence alias:EmojiData.emojiAliasMap.keySet())values.put(alias.toString().replace("\ufe0f",""),canonical(alias.toString()));for(var entry:values.entrySet())System.out.println(entry.getKey()+"\t"+entry.getValue());}
}
