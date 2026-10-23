// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import com.negzero.monogram.xmpp.GeoLocation;
import org.telegram.tgnet.*;

/** Native static locations reuse the existing protected text/outbox pipeline in every XMPP encryption mode. */
final class MonogramLocation {
    private MonogramLocation() { }
    static boolean supports(TLObject request) {
        if(!(request instanceof TLRPC.TL_messages_sendMedia)) return false;
        TLRPC.InputMedia media=((TLRPC.TL_messages_sendMedia)request).media;
        return media instanceof TLRPC.TL_inputMediaGeoPoint || media instanceof TLRPC.TL_inputMediaVenue;
    }
    static TLRPC.TL_messages_sendMessage text(TLRPC.TL_messages_sendMedia source) {
        if(!supports(source) || source.media.geo_point==null || source.media.geo_point instanceof TLRPC.TL_inputGeoPointEmpty) throw new IllegalArgumentException("Invalid location");
        TLRPC.InputMedia media=source.media;
        GeoLocation location=new GeoLocation(media.geo_point.lat,media.geo_point._long,(media.geo_point.flags&1)!=0?media.geo_point.accuracy_radius:0,
                media instanceof TLRPC.TL_inputMediaVenue?media.title:null,media instanceof TLRPC.TL_inputMediaVenue?media.address:null);
        TLRPC.TL_messages_sendMessage result=new TLRPC.TL_messages_sendMessage();
        result.peer=source.peer;result.random_id=source.random_id;result.reply_to=source.reply_to;
        result.silent=source.silent;result.background=source.background;result.clear_draft=source.clear_draft;
        result.schedule_date=source.schedule_date;result.message=location.wire();result.no_webpage=true;result.flags=source.reply_to==null?0:1;
        com.negzero.monogram.xmpp.ForwardedContent forward=com.negzero.monogram.xmpp.ForwardedContent.parse(source.message);
        if(forward!=null) result.message=com.negzero.monogram.xmpp.ForwardedContent.of(forward.name,forward.date,result.message).wireBody();
        return result;
    }
    static void decorate(TLRPC.Message message) {
        if(message.media!=null && !(message.media instanceof TLRPC.TL_messageMediaEmpty)) return;
        GeoLocation location=GeoLocation.parse(message.message);if(location==null) return;
        TLRPC.MessageMedia media=location.title.isEmpty()?new TLRPC.TL_messageMediaGeo():new TLRPC.TL_messageMediaVenue();
        TLRPC.TL_geoPoint point=new TLRPC.TL_geoPoint();point.lat=location.latitude;point._long=location.longitude;
        if(location.uncertainty>0) { point.flags|=1;point.accuracy_radius=(int)Math.ceil(location.uncertainty); }
        media.geo=point;
        if(media instanceof TLRPC.TL_messageMediaVenue) { media.title=location.title;media.address=location.address;media.provider="";media.venue_id="";media.venue_type=""; }
        message.media=media;message.flags|=512;message.entities.clear(); // Keep canonical geo body for copy/reply excerpts and durable resend.
    }
}
