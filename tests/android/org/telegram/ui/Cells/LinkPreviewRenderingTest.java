package org.telegram.ui.Cells;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.xmpp.MonogramArticles;
import org.telegram.tgnet.TLRPC;

/** Run as instrumentation in a disposable emulator; no accounts or network are required. */
public final class LinkPreviewRenderingTest extends Instrumentation {
    private final java.util.concurrent.CountDownLatch resumed=new java.util.concurrent.CountDownLatch(1);
    private volatile Activity resumedActivity;
    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if(activity.getClass().getName().equals("org.telegram.ui.LaunchActivity")) { resumedActivity=activity;resumed.countDown(); }
    }
    private static final String URL="https://x.com/larp_stats/status/2126279369052307624";
    private static final class Cell extends ChatMessageCell {
        int previewDraws;
        Cell(Activity activity) { super(activity,0); }
        @Override public void drawLinkPreview(Canvas canvas,float alpha) {
            previewDraws++;
            super.drawLinkPreview(canvas,alpha);
        }
    }
    private static Object field(Object value,String name) throws Exception {
        java.lang.reflect.Field field=ChatMessageCell.class.getDeclaredField(name);
        field.setAccessible(true);return field.get(value);
    }
    private void status(String text) { Bundle result=new Bundle();result.putString("stream",text+"\n");sendStatus(1,result); }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);status("Rendering fixture created");start(); }
    @Override public void onStart() {
        Bundle results=new Bundle();
        try {
            status("Starting preview activity");
            Intent intent=new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(),"org.telegram.ui.LaunchActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getTargetContext().startActivity(intent);
            if(!resumed.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Preview activity did not resume");
            Activity activity=resumedActivity;
            status("Preview activity started");
            java.util.concurrent.CountDownLatch attached=new java.util.concurrent.CountDownLatch(1);
            FrameLayout[] frame={null};
            runOnMainSync(() -> {
                frame[0]=new FrameLayout(activity);
                frame[0].addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View view) { attached.countDown(); }
                    public void onViewDetachedFromWindow(View view) {}
                });
                activity.setContentView(frame[0]);
                if(frame[0].isAttachedToWindow())attached.countDown();
            });
            if(!attached.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Preview root did not attach");
            FrameLayout root=frame[0];
            Throwable[] failed={null};
            runOnMainSync(() -> {
                try {
                    org.telegram.ui.ActionBar.Theme.createChatResources(activity,false);
                    for(boolean rich:new boolean[]{false,true}) for(String body:new String[]{URL,"me too "+URL,"**me too** "+URL,URL+"\nMore text"}) {
                        status("Rendering case rich="+rich+", characters="+body.length());
                        TLRPC.TL_message message=new TLRPC.TL_message();
                        message.id=42;message.date=(int)(System.currentTimeMillis()/1000);message.dialog_id=2;message.message=body;message.flags=512;
                        message.peer_id=new TLRPC.TL_peerUser();message.peer_id.user_id=2;
                        message.from_id=new TLRPC.TL_peerUser();message.from_id.user_id=2;
                        TLRPC.TL_webPage page=new TLRPC.TL_webPage();page.id=17;page.url=URL;page.display_url="x.com";page.site_name="X";page.type="article";page.title="Visible preview title";page.description="Visible preview description";page.flags=15;
                        TLRPC.TL_messageMediaWebPage media=new TLRPC.TL_messageMediaWebPage();media.webpage=page;message.media=media;
                        if(rich)MonogramArticles.apply(message,body);
                        MessageObject object=new MessageObject(0,message,true,true);
                        if(rich && object.type!=MessageObject.TYPE_ARTICLE)throw new AssertionError("Fixture did not exercise rich article rendering");
                        Cell cell=new Cell(activity);root.removeAllViews();root.addView(cell);
                        String[] opened={null};
                        cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                            @Override public void didPressWebPage(ChatMessageCell sender,TLRPC.WebPage webpage,String url,boolean safe) { opened[0]=url; }
                        });
                        cell.setMessageObject(object,null,false,false,true);
                        cell.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                        cell.layout(0,0,1080,cell.getMeasuredHeight());
                        Bitmap bitmap=Bitmap.createBitmap(1080,cell.getMeasuredHeight(),Bitmap.Config.ARGB_8888);
                        cell.draw(new Canvas(bitmap));
                        if(!Boolean.TRUE.equals(field(cell,"hasLinkPreview")) || ((Number)field(cell,"linkPreviewHeight")).intValue()<=0 || field(cell,"titleLayout")==null)
                            throw new AssertionError("Webpage preview layout disappeared");
                        if(cell.previewDraws==0)throw new AssertionError("Preview space reserved but webpage not drawn; rich="+rich);
                        int y=((Number)field(cell,"linkPreviewY")).intValue();
                        if(y<0 || y>=bitmap.getHeight())throw new AssertionError("Preview is outside the message");
                        if(rich && y<((Number)field(cell,"textY")).intValue()+object.richLayout.getHeight())
                            throw new AssertionError("Webpage overlaps rich message text");
                        java.lang.reflect.Method touch=ChatMessageCell.class.getDeclaredMethod("checkLinkPreviewMotionEvent",MotionEvent.class);touch.setAccessible(true);
                        float x=((Number)field(cell,"unmovedTextX")).floatValue()+12;
                        float tapY=y+((Number)field(cell,"linkPreviewHeight")).floatValue()/2;
                        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
                            MotionEvent event=MotionEvent.obtain(0,0,action,x,tapY,0);
                            try { boolean consumed=Boolean.TRUE.equals(touch.invoke(cell,event));if(action==MotionEvent.ACTION_DOWN && !consumed)throw new AssertionError("Preview tap ignored"); } finally { event.recycle(); }
                        }
                        if(!URL.equals(opened[0]))throw new AssertionError("Preview opened the wrong URL");
                        File file=new File(getTargetContext().getCacheDir(),"preview-"+(rich?"rich":"plain")+"-"+body.length()+".png");
                        try(FileOutputStream output=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}
                        bitmap.recycle();
                    }
                    activity.finish();
                } catch(Throwable failure) { failed[0]=failure; }
            });
            if(failed[0]!=null)throw new AssertionError("Native link preview rendering failed",failed[0]);
            results.putString("stream","PASS: 8 plain/rich mixed-link previews laid out and drawn\n");finish(Activity.RESULT_OK,results);
        } catch(Throwable failure) {
            java.io.StringWriter trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));
            results.putString("stream",trace.toString());finish(Activity.RESULT_CANCELED,results);
        }
    }
}
