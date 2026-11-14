// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;
import android.graphics.*;import android.graphics.drawable.Drawable;import org.telegram.messenger.*;
/** The same pending-art indicator is used in sticker cells, emoji cells and pack icons. */
public final class MonogramSyncPlaceholder extends Drawable {
 private final java.lang.ref.WeakReference<ImageReceiver> owner;private int alpha=255;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
 public MonogramSyncPlaceholder(ImageReceiver receiver){owner=new java.lang.ref.WeakReference<>(receiver);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(0xffb0b0b0);}
 public static boolean pending(ImageLocation... locations){for(ImageLocation location:locations)if(location!=null&&location.document!=null&&location.document.monogramSyncPending)return true;return false;}
 @Override public void draw(Canvas canvas){Rect b=getBounds();float r=Math.min(b.width(),b.height())*.32f,cx=b.exactCenterX(),cy=b.exactCenterY();paint.setStrokeWidth(Math.max(1,r*.10f));paint.setAlpha(80*alpha/255);canvas.drawCircle(cx,cy,r,paint);paint.setAlpha(230*alpha/255);canvas.drawArc(cx-r,cy-r,cx+r,cy+r,(android.os.SystemClock.uptimeMillis()%1200)*.3f,95,false,paint);canvas.drawLine(cx,cy-r*.38f,cx,cy+r*.35f,paint);canvas.drawLine(cx-r*.22f,cy+r*.12f,cx,cy+r*.35f,paint);canvas.drawLine(cx+r*.22f,cy+r*.12f,cx,cy+r*.35f,paint);ImageReceiver receiver=owner.get();if(receiver!=null)AndroidUtilities.runOnUIThread(receiver::invalidate,32);}
 @Override public void setAlpha(int alpha){this.alpha=alpha;}@Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 @Override public int getIntrinsicWidth(){return 64;}@Override public int getIntrinsicHeight(){return 64;}
}
