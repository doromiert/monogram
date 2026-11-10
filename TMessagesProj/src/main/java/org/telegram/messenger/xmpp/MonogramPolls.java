// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.util.*;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.PollDefinition;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_update;

/** Native polls from structured XML, with an explicit legacy body codec for historical messages. */
public final class MonogramPolls {
    public static final int MAX_OPTIONS=32;
    private MonogramPolls() {}
    public static final class Invalid extends IllegalArgumentException { public Invalid(String reason) { super(reason); } }
    static final String[] EMOJI={"1️⃣","2️⃣","3️⃣","4️⃣","5️⃣","6️⃣","7️⃣","8️⃣","9️⃣","🔟","🔴","🟠","🟡","🟢","🔵","🟣","🟤","⚫","⚪","⭐","🌙","☀️","🌈","⚡","🔥","💧","🌸","🍀","🍎","🍋","🍇","🥝"};
    static final String XML="monogram-poll-xml";
    private static final String BODY="monogram-poll-body";
    static String body(TLRPC.Message message) { return message.params!=null && message.params.containsKey(BODY)?message.params.get(BODY):message.message; }
    private static final String PREFIX="📊 ",SINGLE="Public votes. React with an option's emoji to vote. Choose one.",MULTI="Public votes. React with option emojis to vote. Choose any.",CLOSED="Voting closed.";
    static final class Poll {
        final String question,description;final List<String> answers,emojis;final boolean multiple,closed;
        Poll(String question,List<String> answers,boolean multiple,boolean closed) { this(question,"",answers,multiple,closed); }
        Poll(String question,String description,List<String> answers,boolean multiple,boolean closed) {
            this.question=field(question,4096);this.description=description==null || description.trim().isEmpty()?"":field(description,4096);this.multiple=multiple;this.closed=closed;
            if(answers==null || answers.size()<2 || answers.size()>EMOJI.length) throw new Invalid("Choose between 2 and 32 poll options.");
            ArrayList<String> copy=new ArrayList<>();for(String answer:answers) copy.add(field(answer,1024));this.answers=Collections.unmodifiableList(copy);this.emojis=Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(EMOJI,copy.size())));
        }
        Poll(PollDefinition definition) {
            question=definition.question;description=definition.description;multiple=definition.multiple;closed=definition.closed;
            List<String> labels=new ArrayList<>(),mapping=new ArrayList<>();
            for(PollDefinition.Option option:definition.options){labels.add(option.label);mapping.add(option.emoji);}
            answers=Collections.unmodifiableList(labels);emojis=Collections.unmodifiableList(mapping);
        }
        String body() {
            StringBuilder out=new StringBuilder(PREFIX).append(indent(question)).append("\n\n");
            if(!description.isEmpty()) out.append("📝 ").append(indent(description)).append("\n\n");
            for(int i=0;i<answers.size();i++) { if(i>0) out.append('\n');out.append(emojis.get(i)).append(' ').append(indent(answers.get(i))); }
            return out.append("\n\n").append(multiple?MULTI:SINGLE).append(closed?"\n"+CLOSED:"").toString();
        }
    }
    static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS message_polls(message INTEGER PRIMARY KEY,payload TEXT NOT NULL)");}
    static boolean remember(SQLiteDatabase db,int id,String xml) {
        if(xml==null)return false;
        ContentValues values=new ContentValues();values.put("message",id);values.put("payload",xml);
        return db.insertWithOnConflict("message_polls",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
    }
    static void restore(SQLiteDatabase db,TLRPC.Message message,int payload) {
        try(Cursor row=db.rawQuery("SELECT payload FROM message_polls WHERE message=?",new String[]{Integer.toString(payload)})) {
            if(row.moveToFirst()){if(message.params==null)message.params=new HashMap<>();message.params.put(XML,row.getString(0));}
        }
    }
    static Poll data(TLRPC.Message message) {
        if(message.params!=null&&message.params.containsKey(XML)) {
            try{return new Poll(PollDefinition.fromXml(message.params.get(XML)));}catch(IllegalArgumentException malformed){return null;}
        }
        return parse(body(message));
    }
    static long cutoff(SQLiteDatabase db,int original) {
        long result=Long.MAX_VALUE;
        try(Cursor rows=db.rawQuery("SELECT m.time_ms,p.payload FROM messages m JOIN message_polls p ON p.message=m.id WHERE m.id=? OR m.id IN(SELECT message FROM corrections WHERE resolved=?)",new String[]{Integer.toString(original),Integer.toString(original)})) {
            while(rows.moveToNext())try{if(PollDefinition.fromXml(rows.getString(1)).closed)result=Math.min(result,rows.getLong(0));}catch(IllegalArgumentException malformed){/* Ignore malformed definitions. */}
        }
        return result;
    }
    static boolean correctionAllowed(SQLiteDatabase db,int original,int event) {
        String base=null,edit=null;
        try(Cursor rows=db.rawQuery("SELECT message,payload FROM message_polls WHERE message IN (?,?)",new String[]{Integer.toString(original),Integer.toString(event)})) {
            while(rows.moveToNext()){if(rows.getInt(0)==original)base=rows.getString(1);else edit=rows.getString(1);}
        }
        if(base==null&&edit==null)return true;
        if(base==null||edit==null)return false;
        try{PollDefinition a=PollDefinition.fromXml(base),b=PollDefinition.fromXml(edit);return b.closed&&a.sameDefinition(b);}catch(IllegalArgumentException malformed){return false;}
    }
    private static String field(String text,int max) {
        if(text==null || text.trim().isEmpty() || text.length()>max || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c!='\n')) throw new Invalid("Poll text is empty, too long or contains invalid characters.");
        return text;
    }
    private static String indent(String text) { return text.replace("\n","\n  "); }
    private static String unindent(String text) {
        String[] lines=text.split("\n",-1);StringBuilder out=new StringBuilder(lines[0]);
        for(int i=1;i<lines.length;i++) { if(!lines[i].startsWith("  ")) throw new Invalid("Invalid poll continuation");out.append('\n').append(lines[i].substring(2)); }
        return out.toString();
    }
    static Poll parse(String body) {
        if(body==null || body.length()>40000 || !body.startsWith(PREFIX)) return null;
        try {
            int first=body.indexOf("\n\n"),last=body.lastIndexOf("\n\n");if(first<3 || last<=first) return null;
            String footer=body.substring(last+2);boolean closed=footer.endsWith("\n"+CLOSED);
            if(closed) footer=footer.substring(0,footer.length()-CLOSED.length()-1);
            if(!footer.equals(SINGLE) && !footer.equals(MULTI)) return null;
            String question=unindent(body.substring(PREFIX.length(),first)),description="";
            int options=first+2;
            if(body.startsWith("📝 ",options)) {
                int descriptionEnd=body.indexOf("\n\n",options);
                if(descriptionEnd<0 || descriptionEnd>=last) return null;
                description=unindent(body.substring(options+"📝 ".length(),descriptionEnd));options=descriptionEnd+2;
            }
            List<String> answers=new ArrayList<>();StringBuilder answer=null;
            for(String line:body.substring(options,last).split("\n",-1)) {
                if(line.startsWith("  ") && answer!=null) { answer.append('\n').append(line);continue; }
                if(answer!=null) answers.add(unindent(answer.toString()));
                if(answers.size()>=EMOJI.length || !line.startsWith(EMOJI[answers.size()]+" ")) return null;
                answer=new StringBuilder(line.substring(EMOJI[answers.size()].length()+1));
            }
            if(answer!=null) answers.add(unindent(answer.toString()));
            Poll result=new Poll(question,description,answers,footer.equals(MULTI),closed);
            return result.body().equals(body)?result:null;
        } catch(IllegalArgumentException invalid) { return null; }
    }
    static boolean supports(TLObject request) { return request instanceof TLRPC.TL_messages_sendMedia && ((TLRPC.TL_messages_sendMedia)request).media instanceof TLRPC.TL_inputMediaPoll; }
    static TLRPC.TL_messages_sendMessage text(TLRPC.TL_messages_sendMedia request) {
        TLRPC.TL_inputMediaPoll media=(TLRPC.TL_inputMediaPoll)request.media;TLRPC.Poll poll=media.poll;
        if(poll==null || poll.question==null) throw new Invalid("Missing poll question.");
        if(!poll.public_voters) throw new Invalid("Reaction poll votes are public. Create this poll with public votes enabled.");
        if(poll.quiz) throw new Invalid("Quiz answers are not supported by reaction polls yet.");
        if(poll.open_answers) throw new Invalid("Adding options after sending is not supported by reaction polls yet.");
        if(poll.revoting_disabled) throw new Invalid("Reaction polls allow voters to change their vote.");
        if(poll.hide_results_until_close || poll.close_period!=0 || poll.close_date!=0) throw new Invalid("Timed closure and hidden results are not supported by reaction polls yet.");
        if(poll.subscribers_only || !poll.countries_iso2.isEmpty()) throw new Invalid("Audience restrictions are not supported by reaction polls yet.");
        if(media.attached_media!=null || media.solution_media!=null) throw new Invalid("Send poll attachments separately.");
        List<String> answers=new ArrayList<>();
        for(TLRPC.PollAnswer answer:poll.answers) {
            if(answer==null || answer.text==null || answer.media!=null || answer.input_media!=null) throw new Invalid("Send poll options as text; attachments are not supported yet.");
            answers.add(answer.text.text);
        }
        TLRPC.TL_messages_sendMessage send=new TLRPC.TL_messages_sendMessage();
        send.peer=request.peer;send.random_id=request.random_id;send.reply_to=request.reply_to;send.flags=request.reply_to==null?0:1;
        send.silent=request.silent;send.background=request.background;send.clear_draft=request.clear_draft;send.schedule_date=request.schedule_date;
        send.no_webpage=true;send.message=new Poll(poll.question.text,request.message,answers,poll.multiple_choice,poll.closed).body();
        if(send.message.length()>40000) throw new Invalid("This poll is too long. Shorten its text before sending.");
        return send;
    }
    private static String key(String emoji) { return emoji==null?"":emoji.replace("\ufe0f",""); }
    /** Presentation only: vote transport reactions remain available to tallying, voter lists and resend logic. */
    public static List<TLRPC.ReactionCount> visibleReactions(TLRPC.Message message) {
        if(message==null || message.reactions==null) return Collections.emptyList();
        Poll definition=data(message);
        int options=message.media instanceof TLRPC.TL_messageMediaPoll && ((TLRPC.TL_messageMediaPoll)message.media).poll!=null
                ? Math.min(EMOJI.length,((TLRPC.TL_messageMediaPoll)message.media).poll.answers.size()):0;
        List<TLRPC.ReactionCount> visible=new ArrayList<>();
        for(TLRPC.ReactionCount count:message.reactions.results) {
            boolean vote=false;
            if(count.reaction instanceof TLRPC.TL_reactionEmoji) {
                String emoji=key(((TLRPC.TL_reactionEmoji)count.reaction).emoticon);
                for(int i=0;i<options;i++) if(emoji.equals(key(definition==null?EMOJI[i]:definition.emojis.get(i)))) { vote=true;break; }
            }
            if(!vote) visible.add(count);
        }
        return visible;
    }
    private static byte[] option(int index) { return new byte[]{(byte)('0'+index)}; }
    private static int index(byte[] option,int count) {
        int value=option==null || option.length!=1?-1:option[0]-'0';return value>=0 && value<count?value:-1;
    }
    static Map<Long,TLRPC.TL_messagePeerVoteMultiple> ballots(Poll poll,TLRPC.TL_messageReactions reactions) {
        Map<Long,TLRPC.TL_messagePeerVoteMultiple> result=new LinkedHashMap<>();if(reactions==null) return result;
        for(TLRPC.MessagePeerReaction reaction:reactions.recent_reactions) {
            if(!(reaction.reaction instanceof TLRPC.TL_reactionEmoji) || reaction.peer_id==null || reaction.peer_id.user_id==0) continue;
            String emoji=key(((TLRPC.TL_reactionEmoji)reaction.reaction).emoticon);int index=-1;
            for(int i=0;i<poll.answers.size();i++) if(emoji.equals(key(poll.emojis.get(i)))) { index=i;break; }
            if(index<0) continue;
            TLRPC.TL_messagePeerVoteMultiple vote=result.get(reaction.peer_id.user_id);
            if(vote==null) { vote=new TLRPC.TL_messagePeerVoteMultiple();vote.peer=reaction.peer_id;result.put(reaction.peer_id.user_id,vote); }
            vote.date=Math.max(vote.date,reaction.date);byte[] value=option(index);boolean duplicate=false;
            for(byte[] previous:vote.options) if(Arrays.equals(previous,value)) duplicate=true;
            if(!duplicate) vote.options.add(value);
        }
        if(!poll.multiple) result.values().removeIf(vote -> vote.options.size()!=1);
        return result;
    }
    /** Called after the existing formatting and reaction projections, including archive/restart reads. */
    static void decorate(TLRPC.Message message,long self) {
        if(message.media!=null && !(message.media instanceof TLRPC.TL_messageMediaEmpty)) return;
        if(message.entities!=null && !message.entities.isEmpty() && (message.params==null||!message.params.containsKey(XML)))return; // Only the legacy codec inspects body presentation.
        Poll data=data(message);if(data==null) return;
        TLRPC.TL_messageMediaPoll media=new TLRPC.TL_messageMediaPoll();media.poll=new TLRPC.TL_poll();media.results=new TLRPC.TL_pollResults();
        media.poll.id=Long.MIN_VALUE | (message.id & 0xffffffffL);media.poll.public_voters=true;media.poll.multiple_choice=data.multiple;media.poll.closed=data.closed;media.poll.creator=message.out;
        media.poll.question.text=data.question;
        for(int i=0;i<data.answers.size();i++) { TLRPC.TL_pollAnswer answer=new TLRPC.TL_pollAnswer();answer.text.text=data.answers.get(i);answer.option=option(i);media.poll.answers.add(answer); }
        if(message.dialog_id==self && message.reactions!=null && message.reactions.reactions_as_tags) {
            for(TLRPC.ReactionCount chosen:message.reactions.results) if(chosen.chosen) {
                TLRPC.TL_messagePeerReaction reaction=new TLRPC.TL_messagePeerReaction();reaction.reaction=chosen.reaction;
                reaction.peer_id=new TLRPC.TL_peerUser();reaction.peer_id.user_id=self;reaction.date=message.date;message.reactions.recent_reactions.add(reaction);
            }
        }
        Map<Long,TLRPC.TL_messagePeerVoteMultiple> votes=ballots(data,message.reactions);
        media.results.flags=2|4|8;media.results.total_voters=votes.size();
        for(int i=0;i<data.answers.size();i++) {
            TLRPC.TL_pollAnswerVoters answer=new TLRPC.TL_pollAnswerVoters();answer.option=option(i);
            for(TLRPC.TL_messagePeerVoteMultiple vote:votes.values()) for(byte[] selection:vote.options) if(Arrays.equals(selection,answer.option)) { answer.voters++;if(vote.peer.user_id==self) answer.chosen=true; }
            media.results.results.add(answer);
        }
        for(TLRPC.TL_messagePeerVoteMultiple vote:votes.values()) media.results.recent_voters.add(vote.peer);
        if(message.params==null) message.params=new HashMap<>();message.params.put(BODY,body(message));
        String accompanying=message.params!=null&&message.params.containsKey(XML)?message.message:"";
        int prefix=accompanying.isEmpty()||data.description.isEmpty()?0:data.description.length()+2;
        if(prefix>0&&message.entities!=null)for(TLRPC.MessageEntity entity:message.entities)entity.offset+=prefix;
        message.message=data.description+(accompanying.isEmpty()?"":(data.description.isEmpty()?"":"\n\n")+accompanying);message.media=media;message.flags|=TLRPC.MESSAGE_FLAG_HAS_MEDIA;
    }
    /** Failed native polls store the description as their caption; projected polls also retain the full fallback. */
    public static String retryDescription(TLRPC.Message message) {
        if(message.params!=null && message.params.containsKey(BODY)) {
            Poll original=parse(message.params.get(BODY));if(original!=null) return original.description;
        }
        return message.message==null?"":message.message;
    }
    /** Native short-send responses have no params field; preserve the fallback for immediate forwarding. */
    public static void completeSent(TLRPC.Message message) {
        if(!(message.media instanceof TLRPC.TL_messageMediaPoll)) return;
        TLRPC.Poll poll=((TLRPC.TL_messageMediaPoll)message.media).poll;List<String> answers=new ArrayList<>();
        for(TLRPC.PollAnswer answer:poll.answers) answers.add(answer.text.text);
        if(message.params==null) message.params=new HashMap<>();
        message.params.put(BODY,new Poll(poll.question.text,message.message,answers,poll.multiple_choice,poll.closed).body());
    }
    static String freshBody(TLRPC.Message message) {
        Poll poll=data(message);if(poll==null) throw new Invalid("Poll is unavailable.");
        return new Poll(poll.question,poll.description,poll.answers,poll.multiple,false).body();
    }
    static TLRPC.TL_messageMediaPoll require(TLRPC.Message message,long peer) {
        if(message.dialog_id!=peer || !(message.media instanceof TLRPC.TL_messageMediaPoll) || data(message)==null) throw new Invalid("Poll is unavailable in this conversation.");
        return (TLRPC.TL_messageMediaPoll)message.media;
    }
    static TLRPC.TL_messages_sendReaction vote(TLRPC.TL_messages_sendVote request,TLRPC.Message message,long self) {
        TLRPC.TL_messageMediaPoll media=require(message,dialog(request.peer));Poll poll=data(message);
        if(media.poll.closed) throw new Invalid("This poll is closed.");
        LinkedHashSet<Integer> chosen=new LinkedHashSet<>();for(byte[] value:request.options) { int index=index(value,poll.answers.size());if(index<0) throw new Invalid("Unknown poll option.");chosen.add(index); }
        if(!poll.multiple && chosen.size()>1) throw new Invalid("Choose one option in this poll.");
        TLRPC.TL_messages_sendReaction reaction=new TLRPC.TL_messages_sendReaction();reaction.peer=request.peer;reaction.msg_id=request.msg_id;
        Set<String> keys=new HashSet<>();for(int i=0;i<poll.answers.size();i++) keys.add(key(poll.emojis.get(i)));
        if(message.reactions!=null) for(TLRPC.ReactionCount current:message.reactions.results) if(current.chosen && current.reaction instanceof TLRPC.TL_reactionEmoji
                && !keys.contains(key(((TLRPC.TL_reactionEmoji)current.reaction).emoticon))) reaction.reaction.add(current.reaction);
        for(int index:chosen) { TLRPC.TL_reactionEmoji emoji=new TLRPC.TL_reactionEmoji();emoji.emoticon=poll.emojis.get(index);reaction.reaction.add(emoji); }
        reaction.flags=1;return reaction;
    }
    static long dialog(TLRPC.InputPeer peer) {
        if(peer==null || peer.channel_id!=0 || peer.chat_id==0 && peer.user_id==0) throw new Invalid("Conversation required.");
        return peer.chat_id!=0?-peer.chat_id:peer.user_id;
    }
    static TL_update.TL_updateMessagePoll update(TLRPC.Message message) {
        TLRPC.TL_messageMediaPoll media=(TLRPC.TL_messageMediaPoll)message.media;TL_update.TL_updateMessagePoll update=new TL_update.TL_updateMessagePoll();
        update.poll_id=media.poll.id;update.poll=media.poll;update.results=media.results;update.peer=message.peer_id;update.msg_id=message.id;update.flags=3;return update;
    }
    static TLRPC.TL_messages_votesList voters(TLRPC.TL_messages_getPollVotes request,TLRPC.Message message,MonogramStore store,long self) {
        require(message,dialog(request.peer));Poll poll=data(message);int filter=request.option==null?-1:index(request.option,poll.answers.size());
        if(request.option!=null && filter<0) throw new Invalid("Unknown poll option.");
        List<TLRPC.TL_messagePeerVoteMultiple> votes=new ArrayList<>(ballots(poll,message.reactions).values());
        if(filter>=0) votes.removeIf(vote -> vote.options.stream().noneMatch(value -> Arrays.equals(value,request.option)));
        int offset;try { offset=request.offset==null || request.offset.isEmpty()?0:Integer.parseInt(request.offset); } catch(NumberFormatException invalid) { throw new Invalid("Invalid voter offset."); }
        if(offset<0) throw new Invalid("Invalid voter offset.");
        int end=(int)Math.min(votes.size(),(long)offset+Math.max(1,Math.min(100,request.limit)));
        TLRPC.TL_messages_votesList result=new TLRPC.TL_messages_votesList();result.count=votes.size();
        for(int i=offset;i<end;i++) { TLRPC.TL_messagePeerVoteMultiple vote=votes.get(i);result.votes.add(vote);result.users.add(store.user(vote.peer.user_id,self)); }
        if(end<votes.size()) { result.next_offset=Integer.toString(end);result.flags|=1; } return result;
    }
    static void close(TLRPC.TL_messages_editMessage request,TLRPC.Message message,long self) {
        require(message,dialog(request.peer));
        if(!message.out) throw new Invalid("Only the author can close this poll.");
        if(message.params!=null&&message.params.containsKey(XML))throw new Invalid("Close this poll from a client that supports structured poll updates.");
        TLRPC.Poll input=((TLRPC.TL_inputMediaPoll)request.media).poll;
        if(input==null || !input.closed) throw new Invalid("Only closing an existing poll is supported.");
        Poll poll=data(message);request.message=new Poll(poll.question,poll.description,poll.answers,poll.multiple,true).body();
        com.negzero.monogram.xmpp.ForwardedContent forward=MonogramForwarding.attribution(message.params,request.message);
        if(forward!=null) request.message=forward.wireBody();request.media=null;request.flags=(request.flags&~16384)|2048;
    }
}
