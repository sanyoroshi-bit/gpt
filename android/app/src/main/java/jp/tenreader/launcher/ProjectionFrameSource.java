package jp.tenreader.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.SystemClock;
import java.nio.ByteBuffer;

interface FrameSource extends AutoCloseable {
    interface Callback { void frame(Bitmap image); void error(); void stopped(); }
    interface Factory { FrameSource create(Context context,Handler handler); }
    void start(Intent consent,Callback callback);
    @Override void close();
}

/** One virtual display per consent token; resize reuses that display. */
final class ProjectionFrameSource implements FrameSource {
    private final Context context;
    private final Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Callback callback;
    private boolean closed,delivered;
    private long notBefore;
    private final Runnable read=this::readLatest;
    private final MediaProjection.Callback projectionCallback=new MediaProjection.Callback() {
        @Override public void onStop() { if(!closed && callback!=null)callback.stopped(); }
        @Override public void onCapturedContentResize(int width,int height) {
            if(!closed && !delivered && display!=null)resize(width,height);
        }
    };
    ProjectionFrameSource(Context context,Handler handler) {this.context=context;this.handler=handler;}
    @Override public void start(Intent consent,Callback callback) {
        this.callback=callback;
        try {
            projection=context.getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,consent);
            if(projection==null)throw new IllegalStateException("No projection");
            projection.registerCallback(projectionCallback,handler);
            Context visual=OverlayWindows.context(context);
            Rect area=OverlayWindows.safeBounds(visual);
            // Bounds including system bars; single-app resize callback replaces these when needed.
            android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();
            visual.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
            int[] size=size(metrics.widthPixels,metrics.heightPixels);
            prepareReader(size[0],size[1]);
            display=projection.createVirtualDisplay("ten-visible-text",size[0],size[1],context.getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
        } catch(RuntimeException e) { callback.error(); }
    }
    static int[] size(int width,int height) {
        if(width<=0 || height<=0)throw new IllegalArgumentException("Invalid capture size");
        double scale=Math.min(1,Math.sqrt(3000000.0/((long)width*height)));
        return new int[]{Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale))};
    }
    private void prepareReader(int width,int height) {
        notBefore=SystemClock.uptimeMillis()+250;
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        reader.setOnImageAvailableListener(source->{if(!closed&&!delivered){handler.removeCallbacks(read);handler.postAtTime(read,Math.max(notBefore,SystemClock.uptimeMillis()));}},handler);
    }
    private void resize(int width,int height) {
        try {
            int[] size=size(width,height);
            if(reader!=null && reader.getWidth()==size[0] && reader.getHeight()==size[1])return;
            ImageReader old=reader;display.setSurface(null);prepareReader(size[0],size[1]);
            display.resize(size[0],size[1],context.getResources().getDisplayMetrics().densityDpi);display.setSurface(reader.getSurface());
            if(old!=null)old.close();
        } catch(RuntimeException e) { callback.error(); }
    }
    private void readLatest() {
        if(closed||delivered||reader==null)return;
        Bitmap bitmap=null;
        try(Image image=reader.acquireLatestImage()) {
            if(image==null)return;
            Image.Plane plane=image.getPlanes()[0];
            bitmap=BitmapFrames.rgba(plane.getBuffer(),image.getWidth(),image.getHeight(),plane.getPixelStride(),plane.getRowStride());
            delivered=true;
        } catch(RuntimeException|OutOfMemoryError e) {if(bitmap!=null)bitmap.recycle();callback.error();return;}
        callback.frame(bitmap);
    }
    @Override public void close() {
        if(closed)return;closed=true;handler.removeCallbacks(read);
        if(display!=null){display.release();display=null;}
        if(reader!=null){reader.close();reader=null;}
        if(projection!=null){projection.unregisterCallback(projectionCallback);projection.stop();projection=null;}
        callback=null;
    }
}
