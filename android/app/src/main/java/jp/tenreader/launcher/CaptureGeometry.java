package jp.tenreader.launcher;

import android.graphics.Bitmap;
import android.graphics.Rect;

final class CaptureGeometry {
    final int displayWidth,displayHeight,contentWidth,contentHeight;
    final boolean scopeKnown,visible;
    CaptureGeometry(int displayWidth,int displayHeight,int contentWidth,int contentHeight){this(displayWidth,displayHeight,contentWidth,contentHeight,true,true);}
    CaptureGeometry(int displayWidth,int displayHeight,int contentWidth,int contentHeight,boolean scopeKnown,boolean visible){this.displayWidth=displayWidth;this.displayHeight=displayHeight;this.contentWidth=contentWidth;this.contentHeight=contentHeight;this.scopeKnown=scopeKnown;this.visible=visible;}
    Bitmap targetOnly(Bitmap bitmap,ScrollPort.Target target){
        if(!scopeKnown||!visible||displayWidth!=target.displayWidth||displayHeight!=target.displayHeight||contentWidth!=displayWidth||contentHeight!=displayHeight||displayWidth<=0||displayHeight<=0)throw new IllegalArgumentException("Capture scope differs from target display");
        if(Math.abs((long)bitmap.getWidth()*displayHeight-(long)bitmap.getHeight()*displayWidth)>2L*Math.max(displayWidth,displayHeight))throw new IllegalArgumentException("Capture aspect ratio differs");
        Rect box=target.bounds;
        if(box.left<0||box.top<0||box.right>displayWidth||box.bottom>displayHeight||box.isEmpty())throw new IllegalArgumentException("Invalid target area");
        int left=(int)Math.ceil(box.left*bitmap.getWidth()/(double)displayWidth),top=(int)Math.ceil(box.top*bitmap.getHeight()/(double)displayHeight);
        int right=(int)Math.floor(box.right*bitmap.getWidth()/(double)displayWidth),bottom=(int)Math.floor(box.bottom*bitmap.getHeight()/(double)displayHeight);
        if(right<=left||bottom<=top)throw new IllegalArgumentException("Empty crop");
        return Bitmap.createBitmap(bitmap,left,top,right-left,bottom-top);
    }
}
