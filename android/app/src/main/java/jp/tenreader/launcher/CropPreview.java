package jp.tenreader.launcher;

import android.content.Context;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** User-drawn crop selects already recognized lines; it never triggers a new capture. */
// Requires a live captured document and is never inflated from XML.
@SuppressLint("ViewConstructor")
final class CropPreview extends View {
    private final Bitmap preview;
    private final OcrDocument document;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF image=new RectF();
    private OcrDocument.Region region;
    private float downX,downY;
    CropPreview(Context context, Bitmap preview,OcrDocument document,OcrDocument.Region region) {
        super(context);this.preview=preview;this.document=document;this.region=region;
        setContentDescription("画像上でドラッグして読む範囲を囲み、範囲を適用してください。文章欄からも修正できます。");
    }
    OcrDocument.Region region() { return region; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (preview==null || preview.isRecycled()) return;
        float scale=Math.min(getWidth()/(float)preview.getWidth(),getHeight()/(float)preview.getHeight());
        float w=preview.getWidth()*scale,h=preview.getHeight()*scale;
        image.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
        canvas.drawBitmap(preview,null,image,paint);
        paint.setColor(Color.rgb(81,129,39)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3);
        canvas.drawRect(image.left+region.left*image.width()/document.width,image.top+region.top*image.height()/document.height,
            image.left+region.right*image.width()/document.width,image.top+region.bottom*image.height()/document.height,paint);
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (image.width()==0 || image.height()==0) return false;
        float x=Math.max(0,Math.min(document.width,(event.getX()-image.left)*document.width/image.width()));
        float y=Math.max(0,Math.min(document.height,(event.getY()-image.top)*document.height/image.height()));
        if (event.getActionMasked()==MotionEvent.ACTION_DOWN) { downX=x;downY=y;getParent().requestDisallowInterceptTouchEvent(true);return true; }
        if (event.getActionMasked()==MotionEvent.ACTION_MOVE || event.getActionMasked()==MotionEvent.ACTION_UP) {
            region=new OcrDocument.Region((int)Math.min(x,downX),(int)Math.min(y,downY),(int)Math.max(x,downX),(int)Math.max(y,downY)); invalidate();
            if (event.getActionMasked()==MotionEvent.ACTION_UP) { performClick();getParent().requestDisallowInterceptTouchEvent(false); }
            return true;
        }
        if (event.getActionMasked()==MotionEvent.ACTION_CANCEL) getParent().requestDisallowInterceptTouchEvent(false);
        return true;
    }
    @Override public boolean performClick() { super.performClick();return true; }
}
