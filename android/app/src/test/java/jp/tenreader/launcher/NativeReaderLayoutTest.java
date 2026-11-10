package jp.tenreader.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36,qualifiers="w320dp-h720dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeReaderLayoutTest {
    @Test public void enlargedSystemFontFitsVerticallyForShortUnits(){
        Activity activity=Robolectric.buildActivity(MainActivity.class).setup().get();
        android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration());config.fontScale=2;
        android.content.Context context=activity.createConfigurationContext(config);
        FocusWordView word=new FocusWordView(context);word.setSize(64);word.layout(0,0,268,116);
        for(boolean serif:List.of(false,true))for(String text:List.of("読","👨‍👩‍👧‍👦","é")){
            word.setSerif(serif);word.setWord(text);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTypeface(serif?android.graphics.Typeface.SERIF:android.graphics.Typeface.DEFAULT);paint.setTextSize(word.fittedSize());
            Paint.FontMetrics metrics=paint.getFontMetrics();android.graphics.Rect ink=new android.graphics.Rect();paint.getTextBounds(text,0,text.length(),ink);
            float baseline=word.getHeight()/2f-(metrics.ascent+metrics.descent)/2;
            assertTrue(text,baseline+Math.min(metrics.top,ink.top)>=15);
            assertTrue(text,baseline+Math.max(metrics.bottom,ink.bottom)<=word.getHeight()-15);
        }
    }
    @Test public void fixedStageFitsLargeTextAndReaderControlsAt320px() throws Exception {
        Activity activity=Robolectric.buildActivity(MainActivity.class).setup().get();
        ReaderOverlay reader=new ReaderOverlay(activity,()->{},()->{});
        try {
            OcrDocument doc=new OcrDocument(320,720,List.of(new OcrDocument.Line("この文章を一点で読み進められます。",20,100,300,130),new OcrDocument.Line("元のアプリの上で、読む速さを選びます。",20,140,300,170)));
            reader.showDocument(doc,Bitmap.createBitmap(320,720,Bitmap.Config.ARGB_8888));
            View root=reader.view();root.measure(View.MeasureSpec.makeMeasureSpec(296,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(696,View.MeasureSpec.AT_MOST));root.layout(0,0,296,root.getMeasuredHeight());
            assertTrue(root.getHeight()<=696);
            FocusWordView word=root.findViewById(R.id.reader_word);assertTrue(word.getWidth()>0);
            for(String text:List.of("短文","ＷＷＷＷＷＷＷＷＷＷＷＷ","👨‍👩‍👧‍👦".repeat(12))){
                word.setSize(64);word.setWord(text);
                Paint paint=new Paint();paint.setTextSize(word.fittedSize());
                List<String> chars=ReadingText.graphemes(text);int pivot=(int)Math.ceil(chars.size()/2.0)-1;
                float required=2*Math.max(paint.measureText(String.join("",chars.subList(0,pivot))),paint.measureText(String.join("",chars.subList(pivot+1,chars.size()))))+paint.measureText(chars.get(pivot));
                assertTrue(text,required<=word.getWidth()-23);
            }
            word.setSize(36);word.setWord("一点で読む。");
            Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));
            File file=new File("build/reports/native-reader-layout-320.png");file.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.PNG,100,stream);}image.recycle();
            View close=root.findViewById(R.id.reader_close);assertTrue(close.getWidth()>=44);assertTrue(close.getHeight()>=44);
        } finally {reader.close();}
    }
}
