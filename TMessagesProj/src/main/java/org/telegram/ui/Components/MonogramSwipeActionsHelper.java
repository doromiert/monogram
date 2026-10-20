// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.View;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.ArrayList;
import java.util.List;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MediaDataController;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.xmpp.MonogramChatBehavior;
import org.telegram.messenger.xmpp.MonogramSwipeAction;
import org.telegram.ui.ActionBar.Theme;

/** The supplied exteraGram APK's vertical swipe selector, adapted to native Monogram actions. */
public final class MonogramSwipeActionsHelper {
    private static final float SPRING_UNIT=2000f;
    private final View parent;
    private final int account;
    private final Theme.ResourcesProvider resourcesProvider;
    private final ArrayList<MonogramSwipeAction> actions=new ArrayList<>();
    private final FloatValueHolder position=new FloatValueHolder(),visibility=new FloatValueHolder(),fill=new FloatValueHolder(),ring=new FloatValueHolder();
    private final SpringAnimation positionSpring,visibilitySpring,fillSpring,ringSpring;
    private final AnimatedFloat liveAlpha;
    private final Paint outline=new Paint(Paint.ANTI_ALIAS_FLAG),darkOutline=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final Path path=new Path();
    private final SparseArray<Drawable> icons=new SparseArray<>(),spareIcons=new SparseArray<>();
    private final SparseArray<Float> iconFits=new SparseArray<>();
    private Drawable reactionDrawable;
    private ImageReceiver reactionImage;
    private String reactionEmoji;
    private PorterDuffColorFilter iconFilter;
    private int iconColor,selected,slot,direction;
    private float anchorY,lastX,lastY,driftX,driftY;
    private boolean armed,stepped,scrubbing,looped,reversed,beyondMax;

    public MonogramSwipeActionsHelper(View parent,int account,Theme.ResourcesProvider resourcesProvider) {
        this.parent=parent;this.account=account;this.resourcesProvider=resourcesProvider;
        positionSpring=spring(position,430f,.55f,false);
        visibilitySpring=spring(visibility,1500f,1f,true).setMaxValue(SPRING_UNIT);
        fillSpring=spring(fill,400f,.5f,true);ringSpring=spring(ring,200f,1f,true);
        liveAlpha=new AnimatedFloat(parent::invalidate,0,280,CubicBezierInterpolator.EASE_OUT);
        for(Paint paint:new Paint[]{outline,darkOutline}) {
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(AndroidUtilities.dp(2));
        }
    }
    private SpringAnimation spring(FloatValueHolder value,float stiffness,float damping,boolean positive) {
        SpringAnimation animation=new SpringAnimation(value).setSpring(new SpringForce(0).setStiffness(stiffness).setDampingRatio(damping));
        if(positive) animation.setMinValue(0);
        animation.addUpdateListener((ignored,v,velocity) -> parent.invalidate());return animation;
    }
    public void setReaction(String emoji) {
        if(java.util.Objects.equals(reactionEmoji,emoji)) return;
        if(reactionImage!=null) { reactionImage.onDetachedFromWindow();reactionImage=null; }
        reactionEmoji=emoji;reactionDrawable=emoji==null ? null : Emoji.getEmojiDrawable(emoji);
        TLRPC.TL_availableReaction reaction=emoji==null ? null : MediaDataController.getInstance(account).getReactionsMap().get(emoji);
        if(reaction!=null && reaction.center_icon!=null) {
            reactionImage=new ImageReceiver(parent);reactionImage.ignoreNotifications=true;reactionImage.onAttachedToWindow();
            reactionImage.setImage(ImageLocation.getForDocument(reaction.center_icon),"40_40_lastreactframe",DocumentObject.getSvgThumb(reaction.static_icon,Theme.key_windowBackgroundGray,1f),"webp",reaction,1);
        }
    }
    public void start(List<MonogramSwipeAction> enabled) {
        actions.clear();actions.addAll(enabled);looped=MonogramChatBehavior.swipeLoop(account);reversed=MonogramChatBehavior.swipeReversed(account);
        selected=slot=direction=0;armed=stepped=scrubbing=beyondMax=false;anchorY=lastX=lastY=driftX=driftY=0;liveAlpha.set(0,true);
        reset(positionSpring,position);reset(visibilitySpring,visibility);reset(fillSpring,fill);reset(ringSpring,ring);
    }
    private static void reset(SpringAnimation animation,FloatValueHolder value) {
        animation.cancel();animation.getSpring().setFinalPosition(0);value.setValue(0);
    }
    public void detach() {
        positionSpring.cancel();visibilitySpring.cancel();fillSpring.cancel();ringSpring.cancel();reactionDrawable=null;reactionEmoji=null;
        if(reactionImage!=null) { reactionImage.onDetachedFromWindow();reactionImage=null; }
    }
    public int size() { return actions.size(); }
    public MonogramSwipeAction getSelected() { return selected>=0 && selected<actions.size() ? actions.get(selected) : null; }
    public void update(float offset,float x,float y) {
        if(actions.size()<2) return;
        if(Math.abs(offset)<AndroidUtilities.dp(50)) { armed=scrubbing=false;return; }
        if(!armed) { armed=true;lastX=x;lastY=y;driftX=driftY=0;return; }
        float dx=Math.abs(x-lastX),dy=y-lastY;lastX=x;lastY=y;
        if(!scrubbing) {
            driftX=driftX*.7f+dx*.3f;driftY=driftY*.7f+Math.abs(dy)*.3f;
            if(driftY<AndroidUtilities.dp(.5f) || driftY<=driftX*1.5f) return;
            scrubbing=true;anchorY=y;return;
        }
        float distance=(y-anchorY)*(direction<0 ? -1 : 1)*(reversed ? -1 : 1);
        float step=AndroidUtilities.dp(stepped ? 36 : 44);
        if(distance>=step) { anchorY=y;stepped=true;if(looped || selected<actions.size()-1) move(1); }
        else if(distance<=-step) { anchorY=y;stepped=true;if(looped || selected>0) move(-1); }
    }
    private void move(int step) {
        slot+=step;selected=floorMod(slot,actions.size());
        try { parent.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK,HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING); } catch(Exception ignored) { }
        positionSpring.animateToFinalPosition(slot*SPRING_UNIT);parent.invalidate();
    }
    private static int floorMod(int value,int divisor) { return (value%divisor+divisor)%divisor; }
    public void select(int index) {
        scrubbing=true;if(index<0 || index>=actions.size() || index==selected) return;
        int distance=index-selected;
        if(looped) { distance=floorMod(distance,actions.size());if(distance>actions.size()/2) distance-=actions.size(); }
        selected=index;slot+=distance;positionSpring.animateToFinalPosition(slot*SPRING_UNIT);parent.invalidate();
    }
    public void draw(Canvas canvas,float offset,boolean outgoing,float centerY,float bubbleRight) {
        if(actions.isEmpty() || Looper.myLooper()!=Looper.getMainLooper()) return;
        if(direction==0) {
            float needed=AndroidUtilities.dp(44)+(actions.size()-2)*AndroidUtilities.dp(36);
            float below=parent.getHeight()-centerY;direction=below>=needed || below>=centerY ? 1 : -1;
        }
        Paint background=Theme.getThemePaint(Theme.key_paint_chatActionBackground,resourcesProvider);
        Paint dark=Theme.chat_actionBackgroundGradientDarkenPaint;
        outline.setColor(background.getColor());outline.setShader(background.getShader());darkOutline.setColor(dark.getColor());darkOutline.setShader(dark.getShader());
        if(fill.getValue()>SPRING_UNIT) beyondMax=true;
        if(visibility.getValue()==0) { reset(fillSpring,fill);reset(ringSpring,ring);beyondMax=false; }
        boolean filled=fillSpring.getSpring().getFinalPosition()==SPRING_UNIT;
        float progress=filled ? 1 : MathUtils.clamp((-offset-AndroidUtilities.dp(20))/AndroidUtilities.dp(30),0,1);
        if(progress==1 && !filled) { fillSpring.animateToFinalPosition(SPRING_UNIT);ringSpring.animateToFinalPosition(SPRING_UNIT); }
        float target=offset<=-AndroidUtilities.dp(20) ? SPRING_UNIT : 0;
        if(visibilitySpring.getSpring().getFinalPosition()!=target) visibilitySpring.animateToFinalPosition(target);
        float visible=visibility.getValue()/SPRING_UNIT,filledValue=fill.getValue()/SPRING_UNIT;
        float width=parent.getMeasuredWidth();
        float x=Math.max(width+offset*(outgoing?.5f:1f),Math.min((bubbleRight+width)/2,width));
        float scale=beyondMax ? filledValue : visible,hole=beyondMax ? 0 : 1-filledValue;
        float cursor=position.getValue()/SPRING_UNIT,spacing=AndroidUtilities.dp(36)*direction,others=liveAlpha.set(scrubbing);
        int first=(int)Math.ceil(cursor-2),last=(int)Math.floor(cursor+2);
        if(!looped || actions.size()<2) { first=Math.max(first,0);last=Math.min(last,actions.size()-1); }
        for(int i=first;i<=last;i++) if(i!=slot) drawBubble(canvas,x,centerY+(i-cursor)*spacing,scale,hole,visible,progress,Math.abs(i-cursor),others,i,false,background,dark);
        drawBubble(canvas,x,centerY+(slot-cursor)*spacing,scale,hole,visible,progress,Math.abs(slot-cursor),1,slot,true,background,dark);
    }
    private void drawBubble(Canvas canvas,float x,float y,float scale,float hole,float visible,float progress,float distance,float live,int index,boolean active,Paint background,Paint dark) {
        if(distance>=2) return;
        float nearby=Math.max(0,1-distance),alpha=visible*Math.max(nearby,(2-distance)*.45f)*live;
        if(alpha<=0) return;
        float fit=(nearby*.3f+.7f)*(.85f+.15f*CubicBezierInterpolator.EASE_OUT_BACK.getInterpolation(live));
        float outer=scale*fit,inner=hole*fit,radius=AndroidUtilities.dp(16),size=radius*outer;
        boolean gradient=resourcesProvider!=null && resourcesProvider.hasGradientService();
        boolean darkTheme=ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundWhite,resourcesProvider))<=.5;
        int darkColor=dark.getColor(),outlineDarkColor=darkOutline.getColor();
        try {
            if(active && progress>0 && fill.getValue()==0) {
                setRect(x,y,size-outline.getStrokeWidth()/2);applyServiceShader();
                int a=outline.getAlpha();outline.setAlpha((int)(a*alpha));canvas.drawArc(rect,-90,360*progress,false,outline);outline.setAlpha(a);
                if(gradient) { if(darkTheme) darkOutline.setColor(0xffffffff);a=darkOutline.getAlpha();darkOutline.setAlpha((int)(a*alpha));canvas.drawArc(rect,-90,360*progress,false,darkOutline);darkOutline.setAlpha(a); }
            }
            setRect(x,y,size);applyServiceShader();
            drawSurface(canvas,background,dark,gradient,darkTheme,alpha*.6f*progress);
            int save=canvas.save();
            if(inner!=0) {
                setRect(x,y,radius*inner);path.rewind();path.addRoundRect(rect,radius,radius,Path.Direction.CW);
                canvas.clipPath(path,android.graphics.Region.Op.DIFFERENCE);
            }
            setRect(x,y,size);drawSurface(canvas,background,dark,gradient,darkTheme,alpha*.4f);canvas.restoreToCount(save);
            float ringProgress=ring.getValue()/SPRING_UNIT;
            if(active && ringProgress>0 && ringProgress<1) {
                float stroke=outline.getStrokeWidth(),fade=(1-ringProgress)*stroke,r=(1+ringProgress)*radius;
                setRect(x,y,r-fade);applyServiceShader();int a=outline.getAlpha();outline.setStrokeWidth(fade);outline.setAlpha((int)(a*alpha));canvas.drawRoundRect(rect,r,r,outline);outline.setAlpha(a);outline.setStrokeWidth(stroke);
                if(gradient) { if(darkTheme) darkOutline.setColor(0xffffffff);a=darkOutline.getAlpha();darkOutline.setStrokeWidth(fade);darkOutline.setAlpha((int)(a*alpha));canvas.drawRoundRect(rect,r,r,darkOutline);darkOutline.setAlpha(a);darkOutline.setStrokeWidth(stroke); }
            }
            MonogramSwipeAction action=actions.get(floorMod(index,actions.size()));
            if(action==MonogramSwipeAction.REACTION && reactionImage!=null && reactionImage.hasBitmapImage()) {
                float half=AndroidUtilities.dp(10)*outer;reactionImage.setImageCoords(x-half,y-half,half*2,half*2);reactionImage.setAlpha(alpha);reactionImage.draw(canvas);return;
            }
            if(action==MonogramSwipeAction.REACTION && reactionDrawable!=null) {
                float half=AndroidUtilities.dp(10)*outer;reactionDrawable.setAlpha((int)(alpha*255));reactionDrawable.setBounds((int)(x-half),(int)(y-half),(int)(x+half),(int)(y+half));reactionDrawable.draw(canvas);reactionDrawable.setAlpha(255);return;
            }
            boolean nativeReply=actions.size()==1 && action==MonogramSwipeAction.REPLY;
            Drawable icon=nativeReply ? Theme.getThemeDrawable(Theme.key_drawable_replyIcon,resourcesProvider) : icon(action,(((index-floorMod(index,actions.size()))/actions.size())&1)!=0);
            if(icon==null) return;
            float iconScale=outer*(nativeReply ? 1 : action.iconTrim*iconFits.get(action.iconRes,1f));
            float halfW=icon.getIntrinsicWidth()/2f*iconScale,halfH=icon.getIntrinsicHeight()/2f*iconScale;
            int color=Theme.getColor(Theme.key_chat_serviceIcon,resourcesProvider);
            if(iconFilter==null || iconColor!=color) { iconColor=color;iconFilter=new PorterDuffColorFilter(color,PorterDuff.Mode.MULTIPLY); }
            icon.setColorFilter(iconFilter);icon.setAlpha((int)(alpha*255));icon.setBounds((int)(x-halfW),(int)(y-halfH),(int)(x+halfW),(int)(y+halfH));icon.draw(canvas);icon.setAlpha(255);
        } finally { dark.setColor(darkColor);darkOutline.setColor(outlineDarkColor); }
    }
    private void drawSurface(Canvas canvas,Paint background,Paint dark,boolean gradient,boolean darkTheme,float opacity) {
        int alpha=background.getAlpha();background.setAlpha((int)(alpha*opacity));canvas.drawRoundRect(rect,rect.width()/2,rect.height()/2,background);background.setAlpha(alpha);
        if(gradient) { if(darkTheme) dark.setColor(0xffffffff);alpha=dark.getAlpha();dark.setAlpha((int)(alpha*opacity));canvas.drawRoundRect(rect,rect.width()/2,rect.height()/2,dark);dark.setAlpha(alpha); }
    }
    private void setRect(float x,float y,float radius) { rect.set(x-radius,y-radius,x+radius,y+radius); }
    private void applyServiceShader() {
        float y=parent.getY()+rect.top;
        if(resourcesProvider!=null) resourcesProvider.applyServiceShaderMatrix(parent.getMeasuredWidth(),AndroidUtilities.displaySize.y,0,y);
        else Theme.applyServiceShaderMatrix(parent.getMeasuredWidth(),AndroidUtilities.displaySize.y,0,y);
    }
    private Drawable icon(MonogramSwipeAction action,boolean alternate) {
        SparseArray<Drawable> cache=alternate ? spareIcons : icons;Drawable icon=cache.get(action.iconRes);
        if(icon==null) {
            icon=parent.getContext().getDrawable(action.iconRes);if(icon==null) return null;
            if(iconFits.get(action.iconRes)==null) iconFits.put(action.iconRes,measureIcon(icon));
            icon=icon.mutate();cache.put(action.iconRes,icon);
        }return icon;
    }
    private float measureIcon(Drawable icon) {
        int w=icon.getIntrinsicWidth(),h=icon.getIntrinsicHeight();if(w<=0 || h<=0) return 1;
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,w,h);icon.draw(new Canvas(bitmap));
        int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);bitmap.recycle();int left=w,top=h,right=-1,bottom=-1;
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) if((pixels[y*w+x]>>>24)>=8) { left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y); }
        return right<left || bottom<top ? 1 : Math.min(AndroidUtilities.dpf2(15)/(right-left+1),AndroidUtilities.dpf2(16.5f)/(bottom-top+1));
    }
}
