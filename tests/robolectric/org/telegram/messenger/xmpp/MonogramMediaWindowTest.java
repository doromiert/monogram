package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.annotation.*;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramMessageRegressionTest.Device.class,MonogramMediaWindowTest.ProjectionBudget.class})
public class MonogramMediaWindowTest {
    @Implements(value=MonogramStore.class,isInAndroidSdk=false)
    public static class ProjectionBudget {
        @RealObject MonogramStore store;
        static int calls;
        @Implementation protected TLRPC.Message getMessage(int id,long self) {
            assertTrue("Never project more than eleven media messages",++calls<=11);
            return Shadow.directlyOn(store,MonogramStore.class,"getMessage",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,id),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(long.class,self));
        }
    }
    @Test public void windowsStayBoundedAndBorrowSpaceAtBothEdgesInArchiveOrder() throws Exception {
        Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        String name="media-window.db";context.deleteDatabase(name);
        try(MonogramStore store=new MonogramStore(context,name)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            int[] ids=new int[31];
            for(int i=30;i>=0;i--) {
                ids[i]=store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","file"+i,FileLink.parse("https://files.example.org/"+i+".png"),false));
                store.getWritableDatabase().execSQL("UPDATE messages SET time_ms=?,archive_order=? WHERE id=?",new Object[]{1000+i/3,i%3,ids[i]});
            }
            // Non-media and other chats cannot consume a slot or alter the global index.
            for(int i=0;i<250;i++)store.save(XmppMessage.sent(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","text"+i,"plain text",false));
            store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"carol@example.org","other",FileLink.parse("https://files.example.org/other.png"),false));
            for(int selected:new int[]{0,1,5,15,25,29,30}) {
                ProjectionBudget.calls=0;long started=System.nanoTime();
                MonogramStore.MediaWindow window=store.mediaWindow(self,peer,ids[selected],new TLRPC.TL_inputMessagesFilterPhotoVideo());
                int start=Math.min(Math.max(0,selected-5),20);
                assertEquals(11,window.messages.size());assertEquals(31,window.total);assertEquals(start,window.start);
                assertEquals(selected-start,window.index);assertEquals(ids[selected],window.messages.get(window.index).id);
                for(int i=0;i<11;i++)assertEquals(ids[start+i],window.messages.get(i).id);
                assertEquals(11,ProjectionBudget.calls);
                System.out.println("window selected="+selected+" projected="+ProjectionBudget.calls+" elapsed_ms="+(System.nanoTime()-started)/1_000_000);
            }
            ProjectionBudget.calls=0;
            TLRPC.TL_messages_messagesSlice page=store.search(self,peer,"",ids[20],0,11,0,0,0,new TLRPC.TL_inputMessagesFilterPhotoVideo(),false,false);
            assertEquals(31,page.count);assertEquals(11,page.messages.size());assertEquals(11,ProjectionBudget.calls);
            for(int i=0;i<11;i++)assertEquals(ids[19-i],page.messages.get(i).id);
            ProjectionBudget.calls=0;
            MonogramStore.MediaWindow single=store.mediaWindow(self,store.peer("carol@example.org"),store.search(self,store.peer("carol@example.org"),"",0,0,1,0,0,0,new TLRPC.TL_inputMessagesFilterPhotoVideo(),false,false).messages.get(0).id,new TLRPC.TL_inputMessagesFilterPhotoVideo());
            assertEquals(1,single.messages.size());assertEquals(0,single.index);assertEquals(0,single.start);
        }finally{context.deleteDatabase(name);}
    }
}
