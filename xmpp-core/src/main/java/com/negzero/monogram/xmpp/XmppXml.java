// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import org.jivesoftware.smack.packet.Element;
import org.jivesoftware.smack.packet.XmlEnvironment;
import org.jivesoftware.smack.util.XmlStringBuilder;

public final class XmppXml {
    private XmppXml() {}

    public static String string(Element element) {
        CharSequence xml=element.toXML();
        // Smack's plain toString() repeatedly scans nested builders for every character.
        return xml instanceof XmlStringBuilder
                ? ((XmlStringBuilder)xml).toXML(XmlEnvironment.EMPTY).toString() : xml.toString();
    }
}
