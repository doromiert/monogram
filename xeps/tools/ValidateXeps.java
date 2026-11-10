import java.io.*;
import java.nio.file.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.xml.sax.*;
public class ValidateXeps {
 public static void main(String[] args) throws Exception {
  Path tools=Path.of(args[0]).toAbsolutePath(),root=Path.of(args[1]);
  var factory=DocumentBuilderFactory.newInstance();factory.setValidating(true);factory.setNamespaceAware(false);
  factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"file");
  var builder=factory.newDocumentBuilder();
  builder.setEntityResolver((publicId,systemId)->{
   String name=systemId.substring(systemId.lastIndexOf('/')+1);
   if(!name.equals("xep.dtd")&&!name.equals("xep.ent"))throw new SAXException("Unexpected external resource: "+name);
   return new InputSource(tools.resolve(name).toUri().toString());
  });
  builder.setErrorHandler(new ErrorHandler(){
   public void warning(SAXParseException e)throws SAXException{throw e;}
   public void error(SAXParseException e)throws SAXException{throw e;}
   public void fatalError(SAXParseException e)throws SAXException{throw e;}
  });
  int count=0;
  try(var files=Files.walk(root)) {
   for(Path path:files.filter(p->p.getFileName().toString().startsWith("xep-")&&p.toString().endsWith(".xml")).sorted().toList()) {
    try {builder.parse(path.toFile());System.out.println("DTD valid: "+path);count++;}
    catch(Exception e){System.err.println(path+": "+e);throw e;}
   }
  }
  System.out.println("Validated "+count+" XEP drafts against pinned XSF DTD");
 }
}
