package jp.tenreader.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.View;
import java.util.List;

/** Fixed-size one-line stage. Text never contributes an intrinsic window width. */
final class FocusWordView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String before="",anchor="",after="";
    private int size=36;
    FocusWordView(Context context) { super(context); setBackgroundColor(Color.rgb(244,241,231)); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); }
    void setWord(String text) {
        List<String> chars = ReadingText.graphemes(text);
        int pivot = Math.max(0,(int)Math.ceil(chars.size()/2.0)-1);
        before=String.join("",chars.subList(0,pivot));
        anchor=chars.isEmpty()?"":chars.get(pivot);
        after=chars.isEmpty()?"":String.join("",chars.subList(pivot+1,chars.size()));
        setContentDescription(text); invalidate();
    }
    void setSize(int sp) { size=sp; invalidate(); }
    void setSerif(boolean serif) { paint.setTypeface(serif?Typeface.SERIF:Typeface.DEFAULT); invalidate(); }
    float fittedSize() {
        float desired=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,size,getResources().getDisplayMetrics());
        paint.setTextSize(desired);
        float required=2*Math.max(paint.measureText(before),paint.measureText(after))+paint.measureText(anchor);
        float available=Math.max(1,getWidth()-24*getResources().getDisplayMetrics().density);
        Paint.FontMetrics metrics=paint.getFontMetrics();Rect ink=new Rect();String text=before+anchor+after;
        paint.getTextBounds(text,0,text.length(),ink);
        float middle=(metrics.ascent+metrics.descent)/2;
        float vertical=2*Math.max(middle-Math.min(metrics.top,ink.top),Math.max(metrics.bottom,ink.bottom)-middle);
        float height=Math.max(1,getHeight()-32*getResources().getDisplayMetrics().density);
        float ratio=Math.min(1,Math.min(available/Math.max(1,required),height/Math.max(1,vertical)));
        return desired*ratio;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); paint.setTextSize(fittedSize());
        float center=getWidth()/2f, aw=paint.measureText(anchor);
        Paint.FontMetrics metrics=paint.getFontMetrics(); float y=getHeight()/2f-(metrics.ascent+metrics.descent)/2;
        paint.setColor(Color.rgb(51,72,62)); paint.setTextAlign(Paint.Align.RIGHT); canvas.drawText(before,center-aw/2,y,paint);
        paint.setTextAlign(Paint.Align.LEFT); paint.setColor(Color.rgb(183,92,60)); canvas.drawText(anchor,center-aw/2,y,paint);
        paint.setColor(Color.rgb(51,72,62)); canvas.drawText(after,center+aw/2,y,paint);
        paint.setColor(Color.rgb(198,199,176)); canvas.drawRect(center-1,8,center+1,16,paint); canvas.drawRect(center-1,getHeight()-16,center+1,getHeight()-8,paint);
    }
}
