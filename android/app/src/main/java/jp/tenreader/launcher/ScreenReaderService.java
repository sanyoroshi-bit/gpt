package jp.tenreader.launcher;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public final class ScreenReaderService extends Service {
    static final String START="jp.tenreader.launcher.START_CAPTURE",READY="jp.tenreader.launcher.CAPTURE_READY",STOP="jp.tenreader.launcher.STOP_READER",GRANT="consent";
    private static final String CHANNEL="ten-screen-reader";
    static final int MAX_PAGES=50;
    static FrameSource.Factory frameFactory=ProjectionFrameSource::new;
    static OcrEngine.Factory ocrFactory=JapaneseOcrEngine::new;
    static ScrollPort.Factory scrollFactory=context->AutoScrollService.port();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Handler completions=new Handler(Looper.getMainLooper());
    private long ticket,frameGeneration;
    private boolean live,started,watching,opsWatching,automatic,awaitingFrame,transitioning;
    private int pages;
    private String lastPage="";
    private final Set<String> seen=new HashSet<>();
    private Intent consent;
    private FrameSource frames;
    private ScrollPort scroller;
    private ScrollPort.Target target;
    private ReaderOverlay overlay;
    private OcrJob job;
    private AppOpsManager appOps;
    private final Runnable timeout=()->fail("読み取りに時間がかかっています。閉じて再試行してください。");
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){stopReading();}
    };
    private final AppOpsManager.OnOpChangedListener permission=(op,pkg)->{
        if(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW.equals(op)&&getPackageName().equals(pkg))handler.post(()->{if(!Settings.canDrawOverlays(this))stopReading();});
    };
    private static final class OcrJob {
        final long ticket;
        final Bitmap image;
        final OcrEngine engine;
        boolean released;
        OcrJob(long ticket,Bitmap image,OcrEngine engine){this.ticket=ticket;this.image=image;this.engine=engine;}
        void release(){if(released)return;released=true;try{engine.close();}finally{if(!image.isRecycled())image.recycle();}}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || STOP.equals(intent.getAction())){stopReading();return START_NOT_STICKY;}
        long incoming=intent.getLongExtra(CaptureActivity.TICKET,0);
        if(READY.equals(intent.getAction())) {
            if(live && incoming==ticket && CaptureTicket.owns(ticket) && !started)beginCapture();
            else if(!live){CaptureTicket.end(incoming);OverlayService.refreshVisibility();stopReading();}
            return START_NOT_STICKY;
        }
        if(!START.equals(intent.getAction()) || !CaptureTicket.owns(incoming)) {if(!live)stopReading();return START_NOT_STICKY;}
        if(live){if(incoming==ticket)return START_NOT_STICKY;finishSession();}
        ticket=incoming;target=CaptureTicket.target(ticket);automatic=target!=null;
        consent=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(GRANT,Intent.class):legacyGrant(intent);
        if(consent==null || !Settings.canDrawOverlays(this) || getSystemService(KeyguardManager.class).isKeyguardLocked()){stopReading();return START_NOT_STICKY;}
        try {
            live=true;foreground(true);
            appOps=getSystemService(AppOpsManager.class);
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,getPackageName(),permission);opsWatching=true;
            if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
            watching=true;
            if(automatic){scroller=scrollFactory.create(this);if(scroller==null){fail("自動スクロールのOS設定が無効です。再取得は単発になります。");return START_NOT_STICKY;}}
            handler.postDelayed(timeout,10000);
        } catch(RuntimeException e){stopReading();}
        return START_NOT_STICKY;
    }
    @SuppressWarnings("deprecation") private static Intent legacyGrant(Intent intent){return intent.getParcelableExtra(GRANT);}
    private void beginCapture(){started=true;long expected=ticket;handler.postDelayed(()->startWhenReady(expected,20),300);}
    private void startWhenReady(long expected,int attempts){
        if(!valid()||ticket!=expected)return;
        if(automatic&&!scroller.captureSafe(target)){
            if(attempts>0){handler.postDelayed(()->startWhenReady(expected,attempts-1),100);return;}
            fail("対象画面を確認できません。元のアプリから単発で読み取り直してください。");return;
        }
        if(automatic&&!scroller.begin(ticket,target,()->{if(live&&ticket==expected)stopReading();})){fail("自動スクロールの対象が変わりました。");return;}
        try{
            frames=frameFactory.create(this,handler);Intent grant=consent;consent=null;
            awaitingFrame=true;frames.start(grant,frameCallback(++frameGeneration));
        }catch(RuntimeException e){fail("画面取得を開始できませんでした。もう一度許可して再試行してください。");}
    }
    private FrameSource.Callback frameCallback(long generation){
        return new FrameSource.Callback(){
            @Override public void frame(Bitmap image){acceptFrame(image,null,generation);}
            @Override public void frame(Bitmap image,CaptureGeometry geometry){acceptFrame(image,geometry,generation);}
            @Override public void error(){if(generation==frameGeneration)fail("画面を取得できませんでした。許可や保護画面かどうかを確認してください。");}
            @Override public void stopped(){if(live&&generation==frameGeneration)stopReading();}
        };
    }
    private boolean valid(){return live&&CaptureTicket.owns(ticket)&&Settings.canDrawOverlays(this)&&!getSystemService(KeyguardManager.class).isKeyguardLocked();}
    private boolean scoped(){return automatic&&scroller!=null&&scroller.matches(target);}
    private void closeFrames(){if(frames!=null){FrameSource old=frames;frames=null;old.close();}consent=null;}
    private ReaderOverlay reader(){
        if(overlay==null)overlay=new ReaderOverlay(this,this::stopReading,()->CaptureActivity.open(this,false));
        return overlay;
    }
    private void acceptFrame(Bitmap bitmap,CaptureGeometry geometry,long generation){
        if(!valid()||generation!=frameGeneration||!awaitingFrame||job!=null){bitmap.recycle();return;}
        awaitingFrame=false;transitioning=false;handler.removeCallbacks(timeout);
        Bitmap input=bitmap;
        if(automatic){
            if(!scroller.captureSafe(target)){bitmap.recycle();stopReading();return;}
            try{
                if(geometry==null)throw new IllegalArgumentException("Unknown scope");
                input=geometry.targetOnly(bitmap,target);if(input!=bitmap)bitmap.recycle();
            }catch(RuntimeException e){if(!bitmap.isRecycled())bitmap.recycle();fail("共有範囲と対象画面が一致しません。自動スクロールを停止しました。再取得は単発になります。");return;}
        }else closeFrames();
        try{
            foreground(automatic);reader().setHidden(false);reader().showLoading();
            OcrJob current=new OcrJob(ticket,input,ocrFactory.create());job=current;handler.postDelayed(timeout,30000);
            current.engine.read(input,document->completions.post(()->complete(current,document)));
        }catch(RuntimeException|OutOfMemoryError e){
            if(job!=null){job.release();job=null;}else if(!input.isRecycled())input.recycle();
            fail("文字の読み取りを開始できませんでした。閉じて再試行してください。");
        }
    }
    private void complete(OcrJob current,OcrDocument document){
        try{
            if(!valid()||job!=current)return;
            if(automatic&&!scoped()){stopReading();return;}
            handler.removeCallbacks(timeout);
            if(document==null||document.lines.isEmpty()){fail("文字を読み取れませんでした。保護画面には対応していません。画面を変えるか再試行してください。");return;}
            String full=document.text(document.bodyRegion()),selected=full,notice="";boolean autoplay=ReadingText.visibleCount(full)>=12;
            if(automatic){
                String hash=fingerprint(full);
                if(seen.contains(hash)){fail("同じ本文が繰り返されたため自動読書を終了しました。画面末尾か、スクロールできない画面です。");return;}
                if(pages>0){
                    PageContinuation.Result next=PageContinuation.next(lastPage,full);
                    if(next.exhausted){fail("新しい本文を確認できなかったため、自動読書を終了しました。");return;}
                    if(!next.certain){notice="続きの一致を確認できず、自動スクロールを停止しました。本文を確認して再生してください。";suspendAutomatic(notice);autoplay=false;}
                    else {selected=next.text;autoplay=!ReadingText.blank(selected);}
                }
                if(automatic){pages++;lastPage=full;seen.add(hash);}
            }
            int width=Math.min(600,current.image.getWidth()),height=Math.max(1,Math.round(current.image.getHeight()*(width/(float)current.image.getWidth())));
            Bitmap preview=Bitmap.createScaledBitmap(current.image,width,height,true);if(preview==current.image)preview=current.image.copy(Bitmap.Config.ARGB_8888,false);
            reader().automatic(automatic,pages,this::nextPage,()->suspendAutomatic("自動スクロールを停止しました。現在の本文はそのまま読めます。"));
            reader().notice(notice);reader().showDocument(document,preview,selected,autoplay);
        }catch(RuntimeException|OutOfMemoryError e){fail("本文を表示できませんでした。閉じて再試行してください。");}
        finally{current.release();if(job==current)job=null;}
    }
    private void nextPage(){
        if(!valid()||!automatic||transitioning||job!=null||frames==null)return;
        if(pages>=MAX_PAGES){suspendAutomatic("50画面分で自動読書を停止しました。続けるにはアイコンから再開してください。");return;}
        if(!scoped()){stopReading();return;}
        transitioning=true;reader().setHidden(true);long expected=ticket;
        handler.postDelayed(()->{
            if(!valid()||ticket!=expected||!automatic)return;
            if(!scroller.captureSafe(target)){stopReading();return;}
            handler.postDelayed(timeout,10000);
            boolean requested=scroller.forward(ticket,target,moved->handler.post(()->{
                if(!valid()||ticket!=expected||!automatic||!transitioning)return;
                if(!moved||!scroller.captureSafe(target)){suspendAutomatic("スクロールの成功を確認できず、自動読書を停止しました。");return;}
                awaitingFrame=true;
                if(!frames.request(frameCallback(++frameGeneration)))fail("次の画面を取得できませんでした。再取得は単発になります。");
            }));
            if(!requested)suspendAutomatic("この画面は自動スクロールに対応していません。単発の読み取りは利用できます。");
        },150);
    }
    private void suspendAutomatic(String message){
        automatic=false;awaitingFrame=false;transitioning=false;frameGeneration++;handler.removeCallbacks(timeout);closeFrames();
        if(scroller!=null)scroller.end(ticket);scroller=null;target=null;lastPage="";seen.clear();
        if(live)foreground(false);
        if(overlay!=null){overlay.setHidden(false);overlay.automatic(false,pages,null,null);overlay.notice(message);}
    }
    private void fail(String message){
        if(!live||!CaptureTicket.owns(ticket))return;
        handler.removeCallbacks(timeout);job=null;
        if(!valid()){stopReading();return;}
        try{suspendAutomatic(message);reader().setHidden(false);reader().showError(message);}catch(RuntimeException e){stopReading();}
    }
    private static String fingerprint(String text){
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(String.join("",PageContinuation.visible(text)).getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:hash)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void foreground(boolean capturing){
        NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel(CHANNEL,"tenの画面読み取り",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,8,new Intent(this,ScreenReaderService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(automatic?"ten 自動読書中・画面共有中":capturing?"画面の文字を読み取り中":"tenで読書中")
            .setContentText("タップまたは停止で終了し、取得した画像と文章を破棄します")
            .addAction(new Notification.Action.Builder(null,"停止",stop).build()).setContentIntent(stop).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(8,notification,capturing?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else if(Build.VERSION.SDK_INT>=29)startForeground(8,notification,capturing?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:0);
        else startForeground(8,notification);
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        if(automatic)suspendAutomatic("画面の向きやサイズが変わったため、自動スクロールを停止しました。");
        else if(Build.VERSION.SDK_INT<34&&frames!=null)fail("画面の向きやサイズが変わりました。画面を読み取り直してください。");
        if(overlay!=null)overlay.layout();
    }
    private void finishSession(){
        live=false;started=false;automatic=false;awaitingFrame=false;transitioning=false;frameGeneration++;
        handler.removeCallbacksAndMessages(null);closeFrames();job=null;
        if(scroller!=null)scroller.end(ticket);scroller=null;target=null;lastPage="";seen.clear();pages=0;
        if(overlay!=null){overlay.close();overlay=null;}
        if(watching){unregisterReceiver(screenOff);watching=false;}if(opsWatching){appOps.stopWatchingMode(permission);opsWatching=false;}
        CaptureTicket.end(ticket);OverlayService.refreshVisibility();stopForeground(STOP_FOREGROUND_REMOVE);
    }
    private void stopReading(){finishSession();stopSelf();}
    @Override public void onDestroy(){finishSession();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
