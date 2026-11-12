package jp.tenreader.launcher;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;
import java.time.Duration;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36)
public class ScreenReadingTest {
    private FakeFrames frames;
    private FakeOcr ocr;
    @Before public void setup(){
        ReaderOverlay.textExecutor=Runnable::run;
        CaptureTicket.cancelAll();ShadowSettings.setCanDrawOverlays(true);
        frames=new FakeFrames();ocr=new FakeOcr();ScreenReaderService.frameFactory=(c,h)->frames;ScreenReaderService.ocrFactory=()->ocr;
    }
    @After public void cleanup(){CaptureTicket.cancelAll();ReaderOverlay.textExecutor=ReaderOverlay.DEFAULT_TEXT_EXECUTOR;ScreenReaderService.frameFactory=ProjectionFrameSource::new;ScreenReaderService.ocrFactory=JapaneseOcrEngine::new;}
    static final class FakeFrames implements FrameSource {
        Callback callback;int starts,closes;
        @Override public void start(Intent consent,Callback callback){assertNotNull(consent);starts++;this.callback=callback;}
        @Override public void close(){closes++;}
    }
    static final class FakeOcr implements OcrEngine {
        Callback callback;Bitmap image;int closes;
        @Override public void read(Bitmap image,Callback callback){this.image=image;this.callback=callback;}
        @Override public void close(){closes++;}
    }
    private ServiceController<ScreenReaderService> start(long ticket){
        ServiceController<ScreenReaderService> controller=Robolectric.buildService(ScreenReaderService.class).create();
        controller.get().onStartCommand(new Intent().setAction(ScreenReaderService.START).putExtra(CaptureActivity.TICKET,ticket).putExtra(ScreenReaderService.GRANT,new Intent("system-consent")),0,1);
        controller.get().onStartCommand(new Intent().setAction(ScreenReaderService.READY).putExtra(CaptureActivity.TICKET,ticket),0,2);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(301));return controller;
    }
    private static OcrDocument document(){return new OcrDocument(200,400,List.of(new OcrDocument.Line("今日は新しい本を読みます。",10,70,190,100),new OcrDocument.Line("本文を一点で読み進めます。",10,110,190,140)));}
    @Test public void closingReaderRestoresFloatingIconAndKeepsLauncherAlive(){
        ServiceController<OverlayService> launcher=Robolectric.buildService(OverlayService.class).create();launcher.get().onStartCommand(new Intent(),0,1);
        ServiceController<ScreenReaderService> reader=start(CaptureTicket.begin());
        try{
            android.view.View bubble=org.robolectric.util.ReflectionHelpers.getField(launcher.get(),"bubble");OverlayService.refreshVisibility();assertEquals(android.view.View.GONE,bubble.getVisibility());
            reader.get().onStartCommand(new Intent().setAction(ScreenReaderService.STOP),0,3);launcher.get().sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));shadowOf(Looper.getMainLooper()).idle();
            assertEquals(android.view.View.VISIBLE,bubble.getVisibility());assertTrue(OverlayService.isRunning());assertFalse(shadowOf(launcher.get()).isStoppedBySelf());
        }finally{reader.destroy();launcher.destroy();}
    }

    @Test public void consentStartsOnlyOneCaptureAndOcrReleasesImageAfterCompletion(){
        long ticket=CaptureTicket.begin();ServiceController<ScreenReaderService> controller=start(ticket);
        try{
            controller.get().onStartCommand(new Intent().setAction(ScreenReaderService.READY).putExtra(CaptureActivity.TICKET,ticket),0,3);
            assertEquals(1,frames.starts);
            Bitmap bitmap=Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888);frames.callback.frame(bitmap);
            assertEquals(1,frames.closes);assertFalse(bitmap.isRecycled());
            ocr.callback.complete(document());shadowOf(Looper.getMainLooper()).idle();
            assertTrue(bitmap.isRecycled());assertEquals(1,ocr.closes);assertTrue(CaptureTicket.busy());
        }finally{controller.destroy();}
        assertFalse(CaptureTicket.busy());
    }
    @Test public void cancelDuringOcrDoesNotRecycleInUseImageOrShowLateResults(){
        long ticket=CaptureTicket.begin();ServiceController<ScreenReaderService> controller=start(ticket);
        Bitmap bitmap=Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888);frames.callback.frame(bitmap);
        controller.destroy();assertFalse(bitmap.isRecycled());assertFalse(CaptureTicket.busy());
        ocr.callback.complete(document());shadowOf(Looper.getMainLooper()).idle();
        assertTrue(bitmap.isRecycled());assertEquals(1,ocr.closes);assertFalse(CaptureTicket.busy());
    }
    @Test public void queuedOcrCompletionStillReleasesImageWhenServiceIsDestroyed(){
        ServiceController<ScreenReaderService> controller=start(CaptureTicket.begin());
        Bitmap bitmap=Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888);frames.callback.frame(bitmap);
        ocr.callback.complete(document());controller.destroy();shadowOf(Looper.getMainLooper()).idle();assertTrue(bitmap.isRecycled());
    }
    @Test public void staleCaptureCallbackCannotEndNewTicket(){
        long first=CaptureTicket.begin();ServiceController<ScreenReaderService> controller=start(first);
        long second=CaptureTicket.begin();Bitmap stale=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888);frames.callback.frame(stale);
        assertTrue(stale.isRecycled());controller.destroy();assertTrue(CaptureTicket.owns(second));
    }
    @Test public void revokedPermissionClosesReaderAndDiscardsLaterOcr(){
        ServiceController<ScreenReaderService> controller=start(CaptureTicket.begin());
        Bitmap bitmap=Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888);frames.callback.frame(bitmap);
        ShadowSettings.setCanDrawOverlays(false);
        ScreenReaderService service=controller.get();shadowOf(service.getSystemService(AppOpsManager.class)).setMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,android.os.Process.myUid(),service.getPackageName(),AppOpsManager.MODE_ERRORED);
        shadowOf(Looper.getMainLooper()).idle();assertTrue(shadowOf(service).isStoppedBySelf());controller.destroy();
        ocr.callback.complete(document());shadowOf(Looper.getMainLooper()).idle();assertTrue(bitmap.isRecycled());
    }
    @Test public void osStopsProjectionBeforeOcr(){
        ServiceController<ScreenReaderService> controller=start(CaptureTicket.begin());
        try{frames.callback.stopped();assertTrue(shadowOf(controller.get()).isStoppedBySelf());assertNull(ocr.callback);}finally{controller.destroy();}
    }
    @Test public void timeoutRejectsLateOcrResult(){
        ServiceController<ScreenReaderService> controller=start(CaptureTicket.begin());
        try{Bitmap bitmap=Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888);frames.callback.frame(bitmap);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));ocr.callback.complete(document());shadowOf(Looper.getMainLooper()).idle();assertTrue(bitmap.isRecycled());
        }finally{controller.destroy();}
    }
    @Test public void iconOpensIsolatedConsentTaskWithoutNoHistory(){
        Activity activity=Robolectric.buildActivity(MainActivity.class).setup().get();
        CaptureActivity.open(activity);Intent intent=shadowOf(activity).getNextStartedActivity();
        assertEquals(CaptureActivity.class.getName(),intent.getComponent().getClassName());assertTrue((intent.getFlags()&Intent.FLAG_ACTIVITY_MULTIPLE_TASK)!=0);assertEquals(0,intent.getFlags()&Intent.FLAG_ACTIVITY_NO_HISTORY);
    }
    @Test public void denyingConsentRestoresButtonAndStartsNoCaptureService(){
        long ticket=CaptureTicket.begin();Intent intent=new Intent().putExtra(CaptureActivity.TICKET,ticket);
        try(ActivityController<CaptureActivity> controller=Robolectric.buildActivity(CaptureActivity.class,intent).create()){
            CaptureActivity activity=controller.get();activity.onActivityResult(71,Activity.RESULT_CANCELED,null);assertNull(shadowOf(activity).getNextStartedService());
        }
        assertFalse(CaptureTicket.busy());
    }
    @Test public void grantedConsentReachesServiceOnlyForCurrentTicket(){
        long ticket=CaptureTicket.begin();Intent intent=new Intent().putExtra(CaptureActivity.TICKET,ticket);
        try(ActivityController<CaptureActivity> controller=Robolectric.buildActivity(CaptureActivity.class,intent).create()){
            CaptureActivity activity=controller.get();activity.onActivityResult(71,Activity.RESULT_OK,new Intent("granted"));
            Intent service=shadowOf(activity).getNextStartedService();assertNotNull(service);assertEquals(ScreenReaderService.START,service.getAction());
        }
    }
    @Test public void editingDetectedTextUpdatesPlaybackWithoutRecapture(){
        Activity activity=Robolectric.buildActivity(MainActivity.class).setup().get();
        ReaderOverlay reader=new ReaderOverlay(activity,()->{},()->{});
        try{
            reader.showDocument(document(),Bitmap.createBitmap(200,400,Bitmap.Config.ARGB_8888));
            EditText editor=reader.view().findViewById(R.id.reader_editor);editor.setText("修正した文章です。");
            Button apply=reader.view().findViewById(R.id.reader_apply);apply.performClick();
            FocusWordView word=reader.view().findViewById(R.id.reader_word);assertTrue("修正した文章です。".startsWith(word.getContentDescription().toString()));
            assertEquals(0,frames.starts);
        }finally{reader.close();}
    }
    @Test public void graphemeUnitsPreserveTextEmojiAndLongWords(){
        String text="「"+"👨‍👩‍👧‍👦🇯🇵e\u0301".repeat(15)+"ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(10)+"」。";
        for(String mode:List.of("phrase","word","character")){
            List<String> units=ReadingText.tokenize(text,mode);
            assertEquals(java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFC),String.join("",units));
            for(String unit:units)assertTrue(ReadingText.graphemes(unit).size()<=(mode.equals("character")?1:12));
        }
    }
    @Test public void largeWordIsSplitWithoutLosingCharacters(){String text="x".repeat(200000);List<String> units=ReadingText.tokenize(text,"word");assertEquals(text,String.join("",units));assertEquals(16667,units.size());}
    @Test public void captureSizeIsBoundedAndPreservesAspect(){int[] size=ProjectionFrameSource.size(1440,3200);assertTrue((long)size[0]*size[1]<=3000000);assertEquals(1440/3200.0,size[0]/(double)size[1],.001);}
    @Test public void unicodeWhitespaceIsNotDisplayedAndPhraseSpacesRemain(){
        for(String mode:List.of("phrase","word","character"))assertTrue(ReadingText.tokenize(" \t　\u00a0",mode).isEmpty());
        List<String> phrases=ReadingText.tokenize("I am a reader", "phrase");assertTrue(phrases.contains("I am a"));
        assertEquals("Iamareader",String.join("",phrases).replace(" ",""));
    }
    @Test public void rowPaddingDoesNotRequireLastRowPadding(){
        java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(20); // 2x2 RGBA, row stride 12, final valid row only 8 bytes.
        for(int i=0;i<20;i++)buffer.put((byte)0xff);buffer.flip();
        Bitmap image=BitmapFrames.rgba(buffer,2,2,4,12);assertEquals(2,image.getWidth());assertEquals(2,image.getHeight());assertEquals(android.graphics.Color.WHITE,image.getPixel(1,1));image.recycle();
    }
    @Test public void truncatedImageIsRejected(){try{BitmapFrames.rgba(java.nio.ByteBuffer.allocate(19),2,2,4,12);fail("Truncated image accepted");}catch(IllegalArgumentException expected){}}
    @Test @Config(sdk=26) public void rotationDuringCaptureOnOlderAndroidClosesProjection(){
        ServiceController<ScreenReaderService> controller=start(CaptureTicket.begin());
        try{controller.get().onConfigurationChanged(new android.content.res.Configuration());assertEquals(1,frames.closes);}finally{controller.destroy();}
    }
    @Test public void modeSwitchKeepsEnglishReadingPositionAcrossWhitespace(){
        Activity activity=Robolectric.buildActivity(MainActivity.class).setup().get();ReaderOverlay reader=new ReaderOverlay(activity,()->{},()->{});
        String english="I am a reader. You are a reader. We read words every day.";
        OcrDocument doc=new OcrDocument(400,800,List.of(new OcrDocument.Line(english,10,100,390,150)));
        try{
            reader.showDocument(doc,Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888));
            shadowOf(Looper.getMainLooper()).idle();
            reader.view().findViewById(R.id.reader_next).performClick();
            List<String> phrases=ReadingText.tokenize(english,"phrase");int prefix=0;for(int i=0;i<5;i++)prefix+=phrases.get(i).replaceAll("\\s","").length();
            android.widget.Spinner modes=reader.view().findViewById(R.id.reader_mode);modes.setSelection(2);shadowOf(Looper.getMainLooper()).idle();
            FocusWordView word=reader.view().findViewById(R.id.reader_word);assertEquals(english.replaceAll("\\s","").substring(prefix,prefix+1),word.getContentDescription());
        }finally{reader.close();}
    }
}
