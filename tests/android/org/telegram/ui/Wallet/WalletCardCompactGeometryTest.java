package org.telegram.ui.Wallet;
import org.junit.Test;
import static org.junit.Assert.*;
public class WalletCardCompactGeometryTest {
 @Test public void fullSizeGeometryIsUnchanged(){
  for(float value:new float[]{-1f,-.593561f,0f,.0125f,.618561f,1f}){
   assertEquals(value,WalletCardGeometry.compactY(value,1f),0f);
   assertEquals(value,WalletCardGeometry.compactZ(value,1f),0f);
  }
 }
 @Test public void compactBoundsAndCornerArcsRemainConsistent(){
  for(float ratio:new float[]{.065f,.15f,.25f,.5f,.8f}){
   assertEquals(WalletCardGeometry.TOP*ratio,WalletCardGeometry.compactY(WalletCardGeometry.TOP,ratio),.000001f);
   assertEquals(WalletCardGeometry.BOTTOM*ratio,WalletCardGeometry.compactY(WalletCardGeometry.BOTTOM,ratio),.000001f);
   assertEquals(1f,WalletCardGeometry.compactZ(1f,ratio),.000001f);
   assertEquals(-1f,WalletCardGeometry.compactZ(-1f,ratio),.000001f);
   float radius=WalletCardGeometry.compactRadius(ratio);
   float yArc=WalletCardGeometry.compactY(WalletCardGeometry.TOP,ratio)-WalletCardGeometry.compactY(WalletCardGeometry.TOP-.15f,ratio);
   float zArc=WalletCardGeometry.compactZ(1f,ratio)-WalletCardGeometry.compactZ(.85f,ratio);
   assertEquals(radius,yArc,.000001f);assertEquals(radius,zArc,.000001f);
  }
 }
 @Test public void shorteningDoesNotInvertOrFoldTheMesh(){
  for(float ratio:new float[]{.065f,.15f,.25f,.5f}){
   float lastY=Float.NEGATIVE_INFINITY,lastZ=Float.NEGATIVE_INFINITY;
   for(int i=0;i<=1000;i++){
    float y=WalletCardGeometry.BOTTOM+(WalletCardGeometry.TOP-WalletCardGeometry.BOTTOM)*i/1000f;
    float mappedY=WalletCardGeometry.compactY(y,ratio),mappedZ=WalletCardGeometry.compactZ(-1f+2f*i/1000f,ratio);
    assertTrue(mappedY>=lastY);assertTrue(mappedZ>=lastZ);lastY=mappedY;lastZ=mappedZ;
   }
  }
 }
}
