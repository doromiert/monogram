// SPDX-License-Identifier: AGPL-3.0-only
// JVM interoperability fixture; never included in the APK.
package eu.siacs.conversations.xml;
public final class XmlReader {
 private Element root; private boolean consumed;
 public void setInputStream(java.io.InputStream input) throws java.io.IOException {root=Element.parse(input);}
 public Tag readTag(){if(consumed)return null;consumed=true;return new Tag(root.getName());}
 public Element readElement(Tag tag){return root;}
}
