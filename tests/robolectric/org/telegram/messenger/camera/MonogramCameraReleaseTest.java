package org.telegram.messenger.camera;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramCameraReleaseTest {
    static final class Session extends CameraSessionWrapper {
        int closes;
        @Override public void destroy(boolean async,Runnable before,Runnable after) { closes++; }
    }
    private CameraView view(Session session) throws Exception {
        Class<?> allocator=Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field=allocator.getDeclaredField("theUnsafe");field.setAccessible(true);
        CameraView view=(CameraView)allocator.getMethod("allocateInstance",Class.class).invoke(field.get(null),CameraView.class);
        ReflectionHelpers.setField(view,"cameraSession",new CameraSessionWrapper[]{session,null});
        ReflectionHelpers.setField(view,"releasedSessions",Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>())));
        return view;
    }
    @Test public void explicitReleaseThenLateSurfaceDestructionCannotCloseTheReopenedCamera() throws Exception {
        Session old=new Session(),current=new Session();CameraView oldView=view(old),currentView=view(current);
        oldView.destroy(true,null);assertEquals(1,old.closes);
        oldView.onSurfaceTextureDestroyed(null);oldView.destroy(true,null);
        assertEquals(1,old.closes);assertEquals(0,current.closes);
        currentView.destroy(true,null);assertEquals(1,current.closes);
    }
    @Test public void surfaceDestructionThenExplicitReleaseClosesBothSessionsOnlyOnce() throws Exception {
        Session first=new Session(),second=new Session();CameraView view=view(first);
        ReflectionHelpers.setField(view,"cameraSession",new CameraSessionWrapper[]{first,second});
        view.onSurfaceTextureDestroyed(null);view.destroy(true,null);view.onSurfaceTextureDestroyed(null);
        assertEquals(1,first.closes);assertEquals(1,second.closes);
    }
}
