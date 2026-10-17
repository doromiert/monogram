// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.LongFunction;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/** Native call controls, evaluated locally before accepting calls or sharing direct-network candidates. */
final class MonogramCallPrivacy {
    private static final int ALLOW_ALL=1,DENY_ALL=2,ALLOW_CONTACTS=3,DENY_CONTACTS=4,
            ALLOW_USERS=5,DENY_USERS=6,ALLOW_GROUPS=7,DENY_GROUPS=8;
    private static final int MAX_RULES=128,MAX_IDS=2048,MAX_TEXT=128*1024;
    private final SharedPreferences preferences;
    private final String owner;
    private final LongFunction<TLRPC.User> users;
    private final LongFunction<TLRPC.Chat> chats;
    private final BiPredicate<Long,Long> memberOf;

    MonogramCallPrivacy(int account,String owner,LongFunction<TLRPC.User> users,LongFunction<TLRPC.Chat> chats,BiPredicate<Long,Long> memberOf) throws Exception {
        this(ApplicationLoader.applicationContext,account,owner,users,chats,memberOf);
    }
    MonogramCallPrivacy(Context context,int account,String owner,LongFunction<TLRPC.User> users,LongFunction<TLRPC.Chat> chats,BiPredicate<Long,Long> memberOf) throws Exception {
        if(account<0 || owner==null || owner.indexOf('/')>=0) throw new IllegalArgumentException("Invalid call privacy account");
        this.owner=JidCreate.entityBareFrom(owner).toString();
        this.preferences=context.getSharedPreferences("monogram-call-privacy-"+account,Context.MODE_PRIVATE);
        this.users=Objects.requireNonNull(users);this.chats=Objects.requireNonNull(chats);this.memberOf=Objects.requireNonNull(memberOf);
    }
    static boolean handles(TLObject request) {
        return request instanceof TL_account.getPrivacy && supported(((TL_account.getPrivacy)request).key)
                || request instanceof TL_account.setPrivacy && supported(((TL_account.setPrivacy)request).key);
    }
    private static boolean supported(TLRPC.InputPrivacyKey key) {
        return key instanceof TLRPC.TL_inputPrivacyKeyPhoneCall || key instanceof TLRPC.TL_inputPrivacyKeyPhoneP2P;
    }
    private String key(boolean direct) { return owner+(direct ? "|p2p" : "|incoming"); }
    TLObject handle(TLObject request) throws Exception {
        if(!handles(request)) throw new IllegalArgumentException("Unsupported call privacy request");
        if(request instanceof TL_account.getPrivacy)
            return response(read(((TL_account.getPrivacy)request).key instanceof TLRPC.TL_inputPrivacyKeyPhoneP2P));
        TL_account.setPrivacy update=(TL_account.setPrivacy)request;
        if(update.rules==null || update.rules.isEmpty() || update.rules.size()>MAX_RULES) throw new IllegalArgumentException("Invalid call privacy rules");
        ArrayList<Rule> rules=new ArrayList<>();int ids=0;
        for(TLRPC.InputPrivacyRule input:update.rules) {
            Rule rule=convert(input);ids+=rule.ids.length;
            if(ids>MAX_IDS) throw new IllegalArgumentException("Too many call privacy exceptions");
            rules.add(rule);
        }
        TL_account.privacyRules result=response(rules);
        JSONArray encoded=new JSONArray();
        for(Rule rule:rules) {
            JSONArray values=new JSONArray();for(long id:rule.ids) values.put(id);
            encoded.put(new JSONObject().put("type",rule.type).put("ids",values));
        }
        String value=new JSONObject().put("version",1).put("rules",encoded).toString();
        if(value.length()>MAX_TEXT) throw new IllegalArgumentException("Call privacy settings too large");
        if(!preferences.edit().putString(key(update.key instanceof TLRPC.TL_inputPrivacyKeyPhoneP2P),value).commit())
            throw new IOException("Cannot save call privacy settings");
        return result;
    }
    boolean allowIncoming(long peer,boolean rosterContact) { return allowed(false,peer,rosterContact); }
    boolean allowDirect(long peer,boolean rosterContact) { return allowed(true,peer,rosterContact); }
    private boolean allowed(boolean direct,long peer,boolean contact) {
        if(peer<=0) return false;
        try {
            for(Rule rule:read(direct)) {
                boolean match=rule.type<=DENY_ALL || (rule.type==ALLOW_CONTACTS || rule.type==DENY_CONTACTS) && contact;
                if(rule.type==ALLOW_USERS || rule.type==DENY_USERS) for(long id:rule.ids) if(id==peer) { match=true;break; }
                if(rule.type==ALLOW_GROUPS || rule.type==DENY_GROUPS) for(long chat:rule.ids) if(memberOf.test(peer,chat)) { match=true;break; }
                if(match) return rule.type==ALLOW_ALL || rule.type==ALLOW_CONTACTS || rule.type==ALLOW_USERS || rule.type==ALLOW_GROUPS;
            }
        } catch(RuntimeException unavailable) { return false; }
        return false;
    }
    private List<Rule> read(boolean direct) {
        try {
            String value=preferences.getString(key(direct),null);
            if(value==null) return Collections.singletonList(new Rule(ALLOW_CONTACTS,new long[0]));
            if(value.length()>MAX_TEXT) throw new IllegalArgumentException("Call privacy settings too large");
            JSONObject document=new JSONObject(value);JSONArray encoded=document.getJSONArray("rules");
            if(!Integer.valueOf(1).equals(document.get("version")) || encoded.length()==0 || encoded.length()>MAX_RULES) throw new IllegalArgumentException("Invalid call privacy settings");
            ArrayList<Rule> rules=new ArrayList<>();int count=0;
            for(int at=0;at<encoded.length();at++) {
                JSONObject item=encoded.getJSONObject(at);Object encodedType=item.get("type");
                if(!(encodedType instanceof Integer)) throw new IllegalArgumentException("Invalid call privacy rule type");
                int type=(Integer)encodedType;JSONArray ids=item.getJSONArray("ids");
                count+=ids.length();if(type<ALLOW_ALL || type>DENY_GROUPS || count>MAX_IDS || type<ALLOW_USERS && ids.length()!=0)
                    throw new IllegalArgumentException("Invalid call privacy rule");
                long[] values=new long[ids.length()];for(int i=0;i<values.length;i++) {
                    Object valueId=ids.get(i);
                    if(!(valueId instanceof Integer) && !(valueId instanceof Long)) throw new IllegalArgumentException("Invalid call privacy peer");
                    values[i]=((Number)valueId).longValue();if(values[i]<=0) throw new IllegalArgumentException("Invalid call privacy peer");
                }
                rules.add(new Rule(type,values));
            }
            return rules;
        } catch(Exception invalid) { return Collections.singletonList(new Rule(DENY_ALL,new long[0])); }
    }
    private static final class Rule {
        final int type;final long[] ids;
        Rule(int type,long[] ids) { this.type=type;this.ids=ids; }
    }
    private Rule convert(TLRPC.InputPrivacyRule input) {
        if(input instanceof TLRPC.TL_inputPrivacyValueAllowAll) return new Rule(ALLOW_ALL,new long[0]);
        if(input instanceof TLRPC.TL_inputPrivacyValueDisallowAll) return new Rule(DENY_ALL,new long[0]);
        if(input instanceof TLRPC.TL_inputPrivacyValueAllowContacts) return new Rule(ALLOW_CONTACTS,new long[0]);
        if(input instanceof TLRPC.TL_inputPrivacyValueDisallowContacts) return new Rule(DENY_CONTACTS,new long[0]);
        List<TLRPC.InputUser> peers=null;List<Long> groups=null;int type;
        if(input instanceof TLRPC.TL_inputPrivacyValueAllowUsers) { type=ALLOW_USERS;peers=((TLRPC.TL_inputPrivacyValueAllowUsers)input).users; }
        else if(input instanceof TLRPC.TL_inputPrivacyValueDisallowUsers) { type=DENY_USERS;peers=((TLRPC.TL_inputPrivacyValueDisallowUsers)input).users; }
        else if(input instanceof TLRPC.TL_inputPrivacyValueAllowChatParticipants) { type=ALLOW_GROUPS;groups=((TLRPC.TL_inputPrivacyValueAllowChatParticipants)input).chats; }
        else if(input instanceof TLRPC.TL_inputPrivacyValueDisallowChatParticipants) { type=DENY_GROUPS;groups=((TLRPC.TL_inputPrivacyValueDisallowChatParticipants)input).chats; }
        else throw new IllegalArgumentException("Unsupported call privacy rule");
        if((peers==null && groups==null) || peers!=null && peers.size()>MAX_IDS || groups!=null && groups.size()>MAX_IDS) throw new IllegalArgumentException("Invalid call privacy exceptions");
        long[] values=new long[peers!=null ? peers.size() : groups.size()];
        for(int i=0;i<values.length;i++) {
            if(peers!=null) {
                TLRPC.InputUser peer=peers.get(i);
                if(!(peer instanceof TLRPC.TL_inputUser)) throw new IllegalArgumentException("Unsupported call privacy user");
                values[i]=peer.user_id;
            } else { Long group=groups.get(i);values[i]=group==null ? 0 : group; }
            if(values[i]<=0) throw new IllegalArgumentException("Invalid call privacy exception");
        }
        return new Rule(type,values);
    }
    private TL_account.privacyRules response(List<Rule> rules) {
        TL_account.privacyRules response=new TL_account.privacyRules();Set<Long> peerIds=new LinkedHashSet<>(),chatIds=new LinkedHashSet<>();
        for(Rule rule:rules) {
            TLRPC.PrivacyRule nativeRule;
            switch(rule.type) {
                case ALLOW_ALL: nativeRule=new TLRPC.TL_privacyValueAllowAll();break;
                case DENY_ALL: nativeRule=new TLRPC.TL_privacyValueDisallowAll();break;
                case ALLOW_CONTACTS: nativeRule=new TLRPC.TL_privacyValueAllowContacts();break;
                case DENY_CONTACTS: nativeRule=new TLRPC.TL_privacyValueDisallowContacts();break;
                case ALLOW_USERS:
                    TLRPC.TL_privacyValueAllowUsers allow=new TLRPC.TL_privacyValueAllowUsers();for(long id:rule.ids) { allow.users.add(id);peerIds.add(id); }nativeRule=allow;break;
                case DENY_USERS:
                    TLRPC.TL_privacyValueDisallowUsers deny=new TLRPC.TL_privacyValueDisallowUsers();for(long id:rule.ids) { deny.users.add(id);peerIds.add(id); }nativeRule=deny;break;
                case ALLOW_GROUPS:
                    TLRPC.TL_privacyValueAllowChatParticipants allowGroups=new TLRPC.TL_privacyValueAllowChatParticipants();for(long id:rule.ids) { allowGroups.chats.add(id);chatIds.add(id); }nativeRule=allowGroups;break;
                case DENY_GROUPS:
                    TLRPC.TL_privacyValueDisallowChatParticipants denyGroups=new TLRPC.TL_privacyValueDisallowChatParticipants();for(long id:rule.ids) { denyGroups.chats.add(id);chatIds.add(id); }nativeRule=denyGroups;break;
                default: throw new IllegalArgumentException("Unsupported call privacy rule");
            }
            response.rules.add(nativeRule);
        }
        for(long id:peerIds) { TLRPC.User user=users.apply(id);if(user==null || user.id!=id) throw new IllegalArgumentException("Unknown call privacy user");response.users.add(user); }
        for(long id:chatIds) { TLRPC.Chat chat=chats.apply(id);if(chat==null || chat.id!=id) throw new IllegalArgumentException("Unknown call privacy group");response.chats.add(chat); }
        return response;
    }
}
