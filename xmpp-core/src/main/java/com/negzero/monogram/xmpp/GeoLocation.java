// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.Locale;
import java.util.regex.*;

/** RFC 5870 WGS84 point, carried inside the authenticated message body, never public OMEMO metadata. */
public final class GeoLocation {
    private static final Pattern URI=Pattern.compile("(?i)geo:([+-]?[0-9]+(?:\\.[0-9]+)?),([+-]?[0-9]+(?:\\.[0-9]+)?)(?:,[+-]?[0-9]+(?:\\.[0-9]+)?)?(?:;crs=wgs84)?(?:;u=([0-9]+(?:\\.[0-9]+)?))?");
    public final double latitude,longitude,uncertainty;
    public final String title,address;
    public GeoLocation(double latitude,double longitude,double uncertainty,String title,String address) {
        if(Double.isNaN(latitude) || Double.isInfinite(latitude) || Double.isNaN(longitude) || Double.isInfinite(longitude) || Double.isNaN(uncertainty) || Double.isInfinite(uncertainty) || Math.abs(latitude)>90 || Math.abs(longitude)>180 || uncertainty<0 || uncertainty>10000000) throw new IllegalArgumentException("Invalid geographic point");
        this.latitude=latitude;this.longitude=longitude;this.uncertainty=uncertainty;
        this.title=label(title);this.address=label(address);
    }
    private static String label(String text) {
        if(text==null) return "";String value=text.replace('\r',' ').replace('\n',' ').trim();
        if(value.length()>512) throw new IllegalArgumentException("Location label is too long");return value;
    }
    public String wire() {
        String point=String.format(Locale.ROOT,"geo:%.7f,%.7f",latitude,longitude)+(uncertainty>0?String.format(Locale.ROOT,";u=%.1f",uncertainty):"");
        return title.isEmpty()?point:point+"\n"+title+(address.isEmpty()?"":"\n"+address);
    }
    public static GeoLocation parse(String body) {
        if(body==null || body.length()>1200) return null;String[] lines=body.trim().split("\n",-1);if(lines.length>3) return null;
        Matcher match=URI.matcher(lines[0].trim());if(!match.matches()) return null;
        try { return new GeoLocation(Double.parseDouble(match.group(1)),Double.parseDouble(match.group(2)),match.group(3)==null?0:Double.parseDouble(match.group(3)),lines.length>1?lines[1]:"",lines.length>2?lines[2]:""); }
        catch(IllegalArgumentException invalid) { return null; }
    }
}
