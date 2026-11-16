package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramInvitationResolutionTest {
 @Test public void acceptedBookmarksRemoveOnlyTheirPendingInvitesAndAreIdempotent() throws Exception {
  JSONObject invites=new JSONObject().put("accepted@rooms.example",new JSONObject().put("inviter","a@example"))
    .put("pending@rooms.example",new JSONObject().put("inviter","b@example"));
  assertFalse(MonogramController.removeAcceptedInvitations(invites,Collections.emptyList()));
  assertTrue(MonogramController.removeAcceptedInvitations(invites,Arrays.asList("accepted@rooms.example","unknown@rooms.example")));
  assertFalse(invites.has("accepted@rooms.example"));assertTrue(invites.has("pending@rooms.example"));
  assertFalse(MonogramController.removeAcceptedInvitations(invites,Collections.singletonList("accepted@rooms.example")));
 }
}
