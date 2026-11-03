// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Set;
import org.jivesoftware.smack.packet.StandardExtensionElement;

/** Read/modify/verify our PEP list without reconstructing another client's device entries. */
final class Omemo2DeviceList {
    interface Store {
        StandardExtensionElement read() throws Exception;
        void publish(StandardExtensionElement value) throws Exception;
    }
    static Set<Integer> ids(StandardExtensionElement root) throws GeneralSecurityException { return ids(root,Omemo2Bundle.NS); }
    static Set<Integer> ids(StandardExtensionElement root,String namespace) throws GeneralSecurityException {
        Set<Integer> ids=new HashSet<>();
        if(root==null)return ids;
        if(!"devices".equals(root.getElementName()) || !namespace.equals(root.getNamespace()) || root.toXML().length()>65536)throw Omemo2Bundle.invalid();
        for(StandardExtensionElement child:root.getElements()) {
            if(!"device".equals(child.getElementName()) || !namespace.equals(child.getNamespace()))continue;
            if(ids.size()>=500 || !ids.add(Omemo2Bundle.positive(child.getAttributeValue("id"))))throw Omemo2Bundle.invalid();
        }
        return ids;
    }
    static StandardExtensionElement addOwn(StandardExtensionElement root,int own) throws GeneralSecurityException { return addOwn(root,own,Omemo2Bundle.NS); }
    static StandardExtensionElement addOwn(StandardExtensionElement root,int own,String namespace) throws GeneralSecurityException {
        if(own<=0)throw Omemo2Bundle.invalid();
        Set<Integer> ids=ids(root,namespace);
        if(ids.contains(own))return root;
        if(ids.size()>=500)throw Omemo2Bundle.invalid();
        StandardExtensionElement.Builder result=StandardExtensionElement.builder("devices",namespace);
        if(root!=null) {
            result.addAttributes(root.getAttributes());
            if(root.getText()!=null)result.setText(root.getText());
            for(StandardExtensionElement child:root.getElements())result.addElement(child);
        }
        result.addElement(StandardExtensionElement.builder("device",namespace).addAttribute("id",Integer.toString(own)).build());
        StandardExtensionElement merged=result.build();ids(merged,namespace);return merged;
    }
    static StandardExtensionElement announce(Store store,int own) throws Exception { return announce(store,own,Omemo2Bundle.NS); }
    static StandardExtensionElement announce(Store store,int own,String namespace) throws Exception {
        if(own<=0)throw Omemo2Bundle.invalid();
        StandardExtensionElement latest=store.read();
        // PEP has no compare-and-swap. Re-read after each write; own-account events repair later races.
        for(int attempt=0;attempt<3;attempt++) {
            if(ids(latest,namespace).contains(own))return latest;
            store.publish(addOwn(latest,own,namespace));
            latest=store.read();
        }
        if(ids(latest,namespace).contains(own))return latest;
        throw new GeneralSecurityException("OMEMO device list changed repeatedly. Reconnect to announce this device safely.");
    }
}
