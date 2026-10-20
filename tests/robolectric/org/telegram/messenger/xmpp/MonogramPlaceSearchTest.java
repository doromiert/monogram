package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramPlaceSearchTest {
    @Test public void photonCoordinatesAreLongitudeFirstAndBecomeNativeVenueWithoutRemoteIcon() throws Exception {
        String feature="{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[13.4,52.5]},\"properties\":{\"name\":\"A place\",\"city\":\"Berlin\",\"country\":\"Germany\",\"osm_type\":\"N\",\"osm_id\":42}}";
        ArrayList<TLRPC.TL_messageMediaVenue> result=MonogramPlaceSearch.parse("{\"type\":\"FeatureCollection\",\"features\":["+feature+","+feature+"]}");
        assertEquals(1,result.size());assertEquals(52.5,result.get(0).geo.lat,0);assertEquals(13.4,result.get(0).geo._long,0);assertEquals("A place",result.get(0).title);assertEquals("Berlin, Germany",result.get(0).address);assertEquals("pin",result.get(0).icon);
    }
    @Test public void invalidCoordinatesAndUnexpectedProviderPayloadDoNotProduceFakeResults() throws Exception {
        assertTrue(MonogramPlaceSearch.parse("{\"type\":\"FeatureCollection\",\"features\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[999,999]},\"properties\":{\"name\":\"invalid\"}}]}").isEmpty());
        try { MonogramPlaceSearch.parse("{\"error\":\"rate limited\"}");fail(); } catch(Exception expected) { }
    }
}
