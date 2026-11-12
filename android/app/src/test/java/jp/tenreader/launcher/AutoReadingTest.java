package jp.tenreader.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36)
public class AutoReadingTest {
    private final List<ScreenReadingTest.FakeOcr> jobs=new ArrayList<>();
    private FakeFrames frames;
    private FakeScroll scroll;
    static final String OVERLAP="この本文の続きが正しく重なっていることを確認します。";
    static final class FakeFrames implements FrameSource {
        Callback callback;final List<Callback> callbacks=new ArrayList<>();int starts,requests,closes;
        @Override public void start(Intent grant,Callback callback){starts++;this.callback=callback;callbacks.add(callback);}
        @Override public boolean request(Callback callback){requests++;this.callback=callback;callbacks.add(callback);return true;}
        @Override public void close(){closes++;}
    }
    static final class FakeScroll implements ScrollPort {
        final Target target=new Target("example.reader",7,new Rect(0,0,100,400),200,400);
        boolean matches=true,canScroll=true,moved=true;Runnable interrupted;int actions,ends;
        @Override public Target snapshot(){return target;}
        @Override public boolean matches(Target t){return matches&&t==target;}
        @Override public boolean begin(long ticket,Target t,Runnable interrupted){this.interrupted=interrupted;return matches(t);}
        @Override public boolean forward(long ticket,Target t,Consumer<Boolean> callback){if(!canScroll)return false;actions++;callback.accept(moved);return true;}
        @Override public void end(long ticket){ends++;interrupted=null;}
    }
    @Before public void setup(){ReaderOverlay.textExecutor=Runnable::run;CaptureTicket.cancelAll();ShadowSettings.setCanDrawOverlays(true);frames=new FakeFrames();scroll=new FakeScroll();ScreenReaderService.frameFactory=(c,h)->frames;ScreenReaderService.ocrFactory=()->{ScreenReadingTest.FakeOcr job=new ScreenReadingTest.FakeOcr();jobs.add(job);return job;};ScreenReaderService.scrollFactory=c->scroll;}
    @After public void cleanup(){ReaderOverlay.textExecutor=ReaderOverlay.DEFAULT_TEXT_EXECUTOR;CaptureTicket.cancelAll();ScreenReaderService.frameFactory=ProjectionFrameSource::new;ScreenReaderService.ocrFactory=JapaneseOcrEngine::new;ScreenReaderService.scrollFactory=c->AutoScrollService.port();}
    private ServiceController<ScreenReaderService> start(){
        long ticket=CaptureTicket.begin(scroll.target);
        ServiceController<ScreenReaderService> controller=Robolectric.buildService(ScreenReaderService.class).create();
        controller.get().getSharedPreferences("native-reader",0).edit().putInt("speed",6000).apply();
        controller.get().onStartCommand(new Intent().setAction(ScreenReaderService.START).putExtra(CaptureActivity.TICKET,ticket).putExtra(ScreenReaderService.GRANT,new Intent("consent")),0,1);
        controller.get().onStartCommand(new Intent().setAction(ScreenReaderService.READY).putExtra(CaptureActivity.TICKET,ticket),0,2);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(301));return controller;
    }
    private Bitmap frame(){Bitmap b=Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888);b.eraseColor(Color.RED);int[] blue=new int[40000];java.util.Arrays.fill(blue,Color.BLUE);b.setPixels(blue,0,100,100,0,100,400);frames.callback.frame(b,new CaptureGeometry(200,400,200,400));return b;}
    private void complete(String text){ScreenReadingTest.FakeOcr job=jobs.get(jobs.size()-1);job.callback.complete(new OcrDocument(100,400,List.of(new OcrDocument.Line(text,5,60,95,90))));shadowOf(Looper.getMainLooper()).idle();}
    @Test public void targetIsCroppedBeforeOcrAndOneVirtualDisplayServesNextPage(){
        ServiceController<ScreenReaderService> controller=start();
        try{
            Bitmap full=frame();assertTrue(full.isRecycled());assertEquals(100,jobs.get(0).image.getWidth());assertEquals(Color.RED,jobs.get(0).image.getPixel(99,0));
            complete("最初の画面です。"+OVERLAP);assertEquals(0,frames.closes);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(1,scroll.actions);assertEquals(1,frames.starts);assertEquals(1,frames.requests);
            frame();complete(OVERLAP+"まだ読んでいない次の文章を表示します。");assertTrue(CaptureTicket.busy());
        }finally{controller.destroy();}
        assertEquals(1,frames.closes);
    }
    @Test public void wrongScopeIsRejectedWithoutOcr(){
        ServiceController<ScreenReaderService> controller=start();
        try{Bitmap image=Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888);frames.callback.frame(image,new CaptureGeometry(200,400,100,400));assertTrue(image.isRecycled());assertTrue(jobs.isEmpty());assertEquals(1,frames.closes);}finally{controller.destroy();}
    }
    @Test public void unknownCaptureScopeCannotReadNeighborPixels(){
        ServiceController<ScreenReaderService> controller=start();
        try{Bitmap image=Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888);frames.callback.frame(image,new CaptureGeometry(200,400,200,400,false,true));assertTrue(image.isRecycled());assertTrue(jobs.isEmpty());}finally{controller.destroy();}
    }
    @Test public void changedForegroundStopsBeforeOcr(){
        ServiceController<ScreenReaderService> controller=start();
        try{scroll.matches=false;Bitmap image=frame();assertTrue(image.isRecycled());assertTrue(jobs.isEmpty());assertFalse(CaptureTicket.busy());}finally{controller.destroy();}
    }
    @Test public void manualInterruptionInvalidatesAlreadyQueuedOcr(){
        ServiceController<ScreenReaderService> controller=start();
        try{frame();ScreenReadingTest.FakeOcr job=jobs.get(0);Runnable cancel=scroll.interrupted;cancel.run();assertFalse(CaptureTicket.busy());assertFalse(job.image.isRecycled());job.callback.complete(new OcrDocument(100,400,List.of(new OcrDocument.Line(OVERLAP,5,60,95,90))));shadowOf(Looper.getMainLooper()).idle();assertTrue(job.image.isRecycled());assertEquals(0,scroll.actions);}finally{controller.destroy();}
    }
    @Test public void failedScrollNeverRequestsAnotherFrame(){
        ServiceController<ScreenReaderService> controller=start();
        try{scroll.moved=false;frame();complete("最初の本文です。"+OVERLAP);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));assertEquals(0,frames.requests);assertEquals(1,frames.closes);}finally{controller.destroy();}
    }
    @Test public void repeatedPageEndsWithoutAnAutomaticLoop(){
        ServiceController<ScreenReaderService> controller=start();
        try{String text="同じ本文です。"+OVERLAP;frame();complete(text);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));frame();complete(text);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));assertEquals(1,scroll.actions);assertEquals(1,frames.requests);assertEquals(1,frames.closes);}finally{controller.destroy();}
    }
    @Test public void uncertainContinuationStopsAutomaticPlayback(){
        ServiceController<ScreenReaderService> controller=start();
        try{frame();complete("最初の本文です。"+OVERLAP);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));frame();complete("共通する文章が存在しない別の本文です。勝手に読み進めません。");shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));assertEquals(1,scroll.actions);assertEquals(1,frames.closes);}finally{controller.destroy();}
    }
    @Test public void oldFrameCallbackIsRejectedDuringNextRequest(){
        ServiceController<ScreenReaderService> controller=start();
        try{FrameSource.Callback old=frames.callback;frame();complete("最初の本文です。"+OVERLAP);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));Bitmap stale=Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888);old.frame(stale,new CaptureGeometry(200,400,200,400));assertTrue(stale.isRecycled());assertEquals(1,jobs.size());}finally{controller.destroy();}
    }
    private OcrDocument tableViewport(int from,int to,String before,String after){
        List<OcrDocument.Line> lines=new ArrayList<>();int y=30;
        if(!before.isEmpty()){lines.add(new OcrDocument.Line(before,5,y,95,y+14));y+=35;}
        for(int i=from;i<=to;i++){lines.add(new OcrDocument.Line("項目"+i,5,y,40,y+14));lines.add(new OcrDocument.Line((100+i)+"億円",55,y,95,y+14));y+=24;}
        if(!after.isEmpty())lines.add(new OcrDocument.Line(after,5,y+15,95,y+29));return new OcrDocument(100,400,lines);
    }
    private void completeDocument(OcrDocument doc){jobs.get(jobs.size()-1).callback.complete(doc);shadowOf(Looper.getMainLooper()).idle();}
    @Test public void tableOnlyViewportDoesNotEndArticleAndProseAfterItIsRecovered(){
        ServiceController<ScreenReaderService> controller=start();
        try{
            frame();completeDocument(tableViewport(0,3,"表の前の本文をすべて読み進めます。",""));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));assertEquals(1,frames.requests);
            frame();completeDocument(tableViewport(1,6,"",""));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));assertEquals(2,frames.requests);assertEquals(0,frames.closes);
            frame();completeDocument(tableViewport(4,6,"","図表が終わった後にも本文が続きます。"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));assertEquals(0,frames.closes);assertTrue(CaptureTicket.busy());
        }finally{controller.destroy();}
    }
    @Test public void alreadyReadProseAboveNewTableRowsDoesNotLeaveAnEmptyAutomaticReader(){
        ServiceController<ScreenReaderService> controller=start();String before="表の前の本文をすべて読み進めます。";
        try{
            frame();completeDocument(tableViewport(0,3,before,""));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));assertEquals(1,frames.requests);
            frame();completeDocument(tableViewport(0,6,before,""));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));assertEquals(2,frames.requests);assertEquals(0,frames.closes);
            frame();completeDocument(tableViewport(4,6,"","図表の下の新しい本文を読み取ります。"));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));assertEquals(0,frames.closes);
        }finally{controller.destroy();}
    }
    @Test public void articleEndingWithTableShowsStopReasonInsteadOfPerpetualLoading(){
        ServiceController<ScreenReaderService> controller=start();
        try{
            frame();completeDocument(tableViewport(0,3,"表の前の本文をすべて読み進めます。",""));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            scroll.canScroll=false;frame();completeDocument(tableViewport(1,6,"",""));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));assertEquals(1,frames.closes);
            ReaderOverlay overlay=org.robolectric.util.ReflectionHelpers.getField(controller.get(),"overlay");android.widget.TextView status=overlay.view().findViewById(R.id.reader_status);assertTrue(status.getText().toString(),status.getText().toString().contains("下方向"));assertTrue(overlay.view().findViewById(R.id.reader_close).isShown());
        }finally{controller.destroy();}
    }
    @Test public void pageLimitStopsBeforeIssuingFiftyFirstPage(){
        ServiceController<ScreenReaderService> controller=start();
        try{for(int i=0;i<ScreenReaderService.MAX_PAGES;i++){frame();complete(OVERLAP+"画面"+i+"。"+OVERLAP);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));}assertEquals(ScreenReaderService.MAX_PAGES-1,scroll.actions);assertEquals(1,frames.closes);}finally{controller.destroy();}
    }
    @Test public void disclosureDoesNotEnableAccessibilityByItself(){
        try(org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();activity.findViewById(R.id.auto_scroll_setting).performClick();
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            assertFalse(activity.getSharedPreferences("native-reader",0).getBoolean("auto-scroll",false));dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
            assertNull(shadowOf(activity).getNextStartedActivity());assertFalse(activity.getSharedPreferences("native-reader",0).getBoolean("auto-scroll",false));
        }
    }
    @Test public void continuationRetainsUncertainTextAndGraphemes(){
        String text="未知の本文👨‍👩‍👧‍👦を勝手に省きません。";
        PageContinuation.Result uncertain=PageContinuation.next("全く別の文章です。",text);assertFalse(uncertain.certain);assertEquals(text,uncertain.text);
        PageContinuation.Result certain=PageContinuation.next("前文。"+OVERLAP,OVERLAP+"👨‍👩‍👧‍👦新しい本文。");assertTrue(certain.certain);assertEquals("👨‍👩‍👧‍👦新しい本文。",certain.text);
        assertTrue(PageContinuation.next(text,text).exhausted);
    }
}
