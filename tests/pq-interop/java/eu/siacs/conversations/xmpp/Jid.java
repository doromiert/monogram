// SPDX-License-Identifier: AGPL-3.0-only
// JVM interoperability fixture; never included in the APK.
package eu.siacs.conversations.xmpp;
public final class Jid {
 private final org.jxmpp.jid.Jid jid;
 private Jid(org.jxmpp.jid.Jid jid){this.jid=jid;}
 public static Jid of(String value){try{return new Jid(org.jxmpp.jid.impl.JidCreate.from(value));}catch(Exception e){throw new IllegalArgumentException(e);}}
 public Jid asBareJid(){return new Jid(jid.asBareJid());}
 public String toString(){return jid.toString();}
 public boolean equals(Object o){return o instanceof Jid && jid.equals(((Jid)o).jid);}
 public int hashCode(){return jid.hashCode();}
}
