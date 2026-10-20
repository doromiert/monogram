package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeoLocationTest {
    @Test void coordinatesAndUncertaintyRoundTripWithoutDependingOnLocale() {
        GeoLocation point=new GeoLocation(51.5,-.1234567,12.5,"A\nplace","An address");GeoLocation restored=GeoLocation.parse(point.wire());
        assertNotNull(restored);assertEquals(51.5,restored.latitude,1e-7);assertEquals(-.1234567,restored.longitude,1e-7);assertEquals(12.5,restored.uncertainty);assertEquals("A place",restored.title);assertEquals("An address",restored.address);
        assertEquals(0,GeoLocation.parse("geo:0,0;crs=wgs84;u=0").latitude);assertEquals(90,GeoLocation.parse("geo:90,180,300").latitude);
    }
    @Test void invalidOrEmbeddedCoordinatesRemainOrdinaryText() {
        for(String value:new String[]{"geo:91,1","geo:1,181","geo:NaN,0","geo:1,2;u=-1","geo:1,2;crs=other","See geo:1,2","geo:1,2\na\nb\nc","https://example.org/?q=geo:1,2"}) assertNull(GeoLocation.parse(value),value);
        assertThrows(IllegalArgumentException.class,() -> new GeoLocation(Double.NaN,0,0,null,null));
        assertThrows(IllegalArgumentException.class,() -> new GeoLocation(0,0,-1,null,null));
    }
}
