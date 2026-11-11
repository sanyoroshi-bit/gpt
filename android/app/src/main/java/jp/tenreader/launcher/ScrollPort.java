package jp.tenreader.launcher;

import android.content.Context;
import android.graphics.Rect;
import java.util.function.Consumer;

interface ScrollPort {
    interface Factory { ScrollPort create(Context context); }
    final class Target {
        final String packageName;
        final int windowId,displayWidth,displayHeight;
        final Rect bounds,windowBounds;
        Target(String packageName,int windowId,Rect bounds,int displayWidth,int displayHeight){
            this(packageName,windowId,bounds,bounds,displayWidth,displayHeight);
        }
        Target(String packageName,int windowId,Rect bounds,Rect windowBounds,int displayWidth,int displayHeight){
            this.packageName=packageName;this.windowId=windowId;this.bounds=new Rect(bounds);this.windowBounds=new Rect(windowBounds);this.displayWidth=displayWidth;this.displayHeight=displayHeight;
        }
    }
    Target snapshot();
    boolean matches(Target target);
    default boolean captureSafe(Target target){return matches(target);}
    boolean begin(long ticket,Target target,Runnable interrupted);
    boolean forward(long ticket,Target target,Consumer<Boolean> settled);
    void end(long ticket);
}
