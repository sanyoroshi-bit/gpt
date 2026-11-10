package jp.tenreader.launcher;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;

final class BitmapFrames {
    static Bitmap rgba(ByteBuffer source,int width,int height,int pixelStride,int rowStride) {
        if(width<=0||height<=0||pixelStride!=4||(long)width*height>3000000||rowStride<(long)width*4)throw new IllegalArgumentException("Unsupported image buffer");
        ByteBuffer input=source.slice();
        long required=(long)rowStride*(height-1)+(long)width*4;
        if(required>input.remaining())throw new IllegalArgumentException("Truncated image buffer");
        ByteBuffer packed=ByteBuffer.allocateDirect(width*height*4);
        for(int row=0;row<height;row++){
            ByteBuffer line=input.duplicate();line.position(row*rowStride);line.limit(row*rowStride+width*4);packed.put(line);
        }
        packed.flip();Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{bitmap.copyPixelsFromBuffer(packed);return bitmap;}catch(RuntimeException e){bitmap.recycle();throw e;}
    }
}
