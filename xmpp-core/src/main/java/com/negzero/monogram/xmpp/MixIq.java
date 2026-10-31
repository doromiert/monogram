// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.Map;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;

/** IQ envelope for MIX's small command elements. Attribute/text escaping belongs to Smack. */
final class MixIq extends IQ {
    final StandardExtensionElement element;
    MixIq(StandardExtensionElement element) { super(element.getElementName(),element.getNamespace());this.element=element; }
    @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
        for(Map.Entry<String,String> attr:element.getAttributes().entrySet()) xml.attribute(attr.getKey(),attr.getValue());
        xml.rightAngleBracket();if(element.getText()!=null)xml.escape(element.getText());
        for(StandardExtensionElement child:element.getElements())xml.append(child.toXML(element.getNamespace()));
        return xml;
    }
    static void provider(String name,String namespace) {
        ProviderManager.addIQProvider(name,namespace,new IQProvider<MixIq>() {
            @Override public MixIq parse(org.jivesoftware.smack.xml.XmlPullParser parser,int depth,XmlEnvironment env)
                    throws java.io.IOException,org.jivesoftware.smack.xml.XmlPullParserException {
                return new MixIq(StandardExtensionElementProvider.INSTANCE.parse(parser,depth,env));
            }
        });
    }
}
