package org.telegram.ui.Components;
import static org.junit.Assert.*;
import org.junit.Test;
public class MonogramFavoriteDragTest {
 @Test public void holdMoveReordersButScrollAndVisiblePreviewDoNot(){
  MonogramFavoriteDrag.Hold hold=new MonogramFavoriteDrag.Hold();hold.down(0,0,0);assertFalse(hold.move(100,1,1,8,500,false));assertTrue(hold.move(150,25,0,8,500,false));assertFalse(hold.move(200,40,0,8,500,false));
  hold.down(0,0,0);assertFalse(hold.move(50,25,0,8,500,false));assertFalse(hold.move(200,40,0,8,500,false));
  hold.down(0,0,0);assertFalse(hold.move(220,25,0,8,500,true));assertFalse(hold.move(300,40,0,8,500,false));
  hold.down(0,0,0);assertFalse(hold.move(500,25,0,8,500,false));assertFalse(hold.move(700,40,0,8,500,false));
 }
}
