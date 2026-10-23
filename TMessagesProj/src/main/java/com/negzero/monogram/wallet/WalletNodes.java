// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import com.negzero.monogram.xmpp.MoneroPayment;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Bundled choices only: loading this catalogue performs no discovery or network I/O. */
public final class WalletNodes {
    private WalletNodes() {}
    public static final class Node {
        public final String name,url;
        private Node(String name,String url){this.name=name;this.url=url;}
    }
    // Operator sources and verification date: docs/MONERO_PUBLIC_NODES.md.
    private static final List<Node> MAINNET=Collections.unmodifiableList(Arrays.asList(
        new Node("Cake Wallet", "https://xmr-node.cakewallet.com:18081"),
        new Node("Seth For Privacy", "https://node.sethforprivacy.com"),
        new Node("Monero.one · Europe", "https://eu.node.monero.one"),
        new Node("Monero.one · United States", "https://us.node.monero.one")
    ));
    public static List<Node> forNetwork(MoneroPayment.Network network){return network==MoneroPayment.Network.MAINNET ? MAINNET : Collections.emptyList();}
    public static String label(String url,MoneroPayment.Network network){for(Node node:forNetwork(network))if(node.url.equals(url))return node.name;return url;}
}
