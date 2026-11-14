package org.telegram.ui.Components;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.commonmark.node.*;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MarkdownCurrencyTest {
 private Node parse(String text){
  io.noties.markwon.inlineparser.MarkwonInlineParserPlugin inline=io.noties.markwon.inlineparser.MarkwonInlineParserPlugin.create();
  io.noties.markwon.ext.latex.JLatexMathPlugin latex=io.noties.markwon.ext.latex.JLatexMathPlugin.create(18,builder->builder.inlinesEnabled(true));
  latex.configure(new io.noties.markwon.MarkwonPlugin.Registry(){
   public <P extends io.noties.markwon.MarkwonPlugin>P require(Class<P> plugin){if(plugin!=io.noties.markwon.inlineparser.MarkwonInlineParserPlugin.class)throw new IllegalStateException();return plugin.cast(inline);}
   public <P extends io.noties.markwon.MarkwonPlugin>void require(Class<P> plugin,io.noties.markwon.MarkwonPlugin.Action<? super P> action){action.apply(require(plugin));}
  });
  inline.factoryBuilder().addInlineProcessor(new MarkdownParser.SingleDollarLatexInlineProcessor());
  org.commonmark.parser.Parser.Builder builder=org.commonmark.parser.Parser.builder();inline.configureParser(builder);latex.configureParser(builder);return builder.build().parse(text);
 }
 private int math(Node node){int count=node instanceof io.noties.markwon.ext.latex.JLatexMathNode?1:0;for(Node child=node.getFirstChild();child!=null;child=child.getNext())count+=math(child);return count;}
 @Test public void amountsStayPlainWhileFormulasStillRender(){
  for(String source:new String[]{"She said $100 then later $20","Costs $100 and yours is $20.","$100 mine's 20$","$100\nthen later $20"})assertEquals(source,0,math(parse(source)));
  assertEquals(2,math(parse("A formula $2+2=4$ and $x^2$.")));
 }
}
