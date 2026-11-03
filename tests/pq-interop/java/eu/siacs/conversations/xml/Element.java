// SPDX-License-Identifier: AGPL-3.0-only
// Minimal JVM XML adapter for running the unmodified Monocles wire codec. Never APK code.
package eu.siacs.conversations.xml;

import java.io.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

public final class Element {
    private final String name;
    private String namespace, text;
    private final Map<String,String> attributes = new LinkedHashMap<>();
    private final List<Element> children = new ArrayList<>();
    public Element(String name) { this(name,null); }
    public Element(String name,String namespace) { this.name = name; this.namespace = namespace; }
    public String getName() { return name; }
    public String getNamespace() { return namespace; }
    public String getContent() { return text; }
    public String getAttribute(String name) { return attributes.get(name); }
    public Element setAttribute(String name,Object value) { attributes.put(name,String.valueOf(value)); return this; }
    public Element setContent(String value) { text = value; return this; }
    public List<Element> getChildren() { return children; }
    public Element addChild(String name) { return addChild(new Element(name,namespace)); }
    public Element addChild(Element child) {
        if (child.namespace == null) child.namespace = namespace; children.add(child); return child;
    }
    public Element findChild(String name) {
        for (Element child : children) if (child.name.equals(name)) return child; return null;
    }
    public String findChildContent(String name) { Element child = findChild(name); return child == null ? null : child.getContent(); }
    public static Element parse(InputStream input) throws IOException {
        try {
            DocumentBuilderFactory factory = factory();
            return read(factory.newDocumentBuilder().parse(input).getDocumentElement());
        } catch (Exception error) { throw new IOException("Invalid fixture XML",error); }
    }
    public static Element parse(String xml) throws IOException { return parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false); return factory;
    }
    private static Element read(org.w3c.dom.Element node) {
        Element result = new Element(node.getLocalName(),node.getNamespaceURI());
        for (int i = 0; i < node.getAttributes().getLength(); i++) {
            Node attr = node.getAttributes().item(i);
            if (!attr.getNodeName().startsWith("xmlns")) result.setAttribute(attr.getNodeName(),attr.getNodeValue());
        }
        StringBuilder text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof org.w3c.dom.Element) result.children.add(read((org.w3c.dom.Element)child));
            else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) text.append(child.getNodeValue());
        }
        if (text.length() > 0) result.text = text.toString(); return result;
    }
    private org.w3c.dom.Element dom(Document document) {
        org.w3c.dom.Element node = document.createElementNS(namespace,name);
        for (Map.Entry<String,String> attribute : attributes.entrySet()) node.setAttribute(attribute.getKey(),attribute.getValue());
        if (text != null) node.appendChild(document.createTextNode(text));
        for (Element child : children) node.appendChild(child.dom(document)); return node;
    }
    @Override public String toString() {
        try {
            Document document = factory().newDocumentBuilder().newDocument(); document.appendChild(dom(document));
            Transformer transformer = TransformerFactory.newInstance().newTransformer(); transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");
            StringWriter text = new StringWriter(); transformer.transform(new DOMSource(document),new StreamResult(text)); return text.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
