package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.*;
import org.telegram.tgnet.tl.TL_account;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramCallPrivacyTest {
    private static final String OWNER="alice@example.org";
    Context context;
    final Set<String> memberships=new HashSet<>();
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();
        for(int account:new int[]{0,1}) context.getSharedPreferences("monogram-call-privacy-"+account,0).edit().clear().commit();
    }
    @After public void cleanup() {
        for(int account:new int[]{0,1}) context.getSharedPreferences("monogram-call-privacy-"+account,0).edit().clear().commit();
    }
    private MonogramCallPrivacy helper(int account,String owner) throws Exception {
        return new MonogramCallPrivacy(context,account,owner,id -> { TLRPC.TL_user user=new TLRPC.TL_user();user.id=id;return user; },
                id -> { TLRPC.TL_chat chat=new TLRPC.TL_chat();chat.id=id;return chat; },(peer,chat) -> memberships.contains(peer+":"+chat));
    }
    private TL_account.setPrivacy request(boolean direct,TLRPC.InputPrivacyRule... rules) {
        TL_account.setPrivacy request=new TL_account.setPrivacy();
        request.key=direct ? new TLRPC.TL_inputPrivacyKeyPhoneP2P() : new TLRPC.TL_inputPrivacyKeyPhoneCall();
        request.rules.addAll(Arrays.asList(rules));return request;
    }
    private TL_account.getPrivacy get(boolean direct) {
        TL_account.getPrivacy request=new TL_account.getPrivacy();
        request.key=direct ? new TLRPC.TL_inputPrivacyKeyPhoneP2P() : new TLRPC.TL_inputPrivacyKeyPhoneCall();return request;
    }
    private TLRPC.InputUser input(long id) { TLRPC.TL_inputUser user=new TLRPC.TL_inputUser();user.user_id=id;return user; }
    private TLRPC.TL_inputPrivacyValueAllowUsers allow(long... ids) {
        TLRPC.TL_inputPrivacyValueAllowUsers rule=new TLRPC.TL_inputPrivacyValueAllowUsers();for(long id:ids) rule.users.add(input(id));return rule;
    }
    private TLRPC.TL_inputPrivacyValueDisallowUsers deny(long... ids) {
        TLRPC.TL_inputPrivacyValueDisallowUsers rule=new TLRPC.TL_inputPrivacyValueDisallowUsers();for(long id:ids) rule.users.add(input(id));return rule;
    }
    @Test public void defaultsPermitContactsAndPreventStrangerCallsAndDirectIpSharing() throws Exception {
        MonogramCallPrivacy privacy=helper(0,OWNER);
        assertTrue(privacy.allowIncoming(20,true));assertFalse(privacy.allowIncoming(20,false));
        assertTrue(privacy.allowDirect(20,true));assertFalse(privacy.allowDirect(20,false));
        assertFalse(privacy.allowIncoming(0,true));assertFalse(privacy.allowDirect(-1,true));
        TL_account.privacyRules result=(TL_account.privacyRules)privacy.handle(get(false));
        assertEquals(1,result.rules.size());assertTrue(result.rules.get(0) instanceof TLRPC.TL_privacyValueAllowContacts);
        assertTrue(result.users.isEmpty());assertTrue(result.chats.isEmpty());
    }
    @Test public void orderedUserExceptionsPersistAndNativeResponseContainsUsersWithoutMutatingP2p() throws Exception {
        MonogramCallPrivacy privacy=helper(0,OWNER);
        TLRPC.TL_inputPrivacyValueAllowUsers allowed=allow(30);TL_account.setPrivacy update=request(false,deny(20),allowed,
                new TLRPC.TL_inputPrivacyValueAllowChatParticipants(),new TLRPC.TL_inputPrivacyValueAllowContacts(),new TLRPC.TL_inputPrivacyValueDisallowAll());
        TL_account.privacyRules response=(TL_account.privacyRules)privacy.handle(update);
        assertEquals(5,response.rules.size());assertTrue(response.rules.get(0) instanceof TLRPC.TL_privacyValueDisallowUsers);
        assertEquals(Arrays.asList(20L,30L),Arrays.asList(response.users.get(0).id,response.users.get(1).id));
        assertFalse(privacy.allowIncoming(20,true));assertTrue(privacy.allowIncoming(30,false));
        assertTrue(privacy.allowIncoming(40,true));assertFalse(privacy.allowIncoming(40,false));assertFalse(privacy.allowDirect(30,false));
        allowed.users.clear();update.rules.clear();response.rules.clear();response.users.clear();
        MonogramCallPrivacy restarted=helper(0,OWNER);
        assertFalse(restarted.allowIncoming(20,true));assertTrue(restarted.allowIncoming(30,false));
        assertEquals(5,((TL_account.privacyRules)restarted.handle(get(false))).rules.size());
        restarted.handle(request(true,new TLRPC.TL_inputPrivacyValueDisallowContacts(),new TLRPC.TL_inputPrivacyValueAllowAll()));
        assertFalse(restarted.allowDirect(40,true));assertTrue(restarted.allowDirect(40,false));assertTrue(restarted.allowIncoming(30,false));
    }
    @Test public void settingsAreIsolatedByBothAccountAndOwnerBareJid() throws Exception {
        helper(0,OWNER).handle(request(false,new TLRPC.TL_inputPrivacyValueDisallowAll()));
        helper(0,OWNER).handle(request(true,new TLRPC.TL_inputPrivacyValueAllowAll()));
        assertFalse(helper(0,OWNER).allowIncoming(20,true));assertTrue(helper(0,OWNER).allowDirect(20,false));
        for(MonogramCallPrivacy other:Arrays.asList(helper(1,OWNER),helper(0,"carol@example.org"))) {
            assertTrue(other.allowIncoming(20,true));assertFalse(other.allowIncoming(20,false));assertFalse(other.allowDirect(20,false));
        }
        try { helper(0,OWNER+"/phone");fail("Resource account accepted"); } catch(IllegalArgumentException expected) {}
    }
    @Test public void currentGroupMembershipAndNativeGroupVectorsFollowOrderedRules() throws Exception {
        MonogramCallPrivacy privacy=helper(0,OWNER);memberships.add("20:7");
        TLRPC.TL_inputPrivacyValueDisallowChatParticipants denied=new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();denied.chats.add(7L);
        TLRPC.TL_inputPrivacyValueAllowChatParticipants allowed=new TLRPC.TL_inputPrivacyValueAllowChatParticipants();allowed.chats.add(8L);
        TL_account.privacyRules result=(TL_account.privacyRules)privacy.handle(request(false,denied,allowed,new TLRPC.TL_inputPrivacyValueAllowContacts()));
        assertEquals(Arrays.asList(7L,8L),Arrays.asList(result.chats.get(0).id,result.chats.get(1).id));
        assertFalse(privacy.allowIncoming(20,true));assertFalse(privacy.allowIncoming(30,false));
        memberships.add("30:8");assertTrue(privacy.allowIncoming(30,false));
        memberships.remove("20:7");assertTrue(privacy.allowIncoming(20,true));
        privacy.handle(request(false,allow(20),denied,new TLRPC.TL_inputPrivacyValueDisallowAll()));
        memberships.add("20:7");assertTrue(privacy.allowIncoming(20,false));
        privacy.handle(request(false,denied,new TLRPC.TL_inputPrivacyValueAllowAll()));assertTrue(privacy.allowIncoming(30,false));
        MonogramCallPrivacy brokenMembership=new MonogramCallPrivacy(context,0,OWNER,id -> null,id -> null,(peer,chat) -> { throw new IllegalStateException("Unavailable membership"); });
        assertFalse(brokenMembership.allowIncoming(30,true));
    }
    @Test public void unsupportedOrMalformedRulesFailExplicitlyWithoutReplacingSavedPolicy() throws Exception {
        MonogramCallPrivacy privacy=helper(0,OWNER);
        privacy.handle(request(false,new TLRPC.TL_inputPrivacyValueDisallowAll()));
        ArrayList<TL_account.setPrivacy> invalid=new ArrayList<>();
        invalid.add(request(false,new TLRPC.TL_inputPrivacyValueAllowPremium()));
        invalid.add(request(false,new TLRPC.TL_inputPrivacyValueAllowBots()));
        invalid.add(request(false,new TLRPC.TL_inputPrivacyValueAllowCloseFriends()));
        invalid.add(request(false,allow(0)));invalid.add(request(false));
        TLRPC.TL_inputPrivacyValueAllowUsers self=allow();self.users.add(new TLRPC.TL_inputUserSelf());invalid.add(request(false,self));
        TLRPC.TL_inputPrivacyValueAllowChatParticipants invalidGroup=new TLRPC.TL_inputPrivacyValueAllowChatParticipants();invalidGroup.chats.add(0L);invalid.add(request(false,invalidGroup));
        for(TL_account.setPrivacy update:invalid) {
            try { privacy.handle(update);fail("Unsupported policy accepted"); } catch(IllegalArgumentException expected) {}
            assertFalse(privacy.allowIncoming(20,true));
        }
        TL_account.getPrivacy unrelated=get(false);unrelated.key=new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        assertFalse(MonogramCallPrivacy.handles(unrelated));assertFalse(MonogramCallPrivacy.handles(null));
        assertTrue(MonogramCallPrivacy.handles(get(true)));assertTrue(MonogramCallPrivacy.handles(request(false,new TLRPC.TL_inputPrivacyValueAllowAll())));
        try { privacy.handle(unrelated);fail("Unrelated privacy key accepted"); } catch(IllegalArgumentException expected) {}
        MonogramCallPrivacy unknownLookup=new MonogramCallPrivacy(context,0,OWNER,id -> null,id -> null,(peer,chat) -> false);
        try { unknownLookup.handle(request(false,allow(999),new TLRPC.TL_inputPrivacyValueAllowAll()));fail("Missing user accepted"); } catch(IllegalArgumentException expected) {}
        assertFalse(privacy.allowIncoming(20,true));
    }
    @Test public void corruptedPersistedPolicyFailsClosedInsteadOfRevertingToLooserDefault() throws Exception {
        context.getSharedPreferences("monogram-call-privacy-0",0).edit().putString(OWNER+"|incoming","broken JSON")
                .putString(OWNER+"|p2p","{\"version\":1,\"rules\":[{\"type\":99,\"ids\":[]}]}").commit();
        MonogramCallPrivacy privacy=helper(0,OWNER);
        assertFalse(privacy.allowIncoming(20,true));assertFalse(privacy.allowDirect(20,true));
        assertTrue(((TL_account.privacyRules)privacy.handle(get(false))).rules.get(0) instanceof TLRPC.TL_privacyValueDisallowAll);
    }
}
