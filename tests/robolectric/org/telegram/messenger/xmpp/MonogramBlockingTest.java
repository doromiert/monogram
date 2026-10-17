package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.Collections;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramBlockingTest {
    private Context context;private SharedPreferences prefs;private MonogramStore store;private long self;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("blocking-cache.db");
        prefs=context.getSharedPreferences("blocking-cache",0);prefs.edit().clear().commit();
        store=new MonogramStore(context,"blocking-cache.db");self=store.peer("alice@example.org");
    }
    @After public void close() { store.close();context.deleteDatabase("blocking-cache.db"); }
    private MonogramBlocking cache(String owner) { return new MonogramBlocking(prefs,store,owner,self); }
    @Test public void accountScopedCacheKeepsExactDomainAndResourceEntriesAndPagesNativeUsers() throws Exception {
        MonogramBlocking cache=cache("alice@example.org");assertNull(cache.cached());
        cache.remember(Arrays.asList("bob@example.org","blocked.example","carol@example.org/phone","bob@example.org"));
        assertEquals(3,cache.cached().size());assertNull(cache("other@example.org").cached());
        assertTrue(cache.blocked("bob@example.org/laptop"));assertTrue(cache.blocked("user@blocked.example/phone"));
        assertTrue(cache.blocked("carol@example.org/phone"));assertFalse(cache.blocked("carol@example.org"));
        assertFalse(cache.blocked("carol@example.org/laptop"));assertFalse(cache.blocked("blocked.example.attacker.org"));
        TLRPC.TL_contacts_blockedSlice first=cache.page(cache.cached(),0,2),last=cache.page(cache.cached(),2,2);
        assertEquals(3,first.count);assertEquals(2,first.blocked.size());assertEquals(1,last.blocked.size());
        assertEquals("carol@example.org/phone",store.jid(last.users.get(0).id));
        assertEquals(last.users.get(0).id,last.blocked.get(0).peer_id.user_id);
        assertEquals(Collections.emptyList(),cache.page(cache.cached(),99,20).blocked);
        cache=cache("alice@example.org");assertEquals(3,cache.cached().size());
        TLRPC.TL_inputPeerUser input=new TLRPC.TL_inputPeerUser();input.user_id=last.users.get(0).id;
        assertEquals("carol@example.org/phone",cache.target(input));
    }
    @Test public void rejectsInvalidCacheSelfGroupAndInvalidPagesWithoutChangingSavedSnapshot() throws Exception {
        MonogramBlocking cache=cache("alice@example.org");cache.remember(Collections.singletonList("bob@example.org"));
        assertThrows(IllegalArgumentException.class,() -> cache.remember(Collections.singletonList("")));
        assertEquals(Collections.singletonList("bob@example.org"),cache.cached());
        TLRPC.TL_inputPeerUser selfInput=new TLRPC.TL_inputPeerUser();selfInput.user_id=self;
        assertThrows(IllegalArgumentException.class,() -> cache.target(selfInput));
        assertThrows(IllegalArgumentException.class,() -> cache.target(new TLRPC.TL_inputPeerChat()));
        assertThrows(IllegalArgumentException.class,() -> cache.page(cache.cached(),-1,20));
        prefs.edit().putString(MonogramBlocking.key("alice@example.org"),"broken").commit();assertNull(cache.cached());
        cache.remember(Collections.emptyList());assertEquals(Collections.emptyList(),cache.cached());
    }
}
