// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
/** Resource interception covers native ImageViews and themed drawables uniformly. */
public final class MonogramResources extends Resources {
    private static final WeakHashMap<Resources,WeakReference<MonogramResources>> wrappers=new WeakHashMap<>();
    private MonogramResources(Resources base) {super(base.getAssets(),base.getDisplayMetrics(),base.getConfiguration());}
    public static synchronized Resources wrap(Resources base) {
        if(base instanceof MonogramResources)return base;
        WeakReference<MonogramResources> ref=wrappers.get(base);MonogramResources wrapper=ref==null?null:ref.get();
        if(wrapper==null){wrapper=new MonogramResources(base);wrappers.put(base,new WeakReference<>(wrapper));}
        if(!wrapper.getConfiguration().equals(base.getConfiguration()))wrapper.updateConfiguration(base.getConfiguration(),base.getDisplayMetrics());
        return wrapper;
    }
    @Override public Drawable getDrawable(int id,Theme theme) throws NotFoundException {return MonogramIconPacks.drawable(this,id,super.getDrawable(id,theme));}
    @Override public Drawable getDrawable(int id) throws NotFoundException {return getDrawable(id,null);}
    @Override public Drawable getDrawableForDensity(int id,int density,Theme theme) throws NotFoundException {return MonogramIconPacks.drawable(this,id,super.getDrawableForDensity(id,density,theme));}
    @Override public Drawable getDrawableForDensity(int id,int density) throws NotFoundException {return getDrawableForDensity(id,density,null);}
}
