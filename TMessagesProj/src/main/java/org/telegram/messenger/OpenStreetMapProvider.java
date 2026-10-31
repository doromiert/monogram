// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger;

import android.animation.*;
import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.os.Bundle;
import android.view.*;
import androidx.core.util.Consumer;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Keyless native raster map. Only tiles visible in an explicitly opened map are requested. */
public final class OpenStreetMapProvider implements IMapsProvider {
    public void initializeMaps(Context context) { }
    public IMapView onCreateMapView(Context context) { return new MapView(context); }
    public IMarkerOptions onCreateMarkerOptions() { return new MarkerOptions(); }
    public ICircleOptions onCreateCircleOptions() { return new CircleOptions(); }
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() { return new BoundsBuilder(); }
    public ICameraUpdate newCameraUpdateLatLng(LatLng point) { return new Update(point,Float.NaN,null,0); }
    public ICameraUpdate newCameraUpdateLatLngZoom(LatLng point,float zoom) { return new Update(point,zoom,null,0); }
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds,int padding) { return new Update(bounds.getCenter(),Float.NaN,(Bounds)bounds,padding); }
    public IMapStyleOptions loadRawResourceStyle(Context context,int resId) { return new IMapStyleOptions() { }; }
    public String getMapsAppPackageName() { return null; }
    public int getInstallMapsString() { return R.string.InstallGoogleMaps; }
    public boolean supportsMapTypes() { return false; }
    static double wrap(double longitude) { return ((longitude+180)%360+360)%360-180; }
    static double clamp(double latitude) { return Math.max(-85.05112878,Math.min(85.05112878,latitude)); }
    static double x(double longitude) { return (longitude+180)/360; }
    static double y(double latitude) { double sin=Math.sin(Math.toRadians(clamp(latitude)));return .5-Math.log((1+sin)/(1-sin))/(4*Math.PI); }
    static LatLng coordinates(double x,double y) { return new LatLng(Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*Math.max(0,Math.min(1,y)))))),wrap(x*360-180)); }
    private static final class Update implements ICameraUpdate {
        final LatLng point;final float zoom;final Bounds bounds;final int padding;
        Update(LatLng point,float zoom,Bounds bounds,int padding) { this.point=point;this.zoom=zoom;this.bounds=bounds;this.padding=padding; }
    }
    private static final class Bounds implements ILatLngBounds {
        final double south,north,west,east;
        Bounds(double south,double north,double west,double east) { this.south=south;this.north=north;this.west=west;this.east=east; }
        public LatLng getCenter() { return new LatLng((south+north)/2,wrap((west+east)/2)); }
    }
    private static final class BoundsBuilder implements ILatLngBoundsBuilder {
        double south=90,north=-90,west=180,east=-180;
        public ILatLngBoundsBuilder include(LatLng point) { south=Math.min(south,point.latitude);north=Math.max(north,point.latitude);west=Math.min(west,point.longitude);east=Math.max(east,point.longitude);return this; }
        public ILatLngBounds build() { if(south>north) throw new IllegalStateException("No map points");return east-west>180?new Bounds(south,north,east,west+360):new Bounds(south,north,west,east); }
    }
    private static final class MarkerOptions implements IMarkerOptions {
        LatLng point=new LatLng(0,0);Bitmap bitmap;float ax=.5f,ay=1;
        public IMarkerOptions position(LatLng point) { this.point=point;return this; }
        public IMarkerOptions icon(Bitmap bitmap) { this.bitmap=bitmap;return this; }
        public IMarkerOptions icon(int id) { bitmap=org.telegram.messenger.MonogramIconPacks.decodeResource(ApplicationLoader.applicationContext.getResources(),id);return this; }
        public IMarkerOptions anchor(float x,float y) { ax=x;ay=y;return this; }
        public IMarkerOptions title(String value) { return this; }
        public IMarkerOptions snippet(String value) { return this; }
        public IMarkerOptions flat(boolean value) { return this; }
    }
    private static final class CircleOptions implements ICircleOptions {
        LatLng point=new LatLng(0,0);double radius;int fill,stroke;float width=2;
        public ICircleOptions center(LatLng point) { this.point=point;return this; }
        public ICircleOptions radius(double value) { radius=value;return this; }
        public ICircleOptions strokeColor(int value) { stroke=value;return this; }
        public ICircleOptions fillColor(int value) { fill=value;return this; }
        public ICircleOptions strokePattern(List<PatternItem> values) { return this; }
        public ICircleOptions strokeWidth(int value) { width=value;return this; }
    }
    private static final class MapView extends android.widget.FrameLayout implements IMapView,IMap,IUISettings {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final android.util.LruCache<String,Bitmap> memory=new android.util.LruCache<String,Bitmap>(16*1024*1024) { protected int sizeOf(String key,Bitmap value) { return value.getAllocationByteCount(); } };
        final Set<String> pending=ConcurrentHashMap.newKeySet(),visible=ConcurrentHashMap.newKeySet();
        final Map<String,Long> retryAfter=new ConcurrentHashMap<>();
        final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();
        final ExecutorService io=Executors.newFixedThreadPool(2,r -> { Thread t=new Thread(r,"monogram-map-tile");t.setDaemon(true);return t; });
        final ArrayList<Marker> markers=new ArrayList<>();final ArrayList<Circle> circles=new ArrayList<>();
        final AndroidLocationProvider locations=new AndroidLocationProvider();
        final ILocationServiceProvider.ILocationListener locationUpdate=location -> { this.myLocation=new Location(location);if(this.locationListener!=null) this.locationListener.accept(new Location(location));invalidate(); };
        final ScaleGestureDetector scale;final GestureDetector gestures;
        Consumer<Location> locationListener;OnMarkerClickListener markerClick;OnCameraMoveStartedListener moveStarted;
        Runnable idle,moved,loaded,layout;ITouchInterceptor dispatch,intercept;
        LatLng center=new LatLng(0,0);float zoom=3;int left,top,right,bottom;volatile boolean resumed,destroyed;boolean locate,registered,mapLoaded;
        Location myLocation;Update deferred;int deferredDuration;ICancelableCallback deferredCallback;ValueAnimator animation;ICancelableCallback animationCallback;
        MapView(Context context) {
            super(context);setWillNotDraw(false);locations.init(context);
            scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                public boolean onScale(ScaleGestureDetector detector) { cancelAnimation();zoom=Math.max(2,Math.min(19,zoom+(float)(Math.log(detector.getScaleFactor())/Math.log(2))));changed(false);return true; }
            });
            gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
                public boolean onDown(MotionEvent event) { cancelAnimation();if(moveStarted!=null) moveStarted.onCameraMoveStarted(OnCameraMoveStartedListener.REASON_GESTURE);return true; }
                public boolean onScroll(MotionEvent first,MotionEvent last,float dx,float dy) { center=coordinates(x(center.longitude)+dx/world(),y(center.latitude)+dy/world());changed(false);return true; }
                public boolean onDoubleTap(MotionEvent event) { animateCamera(new Update(center,Math.min(19,zoom+1),null,0));return true; }
                public boolean onSingleTapConfirmed(MotionEvent event) {
                    float density=getResources().getDisplayMetrics().density;
                    if(event.getY()>=getHeight()-24*density && event.getX()>=getWidth()-180*density) {
                        try { getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://www.openstreetmap.org/copyright")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); } catch(android.content.ActivityNotFoundException unavailable) { }
                        return true;
                    }
                    for(int n=markers.size()-1;n>=0;n--) { Marker marker=markers.get(n);Point point=project(marker.point);if(Math.hypot(point.x-event.getX(),point.y-event.getY())<36*getResources().getDisplayMetrics().density && markerClick!=null && markerClick.onClick(marker)) return true; }return true;
                }
            });
        }
        double world() { return 256*Math.pow(2,zoom); }
        Point project(LatLng point) {
            double dx=x(point.longitude)-x(center.longitude);if(dx>.5) dx--;if(dx<-.5) dx++;
            return new Point((int)Math.round((getWidth()+left-right)/2.0+dx*world()),(int)Math.round((getHeight()+top-bottom)/2.0+(y(point.latitude)-y(center.latitude))*world()));
        }
        void changed(boolean finished) { invalidate();if(moved!=null) moved.run();if(finished && idle!=null) idle.run(); }
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);canvas.drawColor(Color.rgb(229,232,225));if(destroyed) return;
            int level=(int)Math.floor(zoom),count=1<<level;double tileSize=world()/count,cx=x(center.longitude)*count,cy=y(center.latitude)*count;
            double originX=(getWidth()+left-right)/2.0-cx*tileSize,originY=(getHeight()+top-bottom)/2.0-cy*tileSize;
            visible.clear();boolean any=false;
            int x0=(int)Math.floor(-originX/tileSize),x1=(int)Math.floor((getWidth()-originX)/tileSize),y0=Math.max(0,(int)Math.floor(-originY/tileSize)),y1=Math.min(count-1,(int)Math.floor((getHeight()-originY)/tileSize));
            for(int tx=x0;tx<=x1;tx++) for(int ty=y0;ty<=y1;ty++) {
                String key=level+"/"+((tx%count+count)%count)+"/"+ty;visible.add(key);Bitmap tile=memory.get(key);
                RectF area=new RectF((float)(originX+tx*tileSize),(float)(originY+ty*tileSize),(float)(originX+(tx+1)*tileSize),(float)(originY+(ty+1)*tileSize));
                if(tile!=null) { canvas.drawBitmap(tile,null,area,paint);any=true; }else { paint.setColor(0xffd6dad3);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);canvas.drawRect(area,paint);paint.setStyle(Paint.Style.FILL);request(key); }
            }
            for(Circle circle:circles) circle.draw(canvas);for(Marker marker:markers) marker.draw(canvas);
            if(myLocation!=null && locate) { Point point=project(new LatLng(myLocation.getLatitude(),myLocation.getLongitude()));paint.setColor(0xff4285f4);canvas.drawCircle(point.x,point.y,7*getResources().getDisplayMetrics().density,paint); }
            float density=getResources().getDisplayMetrics().density;paint.setTextSize(11*density);String attribution="© OpenStreetMap contributors";float width=paint.measureText(attribution);
            paint.setColor(0xddffffff);canvas.drawRect(getWidth()-width-12*density,getHeight()-20*density,getWidth(),getHeight(),paint);paint.setColor(Color.DKGRAY);canvas.drawText(attribution,getWidth()-width-6*density,getHeight()-6*density,paint);
            if(!any) { paint.setTextSize(14*density);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(LocaleController.getString(R.string.MonogramMapTilesHint),getWidth()/2f,getHeight()/2f,paint);paint.setTextAlign(Paint.Align.LEFT); }
            if(!mapLoaded) { mapLoaded=true;if(loaded!=null) post(loaded); }
        }
        void request(String key) {
            if(!resumed || destroyed || retryAfter.getOrDefault(key,0L)>System.currentTimeMillis() || pending.size()>=48 || !pending.add(key)) return;
            io.execute(() -> {
                boolean attempted=false;
                try {
                    if(!resumed || destroyed || !visible.contains(key)) return;
                    File directory=new File(getContext().getCacheDir(),"monogram-map-tiles"),file=new File(directory,key.replace('/','_')+".png");Bitmap bitmap=null;
                    if(file.isFile() && file.lastModified()>System.currentTimeMillis()) bitmap=BitmapFactory.decodeFile(file.getPath());
                    if(bitmap==null) {
                        attempted=true;HttpURLConnection connection=(HttpURLConnection)new URL("https://tile.openstreetmap.org/"+key+".png").openConnection();connections.add(connection);
                        try {
                            connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
                            connection.setRequestProperty("User-Agent","Monogram Android / github.com/doromiert/monogram");
                            if(connection.getResponseCode()!=200 || !resumed || destroyed) return;
                            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
                            try(InputStream input=connection.getInputStream()) { while((read=input.read(buffer))!=-1) { if(destroyed || !resumed || bytes.size()+read>1024*1024) return;bytes.write(buffer,0,read); } }
                            byte[] data=bytes.toByteArray();BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,size);
                            if(size.outWidth!=256 || size.outHeight!=256) return;bitmap=BitmapFactory.decodeByteArray(data,0,data.length);
                            if(directory.isDirectory() || directory.mkdir()) {
                                File temporary=File.createTempFile("tile-",".tmp",directory);
                                try { try(FileOutputStream output=new FileOutputStream(temporary)) { output.write(data); }if(temporary.renameTo(file)) file.setLastModified(Math.max(connection.getExpiration(),System.currentTimeMillis()+7L*24*60*60*1000)); }finally { temporary.delete(); }
                                trim(directory);
                            }
                        } finally { connections.remove(connection);connection.disconnect(); }
                    }
                    if(bitmap!=null && !destroyed) { memory.put(key,bitmap);postInvalidate(); }
                } catch(Exception unavailable) { /* Keep the local pin/grid; never substitute a different point or repeatedly retry a failed tile. */ }
                finally { pending.remove(key);if(attempted && memory.get(key)==null) retryAfter.put(key,System.currentTimeMillis()+60000); }
            });
        }
        void trim(File directory) {
            File[] files=directory.listFiles((dir,name) -> name.endsWith(".png"));if(files==null) return;long total=0;for(File file:files) total+=file.length();
            if(total<=64L*1024*1024) return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File file:files) { long length=file.length();if(file.delete()) total-=length;if(total<=48L*1024*1024) break; }
        }
        public boolean dispatchTouchEvent(MotionEvent event) { return dispatch==null?super.dispatchTouchEvent(event):dispatch.onInterceptTouchEvent(event,super::dispatchTouchEvent); }
        public boolean onInterceptTouchEvent(MotionEvent event) { return intercept==null?super.onInterceptTouchEvent(event):intercept.onInterceptTouchEvent(event,super::onInterceptTouchEvent); }
        public boolean onTouchEvent(MotionEvent event) { scale.onTouchEvent(event);gestures.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_UP || event.getActionMasked()==MotionEvent.ACTION_CANCEL) { if(idle!=null) idle.run(); }return true; }
        protected void onLayout(boolean changed,int l,int t,int r,int b) { super.onLayout(changed,l,t,r,b);if(changed && layout!=null) layout.run();if(deferred!=null && getWidth()>0 && getHeight()>0) { Update update=deferred;int duration=deferredDuration;ICancelableCallback callback=deferredCallback;deferred=null;deferredCallback=null;camera(update,duration,callback); } }
        public View getView() { return this; }
        public void getMapAsync(Consumer<IMap> callback) { post(() -> { if(!destroyed) callback.accept(this); }); }
        public void onCreate(Bundle state) { }
        public void onResume() { resumed=true;retryAfter.clear();locationUpdates();invalidate(); }
        public void onPause() { resumed=false;locationUpdates();for(HttpURLConnection connection:connections) connection.disconnect(); }
        public void onDestroy() { destroyed=true;onPause();cancelAnimation();if(deferredCallback!=null) deferredCallback.onCancel();deferred=null;deferredCallback=null;io.shutdownNow();memory.evictAll(); }
        public void onLowMemory() { memory.evictAll(); }
        public void setOnDispatchTouchEventInterceptor(ITouchInterceptor value) { dispatch=value; }
        public void setOnInterceptTouchEventInterceptor(ITouchInterceptor value) { intercept=value; }
        public void setOnLayoutListener(Runnable value) { layout=value; }
        void cancelAnimation() { if(animation!=null) { animation.cancel();animation=null; }if(animationCallback!=null) { ICancelableCallback callback=animationCallback;animationCallback=null;callback.onCancel(); } }
        public void moveCamera(ICameraUpdate update) { camera((Update)update,0,null); }
        public void animateCamera(ICameraUpdate update) { camera((Update)update,250,null); }
        public void animateCamera(ICameraUpdate update,ICancelableCallback callback) { camera((Update)update,250,callback); }
        public void animateCamera(ICameraUpdate update,int duration,ICancelableCallback callback) { camera((Update)update,duration,callback); }
        void camera(Update update,int duration,ICancelableCallback callback) {
            if(destroyed) { if(callback!=null) callback.onCancel();return; }
            if(getWidth()==0 || getHeight()==0) { if(deferredCallback!=null) deferredCallback.onCancel();deferred=update;deferredDuration=duration;deferredCallback=callback;return; }
            cancelAnimation();LatLng target=new LatLng(clamp(update.point.latitude),wrap(update.point.longitude));float next=Float.isNaN(update.zoom)?zoom:Math.max(2,Math.min(19,update.zoom));
            if(update.bounds!=null) { double dx=Math.max(.0000001,(update.bounds.east-update.bounds.west)/360),dy=Math.max(.0000001,Math.abs(y(update.bounds.north)-y(update.bounds.south)));next=(float)Math.max(2,Math.min(19,Math.log(Math.max(1,Math.min((getWidth()-2.0*update.padding-left-right)/(256*dx),(getHeight()-2.0*update.padding-top-bottom)/(256*dy))))/Math.log(2))); }
            if(moveStarted!=null) moveStarted.onCameraMoveStarted(OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION);
            if(duration<=0) { center=target;zoom=next;changed(true);if(callback!=null) callback.onFinish();return; }
            LatLng from=center;float fromZoom=zoom,toZoom=next;double longitudeDelta=wrap(target.longitude-from.longitude);animationCallback=callback;
            animation=ValueAnimator.ofFloat(0,1);animation.setDuration(duration);animation.addUpdateListener(value -> { float f=(float)value.getAnimatedValue();center=new LatLng(from.latitude+(target.latitude-from.latitude)*f,wrap(from.longitude+longitudeDelta*f));zoom=fromZoom+(toZoom-fromZoom)*f;changed(false); });
            animation.addListener(new AnimatorListenerAdapter() { boolean cancelled;public void onAnimationCancel(Animator value) { cancelled=true; }public void onAnimationEnd(Animator value) { if(cancelled) return;animation=null;animationCallback=null;changed(true);if(callback!=null) callback.onFinish(); } });animation.start();
        }
        public float getMaxZoomLevel() { return 19; }public float getMinZoomLevel() { return 2; }
        public CameraPosition getCameraPosition() { return new CameraPosition(center,zoom); }
        public void setMapType(int value) { }
        public void setMyLocationEnabled(boolean value) { locate=value;locationUpdates();invalidate(); }
        void locationUpdates() { boolean wanted=resumed && locate && !destroyed;if(wanted==registered) return;registered=wanted;if(!wanted) locations.removeLocationUpdates(locationUpdate);else { ILocationServiceProvider.ILocationRequest request=locations.onCreateLocationRequest();request.setPriority(ILocationServiceProvider.PRIORITY_BALANCED_POWER_ACCURACY);request.setInterval(10000);request.setFastestInterval(5000);locations.requestLocationUpdates(request,locationUpdate);locations.getLastLocation(location -> { if(location!=null && registered) locationUpdate.onLocationChanged(location); }); } }
        public IUISettings getUiSettings() { return this; }
        public void setZoomControlsEnabled(boolean value) { }public void setMyLocationButtonEnabled(boolean value) { }public void setCompassEnabled(boolean value) { }
        public void setOnCameraIdleListener(Runnable value) { idle=value; }
        public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener value) { moveStarted=value; }
        public void setOnCameraMoveListener(Runnable value) { moved=value; }
        public void setOnMapLoadedCallback(Runnable value) { loaded=value;if(mapLoaded && value!=null) post(value); }
        public IProjection getProjection() { return this::project; }
        public void setPadding(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b;invalidate(); }
        public void setMapStyle(IMapStyleOptions value) { }
        public void setOnMyLocationChangeListener(Consumer<Location> value) { locationListener=value; }
        public void setOnMarkerClickListener(OnMarkerClickListener value) { markerClick=value; }
        public IMarker addMarker(IMarkerOptions value) { Marker marker=new Marker((MarkerOptions)value);markers.add(marker);invalidate();return marker; }
        public ICircle addCircle(ICircleOptions value) { Circle circle=new Circle((CircleOptions)value);circles.add(circle);invalidate();return circle; }
        final class Marker implements IMarker {
            LatLng point;Bitmap bitmap;float ax,ay;int rotation;Object tag;
            Marker(MarkerOptions value) { point=value.point;bitmap=value.bitmap;ax=value.ax;ay=value.ay; }
            public Object getTag() { return tag; }public void setTag(Object value) { tag=value; }public LatLng getPosition() { return point; }
            public void setPosition(LatLng value) { point=value;invalidate(); }public void setRotation(int value) { rotation=value;invalidate(); }
            public void setIcon(Bitmap value) { bitmap=value;invalidate(); }public void setIcon(int id) { bitmap=org.telegram.messenger.MonogramIconPacks.decodeResource(getResources(),id);invalidate(); }
            public void remove() { markers.remove(this);invalidate(); }
            void draw(Canvas canvas) { Point screen=project(point);if(bitmap!=null) { canvas.save();canvas.rotate(rotation,screen.x,screen.y);canvas.drawBitmap(bitmap,screen.x-bitmap.getWidth()*ax,screen.y-bitmap.getHeight()*ay,paint);canvas.restore(); }else { paint.setColor(0xffe84b3c);canvas.drawCircle(screen.x,screen.y-10,10,paint);canvas.drawCircle(screen.x,screen.y,4,paint); } }
        }
        final class Circle implements ICircle {
            LatLng point;double radius;int stroke,fill;float width;
            Circle(CircleOptions value) { point=value.point;radius=value.radius;stroke=value.stroke;fill=value.fill;width=value.width; }
            public void setStrokeColor(int value) { stroke=value;invalidate(); }public void setFillColor(int value) { fill=value;invalidate(); }
            public void setRadius(double value) { radius=value;invalidate(); }public double getRadius() { return radius; }public void setCenter(LatLng value) { point=value;invalidate(); }
            public void remove() { circles.remove(this);invalidate(); }
            void draw(Canvas canvas) { Point screen=project(point);float pixels=(float)(Math.max(0,radius)*world()/(40075016.686*Math.max(.001,Math.cos(Math.toRadians(point.latitude)))));paint.setColor(fill);canvas.drawCircle(screen.x,screen.y,pixels,paint);paint.setColor(stroke);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);canvas.drawCircle(screen.x,screen.y,pixels,paint);paint.setStyle(Paint.Style.FILL); }
        }
    }
}
