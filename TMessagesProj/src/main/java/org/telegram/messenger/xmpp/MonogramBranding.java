package org.telegram.messenger.xmpp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.io.File;

public final class MonogramBranding {
    private MonogramBranding() {}
    /** Keep existing cache paths readable; fresh installations use Monogram folder names. */
    public static File mediaDirectory(File parent,String legacyName) {
        File legacy=new File(parent,legacyName);
        return legacy.isDirectory() ? legacy : new File(parent,legacyName.replace("Telegram","Monogram"));
    }

    /** Alpha mask shared by both round-video encoders. Keep the original curved corner placement. */
    public static Bitmap roundVideoWatermark(int size) {
        if(size<=0) throw new IllegalArgumentException("Watermark size must be positive");
        Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ALPHA_8);
        Canvas canvas=new Canvas(bitmap);
        canvas.scale(size/224f,size/224f);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        paint.setTextSize(28f);
        paint.setTextAlign(Paint.Align.CENTER);
        String name="MONOGRAM";
        for(int i=0;i<name.length();i++) {
            float angle=68f-46f*i/(name.length()-1);
            double radians=Math.toRadians(angle);
            float x=-75f+300f*(float)Math.cos(radians);
            float y=-75f+300f*(float)Math.sin(radians);
            canvas.save();
            canvas.translate(x,y);
            canvas.rotate(angle-90f);
            canvas.drawText(name,i,i+1,0,0,paint);
            canvas.restore();
        }
        return bitmap;
    }
}
