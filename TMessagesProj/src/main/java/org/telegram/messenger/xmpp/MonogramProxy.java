// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.utils.proxy.ProxySettings;
import org.jivesoftware.smack.proxy.ProxyInfo;

/** Apply the existing proxy selection before any XMPP login or registration connection. */
public final class MonogramProxy {
    private MonogramProxy(){}
    public static final class ConfigurationException extends IllegalStateException {
        private ConfigurationException(String message){super(message);}
    }
    public static ProxyInfo current(){
        SharedConfig.loadProxyList();
        if(!MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled",false))return null;
        SharedConfig.ProxyInfo selected=SharedConfig.currentProxy;
        if(selected==null || !selected.settings.isValid())throw new ConfigurationException("Choose a valid SOCKS5 proxy in Proxy settings.");
        ProxySettings proxy=selected.settings;
        if(proxy.getType()!=ProxySettings.Type.SOCKS5)throw new ConfigurationException("XMPP requires a SOCKS5 proxy. Telegram MTProto and Web proxies cannot carry this connection.");
        return ProxyInfo.forSocks5Proxy(proxy.getAddress(),proxy.getPort(),proxy.getUser(),proxy.getPassword());
    }
}
