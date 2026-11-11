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
    interface Callback { void frame(Bitmap image);default void frame(Bitmap image,CaptureGeometry geometry){frame(image);} void error(); void stopped(); }
    interface Factory { FrameSource create(Context context,Handler handler); }
    void start(Intent consent,Callback callback);
    default boolean request(Callback callback){return false;}
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
    private int displayWidth,displayHeight,contentWidth,contentHeight;
    private boolean scopeKnown=android.os.Build.VERSION.SDK_INT<34,contentVisible=true;
    private final Runnable read=this::readLatest;
    private final MediaProjection.Callback projectionCallback=new MediaProjection.Callback() {
        @Override public void onStop() { if(!closed && callback!=null)callback.stopped(); }
        @Override public void onCapturedContentResize(int width,int height) {
            contentWidth=width;contentHeight=height;scopeKnown=true;
            if(!closed && !delivered && display!=null)resize(width,height);
        }
        @Override public void onCapturedContentVisibilityChanged(boolean visible){contentVisible=visible;}
    };
    ProjectionFrameSource(Context context,Handler handler) {this.context=context;this.handler=handler;}
    @Override public void start(Intent consent,Callback callback) {
        this.callback=callback;
        try {
            projection=context.getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,consent);
            if(projection==null)throw new IllegalStateException("No projection");
            projection.registerCallback(projectionCallback,handler);
            Context visual=OverlayWindows.context(context);
            Rect area=OverlayWindows.fullBounds(visual);
            // Bounds including system bars; single-app resize callback replaces these when needed.
            displayWidth=contentWidth=area.width();displayHeight=contentHeight=area.height();
            int[] size=size(displayWidth,displayHeight);
            prepareReader(size[0],size[1]);
            display=projection.createVirtualDisplay("ten-visible-text",size[0],size[1],context.getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
        } catch(RuntimeException e) { callback.error(); }
    }
    @Override public boolean request(Callback callback){
        if(closed||display==null||!delivered)return false;
        ImageReader old=reader;
        try{
            handler.removeCallbacks(read);display.setSurface(null);
            int[] dimensions=size(contentWidth,contentHeight);prepareReader(dimensions[0],dimensions[1]);
            this.callback=callback;delivered=false;display.resize(dimensions[0],dimensions[1],context.getResources().getDisplayMetrics().densityDpi);display.setSurface(reader.getSurface());
            return true;
        }catch(RuntimeException e){return false;}
        finally{if(old!=null&&old!=reader)try{old.close();}catch(RuntimeException ignored){}}
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
        ImageReader old=reader;
        try {
            int[] size=size(width,height);
            if(reader!=null && reader.getWidth()==size[0] && reader.getHeight()==size[1])return;
            display.setSurface(null);prepareReader(size[0],size[1]);
            display.resize(size[0],size[1],context.getResources().getDisplayMetrics().densityDpi);display.setSurface(reader.getSurface());
        } catch(RuntimeException e) { callback.error(); }
        finally{if(old!=null&&old!=reader)try{old.close();}catch(RuntimeException ignored){}}
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
        try{if(display!=null)display.setSurface(null);}catch(RuntimeException e){bitmap.recycle();callback.error();return;}
        callback.frame(bitmap,new CaptureGeometry(displayWidth,displayHeight,contentWidth,contentHeight,scopeKnown,contentVisible));
    }
    @Override public void close() {
        if(closed)return;closed=true;handler.removeCallbacks(read);
        if(display!=null){try{display.release();}catch(RuntimeException ignored){}display=null;}
        if(reader!=null){try{reader.close();}catch(RuntimeException ignored){}reader=null;}
        if(projection!=null){try{projection.unregisterCallback(projectionCallback);}catch(RuntimeException ignored){}try{projection.stop();}catch(RuntimeException ignored){}projection=null;}
        callback=null;
    }
}
