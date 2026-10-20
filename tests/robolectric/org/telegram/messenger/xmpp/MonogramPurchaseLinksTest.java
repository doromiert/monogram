package org.telegram.messenger.xmpp;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure synthetic URI fixtures. Prepared for the release gate; never opens a destination. */
public class MonogramPurchaseLinksTest {
    @Test public void paymentGiftBoostAndWalletAliasesAreBlocked() {
        for(String value:new String[]{
                "tg:premium_offer?ref=friend","TG://PREMIUM_MULTIGIFT","tg://giftcode/code",
                "https://t.me/$invoiceSlug","https://telegram.me/invoice/slug","https://telegram.dog/nft/gift",
                "tg://stargift_auction?slug=foo","tg://stargift_preview?slug=foo","tg://stars_topup?balance=20",
                "tg://stars","tg://send_gift?to=123","https://t.me/boost/channel","https://t.me/channel?boost",
                "https://sendgrams.t.me/?startapp=tonconnect-example","tg://resolve?domain=sendgrams&startapp=value",
                "tg://resolve?domain=PremiumBot","tg:sendgrams&amount=10","tg://ton",
                "ton://transfer/example?amount=20","tc://connect?secret=synthetic","tonconnect://connect?secret=synthetic"})
            assertTrue(value,MonogramPurchaseLinks.blocked(value));
    }
    @Test public void importsChatLocationsTranslationAndExternalSitesRemainUnrelated() {
        for(String value:new String[]{
                "https://t.me/addstickers/LocalPack","tg://addstickers?set=LocalPack","https://t.me/addemoji/EmojiPack",
                "tg://resolve?domain=alice","https://t.me/alice/123","https://alice.t.me/123",
                "xmpp:room@conference.example.org?join","xmpp:example.org?register;preauth=private",
                "geo:52.1,21.0","https://maps.example.org/?q=52,21","tg://settings/appearance",
                "tg://settings/appearance/stickers-and-emoji","tg://settings/language","tg://settings/privacy/last-seen",
                "https://example.org/invoice/slug","https://t.me.evil.example/invoice/slug","tonsite://ordinary-site",
                "tg://addstickers?set=invoice","https://t.me/addstickers/premium","https://example.org/?url=tg://stars",
                "mailto:premium@example.org","https://t.me/alice?start=stars","not a link"})
            assertFalse(value,MonogramPurchaseLinks.blocked(value));
        assertFalse(MonogramPurchaseLinks.blocked(null));
    }
    @Test public void nativeSettingsMonetaryRoutesAreBlockedWithoutDisablingOtherSettings() {
        for(String value:new String[]{"tg://settings/premium","tg:settings/stars/top-up","tg://settings/business",
                "tg://settings/ton","tg://settings/send-gift","tg://settings/privacy/gifts/show-icon",
                "tg://settings/privacy/paid-messages","tg://settings?section=stars"})
            assertTrue(value,MonogramPurchaseLinks.blocked(value));
        for(String value:new String[]{"tg://settings/privacy/phone-number","tg://settings/data/automatic-download",
                "tg://settings/appearance/translation","tg://settings/privacy-policy","tg://settings?section=appearance"})
            assertFalse(value,MonogramPurchaseLinks.blocked(value));
    }
    @Test public void encodedCommandsAndMalformedQueryValuesCannotBypassKnownRoutes() {
        for(String value:new String[]{"https://t.me/%69nvoice/slug","tg://%70remium_offer?ref=synthetic",
                "tg://resolve?domain=%73endgrams","tg://stars?label=space here","tg://invoice?slug=100%strange",
                "tg://premium_offer?ref=<synthetic>","https://t.me/invoice/slug?note={synthetic}"})
            assertTrue(value,MonogramPurchaseLinks.blocked(value));
        assertFalse(MonogramPurchaseLinks.blocked("https://example.org/%69nvoice/slug?note=space here"));
        assertTrue(MonogramPurchaseLinks.blocked("tg://invoice?slug="+new String(new char[70000]).replace("\0","x")));
    }
}
