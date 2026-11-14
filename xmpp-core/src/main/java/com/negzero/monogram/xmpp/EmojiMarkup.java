// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;
import java.io.IOException;
import java.util.*;
import java.security.MessageDigest;
import org.jivesoftware.smack.packet.*;
/** XEP-0514 spans and XEP-0447 media; only resolves hash-verified, supplied BoB bytes. */
public final class EmojiMarkup {
 public static final String NAMESPACE="urn:xmpp:markup:emoji:0";
 private static final String MARKUP=MessageMarkup.NAMESPACE,SFS=FileShare.NAMESPACE,FILE=FileShare.METADATA,HASH=FileShare.HASHES,URL=FileShare.URL_DATA;
 private EmojiMarkup(){}
 private static StandardExtensionElement.Builder element(String name,String ns){return StandardExtensionElement.builder(name,ns);}
 private static String digest(InlineEmoji.Asset asset)throws Exception{return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(asset.data()));}
 private static StandardExtensionElement hash(String value){return element("hash",HASH).addAttribute("algo","sha-256").setText(value).build();}
 public static void append(Message message,InlineEmoji model) {
  try {
   StandardExtensionElement.Builder markup=element("markup",MARKUP);
   for(ExtensionElement old:message.getExtensions("markup",MARKUP))for(StandardExtensionElement child:InlineEmoji.element(old).getElements())markup.addElement(child);
   Set<String> seen=new HashSet<>();
   for(InlineEmoji.Range range:model.ranges) {
    String digest=digest(range.asset);
    markup.addElement(element("span",MARKUP).addAttribute("start",Integer.toString(range.start)).addAttribute("end",Integer.toString(range.end))
      .addElement(element("emoji",NAMESPACE).addElement(hash(digest)).build()).build());
    if(seen.add(range.asset.cid))message.addExtension(element("file-sharing",SFS).addAttribute("disposition","inline")
     .addElement(element("file",FILE).addElement(element("media-type",FILE).setText(range.asset.mime).build())
       .addElement(element("size",FILE).setText(Integer.toString(range.asset.size())).build()).addElement(hash(digest)).build())
     .addElement(element("sources",SFS).addElement(element("url-data",URL).addAttribute("target","cid:"+range.asset.cid).build()).build()).build());
   }
   message.removeExtension("markup",MARKUP);message.addExtension(markup.build());
  }catch(Exception invalid){throw new IllegalArgumentException("Invalid emoji markup",invalid);}
 }
 public static boolean present(Message message)throws Exception {
  for(ExtensionElement extension:message.getExtensions("markup",MARKUP))for(StandardExtensionElement span:InlineEmoji.element(extension).getElements())
   if(span.getFirstElement("emoji",NAMESPACE)!=null)return true;
  return false;
 }
 public static InlineEmoji read(Message message,String body,Map<String,InlineEmoji.Asset> assets)throws Exception {
  List<ExtensionElement> markups=message.getExtensions("markup",MARKUP);if(markups.size()!=1)throw new IOException("Duplicate emoji markup");
  Map<String,InlineEmoji.Asset> media=new HashMap<>();int files=0;
  for(ExtensionElement ext:message.getExtensions("file-sharing",SFS)) {
   if(++files>16)throw new IOException("Too many emoji files");
   StandardExtensionElement sharing=InlineEmoji.element(ext),file=sharing.getFirstElement("file",FILE),sources=sharing.getFirstElement("sources",SFS);
   if(file==null||sources==null)continue;
   for(StandardExtensionElement source:sources.getElements()) {
    if(!source.getElementName().equals("url-data")||!source.getNamespace().equals(URL))continue;
    String target=source.getAttributeValue("target");InlineEmoji.Asset asset=target!=null&&target.startsWith("cid:")?assets.get(target.substring(4)):null;if(asset==null)continue;
    StandardExtensionElement type=file.getFirstElement("media-type",FILE),size=file.getFirstElement("size",FILE);
    if(type==null||size==null||!asset.mime.equals(type.getText())||!Integer.toString(asset.size()).equals(size.getText()))continue;
    String expected=digest(asset);for(StandardExtensionElement h:file.getElements())if(h.getElementName().equals("hash")&&h.getNamespace().equals(HASH)&&"sha-256".equals(h.getAttributeValue("algo"))&&expected.equals(h.getText()))media.put(expected,asset);
   }
  }
  List<InlineEmoji.Range> ranges=new ArrayList<>();int count=0;
  for(StandardExtensionElement span:InlineEmoji.element(markups.get(0)).getElements()) {
   if(++count>256)throw new IOException("Too many markup spans");StandardExtensionElement emoji=span.getFirstElement("emoji",NAMESPACE);if(emoji==null)continue;
   if(!span.getElementName().equals("span")||!span.getNamespace().equals(MARKUP)||ranges.size()>=64)throw new IOException("Invalid emoji span");
   InlineEmoji.Asset asset=null;for(StandardExtensionElement h:emoji.getElements())if(h.getElementName().equals("hash")&&h.getNamespace().equals(HASH)&&"sha-256".equals(h.getAttributeValue("algo"))) {asset=media.get(h.getText());if(asset!=null)break;}
   if(asset==null)throw new IOException("Missing verified emoji media");ranges.add(new InlineEmoji.Range(Integer.parseInt(span.getAttributeValue("start")),Integer.parseInt(span.getAttributeValue("end")),asset));
  }
  if(ranges.isEmpty())throw new IOException("Missing emoji spans");return new InlineEmoji(body,ranges);
 }
}
