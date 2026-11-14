// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import androidx.annotation.Keep;
import org.jivesoftware.smack.android.AndroidSmackInitializer;
import org.jivesoftware.smack.xml.SmackXmlParser;
import org.jivesoftware.smack.xml.xpp3.Xpp3XmlPullParserFactory;

/** Android worker context loaders need not expose application ServiceLoader resources. */
@Keep
public final class MonogramSmack {
    private static boolean parserRegistered;
    private MonogramSmack() {}

    /** All account/registration workers enter this monitor before first touching Smack config. */
    public static synchronized void initialize(Context context) {
        if (!parserRegistered) {
            SmackXmlParser.setXmlPullParserFactory(new Xpp3XmlPullParserFactory());
            parserRegistered = true;
        }
        AndroidSmackInitializer.initialize(context);
    }
}
