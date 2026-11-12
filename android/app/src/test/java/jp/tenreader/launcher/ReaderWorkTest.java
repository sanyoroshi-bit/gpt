package jp.tenreader.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Looper;
import android.widget.Button;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36)
public class ReaderWorkTest {
    @After public void restore(){ReaderOverlay.textExecutor=ReaderOverlay.DEFAULT_TEXT_EXECUTOR;}
    @Test public void dictionaryWorkIsDeferredAndClosedReaderRejectsItsResult(){
        List<Runnable> queue=new ArrayList<>();ReaderOverlay.textExecutor=queue::add;
        Activity activity=Robolectric.buildActivity(MainActivity.class).setup().get();ReaderOverlay reader=new ReaderOverlay(activity,()->{},()->{});
        OcrDocument doc=new OcrDocument(300,600,List.of(new OcrDocument.Line("夏目漱石の小説を読み進めます。",10,100,290,130)));
        reader.showDocument(doc,Bitmap.createBitmap(300,600,Bitmap.Config.ARGB_8888));
        assertEquals(1,queue.size());assertFalse(((Button)reader.view().findViewById(R.id.reader_play)).isEnabled());assertFalse(reader.view().findViewById(R.id.reader_next).isEnabled());reader.close();queue.remove(0).run();shadowOf(Looper.getMainLooper()).idle();
        assertEquals("",((FocusWordView)reader.view().findViewById(R.id.reader_word)).getContentDescription()==null?"":((FocusWordView)reader.view().findViewById(R.id.reader_word)).getContentDescription().toString());
    }
    @Test public void logicalProtectedNameStillHonorsDisplayLimitWithoutDroppingCharacters(){
        String name="非常に長い架空未来科学研究開発株式会社";
        List<String> units=ReadingText.tokenize(name+"の発表。","word",JapaneseWords.terms(name));
        assertEquals(name+"の発表。",String.join("",units));for(String unit:units)assertTrue(ReadingText.graphemes(unit).size()<=ReadingText.MAX_UNIT);
    }
    @Test public void morphologicalBoundariesNeverSplitAnEmojiGrapheme(){
        String emoji="👨‍👩‍👧‍👦";List<String> units=ReadingText.tokenize("東京大学と"+emoji+"の発表。","word");assertEquals("東京大学と"+emoji+"の発表。",String.join("",units));assertTrue(units.toString(),units.stream().anyMatch(u->u.contains(emoji)));assertFalse(units.stream().anyMatch(u->u.startsWith("\u200d")||u.endsWith("\u200d")));
    }
    @Test public void rapidModeChangesKeepPendingReadingOffsetAndOnlyLatestResult(){
        List<Runnable> queue=new ArrayList<>();ReaderOverlay.textExecutor=queue::add;Activity activity=Robolectric.buildActivity(MainActivity.class).setup().get();
        ReaderOverlay reader=new ReaderOverlay(activity,()->{},()->{});String text="I am a reader. You are a reader. We read words every day.";
        try{
            reader.showDocument(new OcrDocument(400,800,List.of(new OcrDocument.Line(text,10,100,390,150))),Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888),text,false);
            queue.remove(0).run();shadowOf(Looper.getMainLooper()).idle();reader.view().findViewById(R.id.reader_next).performClick();
            List<String> phrases=ReadingText.tokenize(text,"phrase");int offset=0;for(int i=0;i<5;i++)offset+=ReadingText.visibleCount(phrases.get(i));
            android.widget.Spinner modes=reader.view().findViewById(R.id.reader_mode);modes.setSelection(1);shadowOf(Looper.getMainLooper()).idle();modes.setSelection(2);shadowOf(Looper.getMainLooper()).idle();assertEquals(2,queue.size());
            queue.remove(0).run();queue.remove(0).run();shadowOf(Looper.getMainLooper()).idle();
            assertEquals(ReadingText.graphemes(text.replace(" ","")).get(offset),reader.view().findViewById(R.id.reader_word).getContentDescription());
        }finally{reader.close();}
    }
}
