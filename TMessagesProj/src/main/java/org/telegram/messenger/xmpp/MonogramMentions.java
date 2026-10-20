// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import com.negzero.monogram.xmpp.MessageMarkup;
import com.negzero.monogram.xmpp.MessageMentions;
import java.util.*;
import java.util.regex.*;
import org.telegram.tgnet.TLRPC;

/** Native mention entities use local peer IDs; wire references use bare JIDs and Unicode code points. */
final class MonogramMentions {
    private MonogramMentions() {}
    static MessageMarkup fromEntities(MonogramStore store,long peer,String body,Collection<TLRPC.MessageEntity> entities) {
        MessageMarkup markup=MonogramFormatting.fromEntities(body,entities);List<MessageMentions.Mention> mentions=new ArrayList<>();
        if(entities!=null) for(TLRPC.MessageEntity entity:entities) {
            long user=0;
            if(entity instanceof TLRPC.TL_messageEntityMentionName) user=((TLRPC.TL_messageEntityMentionName)entity).user_id;
            else if(entity instanceof TLRPC.TL_inputMessageEntityMentionName && ((TLRPC.TL_inputMessageEntityMentionName)entity).user_id!=null)
                user=((TLRPC.TL_inputMessageEntityMentionName)entity).user_id.user_id;
            if(user<=0 || entity.offset<0 || entity.length<=0 || (long)entity.offset+entity.length>body.length() || mentions.size()>=MessageMentions.MAX) continue;
            int start=entity.offset,end=start+entity.length;while(end>start && Character.isWhitespace(body.charAt(end-1))) end--;
            if(end<=start || !boundary(body,start) || !boundary(body,end)) continue;
            String address=store.mentionAddress(peer,user);if(address==null) continue;
            try { mentions.add(new MessageMentions.Mention(body.codePointCount(0,start),body.codePointCount(0,end),address)); }
            catch(IllegalArgumentException invalid) { /* Anonymous occupants use the visible room nickname only. */ }
        }
        try { return markup.withMentions(mentions); } catch(IllegalArgumentException crossing) { return markup; }
    }
    private static boolean boundary(String body,int offset) {
        return offset==0 || offset==body.length() || !Character.isHighSurrogate(body.charAt(offset-1)) || !Character.isLowSurrogate(body.charAt(offset));
    }
    static void decorate(MonogramStore store,TLRPC.Message message,long self,MessageMarkup markup) {
        if(self<=0 || message.dialog_id>=0 || message.message==null || message.message.isEmpty()) return;
        String own=store.jid(self);boolean mentioned=false;
        Map<Long,String> names=store.mentionNames(message.dialog_id,self);
        List<int[]> occupied=new ArrayList<>();
        if(markup!=null && message.message.equals(markup.body)) for(MessageMentions.Mention mention:markup.mentions) {
            if(mention.address.equals(own)) mentioned=true;
            long user=store.knownPeer(mention.address);
            if(user!=self && !names.containsKey(user)) { occupied.add(new int[]{message.message.offsetByCodePoints(0,mention.start),message.message.offsetByCodePoints(0,mention.end)});continue; }
            if(user>0) {
                int start=message.message.offsetByCodePoints(0,mention.start),end=message.message.offsetByCodePoints(0,mention.end);
                add(message,user,start,end,occupied);
            }
        }
        String searchable=message.message.toLowerCase(Locale.ROOT);
        for(Map.Entry<Long,String> person:names.entrySet()) {
            String name=person.getValue();if(name==null || name.trim().isEmpty() || name.length()>256) continue;
            if(!searchable.contains(name.toLowerCase(Locale.ROOT))) continue;
            Matcher match=Pattern.compile(Pattern.quote(name),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(message.message);
            while(match.find() && occupied.size()<MessageMentions.MAX) {
                int start=match.start(),end=match.end();
                if(start>0 && word(message.message.codePointBefore(start)) || end<message.message.length() && word(message.message.codePointAt(end)) || excluded(message,start,end) || overlaps(occupied,start,end)) continue;
                if(person.getKey()==self) mentioned=true;
                add(message,person.getKey(),start,end,occupied);
            }
        }
        if(!message.out && mentioned) { message.mentioned=true;message.flags|=16; }
        if(!occupied.isEmpty()) message.flags|=TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
    }
    private static boolean excluded(TLRPC.Message message,int start,int end) {
        for(TLRPC.MessageEntity entity:message.entities) if(start<entity.offset+entity.length && entity.offset<end
                && (entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre || entity instanceof TLRPC.TL_messageEntityBlockquote
                || entity instanceof TLRPC.TL_messageEntityUrl || entity instanceof TLRPC.TL_messageEntityTextUrl)) return true;
        return false;
    }
    private static boolean overlaps(List<int[]> occupied,int start,int end) { for(int[] span:occupied) if(start<span[1] && span[0]<end) return true;return false; }
    private static void add(TLRPC.Message message,long user,int start,int end,List<int[]> occupied) {
        for(int[] span:occupied) if(start<span[1] && span[0]<end) return;
        TLRPC.TL_messageEntityMentionName entity=new TLRPC.TL_messageEntityMentionName();entity.user_id=user;entity.offset=start;entity.length=end-start;
        message.entities.add(entity);occupied.add(new int[]{start,end});
    }
    private static boolean word(int cp) { int type=Character.getType(cp);return Character.isLetterOrDigit(cp) || cp=='_' || type==Character.NON_SPACING_MARK || type==Character.COMBINING_SPACING_MARK || type==Character.ENCLOSING_MARK; }
}
