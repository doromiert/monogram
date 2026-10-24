// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;

/** Connection details. The service domain always comes from the JID, including with a host override. */
public final class Account {
    public final EntityBareJid jid;
    public final String host;
    public final int port;
    public final Resourcepart resource;
    public final org.jivesoftware.smack.proxy.ProxyInfo proxy;

    public Account(String jid, String host, int port, String resource) throws Exception {this(jid,host,port,resource,null);}
    public Account(String jid, String host, int port, String resource,org.jivesoftware.smack.proxy.ProxyInfo proxy) throws Exception {
        this.proxy=proxy;
        if (jid == null || jid.contains("/")) throw new IllegalArgumentException("Use a bare user@domain JID");
        this.jid = JidCreate.entityBareFrom(jid);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        this.host = host == null || host.isEmpty() ? null : host;
        this.port = port;
        this.resource = Resourcepart.from(resource);
    }
}
